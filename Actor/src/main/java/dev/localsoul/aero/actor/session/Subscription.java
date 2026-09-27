package dev.localsoul.aero.actor.session;

import dev.localsoul.aero.actor.ActorRef;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pub/Sub-Ziel eines Clients: hält die {@link ActorRef}, an die gesendet wird,
 * und das <b>aktuelle</b> {@link Interest}.
 *
 * <p>Zwei Anwendungen:
 * <ul>
 *   <li>{@code TickDriver.subscribe(raum)} — der Raum will jeden Tick eine
 *       Nachricht, ohne Interessen.</li>
 *   <li>{@code InterestSet.put(ref, interest)} — der Client will Sichtkontakt zu
 *       einem Volumen.</li>
 * </ul>
 *
 * <p>Beide benoetigen dasselbe: eine Abmeldung, die den Eintrag aus dem
 * Eigentuemer-Register entfernt. Das ist {@link #cancel()}; der Rueckweg zum
 * Eigentuemer ist der Callback, den der Eigentuemer bei der Erzeugung
 * uebergibt.
 *
 * <p>Unveraenderlich in der Identitaet ({@link #target()}), veraenderlich im
 * Interesse — so wandert ein Client, ohne dass sich die Abmeldung aendert.
 */
public final class Subscription {

    private final ActorRef target;
    private final Runnable onCancel;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Interest interest;

    private Subscription(ActorRef target, Interest interest, Runnable onCancel) {
        this.target = Objects.requireNonNull(target, "target");
        this.interest = interest == null ? Interest.UNBOUNDED : interest;
        this.onCancel = onCancel == null ? () -> { } : onCancel;
    }

    /** Ohne Interessenbindung (z. B. Tick-Abonnement). */
    public static Subscription of(ActorRef target, Runnable onCancel) {
        return new Subscription(target, Interest.UNBOUNDED, onCancel);
    }

    /** Mit Interessenbindung. */
    public static Subscription of(ActorRef target, Interest interest, Runnable onCancel) {
        return new Subscription(target, interest, onCancel);
    }

    public ActorRef target() {
        return target;
    }

    public Interest interest() {
        return interest;
    }

    /** Neues Interesse setzen (z. B. weil der Spieler weiterlief). */
    public Subscription withInterest(Interest newInterest) {
        this.interest = newInterest == null ? Interest.UNBOUNDED : newInterest;
        return this;
    }

    /**
     * Abmelden. Idempotent und threadsicher: der Rueckruf laeuft hoechstens
     * einmal, auch wenn Actor-Thread und Raum-Thread gleichzeitig abmelden.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            onCancel.run();
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public boolean isActive() {
        return !cancelled.get();
    }

    @Override
    public String toString() {
        return "Subscription[" + target + " " + interest + (isCancelled() ? " cancelled]" : "]");
    }
}
