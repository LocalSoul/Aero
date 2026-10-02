package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.PLAYERSHOOT;

/**
 * {@code PLAYERSHOOT} (id 41) — der Client hat geschossen. Zeit, bulletId
 * (Byte, 0–255), Waffe ({@code containerType}), Startposition und Winkel.
 * Der Server rollt den Schaden, speichert die Schuss-Spur und validiert
 * spätere {@code EnemyHit}s gegen sie.
 */
public final class PlayerShoot extends IncomingMessage {

    public int time;
    public int bulletId;
    public int containerType;
    public final WorldPosData startingPos = new WorldPosData();
    public float angle;

    public PlayerShoot() {
        super(PLAYERSHOOT);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        time = buf.readInt();
        bulletId = buf.readUnsignedByte();
        containerType = buf.readShort();
        startingPos.readFromInput(buf);
        angle = buf.readFloat();
    }
}