package dev.localsoul.aero.actor.tick;

import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.session.Subscription;
import java.time.Duration;

/**
 * Was ein {@code TickActor} waehrend der Simulation braucht: den Actor-Kontext,
 * den Outbound-Puffer und die Uhr des Ticks.
 *
 * <p>Wird pro Tick neu erzeugt und ist danach unbrauchbar — wie {@code ctx}
 * selbst ist es <b>thread-gebunden</b> an den Raum-Actor.
 */
public final class TickContext {

    private final Tick tick;
    private final ActorContext actor;
    private final OutboundBuffer outbound;
    private final TickDriver driver;
    private final long startedAtNanos;

    public TickContext(Tick tick, ActorContext actor, OutboundBuffer outbound, TickDriver driver) {
        this.tick = tick;
        this.actor = actor;
        this.outbound = outbound;
        this.driver = driver;
        this.startedAtNanos = System.nanoTime();
    }

    /** Der aktuelle Tick. */
    public Tick tick() {
        return tick;
    }

    /** Wie lange die Simulation dieses Ticks schon laeuft. */
    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    /** Der Raum-Actor selbst. */
    public ActorContext actor() {
        return actor;
    }

    /** Pro-Client-Aggregation; nach der Simulation wird geflusht. */
    public OutboundBuffer outbound() {
        return outbound;
    }

    /** Kurzbform: {@code outbound().send(client, packet)}. */
    public void send(ActorRef client, Object packet) {
        outbound.send(client, packet);
    }

    /** Kurzbform fuer {@link OutboundBuffer#sendAll}. */
    public void sendAll(Iterable<? extends ActorRef> targets, Object packet) {
        outbound.sendAll(targets, packet);
    }

    /**
     * Den Raum selbst im Driver anmelden — fuer den Fall, dass er pro Tick
     * abwaehlt oder ein zweites Abo braucht. Kein Fehler, wenn kein Driver
     * hinterlegt ist: der Raum ist dann manuell getaktet.
     */
    public Subscription subscribe() {
        if (driver == null) {
            return null;
        }
        return driver.subscribe(actor.self());
    }

    /** Ein weiteres Ziel an den Driver anbinden (1 Nachricht pro Tick). */
    public Subscription subscribe(ActorRef target) {
        if (driver == null) {
            return null;
        }
        return driver.subscribe(target);
    }
}