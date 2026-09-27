package dev.localsoul.aero.actor.supervision;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Unveraenderliche Supervisor-Konfiguration, BEAM {@code SupervisorSpec}.
 *
 * <p>Die Intensitaetsgrenze ist das wichtigste Feld: ohne sie erzeugt ein
 * haengendes Kind eine Neustart-Schleife, die den ganzen Spielserver mit CPU
 * und Log-Nachrichten zuklebt. {@code maxRestarts} in {@code maxRestartsWithin}
 * zu ueberschreiten heisst: der Fehler ist nicht transient, also eskaliert der
 * Supervisor an <b>seinen</b> Supervisor.
 *
 * <pre>{@code
 * SupervisorSpec.builder()
 *     .strategy(RestartStrategy.REST_FOR_ONE)
 *     .maxRestarts(3, Duration.ofSeconds(5))
 *     .child(ChildSpec.of("lobby", Lobby::new, RestartType.PERMANENT).asPermanent())
 *     .child(ChildSpec.of("session-store", SessionStore::new))
 *     .build();
 * }</pre>
 */
public final class SupervisorSpec {

    private final RestartStrategy strategy;
    private final List<ChildSpec> children;
    private final int maxRestarts;
    private final Duration maxRestartsWithin;
    private final boolean linkChildren;
    private final String shutdownReason;

    private SupervisorSpec(Builder builder) {
        this.strategy = builder.strategy;
        this.children = List.copyOf(builder.children);
        this.maxRestarts = builder.maxRestarts;
        this.maxRestartsWithin = builder.maxRestartsWithin;
        this.linkChildren = builder.linkChildren;
        this.shutdownReason = builder.shutdownReason;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RestartStrategy strategy() {
        return strategy;
    }

    public List<ChildSpec> children() {
        return children;
    }

    public int maxRestarts() {
        return maxRestarts;
    }

    public Duration maxRestartsWithin() {
        return maxRestartsWithin;
    }

    public boolean linkChildren() {
        return linkChildren;
    }

    public String shutdownReason() {
        return shutdownReason;
    }

    public static final class Builder {

        private RestartStrategy strategy = RestartStrategy.ONE_FOR_ONE;
        private final List<ChildSpec> children = new ArrayList<>();
        private int maxRestarts = 3;
        private Duration maxRestartsWithin = Duration.ofSeconds(5);
        private boolean linkChildren = true;
        private String shutdownReason = "supervisor stopped";

        public Builder strategy(RestartStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        public Builder child(ChildSpec spec) {
            this.children.add(Objects.requireNonNull(spec, "spec"));
            return this;
        }

        public Builder children(List<ChildSpec> specs) {
            this.children.addAll(specs);
            return this;
        }

        /** Standard: 3 Neustarts in 5 Sekunden. */
        public Builder maxRestarts(int maxRestarts, Duration within) {
            if (maxRestarts < 1) {
                throw new IllegalArgumentException("maxRestarts must be >= 1");
            }
            this.maxRestarts = maxRestarts;
            this.maxRestartsWithin = Objects.requireNonNull(within, "within");
            return this;
        }

        /** Standard: true. Ohne Link sieht ein Supervisor den Tod seiner Kinder nicht. */
        public Builder linkChildren(boolean linkChildren) {
            this.linkChildren = linkChildren;
            return this;
        }

        /** Absender-Adresse, die in {@code shutdown} gesetzt wird. */
        public Builder shutdownReason(String shutdownReason) {
            this.shutdownReason = shutdownReason;
            return this;
        }

        public SupervisorSpec build() {
            return new SupervisorSpec(this);
        }
    }
}
