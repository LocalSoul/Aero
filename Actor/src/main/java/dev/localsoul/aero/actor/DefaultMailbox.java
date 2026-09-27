package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;
import dev.localsoul.aero.actor.internal.MpscQueue;
import dev.localsoul.aero.actor.internal.Wakeup;

import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standard-Implementierung: lock-freie MPSC-Queue (siehe {@link MpscQueue}) plus
 * Overflow-Policy plus Weck-Mechanismus.
 *
 * <p>Der Selbstsendeschutz ist die Besonderheit: {@code ctx.self().tell(...)} auf
 * eine volle {@code BLOCK}-Mailbox wuerde der einzige Consumer auf sich selbst
 * warten — ein Hanger ohne Stacktrace. Stattdessen {@link IllegalStateException}
 * mit Loesungsvorschlag.
 */
public final class DefaultMailbox implements Mailbox {

    /** Wie oft ein blockierender Producer prueft, bevor er weiter schlaft. */
    private static final int BLOCK_SPINS = 64;

    private final MpscQueue<Envelope> queue;
    private final MailboxOverflow overflow;
    private final ActorRef owner;                 // null = anonymer/unbekannter Besitzer
    private final Wakeup wakeup = new Wakeup();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong blocks = new AtomicLong();

    public DefaultMailbox(int capacity, MailboxOverflow overflow, ActorRef owner) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, was " + capacity);
        }
        this.queue = new MpscQueue<>(capacity);
        this.overflow = overflow;
        this.owner = owner;
    }

    public Wakeup wakeup() {
        return wakeup;
    }

    @Override
    public boolean offer(Envelope envelope) {
        if (overflow == MailboxOverflow.BLOCK) {
            return offerBlocking(envelope);
        }
        if (queue.offer(envelope)) {
            wakeup.signal();
            return true;
        }
        // FAIL: zurueckweisen. DROP_NEWEST: neue Nachricht verwerfen.
        // Beide hinterlaesst die bereits wartenden Nachrichten unangetastet.
        rejected.incrementAndGet();
        return false;
    }

    private boolean offerBlocking(Envelope envelope) {
        ActorRef sender = envelope.sender();
        if (sender != null && sender == owner) {
            throw new IllegalStateException(
                    "self-send to a full BLOCKing mailbox would deadlock: " + owner.name()
                            + " cannot wait for itself. Use ctx.stop(), ctx.become(...),"
                            + " MailboxOverflow.FAIL or a CoalescingMailbox.");
        }
        blocks.incrementAndGet();
        int spins = 0;
        for (;;) {
            if (queue.isClosed()) {
                rejected.incrementAndGet();            // geschlossen: nicht angenommen
                return false;
            }
            if (queue.offer(envelope)) {
                wakeup.signal();
                return true;
            }
            if (++spins > BLOCK_SPINS) {
                spins = 0;
                LockSupport.parkNanos(1_000L);
            } else {
                Thread.onSpinWait();
            }
        }
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
            if (queue.isEmpty() && (queue.isClosed() || killed)) {
                return null;                       // verlustfrei: nichts mehr da
            }
            if (!wakeup.await(this::hasWork) && queue.isEmpty()) {
                return null;
            }
        }
    }

    private boolean hasWork() {
        return !queue.isEmpty() || killed;
    }

    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public int capacity() {
        return queue.capacity();
    }

    @Override
    public boolean isClosed() {
        return queue.isClosed();
    }

    @Override
    public void close() {
        queue.close();
        wakeup.stop();                             // Consumer darf aus der Schleife
    }

    private volatile boolean killed;

    @Override
    public void kill() {
        killed = true;
        queue.clear();
        wakeup.stop();
    }

    @Override
    public long rejected() {
        return rejected.get();
    }

    @Override
    public long blocks() {
        return blocks.get();
    }

    @Override
    public String toString() {
        return "DefaultMailbox[" + queue + ", " + overflow + ']';
    }
}
