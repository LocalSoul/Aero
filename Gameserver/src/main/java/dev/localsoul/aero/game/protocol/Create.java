package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.CREATE;

/**
 * {@code CREATE} (id 48) — neuer Charakter beim ersten Join (der Client setzt
 * {@code createCharacter_ = true} beim „Play"-Klick, siehe
 * {@code EnterGameCommand}). {@code classType} + {@code skinType}.
 */
public final class Create extends IncomingMessage {

    public int classType;
    public int skinType;

    public Create() {
        super(CREATE);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        classType = buf.readUnsignedShort();
        skinType = buf.readUnsignedShort();
    }
}