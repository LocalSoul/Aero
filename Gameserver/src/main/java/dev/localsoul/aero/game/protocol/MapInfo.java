package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

import static dev.localsoul.aero.game.protocol.MessageType.MAPINFO;

/**
 * {@code MAPINFO} (id 28) — Weltdaten. Die Reihenfolge der Felder ist
 * Spezifikation (Client {@code onMapInfo}); eine leere Welt braucht
 * keine clientXML/extraXML.
 */
public final class MapInfo extends OutgoingMessage {

    public int width;
    public int height;
    public String name = "";
    public String displayName = "";
    public long fp;
    public int background;
    public int difficulty;
    public boolean allowPlayerTeleport = true;
    public boolean showDisplays = true;
    public final List<String> clientXML = new ArrayList<>();
    public final List<String> extraXML = new ArrayList<>();

    public MapInfo() {
        super(MAPINFO);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        buf.writeInt(width);
        buf.writeInt(height);
        ByteBufs.writeUTF(buf, name);
        ByteBufs.writeUTF(buf, displayName);
        buf.writeInt((int) fp);
        buf.writeInt(background);
        buf.writeInt(difficulty);
        buf.writeBoolean(allowPlayerTeleport);
        buf.writeBoolean(showDisplays);
        buf.writeShort(clientXML.size());
        for (final String xml : clientXML) {
            buf.writeInt(xml.length());
            ByteBufs.writeUTFBytes(buf, xml);
        }
        buf.writeShort(extraXML.size());
        for (final String xml : extraXML) {
            buf.writeInt(xml.length());
            ByteBufs.writeUTFBytes(buf, xml);
        }
    }
}