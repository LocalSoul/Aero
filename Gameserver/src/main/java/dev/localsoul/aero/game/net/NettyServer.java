package dev.localsoul.aero.game.net;

import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.game.actor.RoomRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * Netty-Bootstrap mit der §5-Pipeline: ConnectionLimiter → IdleStateHandler →
 * {@code LengthFieldBasedFrameDecoder} (mit {@code maxFrameLength}=1 MiB) →
 * {@link PacketDecoder} → {@link GameChannelHandler}; outbound
 * {@link PacketEncoder}. Die Event-Loops machen nur I/O und Decodieren; die
 * Spiel-Logik liegt in den Actor-Virtual-Threads.
 */
public final class NettyServer {

    private static final int MAX_FRAME_LENGTH = 1 << 20;      // 1 MiB OOM-Schutz

    private final int port;
    private final ActorSystem system;
    private final RoomRegistry rooms;
    private final ConnectionLimiter limiter;
    private final int gameId;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel channel;

    public NettyServer(final int port, final ActorSystem system, final RoomRegistry rooms,
                       final ConnectionLimiter limiter, final int gameId) {
        this.port = port;
        this.system = system;
        this.rooms = rooms;
        this.limiter = limiter;
        this.gameId = gameId;
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        final ServerBootstrap bootstrap = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(final SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(30, 0, 0))
                                .addLast(new LengthFieldBasedFrameDecoder(
                                        MAX_FRAME_LENGTH, 0, 4, -4, 0))
                                .addLast(new PacketDecoder())
                                .addLast(new GameChannelHandler(system, rooms, limiter, gameId))
                                .addLast(new PacketEncoder());
                    }
                });
        channel = bootstrap.bind(port).sync().channel();
    }

    public void stop() {
        if (channel != null) {
            channel.close().syncUninterruptibly();
        }
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }
}