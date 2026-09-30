package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.OutboundBatch;
import dev.localsoul.aero.game.actor.GameMessages.PlayerCreate;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.GameMessages.PlayerLeave;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@DisplayName("Multiplayer: zwei Spieler sehen sich")
class MultiplayerTest {

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
    @DisplayName("Join-Update enthält alle, Neuzugänge werden gemeldet, Leave entfernt")
    void twoPlayersSeeEachOther() throws Exception {
        final ActorSystem system = new ActorSystem("mp-test");
        try {
            final RealmActor realm = new RealmActor("realm", null, new Map(50, 50));
            final ActorRef realmRef = system.spawn(realm);
            final CaptureActor capA = new CaptureActor("a");
            final CaptureActor capB = new CaptureActor("b");
            final ActorRef a = system.spawn(capA);
            final ActorRef b = system.spawn(capB);

            // A tritt bei
            realmRef.tell(new PlayerHello(a, 0));
            realmRef.tell(new PlayerCreate(a, 782, 0));
            await(() -> capA.messages.stream().anyMatch(JoinConfirmed.class::isInstance));
            final JoinConfirmed joinA = (JoinConfirmed) capA.messages.stream()
                    .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow();
            assertThat(joinA.objectId()).isEqualTo(1);
            assertThat(joinA.update().newObjs).as("erster Join: nur A sichtbar").hasSize(1);

            // B tritt bei
            realmRef.tell(new PlayerHello(b, 0));
            realmRef.tell(new PlayerCreate(b, 782, 0));
            await(() -> capB.messages.stream().anyMatch(JoinConfirmed.class::isInstance));
            final JoinConfirmed joinB = (JoinConfirmed) capB.messages.stream()
                    .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow();
            assertThat(joinB.objectId()).isEqualTo(2);
            assertThat(joinB.update().newObjs).as("B sieht A und sich selbst").hasSize(2);

            // A bekommt eine Update-Nachricht über B
            await(() -> capA.messages.stream().anyMatch(this::isNewObjectUpdateForB));
            final Update bAnnounce = (Update) capA.messages.stream()
                    .filter(this::isNewObjectUpdateForB).map(this::extractUpdate).findFirst()
                    .orElseThrow();
            assertThat(bAnnounce.newObjs).hasSize(1);
            assertThat(bAnnounce.newObjs.get(0).status.objectId).isEqualTo(2);

            // Nach einem Tick: jeder NewTick enthält beide Spieler
            realmRef.tell(new dev.localsoul.aero.actor.tick.Tick(1, java.time.Duration.ofMillis(50), false));
            await(() -> hasNewTickWithTwoStatuses(capA) && hasNewTickWithTwoStatuses(capB));

            // B verlässt -> A bekommt ein Update mit drop=2
            realmRef.tell(new PlayerLeave(2));
            await(() -> capA.messages.stream().anyMatch(this::isDropUpdateFor2));
        } finally {
            system.close();
        }
    }

    private Update extractUpdate(final Object m) {
        final OutboundBatch batch = (OutboundBatch) m;
        for (final var p : batch.messages()) {
            if (p instanceof Update u) {
                return u;
            }
        }
        throw new IllegalStateException("kein Update in Batch");
    }

    private boolean isNewObjectUpdateForB(final Object m) {
        if (!(m instanceof OutboundBatch batch)) {
            return false;
        }
        for (final var p : batch.messages()) {
            if (p instanceof Update u && !u.newObjs.isEmpty() && u.tiles.isEmpty()
                    && u.newObjs.get(0).status.objectId == 2) {
                return true;
            }
        }
        return false;
    }

    private boolean isDropUpdateFor2(final Object m) {
        if (!(m instanceof OutboundBatch batch)) {
            return false;
        }
        for (final var p : batch.messages()) {
            if (p instanceof Update u && u.drops.contains(2)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNewTickWithTwoStatuses(final CaptureActor cap) {
        for (final Object m : cap.messages) {
            if (m instanceof OutboundBatch batch) {
                for (final var p : batch.messages()) {
                    if (p instanceof NewTick tick && tick.statuses.size() >= 2) {
                        return true;
                    }
                }
            }
        }
        return false;
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