package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Status eines Objekts in {@code NewTick}/{@code Update}
 * (AS3 {@code ObjectStatusData}): objectId, Position, Stats.
 */
public final class ObjectStatusData {

    public int objectId;
    public final WorldPosData pos = new WorldPosData();
    public final List<StatData> stats = new ArrayList<>();

    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(objectId);
        pos.writeToOutput(buf);
        buf.writeShort(stats.size());
        for (final StatData stat : stats) {
            stat.writeToOutput(buf);
        }
    }
}