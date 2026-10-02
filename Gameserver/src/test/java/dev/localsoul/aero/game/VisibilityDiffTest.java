package dev.localsoul.aero.game;

import static dev.localsoul.aero.game.RealmTestKit.awaitPacket;
import static dev.localsoul.aero.game.RealmTestKit.awaitTrue;
import static dev.localsoul.aero.game.RealmTestKit.join;
import static dev.localsoul.aero.game.RealmTestKit.realmWith;
import static dev.localsoul.aero.game.RealmTestKit.ticks;
import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.GameMessages.EnemyHitMsg;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.Monster;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Sichtbarkeits-Diff (knownObjectIds)")
class VisibilityDiffTest {

    @Test
    @DisplayName("Neuankömmling sieht bestehende Monster im Join-Update")
    void newcomerSeesMonsters() throws Exception {
        final ActorSystem system = new ActorSystem("vis-join-test");
        try {
            final RealmActor realm = realmWith(system, 2);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            assertThat(playerId).isEqualTo(3);              // 2 Monster → Spieler id 3
            final JoinConfirmed joined = (JoinConfirmed) cap.messages.stream()
                    .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow();
            assertThat(joined.update().newObjs)
                    .as("Join-Update enthält beide Monster + Spieler")
                    .filteredOn(o -> o.objectType == Monster.GHOST_MAGE)
                    .hasSize(2);
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Kill → Drop, Respawn (neue objectId-frei) → erneutes newObj")
    void killThenRespawnVisibility() throws Exception {
        final ActorSystem system = new ActorSystem("vis-respawn-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);

            // Kill
            for (int i = 0; i < 15; i++) {
                realmRef.tell(new PlayerShootMsg(playerId, i, i, Player.ENERGY_STAFF, 25f, 25f, 0f));
                realmRef.tell(new EnemyHitMsg(playerId, i, i, 1, false));
            }
            awaitPacket(cap, Notification.class, n -> n.objectId == playerId);
            ticks(realmRef, 1, 50);
            awaitPacket(cap, Update.class, u -> u.drops.contains(1));

            // Respawn nach RESPAWN_MS (10 s): 200 × 50 ms
            ticks(realmRef, 200, 50);
            awaitTrue(() -> realm.monster(1).alive());
            final Update respawn = awaitPacket(cap, Update.class,
                    u -> u.newObjs.stream().anyMatch(o -> o.objectType == Monster.GHOST_MAGE));
            assertThat(respawn.newObjs)
                    .filteredOn(o -> o.objectType == Monster.GHOST_MAGE)
                    .anySatisfy(o -> assertThat(o.status.objectId).isEqualTo(1));
        } finally {
            system.close();
        }
    }
}