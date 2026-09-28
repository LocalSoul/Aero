package dev.localsoul.aero.server;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        try (AeroServer server = new AeroServer(port)) {
            server.start();
            System.out.println("Aero HTTP server listening on http://localhost:" + port);
            Thread.currentThread().join();      // Server laufen lassen
        }
    }
}