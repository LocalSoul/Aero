package dev.localsoul.aero.game;

/**
 * Entry-Point des Gameservers. Port als Argument (Default 2050), hält den
 * Server am Leben.
 */
public class Main {

    public static void main(final String[] args) throws Exception {
        final int port = args.length > 0 ? Integer.parseInt(args[0]) : GameServer.DEFAULT_PORT;

        try (GameServer server = new GameServer(port)) {
            server.start();
            System.out.println("Aero Gameserver listening on port " + port);
            Thread.currentThread().join();
        }
    }
}