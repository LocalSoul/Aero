package dev.localsoul.aero.actor.internal;

/** Kanal, auf dem eine Nachricht transportiert wird. */
public enum MessageKind {
    /** Vom Benutzercode. Geht durch {@code Behavior.invoke} und {@code Receive}. */
    USER,
    /** Von der Laufzeit. Erreicht nie eine Benutzerklausel. */
    SYSTEM
}
