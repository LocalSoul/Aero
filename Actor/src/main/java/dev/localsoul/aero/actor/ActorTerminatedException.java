package dev.localsoul.aero.actor;

import java.io.Serial;

/**
 * Ein angefragter Actor ist gestorben, bevor er antworten konnte. Der
 * {@link ExitReason} im Payload sagt, warum.
 */
public class ActorTerminatedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ExitReason reason;

    public ActorTerminatedException(String message, ExitReason reason) {
        super(message + " (" + reason + ')');
        this.reason = reason;
    }

    public ExitReason reason() {
        return reason;
    }

    public static ActorTerminatedException whileHandling(ActorRef target, ExitReason reason) {
        return new ActorTerminatedException("actor died while handling the request: " + target.name(), reason);
    }
}
