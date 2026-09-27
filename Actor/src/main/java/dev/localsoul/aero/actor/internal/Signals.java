package dev.localsoul.aero.actor.internal;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ExitReason;

/** Systemnachrichten der Laufzeit. Erreichen nie eine Benutzerklausel. */
public final class Signals {

    private Signals() {
    }

    /**
     * Exit-Signal eines gelinkten Actors. Nur abnormal bei non-trapping,
     * jeder Reason bei trapping (Supervisor).
     */
    public record Exit(ActorRef target, ExitReason reason) {
    }

    /**
     * Geordneter Stopp: ans Postfach-Ende gelegt, alles davor wird noch
     * verarbeitet (Drain).
     */
    public record Terminate(ExitReason reason) {
    }

    /**
     * Abschluss einer {@link Call} oder eines {@code runBlocking}. Wird ueber die
     * Mailbox des <b>Aufrufer</b>-Actors zugestellt, damit der Callback im
     * Aufrufer-Thread laeuft (BEAM-Prozess-Semantik).
     */
    public record CallResult(Call call, Object value, Throwable error) {
    }
}
