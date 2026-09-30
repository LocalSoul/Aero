package dev.localsoul.aero.game.actor;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.game.protocol.CreateSuccess;
import dev.localsoul.aero.game.protocol.MapInfo;
import dev.localsoul.aero.game.protocol.OutgoingMessage;
import dev.localsoul.aero.game.protocol.Update;

import java.util.List;

/**
 * Nachrichten des Gameserver-Datenflusses (§7 der gameserver_implement.md).
 * Unveränderliche Records; Zustandsübergänge passieren ausschließlich im
 * Realm-Tick, die Session ist reiner Transport.
 */
public final class GameMessages {

    private GameMessages() {
    }

    // ------------------------------------------------ Session → Realm

    /** Phase 1: Client hat {@code Hello} gesendet. */
    public record PlayerHello(ActorRef session, int gameId) {
    }

    /** Phase 2: Client hat {@code Load} gesendet (Charakter laden). */
    public record PlayerJoin(ActorRef session, int charId) {
    }

    /** Phase 2: Client hat {@code Create} gesendet (neuer Charakter). */
    public record PlayerCreate(ActorRef session, int classType, int skinType) {
    }

    /** Bewegungseingabe (Wunsch-Position). */
    public record PlayerMove(int objectId, float x, float y) {
    }

    /** Externer Disconnect (channelInactive) — Spieler aus der Welt. */
    public record PlayerLeave(int objectId) {
    }

    /** Backpressure: Realm soll den Spieler entfernen (vom Realm bestätigt). */
    public record KickPlayer(int objectId, String reason) {
    }

    // ------------------------------------------------ Realm → Session

    /** Phase 1-Ack: MapInfo gebaut (Client sendet danach Load). */
    public record MapInfoReady(MapInfo mapInfo) {
    }

    /** Phase 2-Ack: Entity angelegt, objectId vergeben, Startpakete. */
    public record JoinConfirmed(int objectId, Update update, CreateSuccess createSuccess) {
    }

    /** Realm hat das Entity geräumt — Session darf jetzt schließen. */
    public record KickConfirmed(String reason) {
    }

    /** Per-Tick-Outbound-Batch (ein Flush pro Tick, §6). */
    public record OutboundBatch(int tickId, List<OutgoingMessage> messages) {
    }

    // ------------------------------------------------ NettyClient → Session

    /** NettyClient erkennt Backpressure (Queue voll / !writable). */
    public record BackpressureKick(String reason) {
    }

    // ------------------------------------------------ Netty-Handler → Session

    /** Channel geschlossen (channelInactive) — Session räumt auf. */
    public record ChannelClosed() {
    }
}