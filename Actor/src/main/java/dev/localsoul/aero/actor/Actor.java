package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.ActorCell;

/**
 * Basisklasse fuer Actors. Nimmt einem Actor alles ab, was nicht zu seiner
 * eigentlichen Logik gehoert: Erzeugung, Namensregistrierung, Lebenszyklus.
 *
 * <p>Ein Actor besitzt <b>genau einen</b> Virtual Thread, der sein Postfach
 * verarbeitet. Deshalb braucht keine Methode hier {@code synchronized}: der
 * Zustand des Actors ist per Definition single-threaded. Was zwei Actors
 * gemeinsam nutzen, gehoert in einen dritten Actor oder in ein
 * {@code Immutable}-Feld.
 *
 * <pre>{@code
 * public final class Player extends Actor {
 *     private Health health = new Health(100);
 *
 *     public Player() { super("player-7", MailboxOverflow.FAIL, 1024); }
 *
 *     @Override protected Behavior onStart(ActorContext ctx) {
 *         return Receive.of(
 *                 Clause.on(ApplyDamage.class, (ApplyDamage d, ActorContext c) -> {
 *                     health = health.drain(d.amount());
 *                     return Behavior.NEXT;
 *                 }),
 *                 Clause.on(Input.class, (Input i, ActorContext c) -> Behavior.NEXT)
 *         );
 *     }
 *
 *     @Override protected void onStop(ActorContext ctx, ExitReason reason) {
 *         // in die Lobby zurueckmelden
 *     }
 * }
 * }</pre>
 *
 * <p><b>Was hier bewusst fehlt.</b> Kein {@code equals}/{@code hashCode} — der
 * Name im ActorSystem ist die Identitaet. Kein Constructor-Zwang fuer
 * Nachrichtenargumente: Initialisierung gehoert in {@link #onStart}, dort
 * steht der {@link ActorContext} bereit.
 */
public abstract class Actor {

    private final String name;
    private final MailboxOverflow overflow;
    private final int mailboxCapacity;
    private ActorCell cell;
    private ActorContext context;

    protected Actor(String name) {
        this(name, MailboxOverflow.FAIL, Mailboxes.DEFAULT_CAPACITY);
    }

    protected Actor(String name, MailboxOverflow overflow, int mailboxCapacity) {
        Mailboxes.requireCapacity(mailboxCapacity);
        this.name = name;
        this.overflow = overflow;
        this.mailboxCapacity = mailboxCapacity;
    }

    /**
     * Der {@code name} wird im ActorSystem registriert; {@code null} oder leer
     * ergibt einen anonymen Actor mit {@code ~<id>}.
     */
    public final String name() {
        return name;
    }

    public final MailboxOverflow overflow() {
        return overflow;
    }

    public final int mailboxCapacity() {
        return mailboxCapacity;
    }

    /** Von {@link ActorSystem#spawn} gesetzt, bevor der Virtual Thread laeuft. */
    final void attach(ActorCell cell) {
        this.cell = cell;
        this.context = cell;                   // die Zelle IST der ActorContext
    }

    final ActorCell cell() {
        return cell;
    }

    /**
     * Der {@link ActorContext} dieses Actors. Gueltig ab {@link #onStart} und
     * nur auf dem eigenen Thread lesbar — die Zelle ist der einzige, der ihn
     * erzeugt hat.
     *
     * <p>Nuetzlich fuer Basisklassen, die in Lifecycle-Hooks Nachrichten
     * senden wollen, ohne sie sich durch alle Hooks durchreichen zu muessen.
     */
    protected final ActorContext context() {
        return context;
    }

    /**
     * Die eigene {@link ActorRef}, auch von <b>aussen</b> der Zelle aufrufbar:
     * die Registrierung im Namensregister passiert vor dem Thread-Start, es gibt
     * also keine Race. Damit kann eine Basisklasse (z. B. ein Supervisor) sich
     * selbst Nachrichten schicken, ohne die Referenz durchzureichen.
     */
    protected final ActorRef selfRef() {
        ActorRef ref = context().system().whereis(name()).orElse(null);
        if (ref == null) {
            throw new IllegalStateException("actor is not registered under '" + name() + "'");
        }
        return ref;
    }

    /** Die eigene Referenz. Nur gueltig ab {@link #onStart}. */
    protected final ActorRef self() {
        return cell.self();
    }

    // -------------------------------------------------------------- Lifecycle

    /**
     * Erste Nachrichtenverarbeitung. Hier wird das Start-Verhalten gebaut.
     *
     * <p>Entspricht BEAMs {@code init}: laeuft einmalig, bevor die erste
     * Nachricht ausgewertet wird, und hat vollen Zugriff auf {@code ctx}.
     * Wirft man hier, stirbt der Actor sofort mit diesem Grund.
     */
    protected abstract Behavior onStart(ActorContext ctx);

    /**
     * Muss diese Nachricht beim {@link ActorContext#drainInbox(int)} liegen
     * bleiben, statt im laufenden Handler dispatcht zu werden?
     *
     * <p>Der reentrant Drain holt bis zu seinem Budget an Postfachnachrichten
     * <i>vor</i> der eigentlichen Arbeit. Das ist genau richtig fuer Eingaben
     * (Spielerbefehle) und genau falsch fuer Steuerbefehle, die den Ablauf
     * bestimmen: ein {@code Tick} wuerde sonst <b>innerhalb</b> des laufenden
     * Ticks dispatcht, die Simulation liefe zweimal pro Takt und die
     * Reihenfolge der Ticks geriete durcheinander.
     *
     * <p>Solche Nachrichten werden aus dem Postfach genommen und in die
     * Warteschlange des Zell-Loops gestellt — der naechste Dispatch faengt sie
     * wieder auf, in der richtigen Reihenfolge.
     *
     * <p>Default: {@code false} — der normale Actor hat keinen Sonderfall.
     */
    protected boolean deferDuringDrain(Object message) {
        return false;
    }

    /**
     * Derzeit nicht verarbeitete Nachricht, die auf <b>keine</b> Klausel passte
     * (BEAMs {@code handle_info} mit {@code other}).
     *
     * <p>Standard: Log-Warnung, Actor laeuft weiter. Das ist richtig, weil ein
     * unerwarteter Nachrichtentyp bei einem Spielserver kein Grund ist, einen
     * Raum mit 500 Spielern zu zerlegen — im Gegensatz zu einer kaputten
     * Invariante.
     */
    protected Behavior onUnhandled(ActorContext ctx, Object message) {
        return Behavior.NEXT;
    }

    /**
     * Lief bei <b>jedem</b> Tod, auch nach einem Fehler und auch bei
     * {@code ctx.kill()}. Reihenfolge: letzte Nachricht, dann
     * {@link #onPostStop(ActorContext)}, dann {@code postStop}, dann Aufraeumen.
     */
    protected void onStop(ActorContext ctx, ExitReason reason) {
    }

    /**
     * Wie {@link #onStop}, aber <b>nach</b> dem letzten {@code onStop} und dem
     * {@code postStop}-Benutzerhook: der Ort fuer Aufraeumen, das nicht am
     * Postfach hängt (Registrierungen, Subscription-Kuendigungen).
     */
    protected void onPostStop(ActorContext ctx, ExitReason reason) {
    }
}
