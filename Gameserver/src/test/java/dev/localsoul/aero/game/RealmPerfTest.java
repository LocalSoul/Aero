package dev.localsoul.aero.game;

import static dev.localsoul.aero.game.RealmTestKit.awaitPacket;
import static dev.localsoul.aero.game.RealmTestKit.awaitTrue;
import static dev.localsoul.aero.game.RealmTestKit.join;
import static dev.localsoul.aero.game.RealmTestKit.realmWith;
import static dev.localsoul.aero.game.RealmTestKit.ticks;
import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

@DisplayName("Leistungsgrenze des O(Spieler × Monster)-Scans (vor V3)")
class RealmPerfTest {

    @Test
    @DisplayName("500 Monster + 2 Spieler: 100 Ticks bleiben im Rahmen")
    void manyMonstersStayResponsive() throws Exception {
        final ActorSystem system = new ActorSystem("perf-test");
        try {
            final RealmActor realm = realmWith(system, 500);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            assertThat(realm.monsterCount()).isEqualTo(500);
            join(realmRef, cap);

            final long start = System.nanoTime();
            ticks(realmRef, 100, 50);
            awaitPacket(cap, NewTick.class, t -> t.tickId == 100);
            final long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(realm.playerCount()).isEqualTo(1);
            assertThat(realm.monsterCount()).isEqualTo(500);
            assertThat(elapsedMs).as("100 Ticks mit 500 Monstern").isLessThan(5_000L);
        } finally {
            system.close();
        }
    }
}