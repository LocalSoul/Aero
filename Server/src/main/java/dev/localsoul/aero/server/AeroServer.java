package dev.localsoul.aero.server;

import java.io.IOException;
import java.io.InputStream;

/**
 * Einstiegspunkt des Aero-RotMG-Servers. Aktuell hält er den schlanken
 * {@link AeroHttpServer}; die eigentliche Spielserver-Logik (Login, Reiche,
 * Welt-Simulation) wird auf der Actor-Runtime aufgebaut.
 *
 * <p>Beim Start werden die statischen Ressourcen aus dem Classpath geladen
 * (Sprachdatei {@code languages/lang_en.json}, {@code app/init.xml},
 * {@code app/char_list.xml}, {@code app/packages.xml},
 * {@code app/global_news.json}); die Client-Endpunkte
 * {@code POST /app/getLanguageStrings}, {@code POST /app/init},
 * {@code POST /char/list}, {@code POST /package/getPackages} und
 * {@code POST /app/globalNews} liefern sie aus.
 */
public final class AeroServer implements AutoCloseable {

    private static final String LANGUAGE_RESOURCE = "languages/lang_en.json";
    private static final String INIT_RESOURCE = "app/init.xml";
    private static final String CHAR_LIST_RESOURCE = "app/char_list.xml";
    private static final String PACKAGES_RESOURCE = "app/packages.xml";
    private static final String GLOBAL_NEWS_RESOURCE = "app/global_news.json";

    private final AeroHttpServer http;

    public AeroServer() {
        this(8080);
    }

    public AeroServer(int httpPort) {
        try {
            LanguageStrings languages = new LanguageStrings(LANGUAGE_RESOURCE);
            byte[] initXml = loadResource(INIT_RESOURCE);
            byte[] charListXml = loadResource(CHAR_LIST_RESOURCE);
            byte[] packagesXml = loadResource(PACKAGES_RESOURCE);
            byte[] globalNewsJson = loadResource(GLOBAL_NEWS_RESOURCE);
            this.http = new AeroHttpServer(httpPort, languages.jsonBytes(), initXml,
                    charListXml, packagesXml, globalNewsJson);
        } catch (IOException e) {
            throw new RuntimeException("could not initialise Aero server", e);
        }
    }

    private static byte[] loadResource(String classpathResource) throws IOException {
        try (InputStream in = AeroServer.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IOException("resource not found on classpath: " + classpathResource);
            }
            return in.readAllBytes();
        }
    }

    public void start() {
        http.start();
    }

    @Override
    public void close() {
        http.close();
    }
}