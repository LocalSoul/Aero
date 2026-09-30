package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.HELLO;

/**
 * {@code HELLO} (id 86) — Verbindungsstart. Felder in Client-Reihenfolge
 * (verifiziert gegen {@code kabam/rotmg/messaging/impl/outgoing/Hello.as}).
 */
public final class Hello extends IncomingMessage {

    public String buildVersion = "";
    public int gameId;
    public String guid = "";
    public String password = "";
    public String secret = "";
    public int keyTime;
    public byte[] key = new byte[0];
    public String mapJson = "";
    public String entrytag = "";
    public String gameNet = "";
    public String gameNetUserId = "";
    public String playPlatform = "";
    public String platformToken = "";

    public Hello() {
        super(HELLO);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        buildVersion = ByteBufs.readUTF(buf);
        gameId = buf.readInt();
        guid = ByteBufs.readUTF(buf);
        buf.readInt();                                   // random
        password = ByteBufs.readUTF(buf);
        buf.readInt();                                   // random
        secret = ByteBufs.readUTF(buf);
        keyTime = buf.readInt();
        final int keyLen = buf.readUnsignedShort();
        key = new byte[keyLen];
        buf.readBytes(key);
        final int mapLen = buf.readInt();
        mapJson = ByteBufs.readUTFBytes(buf, mapLen);
        entrytag = ByteBufs.readUTF(buf);
        gameNet = ByteBufs.readUTF(buf);
        gameNetUserId = ByteBufs.readUTF(buf);
        playPlatform = ByteBufs.readUTF(buf);
        platformToken = ByteBufs.readUTF(buf);
    }
}