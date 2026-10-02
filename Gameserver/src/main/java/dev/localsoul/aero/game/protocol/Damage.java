package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static dev.localsoul.aero.game.protocol.MessageType.DAMAGE;

/**
 * {@code DAMAGE} (id 52) — autoritativer Schaden an einem Spieler (Monster-
 * Projektil, §5.2). Die {@code effects} sind in V2 leer; {@code damageAmount}
 * ist der Endwert nach der Rüstungsformel.
 */
public final class Damage extends OutgoingMessage {

    public int targetId;
    public final List<Integer> effects = new ArrayList<>();
    public int damageAmount;
    public boolean kill;
    public int bulletId;
    public int objectId;

    public Damage() {
        super(DAMAGE);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(targetId);
        buf.writeByte(effects.size());
        for (final int effect : effects) {
            buf.writeByte(effect);
        }
        buf.writeShort(damageAmount);
        buf.writeBoolean(kill);
        buf.writeByte(bulletId);
        buf.writeInt(objectId);
    }
}