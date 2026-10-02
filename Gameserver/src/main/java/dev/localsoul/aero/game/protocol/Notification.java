package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.NOTIFICATION;

/**
 * {@code NOTIFICATION} (id 20) — Text über einem Objekt (z. B. XP-Gewinn).
 * {@code message} ist ein {@code LineBuilder}-JSON wie
 * {@code {"key":"server.plus_symbol","tokens":{"amount":"13"}}}.
 */
public final class Notification extends OutgoingMessage {

    public int objectId;
    public String message = "";
    public int color;

    public Notification() {
        super(NOTIFICATION);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(objectId);
        ByteBufs.writeUTF(buf, message);
        buf.writeInt(color);
    }
}