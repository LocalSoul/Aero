package dev.localsoul.aero.game;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.game.net.Hex;
import dev.localsoul.aero.game.net.Rc4Cipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

@DisplayName("Rc4Cipher")
class Rc4CipherTest {

    @Test
    @DisplayName("bekannter RC4-Vektor: Key/Plaintext")
    void knownVectorKeyPlaintext() {
        final Rc4Cipher cipher = new Rc4Cipher("Key".getBytes(StandardCharsets.UTF_8));
        final byte[] data = "Plaintext".getBytes(StandardCharsets.UTF_8);
        cipher.crypt(data);
        assertThat(Hex.fromHex("bbf316e8d940af0ad3")).containsExactly(data);
    }

    @Test
    @DisplayName("bekannter RC4-Vektor: Wiki/pedia")
    void knownVectorWikiPedia() {
        final Rc4Cipher cipher = new Rc4Cipher("Wiki".getBytes(StandardCharsets.UTF_8));
        final byte[] data = "pedia".getBytes(StandardCharsets.UTF_8);
        cipher.crypt(data);
        assertThat(Hex.fromHex("1021bf0420")).containsExactly(data);
    }

    @Test
    @DisplayName("der Zustand läuft über mehrere Aufrufe weiter (Stream, kein Reset)")
    void stateContinuesAcrossCalls() {
        final byte[] key = "testkey".getBytes(StandardCharsets.UTF_8);
        final byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
        final byte[] part2 = "world".getBytes(StandardCharsets.UTF_8);

        final Rc4Cipher split = new Rc4Cipher(key);
        split.crypt(part1);
        split.crypt(part2);

        final byte[] whole = ("hello world").getBytes(StandardCharsets.UTF_8);
        final Rc4Cipher once = new Rc4Cipher(key);
        once.crypt(whole);

        assertThat(part1).containsExactly(java.util.Arrays.copyOfRange(whole, 0, 6));
        assertThat(part2).containsExactly(java.util.Arrays.copyOfRange(whole, 6, 11));
    }
}