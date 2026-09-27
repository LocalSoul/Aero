package dev.localsoul.aero.actor;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Unveränderliche Klauselliste mit BEAM-`receive`-Semantik:
 * <b>die erste passende Klausel gewinnt</b>, eine nicht passende Guard
 * überspringt die Klausel (nicht die ganze Liste).
 *
 * <pre>{@code
 * Receive.of(
 *     Clause.on(Ping.class,    (Ping p,    ActorContext c) -> c.reply("pong")),
 *     Clause.on(Deposit.class, (Deposit d, ActorContext c) -> c.reply(process(d)))
 * );
 * }</pre>
 *
 * <p>Liefert keine Klausel {@link Behavior#UNHANDLED}, wird das an
 * {@code ActorCell} weitergereicht, das {@link Actor#onUnhandled} aufruft.
 */
public final class Receive implements Behavior {

    private final List<Clause> clauses;

    private Receive(List<Clause> clauses) {
        this.clauses = List.copyOf(clauses);
    }

    public static Behavior of(Clause... clauses) {
        return new Receive(List.of(clauses));
    }

    public static Behavior of(List<Clause> clauses) {
        return new Receive(clauses);
    }

    /**
     * {@code become(Behavior, List<Clause>)}: gemeinsamer Prefix plus neues
     * Verhalten (BEAM {@code become(Behavior, [Clause])}).
     *
     * <p>Ist das neue Verhalten selbst eine {@code Receive}, werden die
     * Klausellisten verketten — eine Nachricht durchlaeuft also den Prefix
     * <b>und</b> das neue Verhalten, und gewinnen tut das <b>fruehere</b>.
     * Sonst entscheidet der Prefix, und was er nicht kennt, geht ans neue
     * Verhalten.
     */
    public static Behavior of(List<Clause> prefix, Behavior next) {
        if (prefix.isEmpty()) {
            return next;
        }
        if (next instanceof Receive receive) {
            List<Clause> merged = new java.util.ArrayList<>(prefix);
            merged.addAll(receive.clauses);
            return new Receive(merged);
        }
        Behavior head = new Receive(prefix);
        return new Behavior() {
            @Override public Behavior invoke(Object message, ActorContext ctx) {
                Behavior result = head.invoke(message, ctx);
                return result != UNHANDLED ? result : next.invoke(message, ctx);
            }
            @Override public String toString() {
                return "Receive" + prefix + " then " + next;
            }
        };
    }

    /** Leere Liste: jede Nachricht ist „unhandled". */
    public static Behavior empty() {
        return new Receive(List.of());
    }

    public List<Clause> clauses() {
        return clauses;
    }

    @Override
    public Behavior invoke(Object message, ActorContext ctx) {
        for (int i = 0; i < clauses.size(); i++) {
            Clause clause = clauses.get(i);
            if (!clause.matches(message, ctx)) {
                continue;                       // Guard nicht erfuellt -> naechste Klausel
            }
            Behavior result = clause.apply(message, ctx);
            if (result != Behavior.UNHANDLED) {
                return result;
            }
        }
        return Behavior.UNHANDLED;
    }

    @Override
    public String toString() {
        return "Receive" + clauses;
    }

    // ---------------------------------------------------------------- body

    @FunctionalInterface
    public interface Body {
        Behavior apply(Object message, ActorContext ctx);
    }

    @FunctionalInterface
    public interface TypedBody<T> {
        Behavior apply(T message, ActorContext ctx);
    }

    // -------------------------------------------------------------- clause

    /** Eine `receive`-Klausel: optionale Bedingung plus Rumpf. */
    public static final class Clause {

        private final Predicate<Object> guard;
        private final Body body;

        private Clause(Predicate<Object> guard, Body body) {
            this.guard = guard;
            this.body = body;
        }

        /** Trifft auf Instanzen von {@code type} zu. */
        public static Clause of(Class<?> type, Body body) {
            Objects.requireNonNull(type, "type");
            return new Clause(type::isInstance, body);
        }

        /** Wie {@link #of}, aber mit typisiertem Rumpf — bequemer als Casts. */
        public static <T> Clause on(Class<T> type, TypedBody<T> body) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(body, "body");
            return new Clause(type::isInstance, (m, c) -> body.apply(type.cast(m), c));
        }

        /** Trifft auf alles zu. */
        public static Clause any(Body body) {
            return new Clause(m -> true, body);
        }

        /** Trifft zu, wenn {@code guard} das Erfuellt. */
        public static Clause when(Predicate<Object> guard, Body body) {
            Objects.requireNonNull(guard, "guard");
            return new Clause(guard, body);
        }

        /** Zusaetzliche Bedingung per UND. */
        public Clause and(Predicate<Object> additionalGuard) {
            Objects.requireNonNull(additionalGuard, "additionalGuard");
            Predicate<Object> combined = m -> guard.test(m) && additionalGuard.test(m);
            return new Clause(combined, body);
        }

        public boolean matches(Object message, ActorContext ctx) {
            return guard.test(message);
        }

        public Behavior apply(Object message, ActorContext ctx) {
            return body.apply(message, ctx);
        }

        @Override
        public String toString() {
            return "Clause" + guard;
        }
    }
}
