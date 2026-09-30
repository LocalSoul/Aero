package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.PING;

/**
 * {@code PING} (id 8) — Latenz. In V1 optional, aber vom Client erwartet, um
 * Verbindung/Latenz zu messen.
 */
public final class Ping extends OutgoingMessage {

    public int serial;
    public int time;

    public Ping() {
        super(PING);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(serial);
        buf.writeInt(time);
    }
}