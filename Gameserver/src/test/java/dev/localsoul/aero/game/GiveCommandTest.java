package dev.localsoul.aero.game;

import static dev.localsoul.aero.game.RealmTestKit.awaitPacket;
import static dev.localsoul.aero.game.RealmTestKit.join;
import static dev.localsoul.aero.game.RealmTestKit.realmWith;
import static dev.localsoul.aero.game.RealmTestKit.ticks;
import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.GameMessages.PlayerTextMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chatbefehl /give")
class GiveCommandTest {

    private static NewTick tickAfterGive(final ActorRef realmRef,
                                         final RealmTestKit.CaptureActor cap,
                                         final int playerId) throws Exception {
        ticks(realmRef, 1, 50);
        return awaitPacket(cap, NewTick.class, t -> t.statuses.stream()
                .anyMatch(s -> s.objectId == playerId));
    }

    private static boolean inventoryHas(final NewTick tick, final int playerId, final int item) {
        return tick.statuses.stream()
                .filter(s -> s.objectId == playerId)
                .flatMap(s -> s.stats.stream())
                .anyMatch(st -> st.statType >= 8 && st.statType <= 19 && st.statValue == item);
    }

    @Test
    @DisplayName("/give 0xa07 legt das Item ins erste freie Inventar-Slot")
    void giveItem() throws Exception {
        final ActorSystem system = new ActorSystem("give-test");
        try {
            final RealmActor realm = realmWith(system, 0);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            realmRef.tell(new PlayerTextMsg(playerId, "/give 0xa07"));
            final NewTick tick = tickAfterGive(realmRef, cap, playerId);
            assertThat(inventoryHas(tick, playerId, 0xa07)).isTrue();
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Unbekannter Type und volles Inventar bleiben wirkungslos")
    void invalidAndFull() throws Exception {
        final ActorSystem system = new ActorSystem("give-invalid-test");
        try {
            final RealmActor realm = realmWith(system, 0);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            realmRef.tell(new PlayerTextMsg(playerId, "/give 0xdeadbeef"));
            for (int i = 0; i < 7; i++) {                    // Slots 5–11 füllen
                realmRef.tell(new PlayerTextMsg(playerId, "/give " + (0x100 + i)));
            }
            realmRef.tell(new PlayerTextMsg(playerId, "/give 0x0bad"));
            final NewTick tick = tickAfterGive(realmRef, cap, playerId);
            assertThat(inventoryHas(tick, playerId, 0x0bad)).as("volles Inventar").isFalse();
            assertThat(inventoryHas(tick, playerId, 0xdeadbeef)).as("unbekannter Type").isFalse();
        } finally {
            system.close();
        }
    }
}