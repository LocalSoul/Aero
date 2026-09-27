package dev.localsoul.aero.actor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Nachrichtenkanal zu einem Actor, BEAM-Stil {@code pid()}.
 *
 * <p>Alle Methoden sind <b>nicht blockierend</b> und threadsicher. {@code tell}
 * wirft <b>nie</b> — nicht bei vollem Postfach, nicht bei totem Actor. Wer diese
 * Information braucht, nutzt {@link #tryTell}.
 */
public interface ActorRef {

    /**
     * Asynchron senden (BEAM {@code !}).
     *
     * <p>Wirft <b>nie</b>: ein volles Postfach oder ein toter Actor sind normale
     * Betriebszustaende, keine Fehler. Ein haengender Client darf keinen
     * Absender-Thread toeten — das ist der Unterschied zwischen „ein Raum ist
     * ueberlastet" und „der ganze Spielserver steht".
     */
    void tell(Object message);

    /** Wie {@link #tell}, aber mit explizitem Absender. */
    void tell(Object message, ActorRef sender);

    /**
     * Wie {@link #tell}, aber mit Rueckmeldung.
     *
     * @return {@code false}, wenn das Postfach voll (Policy {@code FAIL}) oder der
     *         Actor tot ist. Der Aufrufer kann dann gezielt reagieren, etwa den
     *         Client kicken.
     */
    boolean tryTell(Object message);

    /**
     * Anfrage mit Antwort (BEAM-`ask`-Aequivalent, mit Timeout).
     *
     * <p>Die Future wird im Thread des <b>aufrufenden</b> Actors vervollstaendigt,
     * auch im Erfolgsfall: {@code future.thenAccept(...)} laeuft also in der
     * eigenen Mailbox, nicht auf einem Timer-Thread.
     *
     * @throws MailboxFullException wenn das Postfach voll ist; der Aufrufer lebt weiter
     */
    CompletableFuture<Object> ask(Object message, Duration timeout);

    default CompletableFuture<Object> ask(Object message) {
        return ask(message, Duration.ofSeconds(5));
    }

    /** Wie {@link #ask(Object, Duration)}, mit explizitem Absender (interne Nutzung). */
    CompletableFuture<Object> ask(Object message, ActorRef sender, Duration timeout);

    /** {@code true}, wenn der Actor lebt. */
    boolean isAlive();

    /** Global eindeutiger Name. */
    String name();

    /** Vollstaendige Adresse. */
    ActorPath path();

    /** An ein {@link ActorSystem} gebunden? Sonst ist {@code name()} {@code ~id}. */
    boolean anonymous();
}
