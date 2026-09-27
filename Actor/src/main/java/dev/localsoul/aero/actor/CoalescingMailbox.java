package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;
import dev.localsoul.aero.actor.internal.MpscQueue;
import dev.localsoul.aero.actor.internal.Wakeup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Postfach, das mehrfache Einreichungen desselben Schluessels verschmilzt
 * (Latest-Wins) — die Antwort auf {@code DROP_OLDEST} ohne dessen Kosten.
 *
 * <h2>Mechanik</h2>
 * Eine Map {@code pending: Key -> Envelope} plus eine FIFO. Kommt eine Nachricht
 * mit bereits belegtem Key <b>vom selben Absender</b>, ersetzt sie den Map-Eintrag
 * <b>ohne</b> ein neues Queue-Element anzulegen. Die aeltere Queue-Datei liegt
 * dann ins Leere; der Consumer erkennt das ueber einen CAS auf den Map-Eintrag
 * und ueberspringt sie. Ergebnis: hunderte eingereichte Inputs kosten <b>eine</b>
 * Queue-Datei und <b>eine</b> Handler-Ausfuehrung.
 *
 * <h2>Groessenzaehlung</h2>
 * {@code queue.size()} ist unbrauchbar: ein Key kann nach einem verlorenen CAS
 * zweimal in der FIFO liegen, und {@code occupied} waere damit wertlos als
 * Lag-Signal (falscher Alarm). Deshalb ein zweiter Zaehler, der <b>logische</b>
 * Nachrichten zaehlt: erhoeht bei Annahme, verringert bei Zustellung oder
 * Verwurf. Kosten ca. 5 ns pro coalescbarer Nachricht, dafuer ist
 * {@link #size()} exakt — dieselbe Zusicherung wie bei {@link Mailbox}.
 *
 * <p><b>Groesse der FIFO waehlen.</b> Sie begrenzt nicht die Zahl logischer
 * Nachrichten, sondern die Zahl <b>unverschmolzener Dateien</b>. Wenn die
 * Kapazitaet erreicht ist und ein Key frisch belegt werden soll, wird
 * abgelehnt. Bei 64 Key-Versionen und 1024 Plätzen ist „frisch" praktisch nie
 * der Fall — deshalb sind 1024 der Default und „O(1) Slots pro Client" die
 * Faustregel.
 */
public final class CoalescingMailbox implements Mailbox {

    /** Key: Absender-Identitaet verschmolzen mit dem Schluessel des Absenders. */
    private record SlotKey(Object sender, Object key) {
    }

    private final MpscQueue<Envelope> queue;
    private final Map<SlotKey, Envelope> pending = new ConcurrentHashMap<>();
    private final Function<Object, Object> keyExtractor;
    private final Wakeup wakeup = new Wakeup();
    private final AtomicLong occupied = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong rejectedCount = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public CoalescingMailbox(int capacity) {
        this(capacity, CoalescingMailbox::defaultKey);
    }

    public CoalescingMailbox(int capacity, Function<Object, Object> keyExtractor) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, was " + capacity);
        }
        this.queue = new MpscQueue<>(capacity);
        this.keyExtractor = keyExtractor;
    }

    /** {@link Coalescible} befragen; alles andere wird nicht verschmolzen. */
    private static Object defaultKey(Object message) {
        return message instanceof Coalescible c ? c.coalesceKey() : null;
    }

    public Wakeup wakeup() {
        return wakeup;
    }

    /** Wie oft viele Einreichungen auf einen Key zusammengefasst wurden. */
    public long coalescedCount() {
        return coalesced.get();
    }

    @Override
    public boolean offer(Envelope envelope) {
        if (closed.get()) {
            rejectedCount.incrementAndGet();
            return false;
        }
        Object key = envelope.isSystem() || envelope.isCall() ? null : keyExtractor.apply(envelope.message());
        if (key == null) {
            return enqueue(envelope);
        }

        SlotKey slot = new SlotKey(envelope.sender(), key);
        Envelope existing = pending.putIfAbsent(slot, envelope);
        if (existing == null) {
            if (enqueue(envelope)) {                        // erster Slot: Datei + Zaehler
                return true;
            }
            pending.remove(slot, envelope);
            return false;
        }
        if (existing.isCall() || existing.isSystem() || existing.sender() != envelope.sender()) {
            // Anderer Absender: nie verschmelzen, sonst waere die Reihenfolge
            // zweier Clients undefiniert.
            return enqueue(envelope);
        }
        if (pending.replace(slot, existing, envelope)) {
            coalesced.incrementAndGet();
            wakeup.signal();
            return true;                                    // verschmolzen, occupied bleibt
        }
        return reoffer(slot, envelope);                     // Consumer war schneller
    }

    /**
     * Der Slot wurde soeben vom Consumer geleert: ganz normal neu anlegen.
     */
    private boolean reoffer(SlotKey slot, Envelope envelope) {
        Envelope current = pending.putIfAbsent(slot, envelope);
        if (current == null) {
            return enqueue(envelope);
        }
        if (current == envelope || pending.replace(slot, current, envelope)) {
            coalesced.incrementAndGet();
            wakeup.signal();
            return true;
        }
        return enqueue(envelope);                           // Notnagel: eigenes Element
    }

    private boolean enqueue(Envelope envelope) {
        if (closed.get()) {
            rejectedCount.incrementAndGet();
            return false;
        }
        occupied.incrementAndGet();                        // vor dem Angebot, damit der
        if (queue.offer(envelope)) {                       // Zaehler nie negativ wird
            wakeup.signal();
            return true;
        }
        occupied.decrementAndGet();
        rejectedCount.incrementAndGet();
        return false;
    }

    /**
     * Die Queue-Datei ist nur ein <b>Weck-Token</b> fuer ihren Slot; die Map
     * ist die Wahrheit. Deshalb wird der aktuelle Map-Stand geliefert und nicht
     * der veraltete Inhalt der Datei — sonst ginge die neueste Nachricht
     * verloren, weil niemand mehr auf sie zeigt.
     *
     * <p>Datei ohne Map-Eintrag heisst: ihr Inhalt wurde bereits zugestellt.
     * Sie wird uebersprungen. Ein Postfach kann nicht mehr anzeigen, dass
     * jemand gestorben ist — deshalb ist das auch kein Fall, in dem der
     * veraltete Inhalt noch etwas waere.
     */
    @Override
    public Envelope poll() {
        for (;;) {
            Envelope file = queue.poll();
            if (file == null) {
                return null;
            }
            SlotKey slot = slotOf(file);
            if (slot == null) {
                occupied.decrementAndGet();
                return file;                           // nicht coalescbar
            }
            Envelope current = pending.remove(slot);
            if (current != null) {
                occupied.decrementAndGet();
                return current;                        // neuester Stand des Schluessels
            }
        }
    }

    private SlotKey slotOf(Envelope envelope) {
        if (envelope.isSystem() || envelope.isCall()) {
            return null;
        }
        Object key = keyExtractor.apply(envelope.message());
        return key == null ? null : new SlotKey(envelope.sender(), key);
    }

    @Override
    public Envelope take() {
        for (;;) {
            Envelope envelope = poll();
            if (envelope != null) {
                return envelope;
            }
            if (isEmpty() && closed.get()) {
                return null;
            }
            if (!wakeup.await(() -> !isEmpty() || closed.get()) && isEmpty()) {
                return null;
            }
        }
    }

    @Override
    public boolean isEmpty() {
        return occupied.get() == 0;
    }

    /** Exakt: zaehlt logische Nachrichten, nicht Queue-Dateien. */
    @Override
    public int size() {
        return (int) occupied.get();
    }

    @Override
    public int capacity() {
        return queue.capacity();
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
        closed.set(true);
        queue.clear();
        pending.clear();
        occupied.set(0);
        wakeup.stop();
    }

    @Override
    public long rejected() {
        return rejectedCount.get();
    }

    @Override
    public long blocks() {
        return 0L;
    }

    @Override
    public String toString() {
        return "CoalescingMailbox[logical=" + occupied + '/' + capacity() + ", slots=" + queue + ']';
    }
}
