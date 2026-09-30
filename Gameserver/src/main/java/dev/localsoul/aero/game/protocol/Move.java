package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static dev.localsoul.aero.game.protocol.MessageType.MOVE;

/**
 * {@code MOVE} (id 24) — Bewegungseingabe des Clients. Der Server ist
 * autoritativ; dieses Paket setzt nur die Wunsch-Position.
 */
public final class Move extends IncomingMessage {

    public int tickId;
    public int time;
    public final WorldPosData newPosition = new WorldPosData();
    public final List<MoveRecord> records = new ArrayList<>();

    public Move() {
        super(MOVE);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        tickId = buf.readInt();
        time = buf.readInt();
        newPosition.readFromInput(buf);
        final int count = buf.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            final MoveRecord record = new MoveRecord();
            record.readFromInput(buf);
            records.add(record);
        }
    }
}