package dev.localsoul.aero.game.net;

import dev.localsoul.aero.game.protocol.Create;
import dev.localsoul.aero.game.protocol.Escape;
import dev.localsoul.aero.game.protocol.Hello;
import dev.localsoul.aero.game.protocol.IncomingMessage;
import dev.localsoul.aero.game.protocol.Load;
import dev.localsoul.aero.game.protocol.MessageType;
import dev.localsoul.aero.game.protocol.Move;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Decodiert einen {@code LengthFieldBasedFrame}-Frame in ein
 * {@link IncomingMessage}: 1 Byte Typ (Klartext), Rest = Payload
 * (RC4-entschlüsselt, Key {@link ProtocolKeys#INCOMING}). Der RC4-Zustand ist
 * pro Verbindung und läuft über alle Pakete weiter.
 */
public final class PacketDecoder extends ByteToMessageDecoder {

    private final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.INCOMING);

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in,
                          final List<Object> out) {
        in.readInt();                                       // Längenfeld (Klartext) verwerfen
        final int typeId = in.readUnsignedByte();           // Typ (Klartext)
        final byte[] payload = new byte[in.readableBytes()];
        in.readBytes(payload);
        cipher.crypt(payload);                              // Payload entschlüsseln

        final IncomingMessage message = newMessage(typeId);
        if (message != null) {
            message.parseFromInput(Unpooled.wrappedBuffer(payload));
            out.add(message);
        }
    }

    private static IncomingMessage newMessage(final int typeId) {
        return switch (MessageType.byId(typeId)) {
            case HELLO -> new Hello();
            case LOAD -> new Load();
            case CREATE -> new Create();
            case MOVE -> new Move();
            case ESCAPE -> new Escape();
            // Akzeptierte, aber in V1 nicht verarbeitete Pakete (Acks usw.):
            default -> null;
        };
    }
}