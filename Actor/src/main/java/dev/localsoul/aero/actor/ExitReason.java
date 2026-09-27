package dev.localsoul.aero.actor;

/**
 * Grund, aus dem ein Actor beendet wurde. Abbildung der BEAM/OTP-Exit-Reason-Werte.
 *
 * <pre>
 *   Normal     :normal
 *   Shutdown   :shutdown        (Supervisor fährt Kind geordnet herunter)
 *   Failure    {:error, Reason} unbehandelte Ausnahme
 *   Terminated :killed          (hartes Abschalten, Postfach wird verworfen)
 * </pre>
 *
 * Die Unterscheidung ist nicht kosmetisch: nur {@link #abnormal()} Reason
 * werden an {@code link}-Peers propagiert, und nur {@link Failure} und
 * {@link Terminated} loesen bei {@code transient} Children einen Restart aus.
 */
public sealed interface ExitReason {

    /** {@code true} fuer {@link Failure} und {@link Terminated}. */
    boolean abnormal();

    /** {@code :normal} — Peer bleibt bei {@code link} unberuehrt. */
    record Normal() implements ExitReason {
        @Override public boolean abnormal() { return false; }
        @Override public String toString() { return "normal"; }
    }

    /** {@code :shutdown} — geordneter Shutdown durch einen Supervisor. */
    record Shutdown() implements ExitReason {
        @Override public boolean abnormal() { return false; }
        @Override public String toString() { return "shutdown"; }
    }

    /** Unbehandelte Ausnahme im Actor-Code oder in {@code start}/{@code postStop}. */
    record Failure(Throwable cause) implements ExitReason {
        public Failure {
            java.util.Objects.requireNonNull(cause, "cause");
        }
        @Override public boolean abnormal() { return true; }
        @Override public String toString() { return "failure(" + cause + ")"; }
    }

    /** {@code :killed} — hartes Abschalten ohne Drain. */
    record Terminated() implements ExitReason {
        @Override public boolean abnormal() { return true; }
        @Override public String toString() { return "terminated"; }
    }

    static ExitReason normal()     { return new Normal(); }
    static ExitReason shutdown()   { return new Shutdown(); }
    static ExitReason failure(Throwable cause) { return new Failure(cause); }
    static ExitReason terminated() { return new Terminated(); }

    /** {@link Failure} bei unchecked Throwable, sonst {@link #normal()}. */
    static ExitReason of(Throwable t) {
        if (t instanceof RuntimeException e) {
            return new Failure(e);
        }
        if (t instanceof Error e) {
            return new Failure(e);
        }
        return normal();                                  // geprueft: vom Actor kaum moeglich
    }
}
