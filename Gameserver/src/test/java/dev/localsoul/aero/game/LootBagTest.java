package dev.localsoul.aero.game;

import static dev.localsoul.aero.game.RealmTestKit.awaitPacket;
import static dev.localsoul.aero.game.RealmTestKit.awaitTrue;
import static dev.localsoul.aero.game.RealmTestKit.firstPacket;
import static dev.localsoul.aero.game.RealmTestKit.join;
import static dev.localsoul.aero.game.RealmTestKit.packets;
import static dev.localsoul.aero.game.RealmTestKit.realmWith;
import static dev.localsoul.aero.game.RealmTestKit.ticks;
import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.GameMessages.EnemyHitMsg;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.PlayerCreate;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.GameMessages.PlayerMove;
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.GameMessages.PlayerTextMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.protocol.ObjectData;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.LootBag;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Loot-Bags: Pickup (nur Eigentümer), volles Inventar, Lebensdauer")
class LootBagTest {

    /** Monster (id 1) töten und die entstehende Bag aus dem Diff lesen. */
    private static ObjectData killAndGetBag(final ActorRef realmRef,
                                            final RealmTestKit.CaptureActor cap,
                                            final int playerId) throws Exception {
        for (int i = 0; i < 15; i++) {
            realmRef.tell(new PlayerShootMsg(playerId, i, i, Player.ENERGY_STAFF, 25f, 25f, 0f));
            realmRef.tell(new EnemyHitMsg(playerId, i, i, 1, false));
        }
        awaitPacket(cap, Notification.class, n -> n.objectId == playerId);
        ticks(realmRef, 1, 50);
        return awaitPacket(cap, Update.class, u -> u.newObjs.stream()
                        .anyMatch(o -> o.objectType == LootBag.SOULBOUND_LOOT_BAG))
                .newObjs.stream().filter(o -> o.objectType == LootBag.SOULBOUND_LOOT_BAG)
                .findFirst().orElseThrow();
    }

    /** Spieler zum Bag-Punkt laufen lassen (speed 4, Distanz ~1.8 → < 1 s). */
    private static void walkTo(final ActorRef realmRef, final int playerId,
                               final float x, final float y) {
        realmRef.tell(new PlayerMove(playerId, x, y));
        ticks(realmRef, 20, 50);
    }

    @Test
    @DisplayName("Pickup überträgt das Item dem Eigentümer, Bag verschwindet")
    void pickupTransfersItemToOwner() throws Exception {
        final ActorSystem system = new ActorSystem("bag-pickup-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            final ObjectData bag = killAndGetBag(realmRef, cap, playerId);
            final int itemType = bag.status.stats.stream()
                    .filter(s -> s.statType == 8).map(s -> s.statValue).findFirst().orElse(-1);

            walkTo(realmRef, playerId, bag.status.pos.x, bag.status.pos.y);
            awaitTrue(() -> realm.bagCount() == 0);

            final NewTick tick = awaitPacket(cap, NewTick.class, t -> t.statuses.stream()
                    .anyMatch(s -> s.objectId == playerId && s.stats.stream()
                            .anyMatch(st -> st.statType >= 8 && st.statType <= 19
                                    && st.statValue == itemType)));
            assertThat(tick).isNotNull();
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Fremder Spieler kann die Bag nicht aufsammeln (nur Eigentümer)")
    void pickupOnlyForOwner() throws Exception {
        final ActorSystem system = new ActorSystem("bag-owner-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor capA = new RealmTestKit.CaptureActor("a");
            final RealmTestKit.CaptureActor capB = new RealmTestKit.CaptureActor("b");
            system.spawn(capA);
            system.spawn(capB);

            final int ownerId = join(realmRef, capA);
            final ObjectData bag = killAndGetBag(realmRef, capA, ownerId);

            realmRef.tell(new PlayerHello(capB.ref(), 0));
            realmRef.tell(new PlayerCreate(capB.ref(), 782, 0));
            awaitTrue(() -> capB.messages.stream().anyMatch(JoinConfirmed.class::isInstance));
            final int foreignId = ((JoinConfirmed) capB.messages.stream()
                    .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow()).objectId();

            walkTo(realmRef, foreignId, bag.status.pos.x, bag.status.pos.y);
            awaitTrue(() -> realm.bagCount() == 1);          // B darf nicht aufsammeln

            walkTo(realmRef, ownerId, bag.status.pos.x, bag.status.pos.y);
            awaitTrue(() -> realm.bagCount() == 0);          // A (Eigentümer) schon
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Volles Inventar → Bag bleibt liegen")
    void pickupFailsWhenInventoryFull() throws Exception {
        final ActorSystem system = new ActorSystem("bag-full-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            for (int i = 0; i < 7; i++) {                    // Slots 5–11 füllen
                realmRef.tell(new PlayerTextMsg(playerId, "/give " + (0x100 + i)));
            }
            ticks(realmRef, 1, 50);

            final ObjectData bag = killAndGetBag(realmRef, cap, playerId);
            walkTo(realmRef, playerId, bag.status.pos.x, bag.status.pos.y);
            assertThat(realm.bagCount()).as("Inventar voll → Bag bleibt").isEqualTo(1);
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("Bag läuft nach BAG_LIFETIME_MS ab (auch ohne Eigentümer)")
    void bagExpiresAfterLifetime() throws Exception {
        final ActorSystem system = new ActorSystem("bag-lifetime-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            killAndGetBag(realmRef, cap, playerId);
            awaitTrue(() -> realm.bagCount() == 1);

            ticks(realmRef, 61, 1000);                       // 61 s > 60 s Lebensdauer
            awaitTrue(() -> realm.bagCount() == 0);
            assertThat(packets(cap, NewTick.class)).isNotEmpty();
        } finally {
            system.close();
        }
    }
}