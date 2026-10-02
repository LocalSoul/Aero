package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.SERVERPLAYERSHOOT;

/**
 * {@code SERVERPLAYERSHOOT} (id 1) — ein anderer Spieler hat geschossen; das
 * Paket rendert das Projektil für die anderen Clients. Der Schütze sieht sein
 * Projektil lokal und bekommt es nicht. {@code damage} ist der vom Server
 * gerollte Wert (identisch zur Schuss-Spur, §5.1).
 */
public final class ServerPlayerShoot extends OutgoingMessage {

    public int bulletId;
    public int ownerId;
    public int containerType;
    public final WorldPosData startingPos = new WorldPosData();
    public float angle;
    public int damage;

    public ServerPlayerShoot() {
        super(SERVERPLAYERSHOOT);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeByte(bulletId);
        buf.writeInt(ownerId);
        buf.writeInt(containerType);
        startingPos.writeToOutput(buf);
        buf.writeFloat(angle);
        buf.writeShort(damage);
    }
}