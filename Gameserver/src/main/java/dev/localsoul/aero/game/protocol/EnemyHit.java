package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.ENEMYHIT;

/**
 * {@code ENEMYHIT} (id 94) — das Client-Projektil hat ein Monster getroffen.
 * Der Server validiert gegen die Schuss-Spur (§5.1) und wendet Schaden an.
 */
public final class EnemyHit extends IncomingMessage {

    public int time;
    public int bulletId;
    public int targetId;
    public boolean kill;

    public EnemyHit() {
        super(ENEMYHIT);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        time = buf.readInt();
        bulletId = buf.readUnsignedByte();
        targetId = buf.readInt();
        kill = buf.readBoolean();
    }
}