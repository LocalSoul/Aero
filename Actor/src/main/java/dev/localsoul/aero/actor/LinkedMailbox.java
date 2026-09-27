package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;
import dev.localsoul.aero.actor.internal.Wakeup;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unbegrenztes Postfach auf {@link ConcurrentLinkedQueue}.
 *
 * <p>Nur fuer kontrollierte Lebensphasen, in denen Backpressure noch keine
 * Rolle spielt: {@code init}, {@code preStart}, der Aufbau einer Raumliste oder
 * das {@code postStop} nach dem Drain. Ab dem ersten {@code Terminate} wird auf
 * eine bounded Mailbox gewechselt.
 *
 * <p>{@code size()} ist bei CLQ {@code O(n)} — nur in Lifecycle-Phasen
 * aufrufen, nie im Per-Tick-Pfad.
 */
public final class LinkedMailbox implements Mailbox {

    private final ConcurrentLinkedQueue<Envelope> queue = new ConcurrentLinkedQueue<>();
    private final Wakeup wakeup = new Wakeup();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong rejectedCount = new AtomicLong();

    public Wakeup wakeup() {
        return wakeup;
    }

    @Override
    public boolean offer(Envelope envelope) {
        if (closed.get()) {
            rejectedCount.incrementAndGet();
            return false;
        }
        queue.add(envelope);
        wakeup.signal();
        return true;
    }

    @Override
    public Envelope poll() {
        return queue.poll();
    }

    @Override
    public Envelope take() {
        for (;;) {
            Envelope envelope = queue.poll();
            if (envelope != null) {
                return envelope;
            }
            if (queue.isEmpty() && closed.get()) {
                return null;
            }
            if (!wakeup.await(() -> !queue.isEmpty() || closed.get()) && queue.isEmpty()) {
                return null;
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * {@code ConcurrentLinkedQueue.size()} ist O(n) und ohne Sperre nur eine
     * Schaetzung — genau das, was ein Lag-Signal fuer Lifecycle-Phasen braucht.
     * Im Per-Tick-Pfad nicht aufrufen (s. {@code implement.md} 6.4).
     */
    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
        wakeup.stop();
    }

    @Override
    public void kill() {
        queue.clear();
        close();
    }

    @Override
    public long rejected() {
        return rejectedCount.get();
    }

    @Override
    public long blocks() {
        return 0L;
    }
}
