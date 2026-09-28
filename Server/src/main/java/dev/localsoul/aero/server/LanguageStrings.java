package dev.localsoul.aero.server;

import java.io.IOException;
import java.io.InputStream;

/**
 * Vom Server beim Start geladene Sprachdatei (Classpath-Ressource). Wird bei
 * {@code POST /app/getLanguageStrings} unverändert als JSON-Array an den
 * Client zurückgegeben (Format: {@code [["key", "value", "language"], ...]}).
 */
public final class LanguageStrings {

    private final byte[] json;

    public LanguageStrings(String classpathResource) throws IOException {
        try (InputStream in = LanguageStrings.class.getClassLoader()
                .getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("language resource not found on classpath: " + classpathResource);
            }
            this.json = in.readAllBytes();
        }
    }

    /** Der unveränderte Inhalt der Sprachdatei als UTF-8-Bytes. */
    public byte[] jsonBytes() {
        return json;
    }
}