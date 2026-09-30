package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.MapInfoReady;
import dev.localsoul.aero.game.actor.GameMessages.OutboundBatch;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.GameMessages.PlayerJoin;
import dev.localsoul.aero.game.actor.GameMessages.PlayerMove;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.world.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@DisplayName("RealmActor (Join/Move-Flow)")
class RealmActorTest {

    /** Fängt Nachrichten der Session-Seite auf. */
    private static final class CaptureActor extends Actor {
        final List<Object> messages = new CopyOnWriteArrayList<>();

        CaptureActor(final String name) {
            super(name);
        }

        @Override
        protected Behavior onStart(final ActorContext ctx) {
            return Receive.of(Receive.Clause.any((m, c) -> {
                messages.add(m);
                return Behavior.NEXT;
            }));
        }
    }

    @Test
    @DisplayName("Hello → MapInfoReady, Join → JoinConfirmed(objectId), Move → NewTick")
    void joinAndMoveFlow() throws Exception {
        final ActorSystem system = new ActorSystem("realm-test");
        try {
            final RealmActor realm = new RealmActor("realm", null, new Map(50, 50));
            final ActorRef realmRef = system.spawn(realm);

            final CaptureActor capture = new CaptureActor("session");
            final ActorRef session = system.spawn(capture);

            // Phase 1: Hello → MapInfoReady
            realmRef.tell(new PlayerHello(session, 0));
            await(() -> capture.messages.stream().anyMatch(MapInfoReady.class::isInstance));
            final MapInfoReady ready = (MapInfoReady) capture.messages.stream()
                    .filter(MapInfoReady.class::isInstance).findFirst().orElseThrow();
            assertThat(ready.mapInfo().width).isEqualTo(50);
            assertThat(ready.mapInfo().height).isEqualTo(50);

            // Phase 2: Load → JoinConfirmed mit objectId vom Realm
            realmRef.tell(new PlayerJoin(session, 7));
            await(() -> capture.messages.stream().anyMatch(JoinConfirmed.class::isInstance));
            final JoinConfirmed joined = (JoinConfirmed) capture.messages.stream()
                    .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow();
            assertThat(joined.objectId()).isEqualTo(1);
            assertThat(joined.createSuccess().charId).isEqualTo(7);
            assertThat(joined.update().newObjs).hasSize(1);

            // Reihenfolge: MapInfoReady kam vor JoinConfirmed (Phase 1 vor Phase 2)
            final int readyIndex = capture.messages.indexOf(ready);
            final int joinIndex = capture.messages.indexOf(joined);
            assertThat(readyIndex).isLessThan(joinIndex);

            // Move → Ziel nahe setzen, einige Ticks fahren, NewTick zeigt neue Position
            realmRef.tell(new PlayerMove(1, 25f, 24f));
            for (int i = 1; i <= 10; i++) {
                realmRef.tell(new dev.localsoul.aero.actor.tick.Tick(i, Duration.ofMillis(50), false));
            }
            await(() -> capture.messages.stream().anyMatch(OutboundBatch.class::isInstance));

            final AtomicReference<Float> lastX = new AtomicReference<>(-1f);
            for (final Object msg : capture.messages) {
                if (msg instanceof OutboundBatch batch) {
                    for (final var packet : batch.messages()) {
                        if (packet instanceof NewTick tick) {
                            tick.statuses.stream()
                                    .filter(s -> s.objectId == 1)
                                    .findFirst()
                                    .ifPresent(s -> lastX.set(s.pos.x));
                        }
                    }
                }
            }
            assertThat(lastX.get()).as("Spieler hat sich zum Ziel bewegt").isEqualTo(25f);
        } finally {
            system.close();
        }
    }

    private static void await(final java.util.function.BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError("Bedingung nicht innerhalb 5s erfüllt");
    }
}