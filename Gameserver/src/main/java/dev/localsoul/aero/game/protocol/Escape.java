package dev.localsoul.aero.game.protocol;

import static dev.localsoul.aero.game.protocol.MessageType.ESCAPE;

/**
 * {@code ESCAPE} (id 16) — Client verlässt die Welt (leer).
 */
public final class Escape extends IncomingMessage {

    public Escape() {
        super(ESCAPE);
    }
}