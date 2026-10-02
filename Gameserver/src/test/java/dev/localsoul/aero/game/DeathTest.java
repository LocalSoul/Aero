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
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.Damage;
import dev.localsoul.aero.game.protocol.Death;
import dev.localsoul.aero.game.protocol.EnemyShoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Spieler-Tod durch Monster-Projektil")
class DeathTest {

    @Test
    @DisplayName("EnemyShoot → Damage → Death genau einmal, Spieler entfernt")
    void monsterKillsPlayer() throws Exception {
        final ActorSystem system = new ActorSystem("death-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);

            // Monster feuert ab nowMs=1000, trifft ~400 ms später, Perioden 2000 ms.
            // Mit 100-ms-Ticks: Tod bei ~nowMs 7400 (≈ 74 Ticks).
            ticks(realmRef, 120, 100);

            final Death death = awaitPacket(cap, Death.class, d -> d.charId == 1);
            assertThat(death.killedBy).isEqualTo("Ghost Mage");
            assertThat(death.zombieId).isEqualTo(-1);
            assertThat(death.zombieType).isEqualTo(-1);
            assertThat(packets(cap, Death.class)).as("genau ein Death").hasSize(1);
            assertThat(packets(cap, Damage.class)).as("es gab autoritative Treffer")
                    .anySatisfy(d -> assertThat(d.targetId).isEqualTo(playerId));

            awaitTrue(() -> realm.playerCount() == 0);
            awaitPacket(cap, EnemyShoot.class, s -> s.ownerId == 1);
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Totes Ziel wird im selben Tick nicht erneut getroffen (kein 2. Death)")
    void noSecondDeathForDeadPlayer() throws Exception {
        final ActorSystem system = new ActorSystem("death-once-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            ticks(realmRef, 200, 100);          // deutlich über den Tod hinaus
            awaitTrue(() -> packets(cap, Death.class).size() == 1);
            awaitTrue(() -> packets(cap, Damage.class).stream().anyMatch(d -> d.kill));
            final var damages = packets(cap, Damage.class);
            assertThat(damages).allMatch(d -> d.targetId == playerId);
            assertThat(damages.stream().filter(d -> d.kill))
                    .as("genau ein tödlicher Treffer").hasSize(1);
            awaitTrue(() -> realm.playerCount() == 0 && realm.monster(1).alive());
        } finally {
            system.close();
        }
    }
}