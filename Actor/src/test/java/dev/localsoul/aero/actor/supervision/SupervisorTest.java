package dev.localsoul.aero.actor.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.TestActor;
import dev.localsoul.aero.actor.internal.ActorCell;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SupervisorTest {

    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    /** Zaehlt, wie oft der Supervisor eine Child-Factory aufgerufen hat. */
    private final Map<String, AtomicInteger> starts = new ConcurrentHashMap<>();

    private ChildFactory child() {
        return name -> {
            starts.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            // UNHANDLED als Startverhalten: damit laeuft jede Nachricht durch
            // onUnhandled, wo "boom" den Actor absichtlich abstuerzen laesst.
            return new TestActor(name, ctx -> Behavior.UNHANDLED, (ctx, message) -> {
                if ("boom".equals(message)) {
                    throw new IllegalStateException("boom: " + name);
                }
                return Behavior.NEXT;
            });
        };
    }

    private ChildSpec spec(String id) {
        return ChildSpec.of(id, child());
    }

    /** Referenz des zuletzt gestarteten Supervisors. */
    private ActorRef lastRef;

    private Supervisor supervisor(RestartStrategy strategy, ChildSpec... children) {
        SupervisorSpec.Builder builder = SupervisorSpec.builder().strategy(strategy);
        for (ChildSpec child : children) {
            builder.child(child);
        }
        return start(builder.build());
    }

    /** Wartet, bis der Supervisor alle Kinder gestartet hat. */
    private static Supervisor ready(Supervisor sup, int children) {
        try {
            awaitUntil(() -> sup.childCount() == children, "children started");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        return sup;
    }

    private Supervisor start(SupervisorSpec spec) {
        system = new ActorSystem("sup");
        lastRef = system.spawn(new Supervisor("root", spec));
        return (Supervisor) ActorCell.cellOf(lastRef).actor();
    }

    @Test
    @DisplayName("Kinder starten beim Supervisor-Start")
    void childrenStart() {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"), spec("b"));
        ready(sup, 2);

        assertThat(sup.childCount()).isEqualTo(2);
        assertThat(sup.child("a")).isNotNull();
        assertThat(sup.child("a").isAlive()).isTrue();
        assertThat(sup.child("b").isAlive()).isTrue();
        assertThat(starts.get("a").get()).isEqualTo(1);
        assertThat(sup.spec().strategy()).isEqualTo(RestartStrategy.ONE_FOR_ONE);
        assertThat(sup.lastEscalation()).isNull();
    }

    @Test
    @DisplayName("childInfos() liefert Startreihenfolge und Restart-Zaehler")
    void childInfos() {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"), spec("b"));
        ready(sup, 2);
        List<Supervisor.ChildInfo> infos = sup.childInfos();

        assertThat(infos).extracting(Supervisor.ChildInfo::id).containsExactly("a", "b");
        assertThat(infos).extracting(Supervisor.ChildInfo::alive).containsOnly(true);
        assertThat(infos).extracting(Supervisor.ChildInfo::restarts).containsOnly(0);
    }

    @Test
    @DisplayName("ONE_FOR_ONE startet nur das abgestuerzte Kind neu")
    void oneForOneRestartsOnlyCrashedChild() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"), spec("b"));
        ready(sup, 2);
        ActorRef originalA = sup.child("a");
        ActorRef originalB = sup.child("b");

        sup.child("a").tell("boom");

        awaitUntil(() -> starts.get("a").get() == 2, "Kind a neu gestartet");
        awaitUntil(() -> !originalA.isAlive(), "alte Instanz tot");
        assertThat(starts.get("b").get()).as("Geschwister unberuehrt").isEqualTo(1);
        assertThat(sup.child("b")).as("gleiche Instanz").isSameAs(originalB);
        assertThat(sup.child("a")).isNotSameAs(originalA);
        assertThat(sup.childInfos()).filteredOn(i -> i.id().equals("a"))
                .extracting(Supervisor.ChildInfo::restarts).containsExactly(1);
    }

    @Test
    @DisplayName("ONE_FOR_ALL startet alle Kinder neu")
    void oneForAllRestartsEveryone() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ALL, spec("a"), spec("b"));
        ready(sup, 2);
        ActorRef originalA = sup.child("a");
        ActorRef originalB = sup.child("b");

        sup.child("a").tell("boom");

        awaitUntil(() -> starts.get("a").get() == 2 && starts.get("b").get() == 2,
                "beide neu gestartet");
        assertThat(originalA.isAlive()).isFalse();
        assertThat(originalB.isAlive()).isFalse();
        assertThat(sup.childCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("REST_FOR_ONE startet das Kind und alle spaeteren neu")
    void restForOneRestartsFromCrashedChild() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.REST_FOR_ONE, spec("a"), spec("b"), spec("c"));
        ready(sup, 3);
        ActorRef originalA = sup.child("a");
        ActorRef originalB = sup.child("b");

        sup.child("b").tell("boom");

        awaitUntil(() -> starts.get("b").get() == 2 && starts.get("c").get() == 2,
                "b und c neu gestartet");
        assertThat(starts.get("a").get()).as("vor dem Crash gestartet bleibt stehen").isEqualTo(1);
        assertThat(sup.child("a")).isSameAs(originalA);
        assertThat(sup.child("b")).isNotSameAs(originalB);
    }

    @Test
    @DisplayName("TEMPORARY startet nie neu")
    void temporaryNeverRestarts() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE,
                ChildSpec.of("t", child(), RestartType.TEMPORARY));
        ready(sup, 1);
        ActorRef ref = sup.child("t");

        ref.tell("boom");

        awaitUntil(() -> !ref.isAlive(), "Kind tot");
        Thread.sleep(50);
        assertThat(starts.get("t").get()).isEqualTo(1);
        assertThat(sup.childCount()).isZero();
    }

    @Test
    @DisplayName("TRANSIENT startet nur bei abnormalem Exit neu")
    void transientRestartsOnlyAbnormal() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE,
                ChildSpec.of("t", child(), RestartType.TRANSIENT));
        ready(sup, 1);
        ActorRef ref = sup.child("t");

        ActorCell.cellOf(ref).stop();                     // normaler Stopp

        awaitUntil(() -> !ref.isAlive(), "Kind beendet");
        awaitUntil(() -> sup.childCount() == 0, "Kind entfernt");
        assertThat(starts.get("t").get()).as("normaler Stopp ist kein Grund").isEqualTo(1);
        assertThat(sup.child("t")).isNull();
    }

    @Test
    @DisplayName("TRANSIENT startet nach einem Crash neu")
    void transientRestartsAfterCrash() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE,
                ChildSpec.of("t", child(), RestartType.TRANSIENT));
        ready(sup, 1);

        ActorRef original = sup.child("t");
        sup.child("t").tell("boom");

        // Der Neustart ist atomar (children.compute), aber die Factory laeuft
        // INNERHALB des compute: starts==2 ist sichtbar, bevor die Map die neue
        // Referenz haelt. Deshalb warten wir auf die neue, lebende Referenz.
        awaitUntil(() -> {
            ActorRef current = sup.child("t");
            return current != original && current.isAlive();
        }, "Neustart nach Crash");
        assertThat(starts.get("t").get()).isEqualTo(2);
        assertThat(sup.child("t").isAlive()).isTrue();
    }

    @Test
    @DisplayName("PERMANENT startet auch nach normalem Stopp neu")
    void permanentRestartsAfterNormalStop() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE,
                ChildSpec.of("p", child(), RestartType.PERMANENT));
        ready(sup, 1);
        ActorRef original = sup.child("p");

        ActorCell.cellOf(original).stop();

        awaitUntil(() -> {
            ActorRef current = sup.child("p");
            return current != original && current.isAlive();
        }, "Neustart nach normalem Stopp");
        assertThat(starts.get("p").get()).isEqualTo(2);
        assertThat(sup.child("p")).isNotSameAs(original);
    }

    @Test
    @DisplayName("ein dauerhaft crashendes Kind eskaliert den Supervisor")
    void escalationAfterBudget() throws Exception {
        system = new ActorSystem("sup");
        SupervisorSpec spec = SupervisorSpec.builder()
                .strategy(RestartStrategy.ONE_FOR_ONE)
                .maxRestarts(2, Duration.ofSeconds(30))
                .child(ChildSpec.of("bad", child()))
                .build();
        Supervisor sup = start(spec);
        ready(sup, 1);
        assertThat(spec.maxRestarts()).isEqualTo(2);

        for (int i = 0; i < 4; i++) {
            ActorRef current = sup.child("bad");
            if (current != null) {
                current.tell("boom");
            }
            Thread.sleep(40);
        }

        awaitUntil(() -> sup.lastEscalation() != null, "Eskalation");
        assertThat(sup.lastEscalation().restarts()).isEqualTo(2);
        assertThat(sup.lastEscalation().childReason()).isNotNull();
        assertThat(sup.lastEscalation()).hasMessageContaining("restart budget");
        awaitUntil(() -> !lastRef.isAlive(), "Supervisor beendet");
        // Die Eskalation ist sichtbar: der Supervisor stirbt mit der
        // EscalationException, nicht mit dem Grund des Kindes (implement.md 6.10).
        ExitReason reason = ActorCell.cellOf(lastRef).deathFuture().get(5, TimeUnit.SECONDS);
        assertThat(reason).isInstanceOf(ExitReason.Failure.class);
        assertThat(((ExitReason.Failure) reason).cause())
                .isInstanceOf(EscalationException.class)
                .hasMessageContaining("restart budget");
    }

    @Test
    @DisplayName("RestartChild erzwingt einen Neustart, StopChild beendet endgueltig")
    void restartAndStopCommands() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"), spec("b"));
        ready(sup, 2);

        lastRef.tell(new Supervisor.RestartChild("a"));
        awaitUntil(() -> starts.get("a").get() == 2, "RestartChild");

        lastRef.tell(new Supervisor.StopChild("b"));
        awaitUntil(() -> sup.child("b") == null, "StopChild hat Kind entfernt");
    }

    @Test
    @DisplayName("ChildrenInfo wird mit der Liste beantwortet")
    void childrenInfoReply() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"));
        ready(sup, 1);

        @SuppressWarnings("unchecked")
        List<Supervisor.ChildInfo> answer = (List<Supervisor.ChildInfo>) lastRef.ask(
                new Supervisor.ChildrenInfo(), Duration.ofSeconds(5)).get();

        assertThat(answer).extracting(Supervisor.ChildInfo::id).containsExactly("a");
    }

    @Test
    @DisplayName("Shutdown haelt den Supervisor an und stoppt die Kinder")
    void shutdownStopsEverything() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"));
        ready(sup, 1);
        ActorRef child = sup.child("a");
        ActorRef self = lastRef;

        self.tell(new Supervisor.Shutdown());

        awaitUntil(() -> !child.isAlive(), "Kind beendet");
        awaitUntil(() -> !self.isAlive(), "Supervisor beendet");
    }

    @Test
    @DisplayName("der Supervisor stoppt seine Kinder beim eigenen Stopp")
    void stoppingSupervisorStopsChildren() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("a"));
        ready(sup, 1);
        ActorRef child = sup.child("a");
        ActorRef self = lastRef;

        ActorCell.cellOf(self).stop();

        awaitUntil(() -> !child.isAlive(), "Kind beendet");
    }

    @Test
    @DisplayName("ohne linkChildren sieht der Supervisor den Tod nicht")
    void unlinkedChildrenAreNotRestarted() throws Exception {
        system = new ActorSystem("sup");
        SupervisorSpec spec = SupervisorSpec.builder()
                .strategy(RestartStrategy.ONE_FOR_ONE)
                .linkChildren(false)
                .child(ChildSpec.of("u", child()))
                .build();
        Supervisor sup = start(spec);
        ready(sup, 1);

        ActorCell.cellOf(sup.child("u")).kill();
        Thread.sleep(100);

        assertThat(starts.get("u").get()).isEqualTo(1);
        assertThat(sup.spec().linkChildren()).isFalse();
    }

    @Test
    @DisplayName("ChildSpec validiert seine Bestandteile")
    void childSpecValidation() {
        assertThat(ChildSpec.of("a", child()).restartType()).isEqualTo(RestartType.TRANSIENT);
        assertThat(ChildSpec.of("a", child()).withType(RestartType.PERMANENT).restartType())
                .isEqualTo(RestartType.PERMANENT);
        assertThat(ChildSpec.of("a", child()).asPermanent().permanent()).isTrue();
        assertThatThrownBy(() -> ChildSpec.of(null, child()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ChildSpec.of("a", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SupervisorSpec prueft maxRestarts")
    void specValidation() {
        assertThatThrownBy(() -> SupervisorSpec.builder().maxRestarts(0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SupervisorSpec.builder().build().maxRestarts()).isEqualTo(3);
        assertThat(SupervisorSpec.builder().build().maxRestartsWithin()).isEqualTo(Duration.ofSeconds(5));
        assertThat(SupervisorSpec.builder().build().shutdownReason()).isEqualTo("supervisor stopped");
    }

    @Test
    @DisplayName("ein Kind kann Nachrichten beantworten, waehrend der Supervisor laeuft")
    void childStillWorks() throws Exception {
        Supervisor sup = supervisor(RestartStrategy.ONE_FOR_ONE, spec("echo"));
        ready(sup, 1);
        // Das Kind ist ein ganz normaler Actor mit eigener Mailbox.
        assertThat(sup.child("echo").tryTell("hallo")).isTrue();
        assertThat(sup.child("echo").isAlive()).isTrue();
    }

    @Test
    @DisplayName("StartChild fuegt Kinder zur Laufzeit hinzu (UserSupervisor)")
    void dynamicChild() throws Exception {
        system = new ActorSystem("sup");
        UserSupervisor sup = new UserSupervisor("dyn");
        ActorRef supRef = system.spawn(sup);

        supRef.tell(new UserSupervisor.StartChild("late", child(), RestartType.PERMANENT));

        awaitUntil(() -> sup.childCount() == 1, "dynamisches Kind");
        ActorRef late = sup.child("late");
        assertThat(late).isNotNull();

        late.tell("boom");
        awaitUntil(() -> starts.get("late").get() == 2, "dynamisches Kind neu gestartet");
    }

    @Test
    @DisplayName("ein Kind mit eigener Behavior kann den Supervisor-Bericht beantworten")
    void supervisedEcho() throws Exception {
        system = new ActorSystem("sup");
        Actor echo = new Actor("echo") {
            @Override
            protected Behavior onStart(ActorContext ctx) {
                return Receive.of(Receive.Clause.on(String.class, (msg, c) -> {
                    c.reply("echo:" + msg);
                    return Behavior.NEXT;
                }));
            }
        };
        Supervisor sup = start(SupervisorSpec.builder().child(ChildSpec.of("echo", n -> echo)).build());
        ready(sup, 1);

        assertThat(sup.child("echo").ask("hi", Duration.ofSeconds(5)).get())
                .isEqualTo("echo:hi");
    }
}
