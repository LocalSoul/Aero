package dev.localsoul.aero.actor.supervision;

import dev.localsoul.aero.actor.ExitReason;

/**
 * Wann ein Kind ueberhaupt neu gestartet wird (BEAM {@code restart}).
 */
public enum RestartType {

    /**
     * Nie automatisch neu starten. fuer Einmal-Jobs und Einmal-Auswertungen:
     * nach dem einen Auftrag ist der Actor fuer immer erledigt.
     */
    TEMPORARY,

    /**
     * Nur bei abnormalem Tod neu starten. Der Normalfall: bei
     * {@link ExitReason#normal()} gilt der Auftrag als erledigt.
     */
    TRANSIENT,

    /**
     * Immer neu starten, auch bei {@code normal()}. fuer langlebige Dienste wie
     * einen Raum: wer ihn stoppt, will ihn gleich wieder haben.
     */
    PERMANENT;

    /** Entscheidet ueber einen konkreten Todesfall. */
    public boolean shouldRestart(ExitReason reason) {
        return switch (this) {
            case TEMPORARY -> false;
            case TRANSIENT -> reason.abnormal();
            case PERMANENT -> true;
        };
    }
}
