package dev.localsoul.aero.actor.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Lock-freie, feste Kapazitaet, <b>ein</b> Consumer und beliebig viele
 * Producer (Vyukov-Style MPSC auf einem Array).
 *
 * <p>Warum {@code AtomicReferenceArray} statt eines normalen {@code Object[]}:
 * nur damit liefern {@code lazySet}/{@code get} Release-/Acquire-Semantik und
 * ein Producer-Write ist fuer den Consumer vollstaendig sichtbar, sobald der
 * Index sichtbar ist.
 *
 * <p>Indexarithmetik mit {@code int}: die Differenz {@code p - c} ist modulo
 * 2^32 korrekt, solange sie die Kapazitaet nicht ueberschreitet, und
 * {@code c & mask} liefert bei negativem {@code c} den richtigen Slot. Ein
 * Ueberlauf der Indizes ist damit unschaedlich (tritt nach 2^31 Nachrichten
 * auf, also nach Stunden bei Millionen msg/s).
 *
 * <p><b>Vertrag:</b> {@link #poll()} ist verlustbehaftet und darf {@code null}
 * liefern, obwohl Elemente angenommen wurden (Producer haengt zwischen CAS und
 * Store). {@link #isEmpty()} ist dagegen exakt: es liest direkt die Indizes, und
 * ein reservierter Slot hat den Producer-Index bereits erhoeht. Genau darauf
 * stuetzt sich die Zusage von {@code Mailbox.take()}.
 */
public final class MpscQueue<E> {

    /** Wie oft {@link #poll()} bei reserviertem, aber unpubliziertem Slot spinnt. */
    private static final int MAX_SPINS = 64;

    private final AtomicReferenceArray<E> buffer;
    private final int mask;
    private final int capacity;
    private final AtomicInteger producerIndex = new AtomicInteger();
    private final AtomicInteger consumerIndex = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MpscQueue(int requestedCapacity) {
        this.capacity = roundUpToPowerOfTwo(requestedCapacity);
        this.mask = capacity - 1;
        this.buffer = new AtomicReferenceArray<>(capacity);
    }

    public static int roundUpToPowerOfTwo(int x) {
        if (x < 2) {
            return 2;
        }
        if (x >= (1 << 30)) {
            throw new IllegalArgumentException("capacity too large: " + x);
        }
        return Integer.highestOneBit(x - 1) << 1;
    }

    public int capacity() {
        return capacity;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void close() {
        closed.set(true);
    }

    /**
     * Endgueltig verwerfen: alle Slots leeren und beide Indizes zuruecksetzen.
     * Nur fuer {@code kill()} — danach wird die Queue nicht mehr benutzt.
     */
    public void clear() {
        for (int i = 0; i < capacity; i++) {
            buffer.lazySet(i, null);
        }
        int index = consumerIndex.get();
        producerIndex.lazySet(index);
        closed.set(true);
    }

    /** Exakt: {@code true}, wenn keine angenommene Nachricht mehr wartet. */
    public boolean isEmpty() {
        return producerIndex.get() == consumerIndex.get();
    }

    /** Anzahl angenommener, noch nicht konsumierter Elemente (obere Schaetzung). */
    public int size() {
        return producerIndex.get() - consumerIndex.get();
    }

    public boolean isFull() {
        return size() >= capacity;
    }

    /**
     * @return false, wenn voll oder geschlossen. Blockiert nie, wirft nie.
     */
    public boolean offer(E element) {
        for (;;) {
            if (closed.get()) {
                return false;
            }
            int p = producerIndex.get();
            int c = consumerIndex.get();
            if (p - c >= capacity) {
                return false;                       // voll
            }
            if (producerIndex.compareAndSet(p, p + 1)) {
                buffer.lazySet(p & mask, element);
                return true;
            }
        }
    }

    /**
     * Verlustbehaftetes Pollen: {@code null} heisst „gerade nichts verfuegbar",
     * <b>nicht</b> zwingend „leer".
     */
    public E poll() {
        int spins = 0;
        for (;;) {
            int c = consumerIndex.get();
            int p = producerIndex.get();
            if (p - c <= 0) {
                return null;
            }
            int i = c & mask;
            E element = buffer.get(i);
            if (element != null) {
                buffer.lazySet(i, null);
                consumerIndex.lazySet(c + 1);
                return element;
            }
            if (++spins > MAX_SPINS) {
                return null;                        // Producer haengt im Publizieren
            }
            Thread.onSpinWait();
        }
    }

    @Override
    public String toString() {
        return "MpscQueue[size=" + size() + '/' + capacity + (closed.get() ? ",closed" : "") + ']';
    }
}
