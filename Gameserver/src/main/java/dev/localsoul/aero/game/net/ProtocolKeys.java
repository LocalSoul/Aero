package dev.localsoul.aero.game.net;

/**
 * Die fest verdrahteten RC4-Keys des 27.7.X2-Clients (kein Handshake).
 * Verifiziert gegen {@code GameServerConnectionConcrete.encryptConnection}.
 */
public final class ProtocolKeys {

    /** Client → Server: Server entschlüsselt eingehende Payloads damit. */
    public static final byte[] INCOMING = Hex.fromHex("311f80691451c71d09a13a2a6e");

    /** Server → Client: Server verschlüsselt ausgehende Payloads damit. */
    public static final byte[] OUTGOING = Hex.fromHex("72c5583cafb6818995cdd74b80");

    private ProtocolKeys() {
    }
}