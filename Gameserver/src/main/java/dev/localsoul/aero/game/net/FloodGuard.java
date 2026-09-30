package dev.localsoul.aero.game.net;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Begrenzt Pakete/s pro Verbindung (verhindert {@code Move}-Spam in die
 * Realm-Mailbox). Einfaches Fenster: erlaubt bis {@code maxPerSecond}
 * Pakete pro Sekunde; darüber {@link #isFlooding()}.
 */
public final class FloodGuard {

    private final int maxPerSecond;
    private final AtomicLong windowStart = new AtomicLong(System.nanoTime());
    private final AtomicInteger count = new AtomicInteger();

    public FloodGuard(final int maxPerSecond) {
        this.maxPerSecond = maxPerSecond;
    }

    /** Zählt ein Paket; {@code true} = Flood (Limit überschritten). */
    public boolean allow() {
        final long now = System.nanoTime();
        final long start = windowStart.get();
        if (now - start > 1_000_000_000L) {
            windowStart.compareAndSet(start, now);
            count.set(0);
        }
        return count.incrementAndGet() <= maxPerSecond;
    }
}