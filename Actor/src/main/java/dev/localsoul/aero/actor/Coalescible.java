package dev.localsoul.aero.actor;

/**
 * Nachricht, die im Postfach mit gleichem {@link #coalesceKey()} verschmolzen
 * werden darf: von mehreren Einreichungen gewinnt die <b>letzte</b>.
 *
 * <p>Anwendung im Spielserver:
 *
 * <pre>{@code
 * record Input(int seq, Vec2 move) implements Coalescible {
 *     @Override public Object coalesceKey() { return sessionId; }   // nur neuester Input
 * }
 * }</pre>
 *
 * <p>Voraussetzung: Der Absender muss <b>derselbe</b> sein. Deshalb ist der
 * Schluessel ein Paar aus Absender und Key — in der Praxis also „ein Client
 * sendet seinen eigenen Zustand". Verschiedene Absender werden nie verschmolzen,
 * auch bei gleichem Key.
 */
public interface Coalescible {

    /**
     * Wert, dessen Gleichheit zwei Nachrichten als dieselbe Nachricht
     * kennzeichnet. Bei {@code record}s ist ein Feld geeignet, nicht das
     * ganze {@code this}.
     */
    Object coalesceKey();
}
