package dev.localsoul.aero.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.localsoul.aero.actor.internal.ActorCell;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActorSystemTest {

    private static final Duration SHORT = Duration.ofSeconds(2);
    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    private ActorSystem system() {
        return system = new ActorSystem("test");
    }

    /** Eigener Typ, damit der Scheduler-Nachrichtt nicht dieselbe Klausel trifft. */
    private record Schedule(String value) {
        static final Schedule LATER = new Schedule("later");
    }

    private static void awaitUntil(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    // ---------------------------------------------------------------- registry

    @Test
    @DisplayName("spawn registriert den Namen und liefert eine lebende Ref")
    void spawnRegistersName() {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("worker", ctx -> Receive.empty()));

        assertThat(ref.name()).isEqualTo("worker");
        assertThat(ref.path().toString()).contains("worker");
        assertThat(ref.isAlive()).isTrue();
        assertThat(sys.whereis("worker")).contains(ref);
        assertThat(sys.names()).containsExactly("worker");
        assertThat(sys.actorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("doppelter Name wird abgelehnt")
    void duplicateNameRejected() {
        ActorSystem sys = system();
        sys.spawn(new TestActor("worker", ctx -> Receive.empty()));

        assertThatThrownBy(() -> sys.spawn(new TestActor("worker", ctx -> Receive.empty())))
                .isInstanceOf(ActorNameTakenException.class);
        assertThat(sys.actorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("anonyme Actoren haben keinen Registry-Eintrag")
    void anonymousActorIsNotRegistered() {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor(null, ctx -> Receive.empty()), null);

        assertThat(ref.anonymous()).isTrue();
        assertThat(ref.name()).startsWith("~");
        assertThat(sys.names()).isEmpty();
    }

    @Test
    @DisplayName("der Name verschwindet, wenn der Actor endet")
    void nameIsReleasedOnStop() {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("worker", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.stop();
                    return Behavior.NEXT;
                }))));

        ref.tell("bye");
        awaitUntil(() -> !sys.whereis("worker").isPresent(), "registry release");

        assertThat(ref.isAlive()).isFalse();
        assertThat(sys.actorCount()).isZero();
    }

    // ---------------------------------------------------------------- dispatch

    @Test
    @DisplayName("erste passende Klausel gewinnt, unpassende fallen durch")
    void firstMatchWins() {
        List<String> log = new ArrayList<>();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("router", Receive.of(
                Receive.Clause.when(m -> "x".equals(m), (m, c) -> {
                    log.add("guard:" + m);
                    return Behavior.NEXT;
                }),
                Receive.Clause.of(String.class, (m, c) -> {
                    log.add("string:" + m);
                    return Behavior.NEXT;
                }),
                Receive.Clause.any((m, c) -> {
                    log.add("any:" + m);
                    return Behavior.NEXT;
                }))));

        ref.tell("x");
        ref.tell("y");
        ref.tell(42);
        awaitUntil(() -> log.size() == 3, "three messages");

        assertThat(log).containsExactly("guard:x", "string:y", "any:42");
    }

    @Test
    @DisplayName("become schaltet das Verhalten um")
    void becomeSwitchesBehavior() {
        List<String> log = new ArrayList<>();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("phoenix", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    if ("ignite".equals(m)) {
                        log.add("ignite");
                        c.become(Receive.of(Receive.Clause.any((m2, c2) -> {
                            log.add("burning:" + m2);
                            return Behavior.NEXT;
                        })));
                        return Behavior.NEXT;
                    }
                    log.add("cold:" + m);
                    return Behavior.NEXT;
                }))));

        ref.tell("hello");
        ref.tell("ignite");
        ref.tell("after");
        awaitUntil(() -> log.size() == 3, "become chain");

        assertThat(log).containsExactly("cold:hello", "ignite", "burning:after");
    }

    @Test
    @DisplayName("unbehandelte Nachrichten landen in onUnhandled")
    void unhandledHookSeesLeftovers() {
        List<Object> unhandled = new ArrayList<>();
        AtomicInteger handled = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("picky",
                ctx -> Receive.of(Receive.Clause.of(Integer.class, (m, c) -> {
                    handled.incrementAndGet();
                    return Behavior.NEXT;
                })),
                (ctx, message) -> {
                    unhandled.add(message);
                    return Behavior.NEXT;
                }));

        ref.tell(1);
        ref.tell("text");
        ref.tell(3.5d);
        awaitUntil(() -> unhandled.size() == 2, "leftovers reached onUnhandled");

        assertThat(handled.get()).isEqualTo(1);
        assertThat(unhandled).containsExactly("text", 3.5d);
        assertThat(ref.isAlive()).as("onUnhandled beendet nicht").isTrue();
    }

    @Test
    @DisplayName("UNHANDLED laesst die Klauseln weiterlaufen")
    void unhandledFallsThroughToNextClause() {
        List<String> log = new ArrayList<>();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("fallthrough", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    if ("skip".equals(m)) {
                        return Behavior.UNHANDLED;
                    }
                    log.add("first:" + m);
                    return Behavior.NEXT;
                }),
                Receive.Clause.any((m, c) -> {
                    log.add("second:" + m);
                    return Behavior.NEXT;
                }))));

        ref.tell("skip");
        ref.tell("kept");
        awaitUntil(() -> log.size() == 2, "fallthrough");

        assertThat(log).containsExactly("second:skip", "first:kept");
    }

    @Test
    @DisplayName("messagesProcessed zaehlt nur Nutzernachrichten")
    void countsProcessedMessages() {
        AtomicInteger count = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("counter", Receive.of(
                Receive.Clause.any((m, c) -> {
                    count.incrementAndGet();
                    return Behavior.NEXT;
                }))));

        ref.tell("a");
        ref.tell("b");
        ref.tell("c");
        awaitUntil(() -> count.get() == 3, "three messages");

        assertThat(ActorCell.cellOf(ref).messagesProcessed()).isEqualTo(3);
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("HALT beendet den Actor normal")
    void haltStopsNormally() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("halter", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> Behavior.HALT))));

        ref.tell("stop-me");
        CompletableFuture<ExitReason> death = ActorCell.cellOf(ref).deathFuture();
        assertThat(death.get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitReason.Normal.class);
        assertThat(ref.isAlive()).isFalse();
    }

    @Test
    @DisplayName("graceful stop verarbeitet alles, was vorher angenommen wurde")
    void gracefulStopDrainsAcceptedMail() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor(
                "drainer", MailboxOverflow.BLOCK, 64,
                ctx -> Receive.of(Receive.Clause.any((m, c) -> {
                    if (processed.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return Behavior.NEXT;
                }))));

        ref.tell("first");
        assertThat(firstEntered.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            ref.tell("later-" + i);
        }
        assertThat(ActorCell.cellOf(ref).mailboxSize()).isEqualTo(4);

        ActorCell.cellOf(ref).stop();
        release.countDown();

        ExitReason reason = ActorCell.cellOf(ref).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Normal.class);
        assertThat(processed.get()).isEqualTo(5);
    }

    @Test
    @DisplayName("kill verwirft die wartende Mail")
    void killDiscardsMailbox() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor(
                "victim", MailboxOverflow.BLOCK, 64,
                ctx -> Receive.of(Receive.Clause.any((m, c) -> {
                    if (processed.incrementAndGet() == 1) {
                        firstEntered.countDown();
                        try {
                            release.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    return Behavior.NEXT;
                }))));

        ref.tell("first");
        assertThat(firstEntered.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            ref.tell("later-" + i);
        }

        ActorCell.cellOf(ref).kill();
        release.countDown();

        ExitReason reason = ActorCell.cellOf(ref).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Terminated.class);
        assertThat(processed.get()).isEqualTo(1);
        assertThat(ActorCell.cellOf(ref).mailboxSize()).isZero();
    }

    @Test
    @DisplayName("nach dem Tod ist die Ref tot und nimmt nichts mehr an")
    void deadRefRejectsSilently() {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("ghost", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> Behavior.HALT))));
        ref.tell("die");
        awaitUntil(() -> !ref.isAlive(), "actor died");

        assertThatCode(() -> ref.tell("ignored")).doesNotThrowAnyException();
        assertThat(ref.tryTell("ignored")).isFalse();
    }

    @Test
    @DisplayName("Name kann ersetzt werden; der alte Actor stirbt hart")
    void spawnReplacingKillsPrevious() throws Exception {
        ActorSystem sys = system();
        ActorRef first = sys.spawn(new TestActor("slot", ctx -> Receive.empty()));
        ActorCell firstCell = ActorCell.cellOf(first);

        ActorRef second = sys.spawnReplacing(new TestActor("slot", ctx -> Receive.empty()), "slot");

        assertThat(firstCell.deathFuture().get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitReason.Terminated.class);
        assertThat(second).isNotSameAs(first);
        assertThat(sys.whereis("slot")).contains(second);
    }

    // ---------------------------------------------------------------- ask/reply

    @Test
    @DisplayName("ask von aussen liefert die Antwort")
    void askFromOutside() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("echo", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.reply("echo:" + m);
                    return Behavior.NEXT;
                }))));

        assertThat(ref.ask("ping", SHORT).get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo("echo:ping");
    }

    @Test
    @DisplayName("ask laeuft in einen Timeout, wenn niemand antwortet")
    void askTimesOut() {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("silent", Receive.empty()));

        CompletableFuture<Object> future = ref.ask("hello", Duration.ofMillis(80));
        assertThatThrownBy(() -> future.get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AskTimeoutException.class);
    }

    @Test
    @DisplayName("ask von Actor zu Actor laeuft ueber den Absender-Actor")
    void askFromInsideActor() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Object> answer = new AtomicReference<>();
        ActorSystem sys = system();
        ActorRef echo = sys.spawn(new TestActor("echo", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.reply("echo:" + m);
                    return Behavior.NEXT;
                }))));
        ActorRef client = sys.spawn(new TestActor("client", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    CompletableFuture<Object> future = c.ask(echo, m, SHORT);
                    future.thenAccept(value -> {
                        answer.set(value);
                        done.countDown();
                    });
                    return Behavior.NEXT;
                }))));

        client.tell("hey");
        assertThat(done.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(answer.get()).isEqualTo("echo:hey");
        assertThat(client.isAlive()).isTrue();
    }

    @Test
    @DisplayName("replyAndStop beantwortet und beendet")
    void replyAndStop() throws Exception {
        AtomicReference<Object> answer = new AtomicReference<>();
        ActorSystem sys = system();
        ActorRef bye = sys.spawn(new TestActor("bye", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.replyAndStop("last words");
                    return Behavior.NEXT;
                }))));

        bye.ask("hi", SHORT).thenAccept(answer::set);
        ExitReason reason = ActorCell.cellOf(bye).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reason).isInstanceOf(ExitReason.Normal.class);
        awaitUntil(() -> "last words".equals(answer.get()), "reply delivered");
    }

    @Test
    @DisplayName("offene Calls brechen ab, wenn der Aufrufer stirbt")
    void pendingCallAbortsWhenCallerDies() throws Exception {
        ActorSystem sys = system();
        ActorRef silent = sys.spawn(new TestActor("silent", Receive.empty()));
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        ActorRef caller = sys.spawn(new TestActor("caller", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.ask(silent, m, Duration.ofSeconds(30)).whenComplete((v, t) -> {
                        error.set(t);
                        answered.countDown();
                    });
                    asked.countDown();
                    return Behavior.NEXT;
                }))));

        caller.tell("hang");
        assertThat(asked.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        ActorCell.cellOf(caller).kill();

        assertThat(answered.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(ActorTerminatedException.class);
    }

    // ---------------------------------------------------------------- monitoring

    @Test
    @DisplayName("monitor liefert die deathFuture des Ziel-Actors")
    void monitorReturnsDeathFuture() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("watched", ctx -> Receive.empty()));
        AtomicReference<CompletableFuture<ExitReason>> monitor = new AtomicReference<>();

        ActorRef watcher = sys.spawn(new TestActor("watcher", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    monitor.set(c.monitor(ref));
                    return Behavior.NEXT;
                }))));
        watcher.tell("watch");
        awaitUntil(() -> monitor.get() != null, "monitor registered");

        ActorCell.cellOf(ref).stop(ExitReason.shutdown());

        assertThat(monitor.get().get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(ExitReason.shutdown());
    }

    // ---------------------------------------------------------------- links

    @Test
    @DisplayName("link ueberlebt ein normales Ende des Partners")
    void normalExitDoesNotPropagate() throws Exception {
        ActorSystem sys = system();
        ActorRef peer = sys.spawn(new TestActor("peer", ctx -> Receive.empty()));
        AtomicReference<CompletableFuture<ExitReason>> monitor = new AtomicReference<>();
        ActorRef owner = sys.spawn(new TestActor("owner", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.link(peer);
                    monitor.set(c.monitor(peer));
                    return Behavior.NEXT;
                }))));
        owner.tell("link");
        awaitUntil(() -> monitor.get() != null, "linked");

        ActorCell.cellOf(peer).stop();
        assertThat(monitor.get().get(SHORT.toMillis(), TimeUnit.MILLISECONDS))
                .isInstanceOf(ExitReason.Normal.class);
        Thread.sleep(80);

        assertThat(owner.isAlive()).as("normales Ende reicht nicht weiter").isTrue();
    }

    @Test
    @DisplayName("link reicht abnorme Enden weiter")
    void abnormalExitPropagates() throws Exception {
        ActorSystem sys = system();
        ActorRef peer = sys.spawn(new TestActor("peer", ctx -> Receive.empty()));
        ActorRef owner = sys.spawn(new TestActor("owner", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.link(peer);
                    return Behavior.NEXT;
                }))));
        owner.tell("link");
        awaitUntil(() -> ActorCell.cellOf(peer).linkCount() == 1, "linked both ways");

        ActorCell.cellOf(peer).kill();

        ExitReason reason = ActorCell.cellOf(owner).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Terminated.class);
    }

    @Test
    @DisplayName("link auf einen bereits Gestorbenen liefert sofort ein Exit-Signal")
    void linkToDeadActorExitsImmediately() throws Exception {
        ActorSystem sys = system();
        ActorRef dead = sys.spawn(new TestActor("dead", ctx -> Receive.empty()));
        ActorCell.cellOf(dead).kill();
        ActorCell.cellOf(dead).deathFuture().get(SHORT.toMillis(), TimeUnit.MILLISECONDS);

        ActorRef late = sys.spawn(new TestActor("late", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.link(dead);
                    return Behavior.NEXT;
                }))));
        late.tell("link-now");

        ExitReason reason = ActorCell.cellOf(late).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Terminated.class);
    }

    @Test
    @DisplayName("self-link ist verboten")
    void selfLinkRejected() {
        ActorSystem sys = system();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ActorRef ref = sys.spawn(new TestActor("narcissus", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    try {
                        c.link(c.self());
                    } catch (RuntimeException e) {
                        error.set(e);
                    }
                    return Behavior.NEXT;
                }))));
        ref.tell("mirror");
        awaitUntil(() -> error.get() != null, "self link rejected");

        assertThat(error.get()).isInstanceOf(IllegalStateException.class);
        assertThat(ref.isAlive()).isTrue();
    }

    // ---------------------------------------------------------------- blocking

    @Test
    @DisplayName("runBlocking liefert das Ergebnis in onResult zurueck")
    void runBlockingReturnsResult() throws Exception {
        AtomicReference<Object> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("worker", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.runBlocking(() -> {
                        Thread.sleep(20);
                        return "computed:" + m;
                    }, (value, error) -> {
                        result.set(error == null ? value : error);
                        done.countDown();
                    });
                    return Behavior.NEXT;
                }))));

        ref.tell("payload");

        assertThat(done.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(result.get()).isEqualTo("computed:payload");
    }

    @Test
    @DisplayName("runBlocking meldet Exceptions statt zu verschlucken")
    void runBlockingReportsError() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("worker", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.runBlocking(() -> {
                        throw new IllegalStateException("boom");
                    }, (value, t) -> {
                        error.set(t);
                        done.countDown();
                    });
                    return Behavior.NEXT;
                }))));

        ref.tell("fail");
        assertThat(done.await(SHORT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
    }

    @Test
    @DisplayName("der Actor-Thread parkt im Leerlauf und wacht auf tell")
    void actorParksWhenIdle() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("sleeper", Receive.of(
                Receive.Clause.any((m, c) -> {
                    hits.incrementAndGet();
                    return Behavior.NEXT;
                }))));

        ref.tell("first");
        awaitUntil(() -> hits.get() == 1, "erste Nachricht");
        ActorCell cell = ActorCell.cellOf(ref);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && cell.thread().getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        assertThat(cell.thread().getState())
                .as("Leerlauf liegt im Park, nicht im Busy-Loop")
                .isEqualTo(Thread.State.WAITING);

        assertThat(ref.tryTell("second")).isTrue();
        awaitUntil(() -> hits.get() == 2, "zweite Nachricht weckt den Actor");
    }

    // ---------------------------------------------------------------- schedule

    @Test
    @DisplayName("schedule stellt eine Nachricht zeitversetzt zu")
    void scheduleDeliversLater() {
        AtomicInteger hits = new AtomicInteger();
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("ticker", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.schedule(Schedule.LATER, Duration.ofMillis(30));
                    return Behavior.NEXT;
                }),
                Receive.Clause.of(Schedule.class, (m, c) -> {
                    hits.incrementAndGet();
                    return Behavior.NEXT;
                }))));

        ref.tell("start");
        awaitUntil(() -> hits.get() == 1, "scheduled message");
    }

    // ---------------------------------------------------------------- crashes

    @Test
    @DisplayName("eine Exception im Handler beendet den Actor mit Failure")
    void handlerExceptionStopsActor() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("fragile", Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    throw new IllegalArgumentException("kaputt");
                }))));

        ref.tell("boom");
        ExitReason reason = ActorCell.cellOf(ref).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reason).isInstanceOf(ExitReason.Failure.class);
        assertThat(((ExitReason.Failure) reason).cause()).hasMessage("kaputt");
        assertThat(ActorCell.cellOf(ref).crashCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("eine Exception in onStart beendet den Actor ebenfalls")
    void startExceptionStopsActor() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("unborn", ctx -> {
            throw new IllegalStateException("start kaputt");
        }));

        ExitReason reason = ActorCell.cellOf(ref).deathFuture()
                .get(SHORT.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(reason).isInstanceOf(ExitReason.Failure.class);
        assertThat(((ExitReason.Failure) reason).cause()).hasMessage("start kaputt");
    }

    // ---------------------------------------------------------------- shutdown

    @Test
    @DisplayName("close() stoppt alle Actoren — auch anonyme — und ist idempotent")
    void closeStopsAllActors() throws Exception {
        ActorSystem sys = system();
        ActorRef named = sys.spawn(new TestActor("worker", ctx -> Receive.empty()));
        ActorRef anon = sys.spawn(new TestActor(null, ctx -> Receive.empty()), null);
        assertThat(sys.actorCount()).isEqualTo(2);
        assertThat(sys.actors()).containsExactlyInAnyOrder(named, anon);
        assertThat(sys.isRunning()).isTrue();

        sys.close();

        assertThat(sys.isRunning()).isFalse();
        assertThat(named.isAlive()).isFalse();
        assertThat(anon.isAlive()).isFalse();
        assertThat(sys.actorCount()).isZero();
        assertThat(sys.awaitTermination(SHORT)).isTrue();
        sys.close();                                  // Idempotenz: kein Fehler
    }

    @Test
    @DisplayName("shutdown() wartet auf einen blockierten Actor und killt ihn nach Ablauf")
    void shutdownKillsStuckActorAfterTimeout() throws Exception {
        ActorSystem sys = system();
        CountDownLatch blocked = new CountDownLatch(1);
        ActorRef stuck = sys.spawn(new TestActor("stuck", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    blocked.countDown();
                    try {
                        Thread.sleep(60_000);        // verboten im Produktivcode, Test-Zweck
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Behavior.NEXT;
                }))));
        stuck.tell("block");
        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        sys.shutdown(Duration.ofMillis(200));
        long took = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(stuck.isAlive()).as("nach dem Kill tot").isFalse();
        assertThat(took).as("nicht laenger als das Budget plus Toleranz")
                .isLessThan(5_000);
        // Der Kill unterbricht den blockierten Handler — die Zelle terminiert wirklich.
        ExitReason reason = ActorCell.cellOf(stuck).deathFuture().get(5, TimeUnit.SECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Terminated.class);
    }

    @Test
    @DisplayName("ActorCell.finish() meldet sich beim System ab — actorCount sinkt")
    void actorCountTracksLiveActors() throws Exception {
        ActorSystem sys = system();
        ActorRef ref = sys.spawn(new TestActor("ephemeral", ctx -> Receive.of(
                Receive.Clause.of(String.class, (m, c) -> {
                    c.stop();
                    return Behavior.NEXT;
                }))));
        assertThat(sys.actorCount()).isEqualTo(1);

        ref.tell("bye");
        awaitUntil(() -> sys.actorCount() == 0, "actorCount sinkt");
        assertThat(sys.actors()).isEmpty();
    }
}
