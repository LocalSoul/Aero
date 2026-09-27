package dev.localsoul.aero.actor;

/** BEAMs {@code badarg}: der Name ist im ActorSystem bereits vergeben. */
public class ActorNameTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ActorNameTakenException(String name) {
        super("actor name already registered: " + name);
    }
}
