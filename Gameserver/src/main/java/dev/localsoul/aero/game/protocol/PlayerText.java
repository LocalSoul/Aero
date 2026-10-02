package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

import static dev.localsoul.aero.game.protocol.MessageType.PLAYERTEXT;

/**
 * {@code PLAYERTEXT} (id 9) — Chattext des Clients. Jede Eingabe (außer
 * {@code /help}) wird als dieses Paket gesendet; V2 nutzt daraus den
 * Serverbefehl {@code /give} (§9).
 */
public final class PlayerText extends IncomingMessage {

    public String text = "";

    public PlayerText() {
        super(PLAYERTEXT);
    }

    @Override
    public void parseFromInput(final ByteBuf buf) {
        text = ByteBufs.readUTF(buf);
    }
}