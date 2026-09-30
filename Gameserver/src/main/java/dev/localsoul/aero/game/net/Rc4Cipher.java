package dev.localsoul.aero.game.net;

/**
 * ARCFOUR (RC4) als laufender Strom — der Zustand bleibt über alle Pakete
 * einer Richtung erhalten (der Flash-Client macht das mit
 * {@code com.hurlant.crypto} genauso). Nicht pro Paket neu seeden!
 */
public final class Rc4Cipher {

    private final int[] s = new int[256];
    private int i;
    private int j;

    public Rc4Cipher(final byte[] key) {
        for (int k = 0; k < 256; k++) {
            s[k] = k;
        }
        int j = 0;
        for (int k = 0; k < 256; k++) {
            j = (j + s[k] + (key[k % key.length] & 0xFF)) & 0xFF;
            swap(k, j);
        }
        this.i = 0;
        this.j = 0;
    }

    /** XORt den Keystream in {@code data} hinein (in place). */
    public void crypt(final byte[] data) {
        for (int k = 0; k < data.length; k++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            swap(i, j);
            data[k] ^= (byte) (s[(s[i] + s[j]) & 0xFF] & 0xFF);
        }
    }

    private void swap(final int a, final int b) {
        final int tmp = s[a];
        s[a] = s[b];
        s[b] = tmp;
    }
}