package dev.localsoul.aero.actor.tick;

import dev.localsoul.aero.actor.ActorRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pro-Client-Aggregation: aus 40 {@code send(client, delta)}-Aufrufen pro
 * Client wird <b>ein</b> Paket pro Client pro Tick.
 *
 * <p>Die Reihenfolge im Tick ist fest: Eingaben → Simulation → Outbound →
 * Messen (s. {@code implement.md} 6.12). Der Raum sammelt also waehrend der
 * Simulation, und genau einmal pro Tick entsteht pro Client genau
 * <b>eine</b> Nachricht in der Session-Mailbox — unabhaengig von der Anzahl der
 * {@code send}-Aufrufe.
 *
 * <p><b>Payload-Recycling:</b> {@link Packer#pack(List, Object)} bekommt den
 * letzten Payload dieses Clients als {@code scratch}, damit ein Encoder seinen
 * {@code ByteBuffer} wiederverwenden kann. Das ist der Grund fuer den
 * {@code scratch}-Parameter und der eigentliche Performance-Gewinn:
 * <b>ein</b> Puffer pro Client pro JVM-Lebensdauer statt eines pro Paket.
 */
public final class OutboundBuffer {

    /** Was pro Client und Tick bei der Session ankommt. */
    public record Outbound(List<Object> packets, Object payload) {

        public Outbound {
            packets = List.copyOf(packets);
        }
    }

    /**
     * Wandelt die gesammelten Pakete in ein Payload. Der {@code scratch}-Parameter
     * ist der Payload des <b>vorigen</b> Ticks desselben Clients (oder
     * {@code null} im ersten Tick).
     */
    @FunctionalInterface
    public interface Packer {

        Object pack(List<Object> queued, Object scratch);
    }

    /** Default: keine Kompression, keine Serialisierung — die Pakete bleiben Liste. */
    public static final Packer IDENTITY = (queued, scratch) -> queued;

    private static final class Outbox {
        private final List<Object> packets = new ArrayList<>(4);
    }

    private final Map<ActorRef, Outbox> clients = new LinkedHashMap<>();

    /**
     * Recycling-Puffer je Client, <b>ueberlebt</b> {@link #flush(Packer)}.
     * Wuerde er im Register liegen, waere er nach dem Flush weg — der
     * {@code scratch}-Parameter waere dann wertlos und es gaebe wieder einen
     * Encoder-Puffer <b>pro Paket</b> statt pro Client.
     */
    private final Map<ActorRef, Object> scratch = new HashMap<>();

    /** Ein Paket fuer einen Client vormerken. */
    public void send(ActorRef client, Object packet) {
        if (client == null) {
            return;
        }
        clients.computeIfAbsent(client, k -> new Outbox()).packets.add(packet);
    }

    /** Ein Paket an viele Clients — ein Postfach-Eintrag pro Client. */
    public void sendAll(Iterable<? extends ActorRef> targets, Object packet) {
        for (ActorRef target : targets) {
            send(target, packet);
        }
    }

    /**
     * Alle vorgemerkten Pakete packen und je Client <b>eine</b> Nachricht an den
     * Client schicken. Das Register wird danach geleert — nur die in diesem Tick
     * aktiven Clients bleiben erhalten, alles andere ist naechsten Tick neu.
     *
     * @return Anzahl der Clients, die tatsaechlich etwas bekommen haben
     */
    public int flush(Packer packer) {
        if (clients.isEmpty()) {
            return 0;
        }
        Packer effective = packer == null ? IDENTITY : packer;
        Map<ActorRef, Outbox> snapshot = new LinkedHashMap<>(clients);
        clients.clear();
        int flushed = 0;
        for (Map.Entry<ActorRef, Outbox> entry : snapshot.entrySet()) {
            Outbox outbox = entry.getValue();
            if (outbox.packets.isEmpty()) {
                continue;
            }
            ActorRef client = entry.getKey();
            List<Object> packets = List.copyOf(outbox.packets);   // unveraenderlich weitergeben
            outbox.packets.clear();
            Object payload = effective.pack(packets, scratch.get(client));
            scratch.put(client, payload);                      // fuer den naechsten Tick
            if (client.tryTell(new Outbound(packets, payload))) {
                flushed++;
            }
        }
        return flushed;
    }

    /** Ohne {@code Packer}: nur zaehlen, was anliegt (Diagnose/Test). */
    public int pendingClients() {
        return clients.size();
    }

    public boolean has(ActorRef client) {
        Outbox outbox = clients.get(client);
        return outbox != null && !outbox.packets.isEmpty();
    }

    public int pendingPackets() {
        int sum = 0;
        for (Outbox outbox : clients.values()) {
            sum += outbox.packets.size();
        }
        return sum;
    }

    public void clear() {
        clients.clear();
        scratch.clear();
    }

    /** Letzter Payload eines Clients — der {@code scratch} des naechsten Ticks. */
    public Object scratchOf(ActorRef client) {
        return scratch.get(client);
    }
}
