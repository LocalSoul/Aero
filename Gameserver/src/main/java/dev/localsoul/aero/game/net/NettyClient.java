package dev.localsoul.aero.game.net;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.session.Client;
import dev.localsoul.aero.game.actor.GameMessages.BackpressureKick;
import dev.localsoul.aero.game.protocol.OutgoingMessage;
import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Die Netty-Verbindung als {@link Client}: {@link #send(Object)} schreibt
 * **ohne Flush** in den Channel, {@link #flush()} einmal am Tick-Ende
 * (§6). Backpressure → {@code session.tell(BackpressureKick)} — der Kick ist
 * eine Nachricht, kein direkter {@code close()} (Invariante: nur der
 * Realm-Thread mutiert die Entity-Map).
 */
public final class NettyClient implements Client {

    /** Maximal ungeflushte Batches, bevor der Client gekickt wird. */
    private static final int MAX_PENDING = 256;

    private final Channel channel;
    private final long id;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong lastFlushNanos = new AtomicLong(System.nanoTime());
    private final AtomicBoolean kicked = new AtomicBoolean();
    private volatile ActorRef session;

    public NettyClient(final Channel channel, final long id) {
        this.channel = channel;
        this.id = id;
    }

    /** Session-Actor binden (für den Kick-Rückkanal). */
    public void bindSession(final ActorRef session) {
        this.session = session;
    }

    @Override
    public void send(final Object packet) {
        if (!(packet instanceof OutgoingMessage message)) {
            return;
        }
        channel.write(message);                          // ohne Flush
        if (pending.incrementAndGet() > MAX_PENDING) {
            kick("outbound queue overflow");
        } else if (!channel.isWritable() && pending.get() > 0) {
            final long age = System.nanoTime() - lastFlushNanos.get();
            if (age > 2_000_000_000L) {                  // 2 s nicht writable → Kick
                kick("channel not writable");
            }
        }
    }

    /** Einmal am Tick-Ende (oder nach Join/Kick) aufrufen. */
    public void flush() {
        pending.set(0);
        lastFlushNanos.set(System.nanoTime());
        channel.flush();
    }

    private void kick(final String reason) {
        if (kicked.compareAndSet(false, true)) {
            final ActorRef session = this.session;
            if (session != null) {
                session.tell(new BackpressureKick(reason));
            }
        }
    }

    @Override
    public void close() {
        channel.close();
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public boolean isOpen() {
        return channel.isActive();
    }
}