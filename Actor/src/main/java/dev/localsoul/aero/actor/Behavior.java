package dev.localsoul.aero.actor;

/**
 * Verhaltensbeschreibung eines Actors: die Antwort auf „was passiert als
 * nächstes?". Abbildung des BEAM-Prozesszustands — `become/1` ersetzt das
 * Behavior, der Rückgabewert eines `receive` entscheidet über den Zustandswechsel.
 *
 * <p>Drei Sentinels steuern den Kontrollfluss, ohne dass eine Implementierung
 * von ihnen wissen muss:
 *
 * <ul>
 *   <li>{@link #NEXT} — aktuelles Behavior beibehalten (BEAM {@code noreply})</li>
 *   <li>{@link #UNHANDLED} — keine Klausel passte, nächste Stufe versuchen</li>
 *   <li>{@link #HALT} — Actor mit {@link ExitReason#normal()} beenden</li>
 * </ul>
 */
@FunctionalInterface
public interface Behavior {

    /** Sentinel: aktuelles Behavior beibehalten. Entspricht BEAM {@code noreply}. */
    Behavior NEXT = new Sentinel("NEXT");

    /** Sentinel: keine Klausel passte. Die nächste Behavior-Stufe wird versucht. */
    Behavior UNHANDLED = new Sentinel("UNHANDLED");

    /** Sentinel: Actor ordnungsgemäß beenden ({@link ExitReason#normal()}). */
    Behavior HALT = new Sentinel("HALT");

    /**
     * Nachricht verarbeiten.
     *
     * @param message die Nachricht (bei {@code USER}-Kanal) bzw. das System-Signal
     * @param ctx     Kontext des laufenden Actors
     * @return {@link #NEXT}, {@link #HALT}, {@link #UNHANDLED} oder ein neues Behavior
     */
    Behavior invoke(Object message, ActorContext ctx);

    /** Identitätsvergleichbare Sentinels. */
    record Sentinel(String name) implements Behavior {
        @Override public Behavior invoke(final Object message, final ActorContext ctx) { return this; }
        @Override public String toString() { return name; }
    }
}
