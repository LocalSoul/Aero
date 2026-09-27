package dev.localsoul.aero.actor.supervision;

import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.actor.internal.ActorCell;

import java.time.Duration;

/**
 * Supervisor fuer <b>zur Laufzeit</b> entstehende Kinder — das Aequivalent zu
 * BEAMs {@code simple_one_for_one}. Der Unterschied: statt eines vorab
 * bekannten Kind-Typs wird pro Start eine eigene {@link ChildFactory} uebergeben,
 * die typischerweise den Auftrag des Kindes kennt.
 *
 * <p>Der klassische Fall im Spielserver: Raeume, die beim ersten Spieler
 * entstehen und beim letzten Spieler wieder verschwinden. Ein Raeume-Actor
 * haengt die {@code shutdown}-Logik an das letzte {@code leave}-Signal, nicht an
 * eine Ueberwachung von aussen.
 *
 * <pre>{@code
 * UserSupervisor rooms = new UserSupervisor("rooms");
 * ActorRef room = rooms.startChild("room-7", () -> new Room(7, gameRules));
 * …
 * rooms.stopChild("room-7");        // graceful: erst drainen, dann beenden
 * }</pre>
 */
public final class UserSupervisor extends Supervisor {

    private static final System.Logger LOG =
            System.getLogger(UserSupervisor.class.getName());

    /** Kommando: ein Kind zur Laufzeit erzeugen. */
    public record StartChild(String name, ChildFactory factory, RestartType restartType) {
    }

    public UserSupervisor(String name) {
        this(name, RestartType.TRANSIENT, 3, Duration.ofSeconds(5));
    }

    public UserSupervisor(String name, RestartType restartType, int maxRestarts, Duration within) {
        super(name, SupervisorSpec.builder()
                .strategy(RestartStrategy.ONE_FOR_ONE)
                .maxRestarts(maxRestarts, within)
                .build());
        this.defaultRestartType = restartType;
    }

    private final RestartType defaultRestartType;

    @Override
    protected Behavior onStart(ActorContext ctx) {
        return Receive.of(
                Clause.on(StartChild.class, (StartChild cmd, ActorContext c) -> {
                    ActorRef ref = startDynamic(c, cmd.name(), cmd.factory(),
                            cmd.restartType() == null ? defaultRestartType : cmd.restartType());
                    c.reply(ref);
                    return Behavior.NEXT;
                }),
                Clause.on(StopChild.class, (StopChild cmd, ActorContext c) -> {
                    stopDynamic(c, cmd.id());
                    return Behavior.NEXT;
                }),
                Clause.on(RestartChild.class, (RestartChild cmd, ActorContext c) -> {
                    ActorRef ref = child(cmd.id());
                    if (ref != null) {
                        ActorCell.cellOf(ref).kill();     // Neustart via onChildExit
                    }
                    return Behavior.NEXT;
                }),
                Clause.on(ChildrenInfo.class, (ChildrenInfo cmd, ActorContext c) -> {
                    c.reply(childInfos());
                    return Behavior.NEXT;
                }),
                Clause.on(Shutdown.class, (Shutdown cmd, ActorContext c) -> {
                    for (var info : childInfos()) {
                        stopDynamic(c, info.id());
                    }
                    return Behavior.HALT;
                })
        );
    }

    /**
     * Startet ein Kind und liefert dessen Referenz. Blockiert bis zur
     * Antwort des Supervisors — typischerweise wenige Mikrosekunden, da die
     * Arbeit im Supervisor-Thread liegt.
     *
     * @throws IllegalStateException wenn es bereits ein lebendes Kind dieses Namens gibt
     */
    public ActorRef startChild(String name, ChildFactory factory) {
        return startChild(name, factory, defaultRestartType);
    }

    public ActorRef startChild(String name, ChildFactory factory, RestartType restartType) {
        if (child(name) != null) {
            throw new IllegalStateException("child already exists: " + name);
        }
        Object result = selfRef().ask(new StartChild(name, factory, restartType), Duration.ofSeconds(5));
        return (ActorRef) result;
    }

    /** Wie {@link #startChild(String, ChildFactory)}, ohne zu blockieren. */
    public java.util.concurrent.CompletableFuture<ActorRef> startChildAsync(
            String name, ChildFactory factory, Duration timeout) {
        return selfRef().ask(new StartChild(name, factory, defaultRestartType), timeout)
                .thenApply(ActorRef.class::cast);
    }

    /**
     * Geordnet beenden: das Postfach wird noch geleert. Der Supervisor bleibt
     * stehen — das ist der Unterschied zu {@code kill()}, der sofort abbricht.
     */
    public void stopChild(String name) {
        selfRef().tell(new StopChild(name));
    }

    public void stopChild(String name, Duration gracePeriod) {
        ActorRef ref = child(name);
        if (ref == null) {
            return;
        }
        ActorCell cell = ActorCell.cellOf(ref);
        cell.stop(dev.localsoul.aero.actor.ExitReason.shutdown());
        if (gracePeriod.isZero()) {
            return;
        }
        try {
            cell.deathFuture().get(gracePeriod.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "child {0} did not stop within {1}",
                    new Object[]{name, gracePeriod});
        }
    }

    public boolean isChildAlive(String name) {
        ActorRef ref = child(name);
        return ref != null && ref.isAlive();
    }
}
