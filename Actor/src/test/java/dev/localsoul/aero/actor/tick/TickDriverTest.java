package dev.localsoul.aero.actor.tick;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.TestActor;
import dev.localsoul.aero.actor.TestRefs;
import dev.localsoul.aero.actor.session.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TickDriverTest {

    private static final Duration FAST = Duration.ofMillis(5);

    private TickDriver driver;
    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.close();
        }
        if (system != null) {
            system.close();
        }
    }

    private TickDriver driver(Duration interval, OverrunPolicy policy) {
        return driver = new TickDriver(interval, policy);
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

    @Test
    @DisplayName("ungueltige Intervalle werden abgewiesen")
    void rejectsBadInterval() {
        assertThatThrownBy(() -> new TickDriver(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TickDriver(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TickDriver(FAST, OverrunPolicy.STEP, -1, 1_000L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TickDriver(FAST, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Ticks laufen monoton und im Takt")
    void ticksAreMonotonic() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        AtomicInteger received = new AtomicInteger();
        CountDownLatchHarness latch = new CountDownLatchHarness(10);
        TestRefs.FakeRef room = new TestRefs.FakeRef("room").onMessage(message -> {
            if (message instanceof Tick tick && tick.number() > 0) {
                received.incrementAndGet();
                latch.countDown();
            }
        });
        d.subscribe(room);
        d.start();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        long first = d.tickCount();
        Thread.sleep(120);
        long second = d.tickCount();

        assertThat(second).isGreaterThan(first);
        assertThat(d.driverErrors()).isZero();
        assertThat(d.isRunning()).isTrue();
        assertThat(d.subscriberCount()).isEqualTo(1);
        assertThat(d.interval()).isEqualTo(FAST);
    }

    @Test
    @DisplayName("der erste Tick ist als first markiert und misst elapsed")
    void firstTickIsMarked() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        List<Tick> seen = new CopyOnWriteArrayList<>();
        d.subscribe(new TestRefs.FakeRef("room").onMessage(m -> seen.add((Tick) m)));
        d.start();

        awaitUntil(() -> seen.size() >= 2, "zwei Ticks");
        assertThat(seen.get(0).first()).isTrue();
        assertThat(seen.get(0).number()).isEqualTo(1);
        assertThat(seen.get(1).first()).isFalse();
        assertThat(seen.get(1).number()).isEqualTo(2);
        assertThat(seen.get(1).elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("jeder Tick kommt genau einmal an — keine Doppelzaehlung")
    void everyTickDeliveredExactlyOnce() throws Exception {
        for (OverrunPolicy policy : OverrunPolicy.values()) {
            List<Long> numbers = new CopyOnWriteArrayList<>();
            try (TickDriver d = new TickDriver(Duration.ofMillis(1), policy, 3,
                    Duration.ofSeconds(30).toNanos())) {
                d.subscribe(TestRefs.FakeRef.slow(Duration.ofMillis(2))
                        .onMessage(m -> numbers.add(((Tick) m).number())));
                d.start();
                Thread.sleep(200);
            }
            assertThat(numbers).as(policy + ": lueckenlos und ohne Duplikate")
                    .isSorted()
                    .doesNotHaveDuplicates();
            assertThat(numbers).isNotEmpty();
        }
    }

    @Test
    @DisplayName("close() stoppt den Driver")
    void closeStopsDriver() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        d.start();
        awaitUntil(() -> d.tickCount() > 2, "ticks laufen");

        d.close();
        long afterClose = d.tickCount();
        Thread.sleep(80);

        assertThat(d.tickCount()).isEqualTo(afterClose);
        assertThat(d.isRunning()).isFalse();
        assertThat(d.isClosed()).isTrue();
    }

    @Test
    @DisplayName("subscribe nach close() wird abgewiesen")
    void subscribeAfterCloseRejected() {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        d.close();
        assertThatThrownBy(() -> d.subscribe(new TestRefs.FakeRef("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("STEP holt gedeckelt nach, CLAMP verwirft verpasste Ticks")
    void stepCatchesUpCapped() throws Exception {
        // Deterministischer Lag: der Subscriber blockiert laenger als das Intervall.
        try (TickDriver step = new TickDriver(Duration.ofMillis(1), OverrunPolicy.STEP, 2,
                Duration.ofSeconds(30).toNanos());
             TickDriver clamp = new TickDriver(Duration.ofMillis(1), OverrunPolicy.CLAMP, 0,
                     Duration.ofSeconds(30).toNanos());
             TickDriver drop = new TickDriver(Duration.ofMillis(1), OverrunPolicy.DROP, 0,
                     Duration.ofSeconds(30).toNanos())) {
            TestRefs.FakeRef slow = TestRefs.FakeRef.slow(Duration.ofMillis(4));
            step.subscribe(slow);
            clamp.subscribe(slow);
            drop.subscribe(slow);
            step.start();
            clamp.start();
            drop.start();
            Thread.sleep(300);

            assertThat(step.overruns()).as("STEP zaehlt die verpassten Deadlines").isPositive();
            assertThat(step.tickCount()).as("STEP holt nach")
                    .isGreaterThan(clamp.tickCount());
            assertThat(drop.tickCount()).isPositive();
        }
    }

    @Test
    @DisplayName("OverrunPolicy kennt sein Catch-up-Verhalten")
    void policySemantics() {
        assertThat(OverrunPolicy.CLAMP.catchesUp()).isFalse();
        assertThat(OverrunPolicy.DROP.catchesUp()).isFalse();
        assertThat(OverrunPolicy.STEP.catchesUp()).isTrue();
        assertThat(OverrunPolicy.values()).hasSize(3);
        // STEP braucht einen Deckel, CLAMP nicht.
        assertThat(new TickDriver(FAST, OverrunPolicy.STEP).maxCatchUpTicks()).isPositive();
        assertThat(new TickDriver(FAST, OverrunPolicy.CLAMP).maxCatchUpTicks()).isZero();
    }

    @Test
    @DisplayName("ein voller Subscriber bremst die anderen nicht aus")
    void fullSubscriberDoesNotStallOthers() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        system = new ActorSystem("tick");

        // Postfach mit genau einem Platz und FAIL: der Actor verarbeitet nichts,
        // also ist es nach dem ersten Tick dauerhaft voll.
        ActorRef blocked = system.spawn(new TestActor("blocked", dev.localsoul.aero.actor.MailboxOverflow.FAIL,
                2, ctx -> {
                    var never = new java.util.concurrent.CountDownLatch(1);
                    return Receive.of(Receive.Clause.any((m, c) -> {
                        try {
                            never.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return Behavior.NEXT;
                    }));
                }));

        AtomicInteger healthy = new AtomicInteger();
        TestRefs.FakeRef good = new TestRefs.FakeRef("good").onMessage(m -> healthy.incrementAndGet());
        d.subscribe(blocked);
        d.subscribe(good);
        d.start();

        awaitUntil(() -> d.deliverFailures() > 3, "voller Subscriber meldet Fehler");
        int healthyAtFailure = healthy.get();
        Thread.sleep(100);

        assertThat(healthy.get()).as("andere Subscriber bekommen weiter Ticks")
                .isGreaterThan(healthyAtFailure);
        assertThat(d.tickCount()).isGreaterThan(3);
        assertThat(d.driverErrors()).isZero();
        assertThat(d.isRunning()).isTrue();
        dev.localsoul.aero.actor.internal.ActorCell.cellOf(blocked).kill();
    }

    @Test
    @DisplayName("ein werfender Subscriber beendet den Driver nicht")
    void throwingSubscriberIsIsolated() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        AtomicInteger good = new AtomicInteger();
        List<ActorRef> failed = new CopyOnWriteArrayList<>();
        d.onSubscriberError((room, error) -> failed.add(room));
        d.subscribe(new TestRefs.FakeRef("broken").exploding());
        d.subscribe(new TestRefs.FakeRef("good").onMessage(m -> good.incrementAndGet()));
        d.start();

        awaitUntil(() -> good.get() > 5, "gute Subscriber laufen weiter");

        assertThat(d.driverErrors()).isPositive();
        assertThat(failed).as("onSubscriberError wurde bedient").isNotEmpty();
        assertThat(d.isRunning()).isTrue();
    }

    @Test
    @DisplayName("die Subscription meldet den Raum ab")
    void subscriptionCancels() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        AtomicInteger ticks = new AtomicInteger();
        ActorRef room = new TestRefs.FakeRef("room").onMessage(m -> ticks.incrementAndGet());
        Subscription subscription = d.subscribe(room);
        d.start();
        awaitUntil(() -> ticks.get() > 2, "ticks ankommen");

        subscription.cancel();
        int after = ticks.get();
        Thread.sleep(80);

        assertThat(ticks.get()).isEqualTo(after);
        assertThat(d.subscriberCount()).isZero();
        assertThat(subscription.isCancelled()).isTrue();
        subscription.cancel();                            // idempotent
    }

    @Test
    @DisplayName("doppeltes subscribe zaehlt nur einmal")
    void subscribeIsIdempotent() {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        ActorRef ref = new TestRefs.FakeRef("room");
        d.subscribe(ref);
        d.subscribe(ref);
        assertThat(d.subscriberCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("der Watchdog meldet einen stillstehenden Driver")
    void watchdogDetectsStall() throws Exception {
        TickDriver d = new TickDriver(FAST, OverrunPolicy.CLAMP, 0, 1L);
        d.start();

        awaitUntil(() -> d.stalls() > 0, "Watchdog meldet Stillstand");
        assertThat(d.isRunning()).as("der Driver laeuft weiter").isTrue();
    }

    @Test
    @DisplayName("start()/ensureRunning() sind idempotent — kein Doppelpump")
    void startIsIdempotent() throws Exception {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        d.start();
        d.start();
        d.ensureRunning();
        d.ensureRunning();
        awaitUntil(() -> d.tickCount() > 2, "ticks laufen");

        assertThat(d.restarts()).as("mehrfaches Starten ist kein Restart").isZero();
        assertThat(d.isRunning()).isTrue();
    }

    @Test
    @DisplayName("start() nach close() startet nicht wieder")
    void startAfterCloseStaysClosed() {
        TickDriver d = driver(FAST, OverrunPolicy.CLAMP);
        d.close();
        d.start();
        d.ensureRunning();
        assertThat(d.isRunning()).isFalse();
    }

    @Test
    @DisplayName("die statischen Fabriken starten sofort")
    void staticFactories() throws Exception {
        system = new ActorSystem("tick");
        try (TickDriver at60 = TickDriver.at60Hz(system);
             TickDriver started = TickDriver.start(system, FAST);
             TickDriver stepped = TickDriver.start(system, FAST, OverrunPolicy.STEP)) {
            assertThat(at60.isRunning()).isTrue();
            assertThat(started.isRunning()).isTrue();
            assertThat(stepped.policy()).isEqualTo(OverrunPolicy.STEP);
            awaitUntil(() -> at60.tickCount() > 0 && started.tickCount() > 0, "ticks");
        }
    }

    /** Winziger Ersatz fuer ein CountDownLatch ohne zusaetzlichen Import. */
    private static final class CountDownLatchHarness {

        private final java.util.concurrent.CountDownLatch latch;

        CountDownLatchHarness(int n) {
            this.latch = new java.util.concurrent.CountDownLatch(n);
        }

        void countDown() {
            latch.countDown();
        }

        boolean await(long time, TimeUnit unit) throws InterruptedException {
            return latch.await(time, unit);
        }
    }
}
