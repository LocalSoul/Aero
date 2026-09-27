package dev.localsoul.aero.actor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Minimale {@link ActorRef} fuer Tests, die keinen echten Actor brauchen. */
public final class TestRefs {

    private TestRefs() {
    }

    /** Referenz ohne Postfach, ohne Zelle — nur als Absender oder Identitaet. */
    public static final class FakeRef implements ActorRef {

        private final String name;
        private final boolean anonymous;
        private volatile boolean alive = true;
        private volatile Object lastMessage;
        private volatile int tellCount;
        private volatile java.util.function.Consumer<Object> onMessage;
        private volatile boolean rejecting;
        private volatile boolean exploding;

        public FakeRef(String name) {
            this(name, true);
        }

        public FakeRef(String name, boolean anonymous) {
            this.name = name;
            this.anonymous = anonymous;
        }

        @Override public void tell(Object message) {
            tell(message, null);
        }

        @Override public void tell(Object message, ActorRef sender) {
            lastMessage = message;
            tellCount++;
        }

        @Override public boolean tryTell(Object message) {
            if (!alive || rejecting) {
                return false;
            }
            if (exploding) {
                throw new IllegalStateException(name + " explodiert");
            }
            if (onMessage != null) {
                onMessage.accept(message);
            }
            tell(message);
            return true;
        }

        /** Beobachtet jede Zustellung, ohne den Ref zu ersetzen. */
        public FakeRef onMessage(java.util.function.Consumer<Object> hook) {
            this.onMessage = hook;
            return this;
        }

        /** Simuliert ein volles Postfach: {@code tryTell} liefert immer {@code false}. */
        public FakeRef rejecting() {
            this.rejecting = true;
            return this;
        }

        /** Blockiert jede Zustellung — erzeugt reproduzierbar Lag auf der Pump-Schleife. */
        public static FakeRef slow(Duration delay) {
            FakeRef ref = new FakeRef("slow");
            ref.onMessage(m -> {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            return ref;
        }

        /** Simuliert einen Subscriber, der beim Zustellen wirft. */
        public FakeRef exploding() {
            this.exploding = true;
            return this;
        }

        @Override public CompletableFuture<Object> ask(Object message, Duration timeout) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            future.complete(null);
            return future;
        }

        @Override public CompletableFuture<Object> ask(Object message, ActorRef sender, Duration timeout) {
            return ask(message, timeout);
        }

        @Override public boolean isAlive() { return alive; }
        @Override public String name() { return name; }
        @Override public ActorPath path() { return new ActorPath("test", name); }
        @Override public boolean anonymous() { return anonymous; }
        @Override public String toString() { return "FakeRef(" + name + ')'; }

        public void kill() { alive = false; }
        public Object lastMessage() { return lastMessage; }
        public int tellCount() { return tellCount; }
    }

    public static ActorRef named(String name) {
        return new FakeRef(name, false);
    }
}
