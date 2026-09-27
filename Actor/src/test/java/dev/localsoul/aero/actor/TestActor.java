package dev.localsoul.aero.actor;

import java.util.List;

/** Kleiner Baustein fuer die Actor-Tests: startet mit einer vorgegebenen Behavior. */
public final class TestActor extends Actor {

    private final Starter starter;
    private final Unhandled unhandled;

    public interface Starter {
        Behavior start(ActorContext ctx);
    }

    public interface Unhandled {
        Behavior handle(ActorContext ctx, Object message);
    }

    public TestActor(String name, Starter starter) {
        this(name, starter, null);
    }

    public TestActor(String name, Starter starter, Unhandled unhandled) {
        super(name);
        this.starter = starter;
        this.unhandled = unhandled;
    }

    public TestActor(String name, Behavior behavior) {
        this(name, ctx -> behavior);
    }

    public TestActor(String name, MailboxOverflow overflow, int capacity, Starter starter) {
        super(name, overflow, capacity);
        this.starter = starter;
        this.unhandled = null;
    }

    @Override
    protected Behavior onStart(ActorContext ctx) {
        return starter.start(ctx);
    }

    @Override
    protected Behavior onUnhandled(ActorContext ctx, Object message) {
        return unhandled == null ? Behavior.NEXT : unhandled.handle(ctx, message);
    }

    /** Nimmt Nachrichten einer Liste an, optional mit Vorspann fuer {@code become}. */
    public static TestActor handling(String name, List<Receive.Clause> clauses) {
        return new TestActor(name, Receive.of(clauses));
    }
}
