package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/** Bodentile in {@code Update} (AS3 {@code GroundTileData}: x, y, type). */
public final class GroundTileData {

    public short x;
    public short y;
    public int type;

    public void writeToOutput(final ByteBuf buf) {
        buf.writeShort(x);
        buf.writeShort(y);
        buf.writeShort(type);
    }
}