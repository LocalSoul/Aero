package dev.localsoul.aero.game.actor;

import dev.localsoul.aero.actor.ActorRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Abbildung {@code gameId → Realm-Shard-Menge} (§2.10): „Nexus" ist keine
 * Singleton. V1 registriert genau eine Instanz; weitere Shards sind eine
 * Konfigurations-, keine Struktur-Änderung.
 */
public final class RoomRegistry {

    private final Map<Integer, List<ActorRef>> shards = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobin = new AtomicInteger();

    public void register(final int gameId, final ActorRef realm) {
        shards.computeIfAbsent(gameId, k -> new ArrayList<>()).add(realm);
    }

    /** Wählt einen Shard (Round-Robin über die registrierten). */
    public ActorRef choose(final int gameId) {
        final List<ActorRef> rooms = shards.get(gameId);
        if (rooms == null || rooms.isEmpty()) {
            return null;
        }
        return rooms.get(Math.floorMod(roundRobin.getAndIncrement(), rooms.size()));
    }

    public int shardCount(final int gameId) {
        final List<ActorRef> rooms = shards.get(gameId);
        return rooms == null ? 0 : rooms.size();
    }
}