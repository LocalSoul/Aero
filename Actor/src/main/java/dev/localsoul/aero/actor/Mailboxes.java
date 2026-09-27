package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.MpscQueue;

/** Fabrik fuer die drei Postfach-Implementierungen. */
public final class Mailboxes {

    /** Game-Default: 1024 Plaetze, {@link MailboxOverflow#FAIL}. */
    public static final int DEFAULT_CAPACITY = 1024;
    public static final MailboxOverflow DEFAULT_OVERFLOW = MailboxOverflow.FAIL;

    private Mailboxes() {
    }

    /** Unbegrenzt — nur fuer kontrollierte Lifecycle-Phasen (s. {@link LinkedMailbox}). */
    public static Mailbox unbounded() {
        return new LinkedMailbox();
    }

    /**
     * Bounded mit Policy. Die Kapazitaet wird auf die naechste Zweierpotenz
     * aufgerundet, damit {@code mask} statt {@code modulo} reicht.
     */
    public static Mailbox bounded(int capacity, MailboxOverflow overflow, ActorRef owner) {
        return new DefaultMailbox(capacity, overflow, owner);
    }

    public static Mailbox bounded(int capacity) {
        return bounded(capacity, DEFAULT_OVERFLOW, null);
    }

    /** Latest-Wins je Schluessel — fuer Input, Position, Velocity, Health-Bar. */
    public static Mailbox coalescing(int capacity) {
        return new CoalescingMailbox(capacity);
    }

    /** Wirft, wenn die Kapazitaet zu klein ist. */
    public static void requireCapacity(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("mailbox capacity must be >= 2, was " + capacity);
        }
        if (MpscQueue.roundUpToPowerOfTwo(capacity) > (1 << 20)) {
            throw new IllegalArgumentException("mailbox capacity unreasonably large: " + capacity);
        }
    }
}
