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
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.PlayerCreate;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.protocol.ObjectData;
import dev.localsoul.aero.game.protocol.ServerPlayerShoot;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.LootBag;
import dev.localsoul.aero.game.world.Monster;
import dev.localsoul.aero.game.world.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kampf-Flow: Spieler→Monster (EnemyHit) und Schuss-Broadcast")
class CombatFlowTest {

    /** Monster (id 1) mit 15 Schüssen sicher töten (15 × min. 10 ≥ 130 HP). */
    private static void killMonster(final ActorRef realmRef, final int playerId) {
        for (int i = 0; i < 15; i++) {
            realmRef.tell(new PlayerShootMsg(playerId, i, i, Player.ENERGY_STAFF, 25f, 25f, 0f));
            realmRef.tell(new EnemyHitMsg(playerId, i, i, 1, false));
        }
    }

    @Test
    @DisplayName("Kill → Notification (+XP) + Loot-Bag + Monster als Drop im Diff")
    void killMonsterFlow() throws Exception {
        final ActorSystem system = new ActorSystem("combat-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            assertThat(playerId).isEqualTo(2);              // Monster hat objectId 1

            killMonster(realmRef, playerId);
            // XP-Notification ist das Observable für "Kill verarbeitet"
            final Notification note = awaitPacket(cap, Notification.class, n -> n.objectId == playerId);
            assertThat(note.message).contains("server.plus_symbol").contains("\"amount\":\"13\"");
            assertThat(note.color).isEqualTo(0xFFFFFF);
            awaitTrue(() -> !realm.monster(1).alive());

            // Nächster Tick: Monster-Drop + Bag-newObj über den Sichtbarkeits-Diff
            ticks(realmRef, 1, 50);
            final Update diff = awaitPacket(cap, Update.class, u -> u.drops.contains(1));
            final ObjectData bag = diff.newObjs.stream()
                    .filter(o -> o.objectType == LootBag.SOULBOUND_LOOT_BAG)
                    .findFirst().orElseThrow();
            assertThat(bag.status.stats).anyMatch(s -> s.statType == 8);
            awaitTrue(() -> realm.bagCount() == 1);
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("EnemyHit außerhalb der Reichweite wird verworfen (sanfte Validierung)")
    void outOfRangeHitRejected() throws Exception {
        final ActorSystem system = new ActorSystem("combat-range-test");
        try {
            final RealmActor realm = realmWith(system, 1);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor cap = new RealmTestKit.CaptureActor("session");
            system.spawn(cap);

            final int playerId = join(realmRef, cap);
            // Schuss von (0,0) — Distanz zum Monster ≈ 33,6 > Reichweite 8,55 + Toleranz
            realmRef.tell(new PlayerShootMsg(playerId, 0, 0, Player.ENERGY_STAFF, 0f, 0f, 0f));
            realmRef.tell(new EnemyHitMsg(playerId, 0, 0, 1, false));
            ticks(realmRef, 1, 50);                          // Tick nach den Nachrichten
            awaitPacket(cap, NewTick.class, t -> t.tickId == 1);
            assertThat(realm.monster(1).hp()).isEqualTo(Monster.MAX_HP);
            assertThat(realm.monster(1).alive()).isTrue();
            assertThat(realm.bagCount()).isZero();
        } finally {
            system.close();
        }
    }

    @Test
    @DisplayName("ServerPlayerShoot geht an andere sichtbare Spieler, nicht an den Schützen")
    void serverPlayerShootBroadcast() throws Exception {
        final ActorSystem system = new ActorSystem("combat-shoot-test");
        try {
            final RealmActor realm = realmWith(system, 0);
            final ActorRef realmRef = system.spawn(realm);
            final RealmTestKit.CaptureActor capA = new RealmTestKit.CaptureActor("a");
            final RealmTestKit.CaptureActor capB = new RealmTestKit.CaptureActor("b");
            system.spawn(capA);
            system.spawn(capB);

            final int idA = join(realmRef, capA);
            realmRef.tell(new PlayerHello(capB.ref(), 0));
            realmRef.tell(new PlayerCreate(capB.ref(), 782, 0));
            awaitTrue(() -> capB.messages.stream().anyMatch(JoinConfirmed.class::isInstance));

            realmRef.tell(new PlayerShootMsg(idA, 5, 7, Player.ENERGY_STAFF, 25f, 25f, 0.5f));

            // B sieht den Schuss, A (Schütze) bekommt ihn nicht
            final ServerPlayerShoot shoot = awaitPacket(capB, ServerPlayerShoot.class,
                    s -> s.ownerId == idA);
            assertThat(shoot.bulletId).isEqualTo(7);
            assertThat(shoot.containerType).isEqualTo(Player.ENERGY_STAFF);
            assertThat(shoot.damage).isBetween(Player.ENERGY_STAFF_MIN_DAMAGE,
                    Player.ENERGY_STAFF_MAX_DAMAGE);
            assertThat(packets(capA, ServerPlayerShoot.class)).isEmpty();
        } finally {
            system.close();
        }
    }
}