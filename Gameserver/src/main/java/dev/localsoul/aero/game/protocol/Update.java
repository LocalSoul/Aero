package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static dev.localsoul.aero.game.protocol.MessageType.UPDATE;

/**
 * {@code UPDATE} (id 44) — Tiles, neue Objekte und Drops. In V1 werden beim
 * Join der Spieler selbst (neues Objekt) und optional Bodentiles übertragen.
 */
public final class Update extends OutgoingMessage {

    public final List<GroundTileData> tiles = new ArrayList<>();
    public final List<ObjectData> newObjs = new ArrayList<>();
    public final List<Integer> drops = new ArrayList<>();

    public Update() {
        super(UPDATE);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeShort(tiles.size());
        for (final GroundTileData tile : tiles) {
            tile.writeToOutput(buf);
        }
        buf.writeShort(newObjs.size());
        for (final ObjectData obj : newObjs) {
            obj.writeToOutput(buf);
        }
        buf.writeShort(drops.size());
        for (final int drop : drops) {
            buf.writeInt(drop);
        }
    }
}