package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Ein neues Objekt in {@code Update} (AS3 {@code ObjectData}):
 * {@code objectType} (Short) + {@code ObjectStatusData}.
 */
public final class ObjectData {

    public short objectType;
    public final ObjectStatusData status = new ObjectStatusData();

    public void writeToOutput(final ByteBuf buf) {
        buf.writeShort(objectType);
        status.writeToOutput(buf);
    }
}