package dev.localsoul.aero.actor;

/**
 * Marker fuer Actors, die den Tod gelinkter Actors selbst behandeln, statt
 * daran zu sterben — die Trap-Exit-Property von BEAM. Wird von
 * {@link dev.localsoul.aero.actor.supervision.Supervisor} implementiert.
 *
 * <p>Das Interface liegt bewusst im Kern und nicht im Paket
 * {@code supervision}: so muss {@code internal.ActorCell} keine Abhaengigkeit
 * zur Supervisions-Schicht aufbauen (Schichtregel 1 in {@code implement.md}).
 *
 * <p>Aufruf erfolgt ausschliesslich fuer <b>abnormale</b> Exit-Reason
 * (Failure, Terminated) sowie fuer alle Reason bei trapping-Verknuepfung —
 * die Filterung passiert am sendenden Ende.
 */
public interface ChildOwner {

    /**
     * Ein gelinkter Actor ist gestorben.
     *
     * @param child  der verstorbene Actor
     * @param reason Grund, nie {@link ExitReason#normal()} bei non-trapping
     * @return {@link Behavior#NEXT} (Kind bleibt entfernt) oder {@link Behavior#HALT}
     */
    Behavior onChildExit(ActorRef child, ExitReason reason);
}
