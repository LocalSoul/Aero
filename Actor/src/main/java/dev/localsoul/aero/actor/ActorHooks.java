package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.ActorCell;

/**
 * Interne Bruecke zwischen der Basisklasse und der Laufzeit.
 *
 * <p>{@link Actor} bietet die Lebenszyklus-Hooks {@code protected} an, weil sie
 * Teil des Benutzercodes sind und nicht Teil einer oeffentlichen Schnittstelle.
 * {@code internal.ActorCell} ist aber weder Subklasse noch im selben Paket und
 * koennte sie deshalb nicht aufrufen. Diese Klasse ist der einzige Weg
 * darum herum — und zugleich die einzige Stelle, an der die Basisklasse
 * angefasst wird.
 *
 * <p>Nicht zur direkten Benutzung vorgesehen.
 */
public final class ActorHooks {

    private ActorHooks() {
    }

    public static void attach(Actor actor, ActorCell cell) {
        actor.attach(cell);
    }

    public static Behavior start(Actor actor, ActorContext ctx) {
        return actor.onStart(ctx);
    }

    /**
     * Bruecke fuer {@link Actor#deferDuringDrain(Object)}: die Zell liegt in einem
     * anderen Paket und kann den protected Hook nicht selbst aufrufen.
     */
    public static boolean deferDuringDrain(Actor actor, Object message) {
        return actor.deferDuringDrain(message);
    }

    public static Behavior unhandled(Actor actor, ActorContext ctx, Object message) {
        return actor.onUnhandled(ctx, message);
    }

    public static void stop(Actor actor, ActorContext ctx, ExitReason reason) {
        actor.onStop(ctx, reason);
    }

    public static void postStop(Actor actor, ActorContext ctx, ExitReason reason) {
        actor.onPostStop(ctx, reason);
    }
}
