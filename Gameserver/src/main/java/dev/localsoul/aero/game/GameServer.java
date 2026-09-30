package dev.localsoul.aero.game;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.tick.OverrunPolicy;
import dev.localsoul.aero.actor.tick.TickDriver;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.actor.RoomRegistry;
import dev.localsoul.aero.game.net.ConnectionLimiter;
import dev.localsoul.aero.game.net.NettyServer;
import dev.localsoul.aero.game.world.Map;

import java.time.Duration;

/**
 * Einstieg des Gameservers: ActorSystem + TickDriver (20 Hz), eine
 * Nexus-Shard-Instanz über die {@link RoomRegistry}, dann der
 * {@link NettyServer}. Schließt in {@link #close()} alles geordnet.
 */
public final class GameServer implements AutoCloseable {

    public static final int NEXUS_GAME_ID = 0;
    public static final int DEFAULT_PORT = 2050;

    private final ActorSystem system;
    private final TickDriver driver;
    private final NettyServer netty;

    public GameServer(final int port) {
        system = new ActorSystem("game");
        driver = TickDriver.start(system, Duration.ofMillis(50), OverrunPolicy.CLAMP);

        final RoomRegistry rooms = new RoomRegistry();
        final Map nexusMap = new Map(50, 50);
        final ActorRef nexus = system.spawn(new RealmActor("nexus", driver, nexusMap));
        rooms.register(NEXUS_GAME_ID, nexus);

        final ConnectionLimiter limiter = new ConnectionLimiter(5);
        netty = new NettyServer(port, system, rooms, limiter, NEXUS_GAME_ID);
    }

    public void start() throws InterruptedException {
        netty.start();
    }

    @Override
    public void close() {
        netty.stop();
        driver.close();
        system.close();
    }
}