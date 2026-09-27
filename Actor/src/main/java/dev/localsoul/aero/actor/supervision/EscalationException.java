package dev.localsoul.aero.actor.supervision;

import dev.localsoul.aero.actor.ExitReason;

import java.io.Serial;

/** Der Fehler eines Kindes liess sich nicht beheben — der Supervisor gibt auf. */
public class EscalationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int restarts;
    private final transient ExitReason childReason;

    public EscalationException(String message, int restarts, ExitReason childReason) {
        super(message + " (restarts=" + restarts + ", lastReason=" + childReason + ')');
        this.restarts = restarts;
        this.childReason = childReason;
    }

    public int restarts() {
        return restarts;
    }

    public ExitReason childReason() {
        return childReason;
    }
}
