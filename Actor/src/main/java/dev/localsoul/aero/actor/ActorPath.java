package dev.localsoul.aero.actor;

import java.util.Objects;

/**
 * Global eindeutige Adresse eines Actors, BEAM-Stil
 * {@code {Name, Node}}. Anonymes Ziel bekommt {@code ~<id>}.
 *
 * <pre>aero://gameserver-1/lobby      registriert
 * aero://gameserver-1/~42           anonym</pre>
 */
public record ActorPath(String system, String name) {

    public ActorPath {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(name, "name");
    }

    /** Anonym: {@code aero://<system>/~<id>}. */
    static ActorPath anonymous(String system, long id) {
        return new ActorPath(system, "~" + id);
    }

    public boolean anonymous() {
        return name.startsWith("~");
    }

    @Override
    public String toString() {
        return "aero://" + system + "/" + name;
    }
}
