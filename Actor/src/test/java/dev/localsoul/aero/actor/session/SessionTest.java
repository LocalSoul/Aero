package dev.localsoul.aero.actor.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.TestClient;
import dev.localsoul.aero.actor.TestSession;
import dev.localsoul.aero.actor.tick.OutboundBuffer;
import dev.localsoul.aero.actor.tick.Tick;
import dev.localsoul.aero.actor.tick.TickContext;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionTest {

    private ActorSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) {
            system.close();
        }
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

    @Test
    @DisplayName("ohne Client gibt es keine Session")
    void requiresClient() {
        system = new ActorSystem("session");
        assertThatThrownBy(() -> new TestSession("s", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("eingehende Nachrichten landen in onInbound")
    void inboundIsDelivered() {
        system = new ActorSystem("session");
        TestSession session = new TestSession("s", TestClient.of(1));
        ActorRef ref = system.spawn(session);

        ref.tell("hallo");
        ref.tell(42);

        awaitUntil(() -> session.inbound().size() == 2, "zwei eingehende Nachrichten");
        assertThat(session.inbound()).containsExactly("hallo", 42);
        assertThat(session.messagesReceived()).isEqualTo(2);
    }

    @Test
    @DisplayName("ein Outbound erzeugt genau einen send() an den Client")
    void outboundCallsClientOnce() {
        system = new ActorSystem("session");
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client);
        ActorRef ref = system.spawn(session);
        awaitUntil(() -> session.starts() == 1, "gestartet");

        ref.tell(new OutboundBuffer.Outbound(List.of("a", "b", "c"), "batch"));

        awaitUntil(() -> client.sendCount() == 1, "ein send");
        assertThat(client.lastPacket()).isEqualTo("batch");
        assertThat(session.packetsSent()).isEqualTo(1);
    }

    @Test
    @DisplayName("UNBOUNDED ist der Default")
    void defaultInterestIsUnbounded() {
        system = new ActorSystem("session");
        TestSession session = new TestSession("s", TestClient.of(1));
        assertThat(session.interest()).isEqualTo(Interest.UNBOUNDED);
        assertThat(session.interest().isUnbounded()).isTrue();
    }

    @Test
    @DisplayName("bindInterest registriert die Session im Index")
    void bindsInterest() {
        system = new ActorSystem("session");
        InterestSet index = InterestSet.forRadius(50);
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client, Interest.at(10, 0, 0, 5), index, null);
        ActorRef ref = system.spawn(session);

        awaitUntil(() -> index.size() == 1, "im Index");
        assertThat(index.contains(ref)).isTrue();
        assertThat(index.areaOf(ref).x()).isEqualTo(10.0);
        assertThat(indexed(session).radius()).isEqualTo(5.0);
    }

    private static Interest indexed(TestSession session) {
        return session.indexedInterest();
    }

    @Test
    @DisplayName("moveInterest aktualisiert den Index mit")
    void moveUpdatesIndex() {
        system = new ActorSystem("session");
        InterestSet index = InterestSet.forRadius(50);
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client, Interest.at(0, 0, 0, 5), index, null);
        ActorRef ref = system.spawn(session);
        awaitUntil(() -> index.size() == 1, "im Index");

        // Der Raum meldet die Bewegung ueber die Nachricht, damit sie im Actor-Thread laeuft.
        ref.tell(new TestSession.Move(50, 0, 0));
        awaitUntil(() -> index.areaOf(ref) != null && index.areaOf(ref).x() == 50.0, "Index aktualisiert");

        assertThat(index.size()).as("kein Duplikat").isEqualTo(1);
        assertThat(index.areaOf(ref).x()).isEqualTo(50.0);
        assertThat(indexed(session).x()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Stopp loest die Interest-Bindung — keine Leaks im Index")
    void stopReleasesInterest() {
        system = new ActorSystem("session");
        InterestSet index = InterestSet.forRadius(50);
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client, Interest.at(10, 0, 0, 5), index, null);
        ActorRef ref = system.spawn(session);
        awaitUntil(() -> index.size() == 1, "im Index");

        dev.localsoul.aero.actor.internal.ActorCell.cellOf(ref).stop();

        awaitUntil(() -> session.stops() == 1, "postStop");
        assertThat(index.size()).as("aus dem Index entfernt").isZero();
        assertThat(index.contains(ref)).isFalse();
        assertThat(client.wasClosed()).as("der Client wird geschlossen").isTrue();
    }

    @Test
    @DisplayName("unbindInterest loest ohne Stopp")
    void unbindWithoutStop() {
        system = new ActorSystem("session");
        InterestSet index = InterestSet.forRadius(50);
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client, Interest.at(10, 0, 0, 5), index, null);
        ActorRef ref = system.spawn(session);
        awaitUntil(() -> index.size() == 1, "im Index");

        ref.tell(new TestSession.Unbind());
        awaitUntil(() -> index.size() == 0, "geloest");
        assertThat(ref.isAlive()).as("die Session lebt weiter").isTrue();
    }

    @Test
    @DisplayName("moveInterest ohne Bindung ist ein No-op")
    void moveWithoutBinding() {
        TestSession session = new TestSession("s", TestClient.of(1));
        ActorContext ctx = null;
        assertThat(session.moveTo(ctx, Interest.point(1, 1, 1))).isNull();
    }

    @Test
    @DisplayName("Subscription meldet ab — zweimal ist harmlos")
    void subscriptionIsIdempotent() {
        AtomicInteger cancels = new AtomicInteger();
        ActorRef target = new dev.localsoul.aero.actor.TestRefs.FakeRef("t");
        Subscription subscription = Subscription.of(target, () -> cancels.incrementAndGet());
        assertThat(subscription.isActive()).isTrue();
        assertThat(subscription.target()).isEqualTo(target);
        assertThat(subscription.interest()).isEqualTo(Interest.UNBOUNDED);

        subscription.cancel();
        subscription.cancel();

        assertThat(cancels.get()).isEqualTo(1);
        assertThat(subscription.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("Subscription traegt das Interesse mit")
    void subscriptionCarriesInterest() {
        ActorRef target = new dev.localsoul.aero.actor.TestRefs.FakeRef("t");
        Subscription subscription = Subscription.of(target, Interest.point(1, 2, 3), () -> { });
        assertThat(subscription.interest().x()).isEqualTo(1.0);
        subscription.withInterest(Interest.point(9, 9, 9));
        assertThat(subscription.interest().x()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("Subscription braucht ein Ziel")
    void subscriptionNeedsTarget() {
        assertThatThrownBy(() -> Subscription.of(null, () -> { }))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Client-Defaults: id 0, offen, close ist ein No-op")
    void clientDefaults() {
        Client client = new Client() {
            @Override
            public void send(Object packet) {
            }
        };
        assertThat(client.id()).isZero();
        assertThat(client.isOpen()).isTrue();
        client.close();
        assertThat(client.isOpen()).isTrue();
    }

    @Test
    @DisplayName("Session und TickActor ergeben einen lauffaehigen Pfad")
    void sessionInTickPipeline() {
        system = new ActorSystem("session");
        TestClient client = TestClient.of(99);
        ActorRef session = system.spawn(new TestSession("s", client));
        AtomicInteger ticks = new AtomicInteger();
        dev.localsoul.aero.actor.tick.TickActor room =
                new dev.localsoul.aero.actor.tick.TickActor("room", (dev.localsoul.aero.actor.tick.TickDriver) null) {
                    @Override
                    protected void onTick(Tick tick, TickContext tc) {
                        ticks.incrementAndGet();
                        for (int i = 0; i < 5; i++) {
                            tc.send(session, "d" + i);
                        }
                    }

                    @Override
                    protected OutboundBuffer.Packer packer() {
                        return (queued, scratch) -> List.copyOf(queued);
                    }
                };
        ActorRef roomRef = system.spawn(room);

        for (int i = 0; i < 3; i++) {
            roomRef.tell(new Tick(i + 1L, Duration.ofMillis(16), i == 0));
        }

        awaitUntil(() -> client.sendCount() == 3, "drei Pakete");
        assertThat((List<?>) client.packets().get(0)).hasSize(5);
        assertThat(ticks.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Restart behaelt den Client — der Netwerk-Zustand ist nicht im Actor")
    void restartKeepsClient() {
        system = new ActorSystem("session");
        TestClient client = TestClient.of(1);
        TestSession session = new TestSession("s", client);
        ActorRef ref = system.spawn(session);
        awaitUntil(() -> session.starts() == 1, "gestartet");

        dev.localsoul.aero.actor.internal.ActorCell.cellOf(ref).stop();
        awaitUntil(() -> !ref.isAlive(), "gestoppt");

        // Neustart der gleichen Instanz — der Client lebt weiter.
        ActorRef again = system.spawn(session);
        awaitUntil(() -> session.starts() == 2, "neu gestartet");
        assertThat(session.client()).isSameAs(client);
    }

    @Test
    @DisplayName("onInbound entscheidet, was passiert")
    void inboundHookDecides() {
        system = new ActorSystem("session");
        TestSession session = new TestSession("s", TestClient.of(1), Interest.UNBOUNDED, null,
                (ctx, message, s) -> "skip".equals(message)
                        ? Behavior.UNHANDLED
                        : Behavior.NEXT);
        ActorRef ref = system.spawn(session);
        ref.tell("skip");
        ref.tell("keep");
        awaitUntil(() -> session.inbound().size() == 2, "beide angekommen");
        assertThat(session.inbound()).containsExactly("skip", "keep");
    }
}
