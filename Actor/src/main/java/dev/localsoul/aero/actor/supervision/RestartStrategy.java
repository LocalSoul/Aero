package dev.localsoul.aero.actor.supervision;

/**
 * Wie viele Kinder ein Supervisor mit einem Fehler beauftragt (BEAM-Strategie).
 *
 * <p>Der Supervisor selbst stirbt in diesen Faellen <b>nicht</b> — er ist ja
 * genau dafuer da. Erst die Eskalation (zu viele Neustarts, s.
 * {@link SupervisorSpec#maxRestarts(int, java.time.Duration)}) beendet ihn.
 */
public enum RestartStrategy {

    /** Nur das fehlgeschlagene Kind neu starten. Der Normalfall. */
    ONE_FOR_ONE,

    /**
     * Alle Kinder neu starten. fuer {@code simple_one_for_one}-Aequivalente:
     * wenn ein gemeinsamer Fehler alle betrifft (Datenbank weg, Konfiguration
     * kaputt), ist Neustart billiger als Fehlersuche.
     */
    ONE_FOR_ALL,

    /**
     * Das fehlgeschlagene Kind und alle danach gestarteten neu starten. fuer
     * geordnete Abhaengigkeiten: haengt ein Raum am Authentifizierungs-Actor,
     * sind die danach gestarteten Raeume sinnlos.
     */
    REST_FOR_ONE;

    public boolean affectsOthers() {
        return this != ONE_FOR_ONE;
    }
}
