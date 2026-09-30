package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Basis für Client→Server-Pakete. Der {@link #id()}-Typ bestimmt die
 * Decodierung; {@link #parseFromInput(ByteBuf)} liest den (bereits
 * entschlüsselten) Payload.
 */
public class IncomingMessage {

    private final MessageType type;

    protected IncomingMessage(final MessageType type) {
        this.type = type;
    }

    public MessageType type() {
        return type;
    }

    /** Liest den Payload (nach RC4-Entschlüsselung). Default: nichts. */
    public void parseFromInput(final ByteBuf payload) {
    }
}