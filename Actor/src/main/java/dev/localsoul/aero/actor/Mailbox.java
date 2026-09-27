package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;

/**
 * Postfach eines Actors. Genau <b>ein</b> Consumer (der Virtual Thread des
 * Actors), beliebig viele Producer.
 *
 * <p>Zusicherungen:
 *
 * <ul>
 *   <li>{@link #offer} wirft <b>nie</b> — auch nicht bei vollem Postfach.
 *       {@code false} heisst „nicht angenommen".</li>
 *   <li>{@link #isEmpty()} ist exakt und indexbasiert.</li>
 *   <li>{@link #take()} liefert {@code null} <b>nur</b>, wenn
 *       {@link #isClosed()} <b>und</b> das Postfach leer ist. Es ist
 *       verlustfrei: wenn ein Element angenommen wurde, liefert `take()` es.</li>
 * </ul>
 */
public interface Mailbox {

    /**
     * Nicht blockierend anbieten. Blockiert <b>nur</b> bei
     * {@link MailboxOverflow#BLOCK}, dort aber mit Selbstsendeschutz.
     *
     * @return true, wenn angenommen
     */
    boolean offer(Envelope envelope);

    /**
     * Verlustbehaftet: {@code null} heisst „jetzt nichts verfuegbar", nicht
     * zwingend „leer". Mit {@link #isEmpty()} als Abbruchbedingung verwenden.
     */
    Envelope poll();

    /**
     * Verlustfrei. Blockiert, bis ein Element da ist.
     *
     * @return {@code null}, wenn {@code closed && leer} — oder wenn der
     *         wartende Thread unterbrochen wurde (dann ist Abbruch richtiger als
     *         weiterer Busy-Loop; der Actor-Thread wird nie unterbrochen)
     */
    Envelope take();

    /** Exakt: {@code true}, wenn keine angenommene Nachricht mehr wartet. */
    boolean isEmpty();

    /** Anzahl wartender Nachrichten (fuer Lag-Erkennung, {@code tickActor.onLagged}). */
    int size();

    /** Maximale Kapazitaet. */
    int capacity();

    boolean isClosed();

    /**
     * Geordneter Stopp: {@link #take()} liefert ab jetzt {@code null}, sobald das
     * Postfach leer ist (Drain). Nachrichten nach {@code close} werden
     * abgelehnt.
     */
    void close();

    /** Hartes Ende: Postfach verwerfen, {@link #take()} liefert sofort {@code null}. */
    void kill();

    /** Anzahl abgelehnter Nachrichten (Overflow/geschlossen) — fuer Metriken. */
    long rejected();

    /** Anzahl blockierender Annahmen (nur bei {@code BLOCK}) — Metrik fuer Wartezeit. */
    long blocks();
}
