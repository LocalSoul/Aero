package dev.localsoul.aero.game.net;

import dev.localsoul.aero.game.protocol.OutgoingMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodiert ein {@link OutgoingMessage} in einen Frame:
 * Payload RC4-verschlüsselt (Key {@link ProtocolKeys#OUTGOING}), dann
 * {@code writeInt(payload+5)}, {@code writeByte(type)}, {@code writeBytes(payload)}.
 */
public final class PacketEncoder extends MessageToByteEncoder<OutgoingMessage> {

    private final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.OUTGOING);

    @Override
    protected void encode(final ChannelHandlerContext ctx, final OutgoingMessage msg,
                          final ByteBuf out) {
        final ByteBuf payload = Unpooled.buffer();
        msg.writeToOutput(payload);
        final byte[] bytes = new byte[payload.readableBytes()];
        payload.readBytes(bytes);
        payload.release();
        cipher.crypt(bytes);

        out.writeInt(bytes.length + 5);                     // Länge = Payload + Typ + Länge
        out.writeByte(msg.type().id());
        out.writeBytes(bytes);
    }
}