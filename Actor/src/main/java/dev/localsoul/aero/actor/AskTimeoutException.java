package dev.localsoul.aero.actor;

import java.io.Serial;
import java.time.Duration;

/** {@code ask} lief ab, bevor der Ziel-Actor geantwortet hat. */
public class AskTimeoutException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AskTimeoutException(String message) {
        super(message);
    }

    public static AskTimeoutException after(ActorRef target, Duration timeout) {
        return new AskTimeoutException("ask to '" + target.name() + "' timed out after " + timeout.toMillis() + "ms");
    }
}
