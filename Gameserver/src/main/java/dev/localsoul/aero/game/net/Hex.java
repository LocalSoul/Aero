package dev.localsoul.aero.game.net;

/** Kleiner Hex-Helfer für die Protokoll-Keys. */
public final class Hex {

    private Hex() {
    }

    public static byte[] fromHex(final String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            final int hi = Character.digit(hex.charAt(i * 2), 16);
            final int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}