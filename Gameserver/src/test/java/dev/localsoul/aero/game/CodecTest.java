package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.net.PacketDecoder;
import dev.localsoul.aero.game.net.PacketEncoder;
import dev.localsoul.aero.game.net.ProtocolKeys;
import dev.localsoul.aero.game.net.Rc4Cipher;
import dev.localsoul.aero.game.protocol.ByteBufs;
import dev.localsoul.aero.game.protocol.CreateSuccess;
import dev.localsoul.aero.game.protocol.Hello;
import dev.localsoul.aero.game.protocol.MessageType;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.ObjectStatusData;
import dev.localsoul.aero.game.protocol.StatData;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Packet-Encoder/Decoder (Framing + RC4)")
class CodecTest {

    @Test
    @DisplayName("Encoder rahmt ein OutgoingMessage und verschlüsselt den Payload")
    void encoderFramesAndEncrypts() {
        final CreateSuccess msg = new CreateSuccess();
        msg.objectId = 42;
        msg.charId = 7;

        final EmbeddedChannel ch = new EmbeddedChannel(new PacketEncoder());
        ch.writeOutbound(msg);

        final ByteBuf frame = ch.readOutbound();
        final int length = frame.readInt();
        assertThat(length).as("Länge = Payload(8) + 5").isEqualTo(13);
        assertThat(frame.readByte() & 0xFF).as("Typ").isEqualTo(MessageType.CREATE_SUCCESS.id());
        final byte[] encrypted = new byte[frame.readableBytes()];
        frame.readBytes(encrypted);
        frame.release();

        final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.OUTGOING);
        cipher.crypt(encrypted);
        final ByteBuf payload = Unpooled.wrappedBuffer(encrypted);
        assertThat(payload.readInt()).isEqualTo(42);
        assertThat(payload.readInt()).isEqualTo(7);
        payload.release();
    }

    @Test
    @DisplayName("Decoder entschlüsselt einen Client-Hello-Frame und parst die Felder")
    void decoderDecryptsAndParsesHello() {
        final ByteBuf payload = Unpooled.buffer();
        ByteBufs.writeUTF(payload, "27.7.X2");
        payload.writeInt(0);                             // gameId
        ByteBufs.writeUTF(payload, "test@local");
        payload.writeInt(0);                             // random
        ByteBufs.writeUTF(payload, "pw");
        payload.writeInt(0);                             // random
        ByteBufs.writeUTF(payload, "secret");
        payload.writeInt(1234);                          // keyTime
        payload.writeShort(0);                           // key.length
        payload.writeInt(0);                             // mapJSON.length
        ByteBufs.writeUTF(payload, "");
        ByteBufs.writeUTF(payload, "rotmg");
        ByteBufs.writeUTF(payload, "userId");
        ByteBufs.writeUTF(payload, "rotmg");
        ByteBufs.writeUTF(payload, "token");
        final byte[] plain = new byte[payload.readableBytes()];
        payload.readBytes(plain);
        payload.release();

        final Rc4Cipher clientCipher = new Rc4Cipher(ProtocolKeys.INCOMING);
        clientCipher.crypt(plain);

        final ByteBuf frame = Unpooled.buffer();
        frame.writeInt(plain.length + 5);
        frame.writeByte(MessageType.HELLO.id());
        frame.writeBytes(plain);

        final EmbeddedChannel ch = new EmbeddedChannel(new PacketDecoder());
        ch.writeInbound(frame);
        final Hello hello = ch.readInbound();
        assertThat(hello).isNotNull();
        assertThat(hello.buildVersion).isEqualTo("27.7.X2");
        assertThat(hello.guid).isEqualTo("test@local");
        assertThat(hello.gameNet).isEqualTo("rotmg");
        assertThat(hello.keyTime).isEqualTo(1234);
    }

    @Test
    @DisplayName("NewTick-Roundtrip über Encoder")
    void newTickRoundtrip() {
        final NewTick tick = new NewTick();
        tick.tickId = 5;
        tick.tickTime = 50;
        final ObjectStatusData status = new ObjectStatusData();
        status.objectId = 42;
        status.pos.x = 10f;
        status.pos.y = 20f;
        status.stats.add(StatData.of(7, 1));
        status.stats.add(StatData.of(31, "Player"));
        tick.statuses.add(status);

        final EmbeddedChannel ch = new EmbeddedChannel(new PacketEncoder());
        ch.writeOutbound(tick);
        final ByteBuf frame = ch.readOutbound();
        frame.readInt();                                 // length
        assertThat(frame.readByte() & 0xFF).isEqualTo(MessageType.NEWTICK.id());
        final byte[] encrypted = new byte[frame.readableBytes()];
        frame.readBytes(encrypted);
        frame.release();

        final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.OUTGOING);
        cipher.crypt(encrypted);
        final ByteBuf payload = Unpooled.wrappedBuffer(encrypted);
        assertThat(payload.readInt()).isEqualTo(5);
        assertThat(payload.readInt()).isEqualTo(50);
        assertThat(payload.readUnsignedShort()).isEqualTo(1);
        assertThat(payload.readInt()).isEqualTo(42);
        assertThat(payload.readFloat()).isEqualTo(10f);
        assertThat(payload.readFloat()).isEqualTo(20f);
        assertThat(payload.readUnsignedShort()).isEqualTo(2);
        payload.release();
    }
}