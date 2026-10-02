package dev.localsoul.aero.game;

import static dev.localsoul.aero.game.RealmTestKit.awaitPacket;
import static dev.localsoul.aero.game.RealmTestKit.awaitTrue;
import static dev.localsoul.aero.game.RealmTestKit.join;
import static dev.localsoul.aero.game.RealmTestKit.packets;
import static dev.localsoul.aero.game.RealmTestKit.realmWith;
import static dev.localsoul.aero.game.RealmTestKit.ticks;
import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.GameMessages.EnemyHitMsg;
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.EnemyShoot;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.world.Monster;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Respawn: kein Sofort-Schuss nach der Auferstehung")
class RespawnGraceTest {

    @Test
    @DisplayName("Respawn → nextAttackAt in der Zukunft, feuert erst nach attackPeriod")
    void respawnedMonsterDoesNotShootImmediately() throws Exception {
        final ActorSystem system = new ActorSystem("respawn-grace-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            // Kill (im Mail-Loop, nowMs=0 → respawnAtMs = 10 000)
            for (int i = 0; i < 15; i++) {
                realmRef.tell(new PlayerShootMsg(playerId, i, i, Player.ENERGY_STAFF, 25f, 25f, 0f));
                realmRef.tell(new EnemyHitMsg(playerId, i, i, 1, false));
            }
            awaitPacket(cap, Notification.class, n -> n.objectId == playerId);

            // 210 × 50 ms = nowMs 10 500 → Monster ist wieder da, Schonfrist läuft
            ticks(realmRef, 210, 50);
            awaitPacket(cap, NewTick.class, t -> t.tickId == 210);
            awaitTrue(() -> realm.monster(1).alive());
            assertThat(realm.monster(1).nextAttackAtMs())
                    .as("Schonfrist ≥ nowMs + attackPeriod").isGreaterThanOrEqualTo(12_000);
            assertThat(packets(cap, EnemyShoot.class)).as("noch kein Schuss").isEmpty();

            // Bis nowMs 12 500 → Monster hat genau einen Schuss gefeuert
            ticks(realmRef, 40, 50);
            awaitPacket(cap, EnemyShoot.class, s -> s.ownerId == 1);
            assertThat(realm.monster(1).hp()).isEqualTo(Monster.MAX_HP);
        } finally {
            system.close();
        }
    }
}