package dev.localsoul.aero.actor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Was ein Actor im laufenden Betrieb tun kann. Erhaelt nur der Actor, und nur
 * auf seinem eigenen Virtual Thread — {@code ctx} ist damit <b>thread-gebunden</b>
 * und darf nicht in andere Threads oder in {@code static}-Felder entkommen.
 *
 * <p>Alle Methoden duerfen im laufenden {@code receive} aufgerufen werden und
 * sind <b>nicht blockierend</b>.
 */
public interface ActorContext {

    // ---------------------------------------------------------- Identitaet

    /** Eigene Referenz. */
    ActorRef self();

    /**
     * Referenz des Aufrufers, falls die Nachricht von einem Actor kam; sonst
     * {@code null} (Netzwerkthread, Timer, Spielserver-Hauptthread).
     */
    ActorRef sender();

    /** Zugehoeriges ActorSystem. */
    ActorSystem system();

    /** Anzahl seit Start verarbeiteter Nachrichten — fuer Tests und Debug. */
    long messagesProcessed();

    // ------------------------------------------------------- Zustandswechsel

    /**
     * Neues Verhalten ab der naechsten Nachricht (BEAM {@code become/1}).
     * Wirkt <b>nicht</b> auf die gerade laufende Nachricht.
     */
    void become(Behavior next);

    /** {@code become} mit gemeinsamem Prefix (BEAM {@code become(Behavior, List)}). */
    void become(Behavior next, java.util.List<Receive.Clause> sharedPrefix);

    /**
     * Geordnet beenden: das Postfach wird noch geleert, alles davor verarbeitet.
     * Nachrichten nach {@code stop} werden abgelehnt.
     */
    void stop();

    /** Wie {@link #stop()}, aber mit abweichendem Grund (fuer den Supervisor sichtbar). */
    void stop(ExitReason reason);

    /** Sofort beenden, Postfach verwerfen. Kein Drain. */
    void kill();

    boolean isStopped();

    // --------------------------------------------------------- Kommunikation

    /** Nach {@code target} senden. Wirft nie. */
    void tell(ActorRef target, Object message);

    /**
     * Wie {@link #tell}, aber mit Rueckmeldung.
     *
     * @return {@code false}, wenn das Postfach voll oder {@code target} tot ist
     */
    boolean tryTell(ActorRef target, Object message);

    /**
     * Anfrage mit Antwort. Die Future wird im Thread dieses Actors erfuellt.
     *
     * @throws MailboxFullException wenn das Postfach voll ist
     */
    CompletableFuture<Object> ask(ActorRef target, Object message, Duration timeout);

    /**
     * Antwort an {@link #sender()}. Nur innerhalb eines {@code ask}-Handlers
     * sinnvoll. Geht an den Aufrufer-Thread, nicht an dessen Mailbox.
     */
    void reply(Object value);

    /**
     * Wie {@link #reply(Object)}, aber der Absender stirbt daran
     * (BEAM {@code {stop, reply}}).
     */
    void replyAndStop(Object value);

    // -------------------------------------------------------------- Links

    /**
     * Der Tod von {@code target} erzeugt ein {@code Exit}-Systemsignal in
     * diesem Actor — <b>nur</b> bei abnormalem Grund, ausser dieses Actor
     * trapped (siehe {@link ChildOwner}).
     *
     * <p>Wie in BEAM eine Verknuepfung <b>symmetrisch</b>: sobald einer der
     * beiden stirbt, meldet er es dem anderen.
     */
    void link(ActorRef target);

    /**
     * Ziel wird <b>beobachtet</b>, nicht verknuepft: sein Tod beendet diesen
     * Actor nicht. BEAM-`monitor` liefert ein `DOWN`-Signal in die Mailbox;
     * hier ist es die direkt zugaengliche {@code deathFuture}.
     */
    java.util.concurrent.CompletableFuture<ExitReason> monitor(ActorRef target);

    /**
     * Link trennen. Wirft nicht, wenn es keine Verknuepfung gab.
     */
    void unlink(ActorRef target);

    // ------------------------------------------------------- Zeit & Auftraege

    /**
     * Nach {@code delay} eine Nachricht an sich selbst (BEAM {@code send_after}).
     * Nutzt einen {@code ScheduledExecutorService} des Systems. Der Timer laeuft
     * auf einem Platform Thread, die Zustellung im eigenen Postfach.
     */
    void schedule(Object message, Duration delay);

    /**
     * Blockierende Arbeit aus dem Handler auslagern (Netz-IO, DB, Datei).
     *
     * <p>Der Handler gibt die Kontrolle sofort ab: das Postfach nimmt wieder
     * Nachrichten an, das Ergebnis kommt als {@code Signals.CallResult} in die
     * eigene Mailbox zurueck und wird dort ausgewertet. Damit bricht ein
     * langsamer Call <b>keinen</b> Raum.
     *
     * <p>Der Handler darf danach nichts weiter tun — der Code nach
     * {@code runBlocking} laeuft in einem anderen Turn (BEAM-Analogon:
     * {@code receive after 0 -> …}).
     */
    void runBlocking(java.util.concurrent.Callable<Object> work);

    /**
     * Wie {@link #runBlocking}, aber das Ergebnis wird zusaetzlich per
     * {@code onResult} ausgewertet (z. B. {@code ask}).
     */
    void runBlocking(java.util.concurrent.Callable<Object> work,
                     java.util.function.BiConsumer<Object, Throwable> onResult);

    // -------------------------------------------------------------- Introspektion

    /** Aktuelle Groesse des Postfachs — Grundlage fuer {@code onLagged}. */
    int mailboxSize();

    /** Wie {@link #mailboxSize()}, aber unter dem Namen des Tick-Pfads. */
    default int inboxSize() {
        return mailboxSize();
    }

    /**
     * Reentrant: bis zu {@code budget} zusaetzliche Nachrichten direkt
     * entnehmen und dispatchen — <b>ohne</b> zurueck in die Schleife zu gehen.
     *
     * <p>Der Zell-Loop hat genau eine Nachricht entnommen; dieses Budget
     * entnimmt weitere. Der Weg ist derselbe, {@code ctx.sender()} und
     * {@code currentMessage} bleiben thanks eines kleinen Envelope-Stacks in der
     * Zelle korrekt. Verschachtelung ist auf die Budget-Tiefe begrenzt.
     *
     * <p>Systemsignale (Terminate, Exit, CallResult) werden mitbehandelt: ein
     * {@code terminate} waehrend des Drains beendet den Actor sofort.
     *
     * @return Anzahl tatsaechlich dispatchter Nachrichten
     */
    int drainInbox(int budget);

    int mailboxCapacity();

    /** Anzahl seit Start beobachteter abnormaler Toedesfaelle. */
    long crashCount();
}
