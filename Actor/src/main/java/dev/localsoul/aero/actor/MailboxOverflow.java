package dev.localsoul.aero.actor;

/**
 * Verhalten im Ueberlauf-Fall. Entscheidet, was mit einer Nachricht passiert,
 * wenn das Postfach voll ist.
 *
 * <table>
 *   <caption>Strategien</caption>
 *   <tr><th>Strategie</th><th>Verhalten</th><th>Typische Verwendung</th></tr>
 *   <tr><td>{@link #FAIL}</td><td>{@code offer} → {@code false}, {@code tryTell} → {@code false},
 *       {@code ask} → {@link MailboxFullException}</td><td>Default. Der Absender
 *       entscheidet (Spieler kicken)</td></tr>
 *   <tr><td>{@link #BLOCK}</td><td>Producer wartet, bis Platz ist. Self-Send → {@link IllegalStateException}</td>
 *       <td>Streng serieller Actor, Absender wartet nie auf ihn</td></tr>
 *   <tr><td>{@link #DROP_NEWEST}</td><td>Neue Nachricht wird verworfen, alte behalten</td>
 *       <td>Idempotente Snapshots („Raum ist jetzt bei Position X")</td></tr>
 * </table>
 *
 * <p>{@link #DROP_OLDEST} gibt es bewusst nicht: es erfordert, das Kopfelement
 * der Queue atomar zu entfernen, und damit einen zweiten CAS auf denselben
 * Consumer-Index, den der laufende Actor benutzt. Fuer Frequenzupdates ist
 * {@link CoalescingMailbox} das richtige Werkzeug (Latest-Wins je Schluessel).
 */
public enum MailboxOverflow {

    /** Anbieten scheitert sofort. Default. */
    FAIL,

    /** Producer blockiert, bis ein Slot frei wird. Nur wo zyklisches Warten ausgeschlossen ist. */
    BLOCK,

    /** Neue Nachricht verwerfen, bestehende behalten. */
    DROP_NEWEST;

    public boolean blocking() {
        return this == BLOCK;
    }
}
