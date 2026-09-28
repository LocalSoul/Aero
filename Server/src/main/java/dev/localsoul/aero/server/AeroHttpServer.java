package dev.localsoul.aero.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Schlanker HTTP-Server auf Basis des JDK-{@link HttpServer} (keine externe
 * Abhängigkeit). Protokolliert jede eingehende Anfrage über
 * {@code System.out.println} — Methode, URI inkl. Query, Header und Body.
 *
 * <p>Beim Serverstart geladene statische Ressourcen werden an den Client
 * ausgeliefert:
 * <ul>
 *   <li>{@code POST /app/getLanguageStrings} → {@link LanguageStrings}
 *       (JSON-Array),</li>
 *   <li>{@code POST /app/init} → {@code app/init.xml} ({@code <AppSettings>}),</li>
 *   <li>{@code POST /char/list} → {@code app/char_list.xml} ({@code <Chars>}),</li>
 *   <li>{@code POST /package/getPackages} → {@code app/packages.xml}
 *       ({@code <Request>} mit {@code <Packages>}),</li>
 *   <li>{@code POST /app/globalNews} → {@code app/global_news.json}
 *       (JSON-Array).</li>
 * </ul>
 *
 * <p>Ein Virtual-Thread-Executor hält den Carrier nicht blockiert; das
 * entspricht der Actor-Runtime-Philosophie des Projekts.
 */
public final class AeroHttpServer implements AutoCloseable {

    private static final String LANGUAGE_ROUTE = "/app/getLanguageStrings";
    private static final String INIT_ROUTE = "/app/init";
    private static final String CHAR_LIST_ROUTE = "/char/list";
    private static final String PACKAGES_ROUTE = "/package/getPackages";
    private static final String GLOBAL_NEWS_ROUTE = "/app/globalNews";

    private final HttpServer server;
    private final byte[] languageStrings;
    private final byte[] initXml;
    private final byte[] charListXml;
    private final byte[] packagesXml;
    private final byte[] globalNewsJson;

    public AeroHttpServer(final int port, final byte[] languageStrings, final byte[] initXml,
                          final byte[] charListXml, final byte[] packagesXml,
                          final byte[] globalNewsJson) throws IOException {
        this.languageStrings = languageStrings;
        this.initXml = initXml;
        this.charListXml = charListXml;
        this.packagesXml = packagesXml;
        this.globalNewsJson = globalNewsJson;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::handleRequest);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleRequest(final HttpExchange exchange) throws IOException {
        logRequest(exchange);

        if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            if (LANGUAGE_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "application/json", languageStrings);
                return;
            }
            if (INIT_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "application/xml", initXml);
                return;
            }
            if (CHAR_LIST_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "application/xml", charListXml);
                return;
            }
            if (PACKAGES_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "application/xml", packagesXml);
                return;
            }
            if (GLOBAL_NEWS_ROUTE.equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "application/json", globalNewsJson);
                return;
            }
        }

        respond(exchange, 200, "text/html", "<error>Upsi</error>".getBytes(StandardCharsets.UTF_8));
    }

    private void logRequest(final HttpExchange exchange) throws IOException {
        System.out.println("[" + Instant.now() + "] " + exchange.getRequestMethod()
                + " " + exchange.getRequestURI() + " ");

        exchange.getRequestHeaders().forEach((name, values) ->
                System.out.println("    " + name + ": " + String.join(", ", values)));

        final byte[] body = exchange.getRequestBody().readAllBytes();
        if (body.length > 0) {
            System.out.println("    body: " + new String(body, StandardCharsets.UTF_8));
        }
        System.out.println();
    }

    private static void respond(final HttpExchange exchange, final int status,
                                final String contentType, final byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}