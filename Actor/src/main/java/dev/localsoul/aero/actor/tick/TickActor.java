package dev.localsoul.aero.actor.tick;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.actor.session.Subscription;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Der Raum: abonniert den {@link TickDriver}, verarbeitet die im Postfach
 * liegenden Eingaben, simuliert, flusht den Outbound-Puffer und misst.
 *
 * <p><b>Reihenfolge ist die Spezifikation</b> (s. {@code implement.md} 6.12):
 * <pre>
 *   1) Eingaben verarbeiten   (drainInbox, Budget)
 *   2) Simulation             (onTick)
 *   3) Outbound               (flush -> ein Paket pro Client)
 *   4) Messen                 (Lag, Dauer, Backlog)
 * </pre>
 * Eingaben, die waehrend der Simulation fuer den <b>naechsten</b> Tick
 * eintreffen, bleiben im Postfach und werden dort verarbeitet — das ist die
 * gewuenschte 1-Tick-Latenz und kein Fehler.
 */
public abstract class TickActor extends Actor {

    private final TickDriver driver;
    private final OutboundBuffer outbound = new OutboundBuffer();

    private volatile Subscription subscription;
    private final AtomicLong ticks = new AtomicLong();
    private final AtomicLong skippedTicks = new AtomicLong();
    private final AtomicLong lagEvents = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong lastNumber = new AtomicLong();
    private volatile long lastTickNanos;
    private volatile int lastBacklog;
    private volatile int lastDrained;

    protected TickActor(String name) {
        this(name, null);
    }

    protected TickActor(String name, TickDriver driver) {
        super(name);
        this.driver = driver;
    }

    // ------------------------------------------------------------------ Einstieg

    @Override
    protected Behavior onStart(ActorContext ctx) {
        if (driver != null) {
            subscription = driver.subscribe(ctx.self());
        }
        return Receive.of(
                Clause.on(Tick.class, this::runTick),
                Clause.any(this::handleMessage)
        );
    }

    @Override
    protected void onPostStop(ActorContext ctx, ExitReason reason) {
        Subscription current = subscription;
        if (current != null) {
            current.cancel();              // sonst Ticks an einen toten Raum
            subscription = null;
        }
        outbound.clear();
        onStop(reason);
    }

    /**
     * Ein Tick: Eingaben → Simulation → Outbound → Messen.
     */
    private Behavior runTick(Tick tick, ActorContext ctx) {
        // 1) Eingaben verarbeiten — reentrant, begrenzt, damit ein Flood den
        //    Tick nicht zumacht. Der Zell-Loop hat den Tick bereits entnommen.
        int drained = ctx.drainInbox(inboxBudget());
        lastDrained = drained;

        // 2) Simulation
        TickContext tc = new TickContext(tick, ctx, outbound, driver);
        long t0 = System.nanoTime();
        onTick(tick, tc);
        lastTickNanos = System.nanoTime() - t0;

        // 3) ein Paket pro Client
        outbound.flush(packer());

        // 4) messen
        ticks.incrementAndGet();
        skippedTicks.addAndGet(tick.skipped(lastNumber.get()));
        lastNumber.set(tick.number());
        int backlog = ctx.inboxSize();
        lastBacklog = backlog;
        if (backlog >= backlogLimit()) {
            lagEvents.incrementAndGet();
            onLagged(drained, backlog);
        }
        if (lastTickNanos > softBudgetNanos()) {
            overruns.incrementAndGet();
            onTickOverrun(tick, lastTickNanos);
        }
        return Behavior.NEXT;
    }

    private Behavior handleMessage(Object message, ActorContext ctx) {
        return onMessage(message, ctx);
    }

    /**
     * Ein {@code Tick} wird nie im laufenden Tick dispatcht: der Drain nimmt ihn
     * aus dem Postfach und stellt ihn fuer den naechsten Durchlauf zurueck.
     * Sonst liefe die Simulation zweimal pro Takt.
     */
    @Override
    protected boolean deferDuringDrain(Object message) {
        return message instanceof Tick;
    }

    // ------------------------------------------------------------------ Hooks

    /** Die eigentliche Simulation. Pflicht fuer jeden Raum. */
    protected abstract void onTick(Tick tick, TickContext tc);

    /** Alles ausser {@link Tick}. Default: nichts annehmen. */
    protected Behavior onMessage(Object message, ActorContext ctx) {
        return Behavior.UNHANDLED;
    }

    /** Nach dem Abbestellen, vor {@code outbound.clear()}. */
    protected void onStop(ExitReason reason) {
    }

    /** Das Postfach leert den Raum: Spieler kicken ist hier Spielcode. */
    protected void onLagged(int drained, int backlog) {
    }

    /** Soft-Budget ueberschritten — Messung, kein Fehler. */
    protected void onTickOverrun(Tick tick, long elapsedNanos) {
    }

    /** Wie viele Nachrichten ein Tick höchstens vor der Simulation verarbeitet. */
    protected int inboxBudget() {
        return 64;
    }

    /** Ab dieser Postfachgroesse gilt der Raum als verwaessert. */
    protected int backlogLimit() {
        return 256;
    }

    /** Ab dieser Tick-Dauer wird ein Overrun gezaehlt. */
    protected long softBudgetNanos() {
        return 8_000_000L;                   // 8 ms
    }

    /** Wie gepackt wird. Default: keine Serialisierung. */
    protected OutboundBuffer.Packer packer() {
        return OutboundBuffer.IDENTITY;
    }

    // ------------------------------------------------------------------ Messwerte

    public final OutboundBuffer outbound() {
        return outbound;
    }

    public final TickDriver driver() {
        return driver;
    }

    public final long ticks() {
        return ticks.get();
    }

    /** Verpasste Ticks, die dieser Raum nie gesehen hat. */
    public final long skippedTicks() {
        return skippedTicks.get();
    }

    public final long lagEvents() {
        return lagEvents.get();
    }

    public final long overruns() {
        return overruns.get();
    }

    public final long lastTickNanos() {
        return lastTickNanos;
    }

    public final int lastBacklog() {
        return lastBacklog;
    }

    public final int lastDrained() {
        return lastDrained;
    }

    public final long lastTickNumber() {
        return lastNumber.get();
    }
}
