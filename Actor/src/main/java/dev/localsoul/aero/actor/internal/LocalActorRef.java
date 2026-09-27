package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorPath;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.AskTimeoutException;
import dev.localsoul.aero.actor.MailboxFullException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nachrichtenkanal zu einer {@link ActorCell} im selben JVM.
 *
 * <p>Der Absender kommt aus {@link CurrentActor} und damit in der Regel ohne
 * Thread-Lookup: der Actor-Thread setzt das einmal, und {@code sender()} liest
 * es direkt. Nur {@code tell} braucht diesen einen {@code ThreadLocal.get()}.
 */
public final class LocalActorRef implements ActorRef {

    private final ActorCell cell;
    private final ActorPath path;

    LocalActorRef(ActorCell cell, ActorPath path) {
        this.cell = cell;
        this.path = path;
    }

    public ActorCell cell() {
        return cell;
    }

    @Override
    public void tell(Object message) {
        tell(message, CurrentActor.get());
    }

    @Override
    public void tell(Object message, ActorRef sender) {
        cell.postUser(Envelope.user(message, sender));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Der einzige Ort, an dem ein volles Postfach sichtbar wird — und der
     * einzige, an dem {@link MailboxFullException} fliegen darf. {@link #tell}
     * ruft das nicht auf: dort waere die Ausnahme nur eine Umleitung.
     */
    @Override
    public boolean tryTell(Object message) {
        return cell.tryPostUser(Envelope.user(message, CurrentActor.get()));
    }

    @Override
    public CompletableFuture<Object> ask(Object message, Duration timeout) {
        return ask(message, CurrentActor.get(), timeout);
    }

    /**
     * Legt die {@code Call}-Struktur an, stellt die Anfrage zu und stellt einen
     * Timeout. Der Timer laeuft auf einem Platform Thread des Systems; die
     * <em>Antwort</em> laeuft ueber {@link Call#settle} im Thread des Aufrufer-
     * Actors.
     */
    @Override
    public CompletableFuture<Object> ask(Object message, ActorRef sender, Duration timeout) {
        ActorCell caller = sender == null ? null : ActorCell.cellOf(sender);
        Call call = caller == null ? Call.request(null) : caller.newRequestCall();
        if (!cell.tryPostUser(Envelope.call(message, sender, call))) {
            MailboxFullException error = new MailboxFullException(this, mailboxCapacity());
            call.settle(null, error);
            throw error;                                  // Aufrufer lebt weiter
        }
        ScheduledExecutorService timer = cell.system().scheduler();
        timer.schedule(() -> call.settle(null, AskTimeoutException.after(this, timeout)),
                timeout.toMillis(), TimeUnit.MILLISECONDS);
        return call.promise();
    }

    @Override
    public boolean isAlive() {
        return cell.isRunning();
    }

    @Override
    public String name() {
        return path.name();
    }

    @Override
    public ActorPath path() {
        return path;
    }

    @Override
    public boolean anonymous() {
        return cell.anonymous();
    }

    @Override
    public String toString() {
        return path.toString();
    }

    private int mailboxCapacity() {
        return cell.mailboxCapacity();
    }
}
