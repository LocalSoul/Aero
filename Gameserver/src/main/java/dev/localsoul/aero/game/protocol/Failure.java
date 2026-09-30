package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.FAILURE;

/**
 * {@code FAILURE} (id 0) — Fehler an den Client (z. B. Kick, Auth-Fehler).
 */
public final class Failure extends OutgoingMessage {

    public int errorId;
    public String errorDescription = "";

    public Failure() {
        super(FAILURE);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(errorId);
        ByteBufs.writeUTF(buf, errorDescription);
    }
}