package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorHooks;
import dev.localsoul.aero.actor.ActorHooks;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ChildOwner;
import dev.localsoul.aero.actor.DefaultMailbox;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.Mailbox;
import dev.localsoul.aero.actor.Mailboxes;
import dev.localsoul.aero.actor.Receive;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Laufzeit eines Actors: sein Virtual Thread, sein Postfach, sein Zustandsautomat.
 * Die Zelle <b>ist</b> der {@link ActorContext} — damit gibt es keine zweite
 * Objektidentitaet fuer „den laufenden Actor" und keine Delegation.
 *
 * <p>Alles, was nur <b>diesen</b> Actor betrifft, ist ein einfaches Feld: der
 * Thread bearbeitet Postfachnachrichten immer ueber denselben Thread, und
 * {@code tell} fasst nie den Behavior an. Nur was von aussen kommt
 * ({@code stop}, {@code kill}, {@code link}, {@code schedule}) braucht
 * Atomic-Felder.
 *
 * <p><b>Zustaende.</b> {@code RUNNING → STOPPING → STOPPED}. {@link #stop()}
 * wechselt nach {@code STOPPING} und legt ein geordnetes {@link Signals.Terminate}
 * ans Postfach-Ende, sodass alles davor noch verarbeitet wird (Drain);
 * {@link #kill()} verwirft sofort. Sobald der Zustand nicht mehr {@code RUNNING}
 * ist, werden <b>Benutzer</b>nachrichten abgewiesen; Systemnachrichten
 * (Antworten laufender {@code ask}s) kommen weiter durch, sonst verliesse ein
 * Actor waehrend des Drains seine eigenen Antworten.
 */
public final class ActorCell implements ActorContext, AutoCloseable {

    private static final int RUNNING = 0;
    private static final int STOPPING = 1;
    private static final int STOPPED = 2;

    // ------------------------------------------------- Zustand: nur Actor-Thread

    private final Actor actor;
    private final ActorSystem system;
    private final Mailbox mailbox;
    private final Wakeup wakeup;
    private final String registeredName;                  // null = anonym
    private final LocalActorRef self;

    private Behavior behavior;                            // nur Actor-Thread
    private Envelope current;                             // aktuelle Nachricht
    private ExitReason stopReason = ExitReason.normal();
    private boolean graceful;                            // Terminate gesehen -> drainen
    private Thread thread;
    private long messagesProcessed;
    private long crashCount;

    // ------------------------------------------- Von aussen: Atomic/Concurrent

    private final AtomicInteger state = new AtomicInteger(RUNNING);
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Set<Call> pendingCalls = ConcurrentHashMap.newKeySet();
    private final Set<ActorCell> links = ConcurrentHashMap.newKeySet();
    private final AtomicLong systemMessages = new AtomicLong();
    private final CompletableFuture<ExitReason> deathFuture = new CompletableFuture<>();

    public ActorCell(Actor actor, ActorSystem system, String name) {
        this(actor, system, name, null);
    }

    /**
     * Test-Seam: Zelle mit vorgegebener Mailbox. {@code null} bedeutet
     * Standard-Mailbox (bounded, Policy des Actors). Nur fuer Tests gedacht.
     */
    ActorCell(Actor actor, ActorSystem system, String name, Mailbox mailbox) {
        this.actor = actor;
        this.system = system;
        this.registeredName = (name == null || name.isBlank()) ? null : name;
        this.self = new LocalActorRef(this, system.pathFor(registeredName));
        this.mailbox = mailbox == null
                ? Mailboxes.bounded(actor.mailboxCapacity(), actor.overflow(), self)
                : mailbox;
        this.wakeup = (this.mailbox instanceof DefaultMailbox m) ? m.wakeup() : new Wakeup();
        ActorHooks.attach(actor, this);
    }

    // ============================================================ Thread-Lebenszyklus

    /** Startet den Virtual Thread; der Name hilft bei {@code Thread.dump_to_file}. */
    public void start() {
        this.thread = Thread.ofVirtual().name(self.name()).unstarted(this::run);
        thread.start();
    }

    public Thread thread() {
        return thread;
    }

    /**
     * Der Actor-Thread. Ein {@code try/catch} um die <b>gesamte</b> Schleife: es
     * gibt keinen Aufruf von aussen, der hier eine Exception werfen kann, also
     * waere ein Hanger das schlimmere Ergebnis — anders als beim Tick-Treiber,
     * wo die Fehler von aussen kommen (s. {@code implement.md} 16.3).
     */
    private void run() {
        CurrentActor.set(self);
        wakeup.bind(Thread.currentThread());
        try {
            try {
                behavior = ActorHooks.start(actor, this);
            } catch (Throwable t) {
                crashCount++;
                stopReason = ExitReason.failure(t);
                return;
            }
            dispatchLoop();
        } catch (Throwable t) {
            crashCount++;
            if (!stopReason.abnormal()) {
                stopReason = ExitReason.failure(t);
            }
        } finally {
            if (graceful) {
                drainQuietly();
            }
            finish(stopReason);
            CurrentActor.clear();
        }
    }

    /** Aus dem Drain gestellte Steuerbefehle — der naechste Dispatch nimmt sie. */
    private final java.util.ArrayDeque<Envelope> deferred = new java.util.ArrayDeque<>(4);

    /** So viele zurueckgestellte Nachrichten sind ein Mass fuer einen Endlosschleifen-Fall. */
    private static final int MAX_DEFERRED = 64;

    /** Hauptschleife. Rueckgabe {@code true} beendet den Actor. */
    private void dispatchLoop() {
        while (true) {
            Envelope envelope = deferred.pollFirst();        // aus dem Drain gestellt
            if (envelope == null) {
                if (mailbox.isEmpty() && !wakeup.await(() -> !mailbox.isEmpty() || !deferred.isEmpty())) {
                    return;                                 // Weck-Mechanismus beendet
                }
                envelope = deferred.pollFirst();
                if (envelope == null) {
                    envelope = mailbox.poll();
                }
            }
            if (envelope == null) {
                continue;                                   // spurious wakeup
            }
            if (envelope.isSystem()) {
                if (handleSystem(envelope.message())) {
                    return;
                }
            } else if (processUser(envelope)) {
                return;
            }
        }
    }

    /**
     * Drain nach {@code stop()}: alles verarbeiten, was vor dem Terminate lag.
     * Kein Budget — BEAM hat auch keins, und ein begrenzter Drain wuerde
     * Nachrichten ungefragt verwerfen.
     */
    private void drainQuietly() {
        try {
            for (;;) {
                Envelope envelope = mailbox.poll();
                if (envelope == null) {
                    if (mailbox.isEmpty()) {
                        return;
                    }
                    continue;                               // eins noch im Anflug
                }
                if (envelope.isSystem()) {
                    if (handleSystem(envelope.message())) {
                        return;
                    }
                } else if (processUser(envelope)) {
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Der Actor ist bereits im Sterben; hier gibt es nichts mehr zu tun.
        }
    }

    /**
     * Systemnachricht verarbeiten. Erreicht nie eine Benutzerklausel.
     *
     * @return true, wenn der Actor enden soll
     */
    private boolean handleSystem(Object signal) {
        systemMessages.incrementAndGet();
        if (signal instanceof Signals.Terminate terminate) {
            stopReason = terminate.reason();
            graceful = true;
            return true;
        }
        if (signal instanceof Signals.Exit exit) {
            return onLinkExit(exit);
        }
        if (signal instanceof Signals.CallResult result) {
            result.call().complete(result.value(), result.error());
            return false;
        }
        return false;
    }

    /**
     * Nutzernachricht durch das aktuelle Behavior schicken.
     *
     * @return true, wenn der Actor enden soll
     */
    private boolean processUser(Envelope envelope) {
        messagesProcessed++;
        Envelope previous = current;
        current = envelope;
        Behavior result;
        try {
            result = behavior.invoke(envelope.message(), this);
        } catch (Throwable t) {
            // BEAM: Exception im Handler -> Actor stirbt mit diesem Grund,
            // der Supervisor entscheidet ueber Neustart.
            crashCount++;
            stopReason = ExitReason.failure(t);
            return true;
        } finally {
            current = previous;
        }
        if (result == null || result == Behavior.UNHANDLED) {
            try {
                result = ActorHooks.unhandled(actor, this, envelope.message());
            } catch (Throwable t) {
                crashCount++;
                stopReason = ExitReason.failure(t);
                return true;
            }
        }
        if (result == Behavior.NEXT) {
            return false;
        }
        if (result == Behavior.HALT) {
            stopReason = ExitReason.normal();
            return true;
        }
        behavior = result;                                  // become
        return false;
    }

    /**
     * Ein gelinkter Actor ist gestorben. Die Filterung (nur abnormal, ausser
     * trapping) hat der sterbende Actor in {@link #finish()} erledigt.
     *
     * @return true, wenn dieser Actor jetzt stirbt
     */
    private boolean onLinkExit(Signals.Exit exit) {
        if (actor instanceof ChildOwner owner) {
            Behavior result = owner.onChildExit(exit.target(), exit.reason());
            if (result == Behavior.HALT) {
                // Der Owner (z. B. ein Supervisor) kann den Stoppgrund selbst
                // gesetzt haben (z. B. Eskalation mit eigener Exception). Dann
                // gewinnt sein Grund; sonst ist es der Grund des sterbenden Peers.
                if (stopReason instanceof ExitReason.Normal) {
                    stopReason = exit.reason();
                }
                return true;
            }
            return false;
        }
        stopReason = exit.reason();                         // abnormal -> mitsterben
        return true;
    }

    // ==================================================================== finish

    /**
     * Einmaliger Abschluss. Reihenfolge wie BEAMs {@code exit/1}: letzte
     * Nachricht, {@code postStop}, Namensfreigabe, dann Signale an Links und
     * Kinder, zuletzt {@code deathFuture}.
     */
    private void finish(ExitReason reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        state.set(STOPPED);
        mailbox.close();
        try {
            ActorHooks.stop(actor, this, reason);
        } catch (Throwable ignored) {
            // Ein Fehler im Stop-Hook darf die Signalschleife nicht verhindern.
        }
        if (registeredName != null) {
            system.unregister(registeredName, self);
        }
        system.onActorFinished(this);
        for (Call call : List.copyOf(pendingCalls)) {
            call.abort(reason);
        }
        pendingCalls.clear();
        // Symmetrisch trennen: beide Seiten loesen die Verknuepfung. Der Peer
        // entscheidet selbst, ob er ein Signal braucht (trapping oder abnormal).
        for (ActorCell peer : List.copyOf(links)) {
            links.remove(peer);
            peer.onLinkPeerDied(self, reason);
        }
        links.clear();
        try {
            ActorHooks.postStop(actor, this, reason);
        } catch (Throwable ignored) {
        }
        deathFuture.complete(reason);
    }

    /** Aufruf durch einen Peer, dessen Actor gerade stirbt. */
    private void onLinkPeerDied(ActorRef dead, ExitReason reason) {
        ActorCell peer = cellOf(dead);
        links.remove(peer);
        if (reason.abnormal() || actor instanceof ChildOwner) {
            postSystem(new Signals.Exit(dead, reason));
        }
    }

    @Override
    public void close() {
        stop();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(Duration.ofSeconds(5).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ================================================================ Zustandswechsel

    @Override
    public void stop() {
        stop(ExitReason.normal());
    }

    @Override
    public void stop(ExitReason reason) {
        if (state.compareAndSet(RUNNING, STOPPING)) {
            this.stopReason = reason;
            // Geordnet ans Postfach-Ende: alles davor wird noch verarbeitet.
            postSystem(new Signals.Terminate(reason));
        }
    }

    @Override
    public void kill() {
        stopReason = ExitReason.terminated();
        state.set(STOPPING);
        mailbox.kill();
        wakeup.stop();
        Thread t = thread;
        if (t != null) {
            t.interrupt();            // Hard-Kill: blockierenden Handler abreissen (implement.md 4.3)
        }
    }

    @Override
    public boolean isStopped() {
        return state.get() != RUNNING;
    }

    public boolean isRunning() {
        return state.get() == RUNNING;
    }

    @Override
    public void become(Behavior next) {
        this.behavior = next;
    }

    @Override
    public void become(Behavior next, List<Receive.Clause> sharedPrefix) {
        this.behavior = Receive.of(sharedPrefix, next);
    }

    // =================================================================== Zugriff

    @Override public ActorRef self() { return self; }
    @Override public ActorSystem system() { return system; }
    @Override public long messagesProcessed() { return messagesProcessed; }
    @Override public long crashCount() { return crashCount; }
    /**
     * Reentrant-Drain fuer den Tick-Pfad: nimmt bis zu {@code budget} Nachrichten
     * direkt aus dem Postfach und dispatcht sie, ohne zurueck in die Schleife zu
     * gehen. Der Envelope-Stack in {@link #processUser} haelt {@code sender()}
     * und {@code currentMessage} fuer die Verschachtelung korrekt.
     */
    @Override public int drainInbox(int budget) {
        int drained = 0;
        while (drained < budget) {
            Envelope envelope = mailbox.poll();
            if (envelope == null) {
                // poll() ist verlustbehaftet: {@code null} heisst nicht „leer",
                // ein Producer kann gerade im Publish-Fenster haengen. Deshalb
                // nur bei isEmpty() (exakt, indexbasiert) abbrechen und sonst
                // spinnen, bis der Slot publiziert ist (s. implement.md 6.4/16.1).
                if (mailbox.isEmpty()) {
                    break;                                    // exakte Abbruchbedingung
                }
                Thread.onSpinWait();                          // Producer publiziert gerade
                continue;
            }
            if (!envelope.isSystem() && ActorHooks.deferDuringDrain(actor, envelope.message())) {
                // Steuerbefehl: gehoert in den naechsten Dispatch, nicht in diesen.
                deferred.addLast(envelope);
                if (deferred.size() >= MAX_DEFERRED) {
                    break;                                // Sicherheitsnetz
                }
                continue;                                 // zaehlt nicht als Drain
            }
            if (envelope.isSystem()) {
                if (handleSystem(envelope.message())) {
                    return drained + 1;                    // Actor endet sofort
                }
            } else if (processUser(envelope)) {
                return drained + 1;                        // Actor endet sofort
            }
            drained++;
        }
        return drained;
    }

    @Override public int mailboxSize() { return mailbox.size(); }
    @Override public int mailboxCapacity() { return mailbox.capacity(); }
    @Override public ActorRef sender() { return current == null ? null : current.sender(); }

    public Mailbox mailbox() { return mailbox; }
    public Actor actor() { return actor; }
    public boolean anonymous() { return registeredName == null; }
    public String registeredName() { return registeredName; }
    public long systemMessages() { return systemMessages.get(); }
    public int linkCount() { return links.size(); }
    public CompletableFuture<ExitReason> deathFuture() { return deathFuture; }

    // =================================================================== Senden

    /**
     * Benutzernachricht annehmen. Verwirft sie, wenn der Actor nicht mehr
     * {@code RUNNING} ist — {@code tell} darf ja nicht werfen.
     */
    boolean postUser(Envelope envelope) {
        if (state.get() != RUNNING) {
            return false;
        }
        boolean accepted = mailbox.offer(envelope);
        if (accepted) {
            wakeup.signal();
        }
        return accepted;
    }

    boolean tryPostUser(Envelope envelope) {
        return postUser(envelope);
    }

    /** Systemnachricht: umgeht alles, wird nie verworfen, selbst im STOPPING. */
    void postSystem(Object signal) {
        mailbox.offer(Envelope.system(signal));
        wakeup.signal();
    }

    // ============================================================ Kontext-Operationen

    @Override
    public void tell(ActorRef target, Object message) {
        target.tell(message, self);
    }

    @Override
    public boolean tryTell(ActorRef target, Object message) {
        return target.tryTell(message);
    }

    @Override
    public CompletableFuture<Object> ask(ActorRef target, Object message, Duration timeout) {
        return target.ask(message, self, timeout);
    }

    @Override
    public void reply(Object value) {
        if (current != null && current.isCall()) {
            current.call().settle(value, null);
            return;
        }
        ActorRef sender = sender();
        if (sender != null) {
            sender.tell(value);                             // Fallback: an den Absender
        }
    }

    @Override
    public void replyAndStop(Object value) {
        reply(value);
        stop();
    }

    // ==================================================================== Links

    @Override
    public void link(ActorRef target) {
        if (target == self) {
            throw new IllegalStateException("cannot link an actor to itself: " + self.name());
        }
        ActorCell other = cellOf(target);
        if (other.state.get() == STOPPED) {
            postSystem(new Signals.Exit(target, other.stopReason));
            return;
        }
        links.add(other);
        other.links.add(this);
    }

    @Override
    public void unlink(ActorRef target) {
        ActorCell other = cellOf(target);
        links.remove(other);
        other.links.remove(this);
    }

    /**
     * Beobachtung statt Verknuepfung. Kein Signal, keine Race: ist die Zelle
     * schon tot, ist die Future bereits abgeschlossen.
     */
    @Override
    public CompletableFuture<ExitReason> monitor(ActorRef target) {
        return cellOf(target).deathFuture;
    }

    public static ActorCell cellOf(ActorRef ref) {
        if (ref instanceof LocalActorRef local) {
            return local.cell();
        }
        throw new IllegalArgumentException(
                "only local actors are supported in this build: " + ref.path());
    }

    // ================================================================ Zeit & Arbeit

    @Override
    public void schedule(Object message, Duration delay) {
        system.scheduler().schedule(() -> self.tell(message), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void runBlocking(java.util.concurrent.Callable<Object> work) {
        runBlocking(work, null);
    }

    /**
     * Blockierende Arbeit auslagern. Der Handler gibt die Kontrolle sofort ab;
     * das Ergebnis kommt als {@link Signals.CallResult} in die eigene Mailbox
     * zurueck. Der Code nach diesem Aufruf laeuft also erst in einem spaeteren
     * Turn — BEAM-Analogon {@code receive after 0}.
     */
    @Override
    public void runBlocking(java.util.concurrent.Callable<Object> work,
                            BiConsumer<Object, Throwable> onResult) {
        Call call = Call.task(this, onResult);
        pendingCalls.add(call);
        system.blockingPool().execute(() -> {
            Object value = null;
            Throwable error = null;
            try {
                value = work.call();
            } catch (Throwable t) {
                error = t;
            }
            call.settle(value, error);
        });
    }

    /** Von {@link Call} aufgerufen, sobald ein Auftrag sich erledigt hat. */
    void forgetPendingCall(Call call) {
        pendingCalls.remove(call);
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** {@code ask}-Auftrag dieses Actors (Aufrufer ist der Actor selbst). */
    Call newRequestCall() {
        Call call = Call.request(this);
        pendingCalls.add(call);
        return call;
    }
}
