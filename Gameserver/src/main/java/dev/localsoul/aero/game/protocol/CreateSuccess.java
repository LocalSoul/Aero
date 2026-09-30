package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.CREATE_SUCCESS;

/**
 * {@code CREATE_SUCCESS} (id 58) — der Spieler ist in der Welt, mit der vom
 * Realm vergebenen {@code objectId}.
 */
public final class CreateSuccess extends OutgoingMessage {

    public int objectId;
    public int charId;

    public CreateSuccess() {
        super(CREATE_SUCCESS);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(objectId);
        buf.writeInt(charId);
    }
}