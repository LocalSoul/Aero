package dev.localsoul.aero.game.net;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.GameMessages.ChannelClosed;
import dev.localsoul.aero.game.actor.RoomRegistry;
import dev.localsoul.aero.game.actor.SessionActor;
import dev.localsoul.aero.game.protocol.IncomingMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Verknüpft eine Netty-Verbindung mit einem {@link SessionActor}: spawns die
 * Session beim {@code channelActive}, reicht decodierte Pakete per
 * {@code tell} an sie weiter (Event-Loop blockiert nie) und räumt beim
 * Disconnect auf. Der {@link ConnectionLimiter}-Zähler wird über das
 * {@code acquired}-Flag genau dann dekrementiert, wenn er auch erhöht wurde
 * (einzelner Exit-Pfad über {@code channelInactive}).
 */
public final class GameChannelHandler extends SimpleChannelInboundHandler<IncomingMessage> {

    private static final AtomicLong CLIENT_IDS = new AtomicLong();
    private static final int FLOOD_LIMIT_PER_SECOND = 500;

    private final ActorSystem system;
    private final RoomRegistry rooms;
    private final ConnectionLimiter limiter;
    private final int gameId;

    private NettyClient client;
    private ActorRef session;
    private FloodGuard flood;
    private boolean acquired;
    private String ip;

    public GameChannelHandler(final ActorSystem system, final RoomRegistry rooms,
                              final ConnectionLimiter limiter, final int gameId) {
        this.system = system;
        this.rooms = rooms;
        this.limiter = limiter;
        this.gameId = gameId;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) {
        ip = remoteIp(ctx);
        acquired = limiter.acquire(ip);
        if (!acquired) {
            ctx.close();                            // Limit überschritten → ablehnen
            return;
        }
        final ActorRef realm = rooms.choose(gameId);
        if (realm == null) {
            ctx.close();
            return;
        }
        flood = new FloodGuard(FLOOD_LIMIT_PER_SECOND);
        client = new NettyClient(ctx.channel(), CLIENT_IDS.incrementAndGet());
        session = system.spawn(new SessionActor("session-" + client.id(), realm, client));
        client.bindSession(session);
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx, final IncomingMessage message) {
        if (flood != null && !flood.allow()) {
            ctx.close();                            // FloodGuard → Kick
            return;
        }
        session.tell(message);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        if (session != null) {
            session.tell(new ChannelClosed());
        }
        if (acquired) {
            limiter.release(ip);
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        ctx.close();                                // → channelInactive (Release-Pfad)
    }

    private static String remoteIp(final ChannelHandlerContext ctx) {
        final InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
        return remote == null ? "?" : remote.getAddress().getHostAddress();
    }
}