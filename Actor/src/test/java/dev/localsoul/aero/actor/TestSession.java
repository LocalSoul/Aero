package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.session.Client;
import dev.localsoul.aero.actor.session.Interest;
import dev.localsoul.aero.actor.session.InterestSet;
import dev.localsoul.aero.actor.session.Session;
import dev.localsoul.aero.actor.session.Subscription;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Testdoppel fuer eine {@link Session}: zaehlt eingehende Nachrichten, meldet
 * sich optional im {@link InterestSet} an und kann ein gebundenes
 * {@link InterestSet} fuer Aufrufer sichtbar machen.
 */
public class TestSession extends Session {

    /** Was die Session mit einer eingehenden Nachricht macht. */
    public interface Inbound {
        Behavior on(ActorContext ctx, Object message, TestSession session);
    }

    private final List<Object> inbound = new CopyOnWriteArrayList<>();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicReference<Interest> moved = new AtomicReference<>();
    private final AtomicReference<ExitReason> stopReason = new AtomicReference<>();
    private final Inbound inboundHook;
    private final InterestSet index;
    private final Interest initialInterest;

    public TestSession(String name, Client client) {
        this(name, client, Interest.UNBOUNDED, null, null);
    }

    public TestSession(String name, Client client, Interest interest, InterestSet index,
                       Inbound inboundHook) {
        super(name, client, interest);
        this.initialInterest = interest == null ? Interest.UNBOUNDED : interest;
        this.index = index;
        this.inboundHook = inboundHook;
    }

    /** Interessengebundene Variante: bindet in {@link #onStart} sofort. */
    public static TestSession bound(String name, Client client, InterestSet index, Interest interest) {
        TestSession session = new TestSession(name, client, interest, index, null);
        return session;
    }

    @Override
    protected Behavior onStart(ActorContext ctx) {
        starts.incrementAndGet();
        if (index != null && !initialInterest.isUnbounded()) {
            bindInterest(ctx, index, initialInterest);
        }
        return super.onStart(ctx);
    }

    @Override
    protected Behavior onInbound(ActorContext ctx, Object message) {
        inbound.add(message);
        if (message instanceof Move move) {
            moveTo(ctx, Interest.at(move.x(), move.y(), move.z(),
                    indexedInterest().radius()));
            moved.set(indexedInterest());
        } else if (message instanceof Unbind) {
            unbind();
        }
        return inboundHook == null ? Behavior.NEXT : inboundHook.on(ctx, message, this);
    }

    /** Kommandos, damit Interest-Aenderungen im Actor-Thread laufen. */
    public record Move(double x, double y, double z) {
    }

    public record Unbind() {
    }

    @Override
    protected void onStopSession(ExitReason reason) {
        stops.incrementAndGet();
        stopReason.set(reason);
    }

    // ------------------------------------------------------------------ Test-API

    public InterestSet index() {
        return index;
    }

    /** Von der Test-Seite aufrufbar: Interessen wandern lassen. */
    public Interest moveTo(ActorContext ctx, Interest interest) {
        return moveInterest(ctx, index, interest);
    }

    public void unbind() {
        unbindInterest();
    }

    public List<Object> inbound() {
        return List.copyOf(inbound);
    }

    public int starts() {
        return starts.get();
    }

    public int stops() {
        return stops.get();
    }

    public ExitReason stopReason() {
        return stopReason.get();
    }

    public Interest lastMove() {
        return moved.get();
    }

    public ActorRef ref() {
        return self();
    }
}
