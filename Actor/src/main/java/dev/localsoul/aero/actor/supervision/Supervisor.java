package dev.localsoul.aero.actor.supervision;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ChildOwner;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.actor.internal.ActorCell;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ueberwacht eine Gruppe von Kindern und entscheidet bei deren Tod: neu
 * starten, Geschwister mitziehen oder eskalieren.
 *
 * <p>Der Supervisor <b>trapped</b> Exit-Signale — das ist der einzige Unterschied
 * zu einem normalen Actor, und er kommt durch {@link ChildOwner} zum Ausdruck,
 * ohne dass die Supervisions-Schicht im Kern bekannt sein muss. Ein Kind, das
 * abnormal stirbt, ist deshalb fuer seinen Supervisor kein Weltuntergang.
 *
 * <h2>Zustandsautomat</h2>
 * <pre>
 *   Kind stirbt
 *        │
 *        ├── restartType erlaubt keinen Neustart ──────────────► Kind entfernen
 *        │
 *        ├── Kind ist {@code shutdown} und nicht permanent ────► Supervisor stirbt
 *        │
 *        ├── Neustart-Budget ueberschritten ─────────────────► Eskalation
 *        │
 *        └── je nach Strategie: Kind (+ Geschwister) neu starten
 * </pre>
 *
 * <p>Neustarts geschehen <b>synchron im Supervisor-Thread</b>: der Ersatz-Actor
 * existiert also, bevor die Exit-Nachricht abgearbeitet ist. Ein haeufiger
 * Fehler in Eigenbauten ist, die uebernommene {@code ActorRef} weiterzugeben —
 * sie zeigt dann auf einen toten Actor. {@link #child(String)} liefert darum
 * immer die aktuelle.
 */
public class Supervisor extends Actor implements ChildOwner {

    /** Live-Zustand eines Kindes. */
    record Child(ChildSpec spec, ActorRef ref, String name, long startOrder, int restarts) {
    }

    private static final System.Logger LOG =
            System.getLogger(Supervisor.class.getName());

    private final SupervisorSpec spec;
    private final Map<String, Child> children = new ConcurrentHashMap<>();
    private final Deque<Long> restartsInWindow = new ArrayDeque<>();
    private long startOrder;

    private volatile EscalationException lastEscalation;
    private volatile String lastEvent;

    protected Supervisor(String name, SupervisorSpec spec) {
        super(name);
        this.spec = spec;
    }

    public SupervisorSpec spec() {
        return spec;
    }

    public EscalationException lastEscalation() {
        return lastEscalation;
    }

    public String lastEvent() {
        return lastEvent;
    }

    // ================================================================ Start

    @Override
    protected Behavior onStart(ActorContext ctx) {
        for (ChildSpec child : spec.children()) {
            startChild(ctx, child);
        }
        return Receive.of(
                Clause.on(StopChild.class, (StopChild cmd, ActorContext c) -> {
                    stopChild(c, cmd.id());
                    return Behavior.NEXT;
                }),
                Clause.on(RestartChild.class, (RestartChild cmd, ActorContext c) -> {
                    restartChildById(c, cmd.id());
                    return Behavior.NEXT;
                }),
                Clause.on(ChildrenInfo.class, (ChildrenInfo cmd, ActorContext c) -> {
                    c.reply(childInfos());
                    return Behavior.NEXT;
                }),
                Clause.on(Shutdown.class, (Shutdown cmd, ActorContext c) -> {
                    shutdown(c, cmd);
                    return Behavior.HALT;
                })
        );
    }

    @SuppressWarnings("AutoCloseableResource")   // ActorSystem wird vom Aufrufer verwaltet
    private void startChild(ActorContext ctx, ChildSpec childSpec) {
        String name = childSpec.id();
        startOrder++;
        ActorRef ref = ctx.system().spawn(childSpec.factory().create(name), name);
        children.put(name, new Child(childSpec, ref, name, startOrder, 0));
        if (spec.linkChildren()) {
            ctx.link(ref);                       // sonst saehe der Supervisor den Tod nie
        }
    }

    // ================================================================ Kind-Tod

    @Override
    public Behavior onChildExit(ActorRef child, ExitReason reason) {
        Child entry = byRef(child);
        if (entry == null) {
            return Behavior.NEXT;                 // schon entfernt oder nie bekannt
        }
        lastEvent = "child " + entry.name() + " died: " + reason;

        // 1. Kein Neustart gewuenscht?
        if (!entry.spec().restartType().shouldRestart(reason)) {
            children.remove(entry.name(), entry);
            return Behavior.NEXT;
        }

        // 2. Absichtlicher Stopp eines nicht-permanenten Kindes beendet den
        //    Supervisor (BEAM: ein shutdown-Kind beendet seinen Vater).
        if (reason instanceof ExitReason.Shutdown && !entry.spec().permanent()) {
            children.remove(entry.name(), entry);
            return Behavior.HALT;                 // Zelle uebernimmt den Grund
        }

        // 3. Intensitaetsgrenze: ein haengendes Kind darf keine Neustart-Schleife
        //    erzeugen. Eskalation an den Parent mit explizitem Grund: der
        //    Supervisor stirbt mit der EscalationException, nicht mit dem
        //    urspruenglichen Kinder-Grund (s. implement.md 6.10).
        if (exceedsRestartBudget()) {
            EscalationException escalation = new EscalationException(
                    "restart budget of " + spec.maxRestarts() + " within " + spec.maxRestartsWithin()
                            + " exceeded, child=" + entry.name(), spec.maxRestarts(), reason);
            lastEscalation = escalation;
            LOG.log(System.Logger.Level.ERROR, "supervisor escalating: {0}", escalation.getMessage());
            context().stop(ExitReason.failure(escalation));
            return Behavior.HALT;
        }
        restartsInWindow.addLast(System.nanoTime());
        lastEvent = "restarting after " + entry.name() + ": " + reason;

        // 4. Strategie
        switch (spec.strategy()) {
            case ONE_FOR_ONE -> restart(context(), entry);
            case ONE_FOR_ALL -> {
                for (Child sibling : orderedChildren()) {
                    restart(context(), sibling);
                }
            }
            case REST_FOR_ONE -> {
                for (Child sibling : orderedChildren()) {
                    if (sibling.startOrder() >= entry.startOrder()) {
                        restart(context(), sibling);
                    }
                }
            }
        }
        return Behavior.NEXT;
    }

    private boolean exceedsRestartBudget() {
        long now = System.nanoTime();
        long window = spec.maxRestartsWithin().toNanos();
        while (!restartsInWindow.isEmpty() && now - restartsInWindow.peekFirst() > window) {
            restartsInWindow.pollFirst();
        }
        return restartsInWindow.size() >= spec.maxRestarts();
    }

    /**
     * Neustart als <b>atomarer</b> Tausch: Der Spawn passiert innerhalb von
     * {@link Map#compute}, sonst sieht ein lesender Thread zwischen
     * {@code remove} und {@code put} eine leere Kindliste und haelt ein
     * {@code child(id)}-Ergebnis von {@code null} fuer "Kind weg".
     */
    @SuppressWarnings("AutoCloseableResource")   // ActorSystem wird vom Aufrufer verwaltet
    private void restart(ActorContext ctx, Child child) {
        children.compute(child.name(), (id, current) -> {
            if (current != child) {
                return current;                 // zwischenzeitlich ersetzt
            }
            startOrder++;
            ChildSpec childSpec = new ChildSpec(child.spec().id(), child.spec().factory(),
                    child.spec().restartType(), child.spec().permanent());
            ActorRef ref = ctx.system().spawnReplacing(childSpec.factory().create(id), id);
            if (spec.linkChildren()) {
                ctx.link(ref);
            }
            return new Child(childSpec, ref, id, startOrder, child.restarts() + 1);
        });
    }

    private List<Child> orderedChildren() {
        List<Child> all = new ArrayList<>(children.values());
        all.sort(Comparator.comparingLong(Child::startOrder));
        return all;
    }

    private Child byRef(ActorRef ref) {
        for (Child child : children.values()) {
            if (child.ref() == ref) {
                return child;
            }
        }
        return null;
    }

    // ======================================================== dynamische Kinder

    /**
     * Erzeugt ein Kind zur Laufzeit. Der Name muss eindeutig sein; der Restart
     * laeuft danach ueber {@link #onChildExit}.
     */
    protected final ActorRef startDynamic(ActorContext ctx, String name,
                                          ChildFactory factory, RestartType restartType) {
        if (children.containsKey(name)) {
            return children.get(name).ref();
        }
        startChild(ctx, ChildSpec.of(name, factory, restartType));
        return children.get(name).ref();
    }

    /** Geordnet beenden: das Postfach wird noch geleert. */
    protected final void stopDynamic(ActorContext ctx, String name) {
        ActorRef ref = child(name);
        if (ref != null) {
            ActorCell.cellOf(ref).stop(dev.localsoul.aero.actor.ExitReason.shutdown());
        }
    }

    /** Aktueller Zustand aller Kinder — fuer Logs und Tests. */
    protected final java.util.List<ChildInfo> allChildInfos() {
        return childInfos();
    }

    // ================================================================ Befehle

    public record StopChild(String id) {
    }

    public record RestartChild(String id) {
    }

    public record ChildrenInfo() {
    }

    public record Shutdown() {
    }

    /** {@code reason}-uebersicht fuer Logs und Tests. */
    public record ChildInfo(String id, String name, boolean alive, int restarts) {
    }

    public List<ChildInfo> childInfos() {
        List<ChildInfo> infos = new ArrayList<>();
        for (Child child : orderedChildren()) {
            infos.add(new ChildInfo(child.spec().id(), child.name(), child.ref().isAlive(), child.restarts()));
        }
        return infos;
    }

    private void stopChild(ActorContext ctx, String id) {
        Child child = children.get(id);
        if (child == null) {
            return;
        }
        // permanent -> shutdown, sonst normal: der Supervisor entscheidet per
        // ChildSpec, ob das sein eigenes Ende bedeutet.
        ActorCell.cellOf(child.ref())
                .stop(child.spec().permanent() ? ExitReason.shutdown() : ExitReason.normal());
    }

    private void restartChildById(ActorContext ctx, String id) {
        Child child = children.get(id);
        if (child == null) {
            return;
        }
        ActorCell.cellOf(child.ref()).kill();
        // Der Kill laeuft asynchron; der Neustart erfolgt ueber onChildExit.
    }

    private void shutdown(ActorContext ctx, Shutdown cmd) {
        for (Child child : orderedChildren()) {
            ActorCell.cellOf(child.ref()).stop(ExitReason.shutdown());
        }
    }

    // ================================================================ Zugriff

    /** Aktuelle Referenz eines Kindes — nach einem Neustart immer die neue. */
    public ActorRef child(String id) {
        Child child = children.get(id);
        return child == null ? null : child.ref();
    }

    public int childCount() {
        return children.size();
    }

    @Override
    protected void onStop(ActorContext ctx, ExitReason reason) {
        for (Child child : children.values()) {
            ActorCell.cellOf(child.ref())
                    .stop(reason instanceof ExitReason.Normal ? ExitReason.normal() : reason);
        }
    }

    /** Fuer {@code UserSupervisor}: wie lange ein Neustart zurueckdatiert wird. */
    protected Duration restartWindow() {
        return spec.maxRestartsWithin();
    }
}
