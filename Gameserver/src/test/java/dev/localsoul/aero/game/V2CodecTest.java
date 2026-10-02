package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.net.PacketDecoder;
import dev.localsoul.aero.game.net.PacketEncoder;
import dev.localsoul.aero.game.net.ProtocolKeys;
import dev.localsoul.aero.game.net.Rc4Cipher;
import dev.localsoul.aero.game.protocol.ByteBufs;
import dev.localsoul.aero.game.protocol.Damage;
import dev.localsoul.aero.game.protocol.Death;
import dev.localsoul.aero.game.protocol.EnemyHit;
import dev.localsoul.aero.game.protocol.EnemyShoot;
import dev.localsoul.aero.game.protocol.MessageType;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.protocol.PlayerShoot;
import dev.localsoul.aero.game.protocol.ServerPlayerShoot;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2-Pakete: Feldreihenfolgen gegen die Client-Klassen
 * ({@code kabam/rotmg/messaging/impl/...}) verifiziert.
 */
@DisplayName("V2-Paketcodec (Kampf)")
class V2CodecTest {

    /** Encoder → Frame → RC4 entschlüsseln → Payload für die Prüfung. */
    private static ByteBuf encode(final Object packet) {
        final EmbeddedChannel ch = new EmbeddedChannel(new PacketEncoder());
        ch.writeOutbound(packet);
        final ByteBuf frame = ch.readOutbound();
        frame.readInt();                                 // Länge
        assertThat(frame.readByte() & 0xFF)
                .as("Typ passt zum Paket")
                .isEqualTo(((dev.localsoul.aero.game.protocol.OutgoingMessage) packet).type().id());
        final byte[] encrypted = new byte[frame.readableBytes()];
        frame.readBytes(encrypted);
        frame.release();
        final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.OUTGOING);
        cipher.crypt(encrypted);
        return Unpooled.wrappedBuffer(encrypted);
    }

    @Test
    @DisplayName("PlayerShoot-Decode (time, bulletId u8, containerType short, pos, angle)")
    void playerShootDecode() {
        final ByteBuf payload = Unpooled.buffer();
        payload.writeInt(1000);
        payload.writeByte(7);
        payload.writeShort(0xa97);
        payload.writeFloat(10f);
        payload.writeFloat(20f);
        payload.writeFloat(0.5f);
        final PlayerShoot shot = decode(payload, MessageType.PLAYERSHOOT.id());
        assertThat(shot.time).isEqualTo(1000);
        assertThat(shot.bulletId).isEqualTo(7);
        assertThat(shot.containerType).isEqualTo(0xa97);
        assertThat(shot.startingPos.x).isEqualTo(10f);
        assertThat(shot.startingPos.y).isEqualTo(20f);
        assertThat(shot.angle).isEqualTo(0.5f);
    }

    @Test
    @DisplayName("EnemyHit-Decode (time, bulletId u8, targetId, kill)")
    void enemyHitDecode() {
        final ByteBuf payload = Unpooled.buffer();
        payload.writeInt(1000);
        payload.writeByte(3);
        payload.writeInt(42);
        payload.writeBoolean(true);
        final EnemyHit hit = decode(payload, MessageType.ENEMYHIT.id());
        assertThat(hit.time).isEqualTo(1000);
        assertThat(hit.bulletId).isEqualTo(3);
        assertThat(hit.targetId).isEqualTo(42);
        assertThat(hit.kill).isTrue();
    }

    @Test
    @DisplayName("EnemyShoot-Encode (bulletId u8, ownerId, bulletType u8, pos, angle, dmg, numShots, angleInc)")
    void enemyShootEncode() {
        final EnemyShoot msg = new EnemyShoot();
        msg.bulletId = 9;
        msg.ownerId = 42;
        msg.bulletType = 0;
        msg.startingPos.x = 1f;
        msg.startingPos.y = 2f;
        msg.angle = 1.5f;
        msg.damage = 40;
        msg.numShots = 1;
        msg.angleInc = 0f;
        final ByteBuf payload = encode(msg);
        assertThat(payload.readUnsignedByte()).isEqualTo((short) 9);
        assertThat(payload.readInt()).isEqualTo(42);
        assertThat(payload.readUnsignedByte()).isEqualTo((short) 0);
        assertThat(payload.readFloat()).isEqualTo(1f);
        assertThat(payload.readFloat()).isEqualTo(2f);
        assertThat(payload.readFloat()).isEqualTo(1.5f);
        assertThat(payload.readShort()).isEqualTo((short) 40);
        assertThat(payload.readUnsignedByte()).isEqualTo((short) 1);
        assertThat(payload.readFloat()).isEqualTo(0f);
    }

    @Test
    @DisplayName("Damage-Encode (targetId, effects-Count, dmg u16, kill, bulletId u8, objectId)")
    void damageEncode() {
        final Damage msg = new Damage();
        msg.targetId = 7;
        msg.damageAmount = 40;
        msg.kill = true;
        msg.bulletId = 5;
        msg.objectId = 3;
        final ByteBuf payload = encode(msg);
        assertThat(payload.readInt()).isEqualTo(7);
        assertThat(payload.readUnsignedByte()).as("keine Effects").isZero();
        assertThat(payload.readUnsignedShort()).isEqualTo(40);
        assertThat(payload.readBoolean()).isTrue();
        assertThat(payload.readUnsignedByte()).isEqualTo((short) 5);
        assertThat(payload.readInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("ServerPlayerShoot-Encode (bulletId u8, ownerId, containerType int, pos, angle, dmg short)")
    void serverPlayerShootEncode() {
        final ServerPlayerShoot msg = new ServerPlayerShoot();
        msg.bulletId = 2;
        msg.ownerId = 9;
        msg.containerType = 0xa97;
        msg.startingPos.x = 3f;
        msg.startingPos.y = 4f;
        msg.angle = 0.25f;
        msg.damage = 17;
        final ByteBuf payload = encode(msg);
        assertThat(payload.readUnsignedByte()).isEqualTo((short) 2);
        assertThat(payload.readInt()).isEqualTo(9);
        assertThat(payload.readInt()).isEqualTo(0xa97);
        assertThat(payload.readFloat()).isEqualTo(3f);
        assertThat(payload.readFloat()).isEqualTo(4f);
        assertThat(payload.readFloat()).isEqualTo(0.25f);
        assertThat(payload.readShort()).isEqualTo((short) 17);
    }

    @Test
    @DisplayName("Notification-Encode (objectId, message UTF, color int)")
    void notificationEncode() {
        final Notification msg = new Notification();
        msg.objectId = 7;
        msg.message = "{\"key\":\"server.plus_symbol\",\"tokens\":{\"amount\":\"13\"}}";
        msg.color = 0xFFFFFF;
        final ByteBuf payload = encode(msg);
        assertThat(payload.readInt()).isEqualTo(7);
        assertThat(ByteBufs.readUTF(payload)).contains("server.plus_symbol");
        assertThat(payload.readInt()).isEqualTo(0xFFFFFF);
    }

    @Test
    @DisplayName("Death-Encode (accountId, charId, killedBy, zombieType, zombieId)")
    void deathEncode() {
        final Death msg = new Death();
        msg.accountId = "";
        msg.charId = 5;
        msg.killedBy = "Ghost Mage";
        final ByteBuf payload = encode(msg);
        assertThat(ByteBufs.readUTF(payload)).isEmpty();
        assertThat(payload.readInt()).isEqualTo(5);
        assertThat(ByteBufs.readUTF(payload)).isEqualTo("Ghost Mage");
        assertThat(payload.readInt()).isEqualTo(-1);
        assertThat(payload.readInt()).isEqualTo(-1);
    }

    /** Frame bauen (RC4 + Typ + Länge) und durch den Decoder schicken. */
    private static <T> T decode(final ByteBuf payload, final int typeId) {
        final byte[] plain = new byte[payload.readableBytes()];
        payload.readBytes(plain);
        payload.release();
        final Rc4Cipher cipher = new Rc4Cipher(ProtocolKeys.INCOMING);
        cipher.crypt(plain);
        final ByteBuf frame = Unpooled.buffer();
        frame.writeInt(plain.length + 5);
        frame.writeByte(typeId);
        frame.writeBytes(plain);

        final EmbeddedChannel ch = new EmbeddedChannel(new PacketDecoder());
        ch.writeInbound(frame);
        final Object message = ch.readInbound();
        assertThat(message).isNotNull();
        return (T) message;
    }
}