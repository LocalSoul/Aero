package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static dev.localsoul.aero.game.protocol.MessageType.NEWTICK;

/**
 * {@code NEWTICK} (id 31) — Status aller sichtbaren Objekte für einen Spieler.
 */
public final class NewTick extends OutgoingMessage {

    public int tickId;
    public int tickTime;
    public final List<ObjectStatusData> statuses = new ArrayList<>();

    public NewTick() {
        super(NEWTICK);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(tickId);
        buf.writeInt(tickTime);
        buf.writeShort(statuses.size());
        for (final ObjectStatusData status : statuses) {
            status.writeToOutput(buf);
        }
    }
}