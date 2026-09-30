package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Basis für Server→Client-Pakete. {@link #writeToOutput(ByteBuf)} schreibt den
 * (unverschlüsselten) Payload; der Encoder verschlüsselt und rahmt ihn.
 */
public class OutgoingMessage {

    private final MessageType type;

    protected OutgoingMessage(final MessageType type) {
        this.type = type;
    }

    public MessageType type() {
        return type;
    }

    /** Schreibt den Payload. Default: nichts. */
    public void writeToOutput(final ByteBuf payload) {
    }
}