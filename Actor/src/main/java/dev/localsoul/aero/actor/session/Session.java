package dev.localsoul.aero.actor.session;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.ExitReason;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.actor.tick.OutboundBuffer.Outbound;

/**
 * Ein Actor pro Client: die Bruecke zwischen Netzwerk und Framework.
 *
 * <p>Zwei Richtungen:
 * <ul>
 *   <li><b>Inbound</b> — der Netzwerk-Layer schickt Bytes/Objekte per
 *       {@code tell} in diese Session; {@link #onInbound} verarbeitet sie auf
 *       dem Virtual Thread des Clients.</li>
 *   <li><b>Outbound</b> — der Raum sammelt pro Tick alles fuer diesen Client in
 *       einem {@code OutboundBuffer} und schickt <b>eine</b> Nachricht hierher.
 *       Die Session ruft {@link Client#send} auf. Damit erzeugt 40 Delta-Pakete
 *       genau einen {@code send}-Aufruf pro Tick.</li>
 * </ul>
 *
 * <p>Die Session ist zugleich <b>Supervisions-Anker</b>: ein Supervisor
 * (Lobby, Connection-Manager) haelt sie als Kind, damit ein Absturz des
 * Verbindungs-Handlers sie neu startet, ohne den Client zu verlieren — der
 * {@link Client} bleibt, weil er nicht Teil des Actors ist.
 *
 * <p>Die Interest-Bindung ist optional: {@link #bindInterest(InterestSet)}
 * registriert diese Session im AoI-Index und meldet sie beim Stopp wieder ab.
 */
public abstract class Session extends Actor {

    private final Client client;
    private volatile Interest interest;
    private volatile Subscription interestSubscription;
    private volatile long sent;
    private volatile long received;

    protected Session(String name, Client client) {
        this(name, client, Interest.UNBOUNDED);
    }

    protected Session(String name, Client client, Interest interest) {
        super(name);
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        this.client = client;
        this.interest = interest == null ? Interest.UNBOUNDED : interest;
    }

    // ------------------------------------------------------------------ Zustand

    public Client client() {
        return client;
    }

    /** Aktuell gebundenes Interesse. */
    public Interest interest() {
        return interest;
    }

    /** Anzahl an den Client gesendeter {@code send}-Aufrufe. */
    public long packetsSent() {
        return sent;
    }

    /** Anzahl eingehender Nachrichten. */
    public long messagesReceived() {
        return received;
    }

    // ------------------------------------------------------------------ Interest

    /**
     * Diese Session im AoI-Index registrieren. Die Abmeldung passiert
     * automatisch beim Stopp — sonst leakte jede Verbindung eine Zelle.
     *
     * <p>Nur im Actor-Thread aufrufen: der Index gehoert dem Raum.
     */
    protected Subscription bindInterest(ActorContext ctx, InterestSet index, Interest newInterest) {
        ActorRef self = ctx.self();
        unbindInterest();
        interest = newInterest == null ? Interest.UNBOUNDED : newInterest;
        interestSubscription = Subscription.of(self, interest, () -> index.remove(self));
        index.put(self, interest);
        return interestSubscription;
    }

    /** Aktuelle Interest-Bindung, falls vorhanden. */
    protected Subscription interestSubscription() {
        return interestSubscription;
    }

    /** Das im AoI-Index hinterlegte Interesse — der Raum liest es beim Broadcast. */
    public Interest indexedInterest() {
        Subscription current = interestSubscription;
        return current == null ? interest : current.interest();
    }

    /**
     * Interessen neu setzen, weil der Spieler weiterlief. Die Zelle im Index wird
     * mitgezogen — der Raum muss das nicht von Hand tun, sonst veraltete
     * Eintraege. Die Handle bleibt dieselbe, damit die Abmeldung spaeter greift.
     *
     * <p>Nur im Actor-Thread aufrufen.
     *
     * @return die geaenderte Interest, oder {@code null} wenn ungebunden
     */
    protected Interest moveInterest(ActorContext ctx, InterestSet index, Interest newInterest) {
        Subscription current = interestSubscription;
        if (current == null) {
            return null;
        }
        interest = newInterest == null ? Interest.UNBOUNDED : newInterest;
        current.withInterest(interest);
        index.move(ctx.self(), interest);
        return interest;
    }

    /** Interest-Bindung loesen, ohne den Actor zu stoppen. */
    protected void unbindInterest() {
        Subscription current = interestSubscription;
        if (current != null) {
            current.cancel();
            interestSubscription = null;
        }
    }

    // ------------------------------------------------------------------ Lifecycle

    @Override
    protected Behavior onStart(ActorContext ctx) {
        return Receive.of(
                Clause.on(Outbound.class, this::deliver),
                Clause.any(this::handleInbound)
        );
    }

    @Override
    protected void onPostStop(ActorContext ctx, ExitReason reason) {
        unbindInterest();                       // Interest-Zelle freigeben
        onStopSession(reason);
        client.close();
    }

    /**
     * Ausgehende Sammelnachricht: genau ein {@code send} pro Client und Tick.
     * Die Reihenfolge der Pakete bleibt erhalten.
     */
    private Behavior deliver(Outbound outbound, ActorContext ctx) {
        client.send(outbound.payload());
        sent++;
        return Behavior.NEXT;
    }

    private Behavior handleInbound(Object message, ActorContext ctx) {
        received++;
        return onInbound(ctx, message);
    }

    /** Jede Nicht-{@code Outbound}-Nachricht: Spielcode entscheidet. */
    protected abstract Behavior onInbound(ActorContext ctx, Object message);

    /** Vor dem Stopp, nach dem Loesen der Interest-Bindung. */
    protected void onStopSession(ExitReason reason) {
    }

    @Override
    public String toString() {
        return "Session[" + name() + " client=" + client.id() + ' ' + interest + ']';
    }
}
