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
import dev.localsoul.aero.game.actor.GameMessages.PlayerLeave;
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.world.Monster;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Killer fehlt beim Treffer → kein XP, keine Bag, kein Absturz")
class KillWithoutOwnerTest {

    @Test
    @DisplayName("PlayerLeave vor EnemyHit: Treffer wird verworfen, Monster lebt")
    void killerLeftBeforeHit() throws Exception {
        final ActorSystem system = new ActorSystem("kill-no-owner-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor capA = new RealmTestKit.CaptureActor("a");
            final RealmTestKit.CaptureActor capB = new RealmTestKit.CaptureActor("b");
            system.spawn(capA);
            system.spawn(capB);

            final int playerId = join(realmRef, capA);
            join(realmRef, capB);

            // A schießt, verlässt, sein Treffer kommt danach an
            realmRef.tell(new PlayerShootMsg(playerId, 0, 0, Player.ENERGY_STAFF, 25f, 25f, 0f));
            realmRef.tell(new PlayerLeave(playerId));
            realmRef.tell(new EnemyHitMsg(playerId, 0, 0, 1, false));

            ticks(realmRef, 1, 50);
            awaitPacket(capB, NewTick.class, t -> t.tickId == 1);

            assertThat(realm.monster(1).alive()).as("Monster überlebt").isTrue();
            assertThat(realm.monster(1).hp()).isEqualTo(Monster.MAX_HP);
            assertThat(realm.bagCount()).as("keine Bag").isZero();
            assertThat(packets(capA, Notification.class)).as("kein XP").isEmpty();
            awaitTrue(() -> realm.playerCount() == 1);       // B ist noch da, A weg
        } finally {
            system.close();
        }
    }
}