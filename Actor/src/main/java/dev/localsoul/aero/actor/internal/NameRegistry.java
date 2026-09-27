package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorNameTakenException;
import dev.localsoul.aero.actor.ActorRef;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Namensaufloesung eines ActorSystems (BEAM-Prozessregister). */
public final class NameRegistry {

    private final Map<String, ActorRef> names = new ConcurrentHashMap<>();

    /**
     * @throws ActorNameTakenException wenn der Name bereits belegt ist (BEAM {@code badarg})
     */
    public ActorRef register(String name, ActorRef ref) {
        ActorRef previous = names.putIfAbsent(name, ref);
        if (previous != null && previous != ref) {
            throw new ActorNameTakenException(name);
        }
        return ref;
    }

    public void unregister(String name, ActorRef ref) {
        names.remove(name, ref);
    }

    public Optional<ActorRef> whereIs(String name) {
        return Optional.ofNullable(names.get(name));
    }

    public boolean isRegistered(String name) {
        return names.containsKey(name);
    }

    public Collection<String> names() {
        return List.copyOf(names.keySet());
    }

    public int size() {
        return names.size();
    }
}
