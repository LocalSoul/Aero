package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.LOAD;

/**
 * {@code LOAD} (id 63) — Charakter in die Welt laden.
 */
public final class Load extends IncomingMessage {

    public int charId;
    public boolean isFromArena;

    public Load() {
        super(LOAD);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        charId = buf.readInt();
        isFromArena = buf.readBoolean();
    }
}