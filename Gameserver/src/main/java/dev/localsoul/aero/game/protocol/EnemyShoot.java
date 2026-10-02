package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.ENEMYSHOOT;

/**
 * {@code ENEMYSHOOT} (id 90) — ein Monster feuert. {@code bulletType} ist der
 * Index ins Projektile-Array des Monsters (Ghost Mage: 0). Der Server
 * simuliert den Schuss selbst und sendet die Visualisierung an alle, die das
 * Monster sehen (§5.2).
 */
public final class EnemyShoot extends OutgoingMessage {

    public int bulletId;
    public int ownerId;
    public int bulletType;
    public final WorldPosData startingPos = new WorldPosData();
    public float angle;
    public int damage;
    public int numShots;
    public float angleInc;

    public EnemyShoot() {
        super(ENEMYSHOOT);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeByte(bulletId);
        buf.writeInt(ownerId);
        buf.writeByte(bulletType);
        startingPos.writeToOutput(buf);
        buf.writeFloat(angle);
        buf.writeShort(damage);
        buf.writeByte(numShots);
        buf.writeFloat(angleInc);
    }
}