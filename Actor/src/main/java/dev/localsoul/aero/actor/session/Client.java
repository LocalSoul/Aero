package dev.localsoul.aero.actor.session;

/**
 * Die Netzwerk-Grenze. Bewusst ein Interface mit <b>einer</b> Pflichtmethode:
 * alles darueber (Kompression, Verschluesselung, Batching) ist Sache des
 * Netzwerk-Layers und nicht des Actor-Frameworks.
 *
 * <p>Ein {@code Client} wird von mehreren Actors referenziert (Raum, Lobby,
 * Session) und ist damit geteilter, veraenderbarer Zustand. Er ist deshalb
 * <b>thread-sicher</b> zu implementieren — der Rest des Frameworks ist es nicht.
 */
public interface Client {

    /**
     * Ein fertig gepacktes Paket senden. Wird synchron aus dem Raum- oder
     * Session-Actor aufgerufen und muss schnell sein: bei UDP ein
     * {@code sendto}, bei TCP ein Schreib in den Ringpuffer.
     *
     * <p>Der Actor darf sich nicht im Zweifel mit einer Ausnahme abmelden: der
     * Aufrufer ist ein Actor, und ein toter Raum nimmt alle anderen Spieler mit.
     * Probleme bitte <b>intern</b> behandeln (Socket schliessen, Client kicken).
     */
    void send(Object packet);

    /** Verbindung schliessen. Idempotent. Default: nichts tun. */
    default void close() {
    }

    /** Stabile Kennung fuer Logs und Interest-Management. Default 0. */
    default long id() {
        return 0L;
    }

    default boolean isOpen() {
        return true;
    }
}
