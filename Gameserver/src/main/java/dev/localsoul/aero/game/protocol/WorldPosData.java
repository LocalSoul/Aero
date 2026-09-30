package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/** Position in der Welt (2 Floats, AS3 {@code WorldPosData}). */
public final class WorldPosData {

    public float x;
    public float y;

    public WorldPosData() {
    }

    public WorldPosData(final float x, final float y) {
        this.x = x;
        this.y = y;
    }

    public void writeToOutput(final ByteBuf buf) {
        buf.writeFloat(x);
        buf.writeFloat(y);
    }

    public void readFromInput(final ByteBuf buf) {
        x = buf.readFloat();
        y = buf.readFloat();
    }
}