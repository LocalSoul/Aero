package dev.localsoul.aero.game.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Begrenzt aktive Verbindungen pro IP. **Das Dekrement muss auf jedem
 * Exit-Pfad laufen** (channelInactive, exceptionCaught, abgelehnter Connect,
 * Kicks) — ein vergessener Pfad leckt den Zähler und sperrt IPs fälschlich.
 */
public final class ConnectionLimiter {

    private final int maxPerIp;
    private final Map<String, AtomicInteger> active = new ConcurrentHashMap<>();

    public ConnectionLimiter(final int maxPerIp) {
        this.maxPerIp = maxPerIp;
    }

    /** Registriert eine Verbindung; {@code false} = Limit überschritten. */
    public boolean acquire(final String ip) {
        final AtomicInteger counter = active.computeIfAbsent(ip, k -> new AtomicInteger());
        for (;;) {
            final int current = counter.get();
            if (current >= maxPerIp) {
                return false;
            }
            if (counter.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Bei jedem Exit-Pfad aufrufen. */
    public void release(final String ip) {
        final AtomicInteger counter = active.get(ip);
        if (counter != null && counter.decrementAndGet() <= 0) {
            active.remove(ip, counter);
        }
    }

    public int activeCount() {
        int sum = 0;
        for (final AtomicInteger counter : active.values()) {
            sum += counter.get();
        }
        return sum;
    }
}