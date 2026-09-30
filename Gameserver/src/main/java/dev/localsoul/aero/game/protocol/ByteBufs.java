package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Schreib-/Lesehelfer auf {@link ByteBuf}, die das AS3-Format der 27.7.X2-
 * Datenklassen abbilden: {@code writeUTF} = 2-Byte-Länge + UTF-8-Bytes,
 * {@code writeUTFBytes} = Bytes ohne Länge, alle Integer Big-Endian.
 * Verifiziert gegen {@code kabam/lib/net} + {@code kabam/rotmg/messaging/impl/data}.
 */
public final class ByteBufs {

    private ByteBufs() {
    }

    public static void writeUTF(final ByteBuf buf, final String value) {
        final byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readUTF(final ByteBuf buf) {
        final int len = buf.readUnsignedShort();
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeUTFBytes(final ByteBuf buf, final String value) {
        final byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        buf.writeBytes(bytes);
    }

    public static String readUTFBytes(final ByteBuf buf, final int len) {
        final byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}