package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.PLAYERHIT;

/**
 * {@code PLAYERHIT} (id 37) — das Client-Projektil eines Monsters hat den
 * Spieler getroffen. Wird geparst, aber **nicht** als Schadensquelle genutzt
 * (§2.3): der Server simuliert Monster-Projektile selbst und sendet
 * {@code Damage} autoritativ.
 */
public final class PlayerHit extends IncomingMessage {

    public int bulletId;
    public int objectId;

    public PlayerHit() {
        super(PLAYERHIT);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        bulletId = buf.readUnsignedByte();
        objectId = buf.readInt();
    }
}