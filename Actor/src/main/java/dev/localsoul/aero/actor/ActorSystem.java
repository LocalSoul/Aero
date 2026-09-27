package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.ActorCell;
import dev.localsoul.aero.actor.internal.CurrentActor;
import dev.localsoul.aero.actor.internal.LocalActorRef;
import dev.localsoul.aero.actor.internal.NameRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Container fuer Actors und der einzige Ort, an dem Namen vergeben werden
 * (BEAM-Prozessregister plus ein paar „Supervised"-Eintraege).
 *
 * <p><b>Threads.</b> Das System selbst besitzt <b>keinen</b> Worker-Thread fuer
 * Actors — jeder Actor hat seinen eigenen Virtual Thread. Nur zwei Hilfspools
 * existieren: ein kleiner Scheduler fuer {@code schedule} und {@code ask}-Timeouts
 * sowie der {@code blockingPool} fuer {@link ActorContext#runBlocking}. Beide
 * sind <b>Platform Threads</b>, weil sie tatsaechlich warten.
 *
 * <p><b>Lebenszyklus.</b> Das System ist der {@code application}-Master
 * ({@code implement.md} 3): {@link #close()}/{@link #shutdown()} stoppen alle
 * Actoren geordnet (Drain) und fahren die Hilfspools herunter. Ein haengender
 * Actor wird nach Ablauf des Timeouts hart beendet (Kill).
 */
public final class ActorSystem implements AutoCloseable {

    /** Default-Shutdown-Fenster: 10 s (s. {@code implement.md} 7.6). */
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final String name;
    private final NameRegistry registry = new NameRegistry();
    private final AtomicLong anonymousIds = new AtomicLong();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService blockingPool;
    /** Alle je gestarteten, noch lebenden Zellen — anonyme wie registrierte. */
    private final Set<ActorCell> actors = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ActorSystem(String name) {
        this.name = name;
        ThreadFactory factory = Thread.ofPlatform().name("aero-sched-", 0).daemon().factory();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(factory);
        ThreadFactory pool = Thread.ofPlatform().name("aero-blocking-", 0).daemon().factory();
        this.blockingPool = Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors()), pool);
    }

    public String name() {
        return name;
    }

    public NameRegistry registry() {
        return registry;
    }

    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    public ExecutorService blockingPool() {
        return blockingPool;
    }

    /** Pfad, den ein Actor mit diesem Namen bekommen wuerde. */
    public ActorPath pathFor(String actorName) {
        return actorName == null
                ? ActorPath.anonymous(name, anonymousIds.incrementAndGet())
                : new ActorPath(name, actorName);
    }

    // ================================================================== Spawn

    /**
     * Erzeugt und startet einen Actor. Der Name wird registriert; ist er schon
     * belegt, wirft {@link #spawn(Actor, String)} {@link ActorNameTakenException}
     * und der neue Actor wird <b>nicht</b> gestartet.
     *
     * @return die Referenz des neuen Actors
     */
    public ActorRef spawn(Actor actor) {
        return spawn(actor, actor.name());
    }

    public ActorRef spawn(Actor actor, String name) {
        ActorCell cell = new ActorCell(actor, this, name);
        LocalActorRef ref = (LocalActorRef) cell.self();
        if (!cell.anonymous()) {                 // anonyme Actoren bekommen keinen Namen
            registry.register(ref.name(), ref);
        }
        actors.add(cell);
        cell.start();
        return ref;
    }

    /**
     * Wie {@link #spawn}, ersetzt aber einen Actor gleichen Namens. Der alte
     * Actor wird hart beendet und sein Abschluss abgewartet, damit die
     * Namensfreigabe sicher vor dem neuen Eintrag liegt.
     */
    public ActorRef spawnReplacing(Actor actor, String name) {
        whereis(name).ifPresent(previous -> {
            ActorCell cell = ActorCell.cellOf(previous);
            cell.kill();
            try {
                cell.deathFuture().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
                // Der alte Actor haengt; die Registrierung wird ersetzt.
            }
        });
        return spawn(actor, name);
    }

    // ================================================================== Namen

    public Optional<ActorRef> whereis(String name) {
        return registry.whereIs(name);
    }

    public void unregister(String name, ActorRef ref) {
        registry.unregister(name, ref);
    }

    public java.util.Collection<String> names() {
        return registry.names();
    }

    /** Alle noch lebenden Actoren — anonyme und registrierte. */
    public List<ActorRef> actors() {
        List<ActorRef> refs = new java.util.ArrayList<>(actors.size());
        for (ActorCell cell : actors) {
            refs.add(cell.self());
        }
        return refs;
    }

    /** Anzahl der noch lebenden Actoren (nicht nur der registrierten). */
    public int actorCount() {
        return actors.size();
    }

    /** Von {@code ActorCell.finish()} aufgerufen, sobald ein Actor endet. */
    public void onActorFinished(ActorCell cell) {
        actors.remove(cell);
    }

    // ================================================================== Helfer

    /**
     * Adresse im Absenderstil, wie ihn Spieler oder Netzwerk-Layer benutzen.
     * Ohne laufenden Actor bleibt der Absender {@code null}.
     */
    public static ActorRef currentActor() {
        return CurrentActor.get();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        shutdown(DEFAULT_SHUTDOWN_TIMEOUT);
    }

    /**
     * Geordneter System-Stopp (BEAM {@code application:stop}):
     * <ol>
     *   <li>alle noch lebenden Actoren {@link ActorCell#stop()}-graceful beenden
     *       (Drain, kein Discard),</li>
     *   <li>auf deren Abschluss warten — jeweils mit dem Restbudget des
     *       Timeouts; ein Actor, der nicht aufhoert, wird hart beendet
     *       ({@link ActorCell#kill()}),</li>
     *   <li>Hilfspools (Scheduler, runBlocking-Pool) herunterfahren.</li>
     * </ol>
     * Idempotent: ein zweiter Aufruf ist folgenlos.
     */
    public void shutdown(Duration timeout) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<ActorCell> live = List.copyOf(actors);
        for (ActorCell cell : live) {
            cell.stop();                          // graceful, nicht blockierend
        }
        if (timeout != null && !timeout.isZero()) {
            long deadline = System.nanoTime() + timeout.toNanos();
            for (ActorCell cell : live) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    cell.deathFuture().get(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException | TimeoutException e) {
                    cell.kill();                  // Nachzuegler: hart beenden
                }
            }
        }
        scheduler.shutdownNow();
        blockingPool.shutdownNow();
    }

    /**
     * Wartet, bis alle Actoren beendet sind.
     *
     * @return {@code true}, wenn alle rechtzeitig beendet wurden
     */
    public boolean awaitTermination(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (ActorCell cell : List.copyOf(actors)) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                cell.deathFuture().get(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (ExecutionException | TimeoutException e) {
                return false;
            }
        }
        return true;
    }

    public boolean isRunning() {
        return !closed.get();
    }
}
