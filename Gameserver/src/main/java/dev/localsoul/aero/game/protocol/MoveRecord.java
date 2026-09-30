package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/** Ein Bewegungseintrag in {@code Move} (AS3 {@code MoveRecord}). */
public final class MoveRecord {

    public int time;
    public float x;
    public float y;

    public void readFromInput(final ByteBuf buf) {
        time = buf.readInt();
        x = buf.readFloat();
        y = buf.readFloat();
    }
}