package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.DEATH;

/**
 * {@code DEATH} (id 12) — der Spieler ist gestorben. {@code killedBy} ist nur
 * informativ (die „Killed by"-Zeile des Death-Screens kommt aus dem
 * Fame-HTTP-Endpoint); {@code zombieType}/{@code zombieId} sind in V2 {@code -1}.
 */
public final class Death extends OutgoingMessage {

    public String accountId = "";
    public int charId;
    public String killedBy = "";
    public int zombieType = -1;
    public int zombieId = -1;

    public Death() {
        super(DEATH);
    }

    @Override
    public void writeToOutput(final ByteBuf buf) {
        ByteBufs.writeUTF(buf, accountId);
        buf.writeInt(charId);
        ByteBufs.writeUTF(buf, killedBy);
        buf.writeInt(zombieType);
        buf.writeInt(zombieId);
    }
}