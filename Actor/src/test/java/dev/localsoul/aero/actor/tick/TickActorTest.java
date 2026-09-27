package dev.localsoul.aero.actor.tick;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.TestActor;
import dev.localsoul.aero.actor.TestClient;
import dev.localsoul.aero.actor.TestRefs;
import dev.localsoul.aero.actor.TestSession;
import dev.localsoul.aero.actor.internal.ActorCell;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TickActorTest {

    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
    }

    /** Tor, mit dem sich der Zell-Loop auf Befehl festfahren laesst. */
    private record Gate(java.util.concurrent.CountDownLatch blocked,
                        java.util.concurrent.CountDownLatch release) {
        static final Object LOCK = new Object();
        static Gate create() {
            return new Gate(new java.util.concurrent.CountDownLatch(1),
                    new java.util.concurrent.CountDownLatch(1));
        }
    }

    /**
     * Ein Raum, dessen erster Nicht-Tick-Handler blockiert. Damit laesst sich
     * ein Postfach erzeugen, in dem <b>der Tick vor den Eingaben</b> liegt — genau
     * die Lage, fuer die es {@code drainInbox} gibt.
     */
    private Room gatedRoom(String name, TickDriver driver, Gate gate) {
        java.util.concurrent.atomic.AtomicBoolean first =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        return new Room(name, driver, null) {
            @Override
            protected Behavior onMessage(Object message, ActorContext ctx) {
                if (first.getAndSet(false)) {
                    gate.blocked().countDown();
                    try {
                        gate.release().await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.onMessage(message, ctx);
            }
        };
    }

    /**
     * Der Tick muss <b>vor</b> den Eingaben in der Queue stehen, sonst leert der
     * FIFO-Loop sie vorher. Also: erst den Raum festfahren, dann den Tick
     * enqueuen lassen, dann die Eingaben dahinter einreihen.
     */
    private void queueInputsBehindTick(TickDriver driver, Gate gate, ActorRef ref, int count)
            throws InterruptedException {
        ref.tell(Gate.LOCK);                          // der Raum faengt hier an zu blockieren
        assertThat(gate.blocked().await(5, TimeUnit.SECONDS)).as("der Raum steckt fest").isTrue();
        long before = driver.tickCount();
        awaitUntil(() -> driver.tickCount() > before, "der Tick liegt in der Queue");
        try {
            Thread.sleep(10);                   // der Treiber muss noch zustellen
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (int i = 0; i < count; i++) {
            ref.tell("input-" + i);
        }
        gate.release().countDown();
    }

    /** Session-Actor starten und die Ref zurueckgeben — der Outbound-Ziel. */
    private ActorRef session(TestClient client) {
        return system.spawn(new TestSession("s" + client.id(), client));
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    /** Raum mit optionalem Client-Ziel und Beobachtung. */
    private static class Room extends TickActor {

        final TestClient client;
        volatile ActorRef session;
        final List<Tick> seen = new CopyOnWriteArrayList<>();
        final AtomicReference<Object> other = new AtomicReference<>();
        final CountDownLatch firstTick = new CountDownLatch(1);
        final AtomicInteger lagEvents = new AtomicInteger();
        final AtomicInteger maxDrained = new AtomicInteger();
        final AtomicInteger maxBacklog = new AtomicInteger();
        final AtomicInteger overruns = new AtomicInteger();
        final AtomicReference<ExitReason> stopReason = new AtomicReference<>();
        volatile java.util.function.Consumer<TickContext> perTick = tc -> { };
        volatile java.util.function.BiConsumer<Object, ActorContext> messageHook =
                (m, c) -> { };
        volatile int backlogLimit = 256;

        @Override
        protected int backlogLimit() {
            return backlogLimit;                 // das Feld steuert den Test
        }

        Room(String name, TickDriver driver, TestClient client) {
            this(name, driver, client, null);
        }

        Room(String name, TickDriver driver, TestClient client, ActorRef session) {
            super(name, driver);
            this.client = client;
            this.session = session;
        }

        @Override
        protected void onTick(Tick tick, TickContext tc) {
            seen.add(tick);
            perTick.accept(tc);
            if (client != null) {
                tc.send(session, "tick-" + tick.number());
            }
            firstTick.countDown();
        }

        @Override
        protected Behavior onMessage(Object message, ActorContext ctx) {
            messageHook.accept(message, ctx);
            other.set(message);
            return Behavior.NEXT;
        }

        @Override
        protected void onLagged(int drained, int backlog) {
            // Reihenfolge ist Teil des Vertrags: erst die Messwerte, dann der
            // Zaehler — wer auf lagEvents wartet, sieht danach alles.
            maxDrained.accumulateAndGet(drained, Math::max);
            maxBacklog.accumulateAndGet(backlog, Math::max);
            lagEvents.incrementAndGet();
        }

        @Override
        protected void onTickOverrun(Tick tick, long elapsedNanos) {
            overruns.incrementAndGet();
        }

        @Override
        protected void onStop(ExitReason reason) {
            stopReason.set(reason);
        }
    }

    @Test
    @DisplayName("der Raum erhaelt Ticks in aufsteigender Nummer")
    void receivesTicks() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(2), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        Room room = new Room("room", driver, null);
        ActorRef ref = system.spawn(room);

        driver.start();
        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil(() -> room.seen.size() >= 5, "5 ticks");

        assertThat(room.seen).extracting(Tick::number).isSorted();
        assertThat(room.ticks()).isPositive();
        assertThat(ref.isAlive()).isTrue();
        driver.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("pro Tick geht genau ein Paket an den Client raus")
    void onePacketPerClient() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient client = TestClient.of(1);
        ActorRef session = session(client);
        Room room = new Room("room", driver, client, session);
        system.spawn(room);
        driver.start();

        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil(() -> client.sendCount() >= 3, "3 pakete");

        assertThat(client.sendCount()).isEqualTo(room.ticks());
        assertThat((List<Object>) client.lastPacket())
                .as("ohne Packer bleibt die Liste")
                .containsExactly("tick-" + room.lastTickNumber());
        driver.close();
    }

    @Test
    @DisplayName("40 Sends im selben Tick ergeben genau ein send()")
    void aggregatesWithinOneTick() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(10), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient client = TestClient.of(7);
        ActorRef session = session(client);
        AtomicInteger batchSize = new AtomicInteger();
        Room withPacker = new Room("room", driver, null, session) {
            @Override
            protected OutboundBuffer.Packer packer() {
                return (queued, scratch) -> {
                    batchSize.set(queued.size());
                    return List.copyOf(queued);
                };
            }
        };
        withPacker.perTick = tc -> {
            for (int i = 0; i < 40; i++) {
                withPacker.outbound().send(session, "delta-" + i);
            }
        };
        system.spawn(withPacker);
        driver.start();

        awaitUntil(() -> client.sendCount() >= 2, "zwei pakete");
        assertThat(batchSize.get()).isEqualTo(40);
        List<?> first = (List<?>) client.packets().get(0);
        assertThat(first).hasSize(40);
        assertThat(first.get(0)).isEqualTo("delta-0");
        assertThat(first.get(39)).isEqualTo("delta-39");
        driver.close();
    }

    @Test
    @DisplayName("der Outbound-Puffer leert sich nach jedem Flush")
    void bufferEmptiesAfterFlush() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient client = TestClient.of(1);
        ActorRef session = session(client);
        Room room = new Room("room", driver, client, session);
        system.spawn(room);
        driver.start();

        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil(() -> client.sendCount() >= 2, "zwei ticks");
        assertThat(room.outbound().has(session)).isFalse();
        assertThat(room.outbound().pendingPackets()).isZero();
        driver.close();
    }

    @Test
    @DisplayName("onMessage bekommt alles ausser Tick")
    void nonTickMessagesArrive() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        Room room = new Room("room", driver, null);
        ActorRef ref = system.spawn(room);
        driver.start();
        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();

        ref.tell("hallo");
        awaitUntil(() -> "hallo".equals(room.other.get()), "fremde Nachricht");
        driver.close();
    }

    /**
     * Der Test faehrt den Raum <b>ohne Treiber</b> und schickt die Ticks selbst.
     * Damit ist die Reihenfolge im Postfach Determinismus, nicht Glueck: erst der
     * Raum fest, dann der Tick, dann die Eingaben dahinter.
     */
    @Test
    @DisplayName("drainInbox verarbeitet die Warteschlange, bevor simuliert wird")
    void drainsInputBeforeSimulation() throws Exception {
        system = new ActorSystem("ticks");
        Gate gate = Gate.create();
        AtomicInteger drainedSeen = new AtomicInteger(-1);
        AtomicInteger handled = new AtomicInteger();
        AtomicInteger simulatedBeforeInputs = new AtomicInteger(-1);
        Room room = gatedRoom("room", null, gate);
        room.messageHook = (message, c) -> {
            if (message instanceof String) {
                handled.incrementAndGet();
            }
        };
        room.perTick = tc -> {
            // Der Drain laeuft vor der Simulation: alles, was er aufgenommen hat,
            // ist bereits behandelt, wenn onTick beginnt.
            simulatedBeforeInputs.set(handled.get());
            drainedSeen.accumulateAndGet(room.lastDrained(), Math::max);
        };
        ActorRef ref = system.spawn(room);

        ref.tell(Gate.LOCK);                                  // der Raum steckt fest
        assertThat(gate.blocked().await(5, TimeUnit.SECONDS)).isTrue();
        ref.tell(new Tick(1, Duration.ZERO, true));        // der Tick liegt vorn
        for (int i = 0; i < 20; i++) {
            ref.tell("input-" + i);
        }
        gate.release().countDown();

        awaitUntil(() -> drainedSeen.get() > 0, "der Drain hat gearbeitet");
        assertThat(drainedSeen.get()).as("alle 20 Eingaben in einem Tick (ggf. plus Gate)")
                .isGreaterThanOrEqualTo(20)
                .isLessThanOrEqualTo(64);                  // und nie mehr als das Budget
        assertThat(simulatedBeforeInputs.get()).as("die Eingaben waren schon behandelt")
                .isGreaterThanOrEqualTo(20);
        assertThat(handled.get()).isEqualTo(20);
        assertThat(room.lastBacklog()).isZero();
    }

    @Test
    @DisplayName("der Backlog wird gemessen, onLagged feuert — und der Drain haelt das Budget")
    void lagIsCounted() throws Exception {
        system = new ActorSystem("ticks");
        Gate gate = Gate.create();
        Room room = gatedRoom("room", null, gate);
        room.backlogLimit = 4;
        ActorRef ref = system.spawn(room);

        ref.tell(Gate.LOCK);
        assertThat(gate.blocked().await(5, TimeUnit.SECONDS)).isTrue();
        ref.tell(new Tick(1, Duration.ZERO, true));
        for (int i = 0; i < 200; i++) {
            ref.tell(i);
        }
        gate.release().countDown();

        awaitUntil(() -> room.lagEvents.get() > 0, "lag event");
        assertThat(room.maxDrained.get()).as("genau das Budget, nie mehr")
                .isEqualTo(64);
        assertThat(room.maxBacklog.get()).as("der Rest bleibt sichtbar")
                .isEqualTo(200 - 64);
        assertThat(room.lagEvents()).isPositive();

        // Erholung: der zweite Tick nimmt den Rest, dann ist der Postfach leer.
        ref.tell(new Tick(2, Duration.ZERO, false));
        awaitUntil(() -> room.lastBacklog() == 0 && room.ticks() >= 2, "Rueckstand abgebaut");
        assertThat(room.maxDrained.get()).isEqualTo(64);
    }

    @Test
    @DisplayName("Eingaben waehrend der Simulation kommen erst im naechsten Tick dran")
    void inputsDuringSimulationWaitForNextTick() throws Exception {
        system = new ActorSystem("ticks");
        java.util.concurrent.CountDownLatch inTick = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger handledBeforeRelease = new AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean firstTick =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Room room = new Room("room", null, null) {
        };
        room.perTick = tc -> {
            inTick.countDown();
            if (firstTick.compareAndSet(true, false)) {
                handledBeforeRelease.set(room.lastDrained());
            }
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ActorRef ref = system.spawn(room);

        ref.tell(new Tick(1, Duration.ZERO, true));
        assertThat(inTick.await(5, TimeUnit.SECONDS)).as("der Tick laeuft").isTrue();
        ref.tell(new Tick(2, Duration.ZERO, false));        // liegt waehrend der Simulation da
        ref.tell("input-1");
        release.countDown();

        awaitUntil(() -> room.ticks() >= 2, "zweiter Tick");
        assertThat(handledBeforeRelease.get()).as("vor der Simulation war nichts da")
                .isEqualTo(0);
        assertThat(room.lastTickNumber()).isEqualTo(2);
    }

    @Test
    @DisplayName("Stopp meldet das Abo ab — keine Ticks an einen toten Raum")
    void stopUnsubscribes() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        Room room = new Room("room", driver, null);
        ActorRef ref = system.spawn(room);
        driver.start();
        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(driver.subscriberCount()).isEqualTo(1);

        ActorCell.cellOf(ref).stop();

        awaitUntil(() -> driver.subscriberCount() == 0, "abbestellt");
        assertThat(driver.subscriberCount()).isZero();
        assertThat(room.stopReason.get()).isNotNull();
        assertThat(ref.isAlive()).isFalse();
        driver.close();
    }

    @Test
    @DisplayName("ohne Driver laeuft der Raum trotzdem — Ticks kommen von Hand")
    void worksWithoutDriver() {
        system = new ActorSystem("ticks");
        AtomicInteger ticks = new AtomicInteger();
        TickActor room = new TickActor("room", (TickDriver) null) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                ticks.incrementAndGet();
            }
        };
        ActorRef ref = system.spawn(room);
        ref.tell(new Tick(1, Duration.ZERO, true));

        awaitUntil(() -> ticks.get() == 1, "manueller tick");
        assertThat(room.driver()).isNull();
    }

    @Test
    @DisplayName("Messwerte: Ticks, Dauer, letzte Nummer, Overruns")
    void exposesMeasurements() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        Room room = new Room("room", driver, null);
        system.spawn(room);
        driver.start();
        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil(() -> room.ticks() >= 3, "3 ticks");

        assertThat(room.ticks()).isGreaterThanOrEqualTo(3);
        assertThat(room.lastTickNanos()).isPositive();
        assertThat(room.lastTickNumber()).isEqualTo(room.seen.get(room.seen.size() - 1).number());
        assertThat(room.skippedTicks()).isNotNegative();
        assertThat(room.driver()).isSameAs(driver);
        driver.close();
    }

    @Test
    @DisplayName("ein werfender Client killt nur die Session, nicht den Raum")
    void throwingClientDoesNotKillRoom() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient client = TestClient.of(1).failingAfter(2);
        ActorRef session = session(client);
        Room room = new Room("room", driver, client, session);
        ActorRef roomRef = system.spawn(room);
        driver.start();

        assertThat(room.firstTick.await(5, TimeUnit.SECONDS)).isTrue();
        awaitUntil(() -> client.sendCount() >= 2, "zwei pakete");
        Thread.sleep(150);

        assertThat(roomRef.isAlive()).as("der Raum lebt weiter").isTrue();
        assertThat(room.ticks()).isPositive();
        assertThat(driver.isRunning()).as("der Treiber laeuft weiter").isTrue();
    }

    @Test
    @DisplayName("onTickOverrun zaehlt langsame Ticks")
    void overrunIsCounted() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        AtomicInteger overrunsSeen = new AtomicInteger();
        TickActor room = new TickActor("room", driver) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                try {
                    Thread.sleep(12);          // > 8 ms Soft-Budget
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            protected void onTickOverrun(Tick tick, long elapsedNanos) {
                overrunsSeen.incrementAndGet();
            }
        };
        system.spawn(room);
        driver.start();

        awaitUntil(() -> overrunsSeen.get() > 0, "overrun gezaehlt");
        assertThat(overrunsSeen.get()).isPositive();
        driver.close();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("der Packer laeuft genau einmal pro Flush und pro Client")
    void packerIsCalledOncePerFlush() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(10), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient a = TestClient.of(1);
        TestClient b = TestClient.of(2);
        ActorRef sessionA = session(a);
        ActorRef sessionB = session(b);
        AtomicInteger packCalls = new AtomicInteger();
        TickActor room = new TickActor("room", driver) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                tc.send(sessionA, "a1");
                tc.send(sessionA, "a2");
                tc.send(sessionB, "b1");
            }

            @Override
            protected OutboundBuffer.Packer packer() {
                return (queued, scratch) -> {
                    packCalls.incrementAndGet();
                    return List.copyOf(queued);
                };
            }
        };
        system.spawn(room);
        driver.start();

        awaitUntil(() -> a.sendCount() >= 1 && b.sendCount() >= 1, "pakete");
        Thread.sleep(80);
        assertThat(a.sendCount()).isEqualTo(b.sendCount());
        assertThat(a.sendCount()).isEqualTo(packCalls.get() / 2);
        assertThat((List<Object>) a.lastPacket()).containsExactly("a1", "a2");
        assertThat((List<Object>) b.lastPacket()).containsExactly("b1");
        driver.close();
    }

    @Test
    @DisplayName("Nur der Tick landet in onTick, alles andere in onMessage")
    void tickIsRoutedThroughReceive() {
        system = new ActorSystem("ticks");
        AtomicInteger handlerCalls = new AtomicInteger();
        AtomicInteger tickCalls = new AtomicInteger();
        TickActor room = new TickActor("room", (TickDriver) null) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                tickCalls.incrementAndGet();
            }

            @Override
            protected Behavior onMessage(Object message, ActorContext ctx) {
                handlerCalls.incrementAndGet();
                return Behavior.NEXT;
            }
        };
        ActorRef ref = system.spawn(room);
        ref.tell(new Tick(1, Duration.ZERO, true));
        ref.tell("x");
        awaitUntil(() -> handlerCalls.get() == 1 && tickCalls.get() == 1, "beide Wege");
        assertThat(handlerCalls.get()).isEqualTo(1);
        assertThat(tickCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ein normaler Actor bleibt unberuehrt")
    void unrelatedActorIsUnaffected() {
        system = new ActorSystem("ticks");
        AtomicInteger handled = new AtomicInteger();
        ActorRef ref = system.spawn(new TestActor("plain", ctx ->
                dev.localsoul.aero.actor.Receive.of(
                        dev.localsoul.aero.actor.Receive.Clause.any((m, c) -> {
                            handled.incrementAndGet();
                            return Behavior.NEXT;
                        }))));
        ref.tell("a");
        awaitUntil(() -> handled.get() == 1, "behandelt");
        assertThat(handled.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("ohne Client und ohne Packe Aufgabe laeuft der Raum trotzdem")
    void silentRoom() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(2), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        AtomicInteger ticks = new AtomicInteger();
        TickActor room = new TickActor("room", driver) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                ticks.incrementAndGet();
            }
        };
        system.spawn(room);
        driver.start();
        awaitUntil(() -> ticks.get() >= 3, "ticks");
        assertThat(room.outbound().pendingClients()).isZero();
        driver.close();
    }

    @Test
    @DisplayName("die Reihenfolge der Pakete bleibt ueber mehrere Ticks erhalten")
    void orderAcrossTicksIsPreserved() throws Exception {
        TickDriver driver = new TickDriver(Duration.ofMillis(5), OverrunPolicy.CLAMP);
        system = new ActorSystem("ticks");
        TestClient client = TestClient.of(1);
        ActorRef session = session(client);
        TickActor room = new TickActor("room", driver) {
            @Override
            protected void onTick(Tick tick, TickContext tc) {
                tc.send(session, "t" + tick.number() + "-a");
                tc.send(session, "t" + tick.number() + "-b");
            }

            @Override
            protected OutboundBuffer.Packer packer() {
                return (queued, scratch) -> List.copyOf(queued);
            }
        };
        system.spawn(room);
        driver.start();

        awaitUntil(() -> client.sendCount() >= 3, "drei pakete");
        List<?> third = (List<?>) client.packets().get(2);
        assertThat(third.get(0)).isEqualTo("t3-a");
        assertThat(third.get(1)).isEqualTo("t3-b");
        driver.close();
    }
}
