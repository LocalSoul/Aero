package dev.localsoul.aero.actor.supervision;

import java.util.Objects;

/**
 * Beschreibung eines Kindes, fest verdrahtet (BEAM {@code ChildSpec}).
 *
 * <pre>{@code
 * ChildSpec.of("registry", Registry::new)
 *           .withType(RestartType.PERMANENT);
 * ChildSpec.of("worker", () -> new Worker(jobId), RestartType.TRANSIENT);
 * }</pre>
 *
 * <p><b>{@code permanent} vs. {@link RestartType}</b> sind zwei verschiedene
 * Fragen: {@code permanent} beantwortet „wer beendet dieses Kind von
 * aussen?" — ein {@code shutdown}-Kind beendet den Supervisor nicht, ein
 * normales Kind schon. {@link RestartType} beantwortet „wird es neu gestartet?".
 */
public record ChildSpec(String id, ChildFactory factory, RestartType restartType, boolean permanent) {

    public ChildSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(restartType, "restartType");
    }

    /** {@link RestartType#TRANSIENT}, Kind beendet bei {@code shutdown} den Supervisor. */
    public static ChildSpec of(String id, ChildFactory factory) {
        return new ChildSpec(id, factory, RestartType.TRANSIENT, false);
    }

    public static ChildSpec of(String id, ChildFactory factory, RestartType restartType) {
        return new ChildSpec(id, factory, restartType, false);
    }

    public ChildSpec withType(RestartType restartType) {
        return new ChildSpec(id, factory, restartType, permanent);
    }

    /** {@code shutdown} beendet den Supervisor nicht. */
    public ChildSpec asPermanent() {
        return new ChildSpec(id, factory, restartType, true);
    }
}
