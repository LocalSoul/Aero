package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorRef;

/**
 * Absender-Aufloesung fuer {@code tell}. Da jeder Actor <b>exakt einen</b>
 * Virtual Thread besitzt, ist ein ThreadLocal hier korrekt und synchronisationsfrei.
 */
public final class CurrentActor {

    private static final ThreadLocal<ActorRef> CURRENT = new ThreadLocal<>();

    private CurrentActor() {
    }

    public static void set(ActorRef ref) {
        CURRENT.set(ref);
    }

    /** @return der laufende Actor oder {@code null} (Hauptthread, Pool-Thread) */
    public static ActorRef get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
