package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorRef;
import java.util.Objects;

import static dev.localsoul.aero.actor.internal.MessageKind.SYSTEM;
import static dev.localsoul.aero.actor.internal.MessageKind.USER;

/**
 * Verpackte Nachricht. Enthaelt die Absender-Referenz fuer {@code reply} und
 * die Korrelation zu einer laufenden {@link Call}.
 *
 * <p>Interner Typ: keine Stabilitaetsgarantie.
 */
public record Envelope(Object message, ActorRef sender, MessageKind kind, Call call) {

    public Envelope {
        Objects.requireNonNull(message, "message");
    }

    public static Envelope user(Object message, ActorRef sender) {
        return new Envelope(message, sender, USER, null);
    }

    public static Envelope call(Object message, ActorRef sender, Call call) {
        return new Envelope(message, sender, USER, Objects.requireNonNull(call, "call"));
    }

    public static Envelope system(Object signal) {
        return new Envelope(signal, null, SYSTEM, null);
    }

    public boolean isSystem() {
        return kind == SYSTEM;
    }

    public boolean isCall() {
        return call != null;
    }

    @Override
    public String toString() {
        return (kind == SYSTEM ? "#" : "") + message + (sender == null ? "" : " <- " + sender.name());
    }
}
