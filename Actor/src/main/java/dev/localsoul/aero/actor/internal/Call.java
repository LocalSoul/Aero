package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorTerminatedException;
import dev.localsoul.aero.actor.ExitReason;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Ein laufender {@code ask} oder ein {@code runBlocking}-Auftrag.
 *
 * <p>Zweistufig, damit das Ergebnis im Thread des <b>Aufrufer</b>-Actors
 * ankommt:
 *
 * <ol>
 *   <li>{@link #settle} — beliebiger Thread (Antwortender, Timeout-Timer,
 *       Blocking-Pool). Gewinnt der Aufrufer das Rennen, wird der Abschluss in
 *       die Mailbox des Aufrufer-Actors gelegt. Ist der Aufrufer schon tot,
 *       wird sofort ausgewertet — sonst verliert er das Ergebnis still.</li>
 *   <li>{@link #complete} — Aufrufer-Thread: Future vervollstaendigen und
 *       {@code onResult} aufrufen.</li>
 * </ol>
 *
 * <p>So kann ein Timeout nicht mit einer spaeten Antwort konkurrieren, und ein
 * {@code thenAccept} eines Aufrufer-Actors laeuft in dessen eigenem Thread.
 */
public final class Call {

    private final ActorCell owner;                                 // null = Aufrufer ist kein Actor
    private final CompletableFuture<Object> promise;                // null = nur onResult
    private final BiConsumer<Object, Throwable> onResult;           // null bei ask
    private final AtomicBoolean settled = new AtomicBoolean();
    private final long createdAtNanos = System.nanoTime();

    private Call(ActorCell owner, CompletableFuture<Object> promise,
                 BiConsumer<Object, Throwable> onResult) {
        this.owner = owner;
        this.promise = promise;
        this.onResult = onResult;
    }

    /** Fuellt das Postfach: {@code ask} und {@code reply}. */
    public static Call request(ActorCell owner) {
        return new Call(owner, new CompletableFuture<>(), null);
    }

    /** Fuellt das Postfach: {@code runBlocking} ohne eigene Future. */
    public static Call task(ActorCell owner, BiConsumer<Object, Throwable> onResult) {
        return new Call(owner, null, onResult);
    }

    public CompletableFuture<Object> promise() {
        return promise;
    }

    public long ageNanos() {
        return System.nanoTime() - createdAtNanos;
    }

    /**
     * Phase 1: Ergebnis anbieten. Nur der erste Aufrufer gewinnt; alle anderen
     * sind folgenlos.
     *
     * @return true, wenn dieser Aufrufer den Abschluss uebernommen hat
     */
    public boolean settle(Object value, Throwable error) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        if (owner != null && !owner.isFinished()) {
            owner.forgetPendingCall(this);
            owner.postSystem(new Signals.CallResult(this, value, error));
        } else {
            complete(value, error);                     // Aufrufer weg: direkt auswerten
        }
        return true;
    }

    /** Phase 2: im Thread des Aufrufer-Actors. */
    public void complete(Object value, Throwable error) {
        if (promise != null) {
            if (error != null) {
                promise.completeExceptionally(error);
            } else {
                promise.complete(value);
            }
        }
        if (onResult != null) {
            onResult.accept(value, error);
        }
    }

    /** Scheitert den Auftrag, wenn der Aufrufer-Actors stirbt. */
    public void abort(ExitReason reason) {
        if (settled.compareAndSet(false, true)) {
            ActorRef target = owner == null ? null : owner.self();
            complete(null, new ActorTerminatedException(
                    "actor died before the answer arrived: " + (target == null ? "?" : target.name()),
                    reason));
        }
    }
}
