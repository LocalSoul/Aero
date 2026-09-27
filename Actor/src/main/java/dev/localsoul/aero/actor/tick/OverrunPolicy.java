package dev.localsoul.aero.actor.tick;

/**
 * Was ein Fixed-Timestep-Treiber mit verpassten Deadlines tut.
 *
 * <p>Der Driver hat zwei getrennte Sorgen: <b>wie</b> der Zeitplan nach einem
 * Aussetzer weiterlaeuft (das ist die Policy) und <b>wie viele</b> Ticks
 * auf einmal nachgeholt werden duerfen (das ist der Deckel, unabhaengig von der
 * Policy). {@link #catchesUp()} sagt nur, ob die Policy ueberhaupt aufholt.
 */
public enum OverrunPolicy {

    /**
     * Default: nach einem Aussetzer wird die verpasste Zeit <b>verworfen</b>, der
     * naechste Tick kommt wieder im normalen Takt. Fuer Game-Logik meist die
     * richtige Wahl — der Client interpoliert, und die Simulation bekommt keine
     * Sekunde Nachhol-Zeit in einem Frame.
     */
    CLAMP,

    /**
     * Aufholen: der Zeitplan wird nachgezogen, verpasste Ticks werden
     * nachgeliefert — bis zum Deckel. Ohne Deckel erzeugt eine 2-Sekunden-Pause
     * 120 Ticks, die alle sofort laufen muessen (Death Spiral).
     */
    STEP,

    /**
     * Alles verwerfen: nach einem Aussetzer faengt der Driver beim ersten
     * Deadline-Punkt wieder an. Der Unterschied zu {@link #CLAMP} ist die
     * Phase: CLAMP behaelt den Takt, DROP beginnt neu zu takten.
     */
    DROP;

    /** Holt diese Policy verpasste Zeit auf? */
    public boolean catchesUp() {
        return this == STEP;
    }
}
