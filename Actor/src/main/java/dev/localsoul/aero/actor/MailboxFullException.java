package dev.localsoul.aero.actor;

/**
 * Das Postfach des Ziel-Actors war voll (Policy {@link MailboxOverflow#FAIL}) —
 * der typische Fall eines haengenden Clients. Gehoert in einen Spielserver
 * <b>nicht</b> in den Hot Path geworfen, sondern geprueft:
 *
 * <pre>{@code
 * if (!session.tryTell(packet)) {
 *     lobby.kick(player, "too slow");     // gezielte Massnahme
 * }
 * }</pre>
 */
public class MailboxFullException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ActorRef target;
    private final int capacity;

    public MailboxFullException(ActorRef target, int capacity) {
        super("mailbox of '" + target.name() + "' is full (capacity " + capacity + ')');
        this.target = target;
        this.capacity = capacity;
    }

    public ActorRef target() {
        return target;
    }

    public int capacity() {
        return capacity;
    }
}
