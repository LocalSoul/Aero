package dev.localsoul.aero.game.world;

import dev.localsoul.aero.game.protocol.ObjectData;
import dev.localsoul.aero.game.protocol.ObjectStatusData;
import dev.localsoul.aero.game.protocol.StatData;

/**
 * Eine Loot-Bag (Soulbound Loot Bag, {@code 0x0503}) als Plain Object (§6.2).
 * Gehört server-seitig dem Killer; Items gehen beim Nähe-Pickup in dessen
 * Inventar. Läuft nach {@link #LIFETIME_MS} ab — auch wenn der Killer weg ist.
 */
public final class LootBag {

    public static final short SOULBOUND_LOOT_BAG = 0x0503;
    public static final long LIFETIME_MS = 60_000;
    public static final float PICKUP_RADIUS = 1.0f;

    private final int objectId;
    private final float x;
    private final float y;
    private final Player owner;
    private final int itemType;
    private final long spawnAtMs;

    public LootBag(final int objectId, final float x, final float y,
                   final Player owner, final int itemType, final long spawnAtMs) {
        this.objectId = objectId;
        this.x = x;
        this.y = y;
        this.owner = owner;
        this.itemType = itemType;
        this.spawnAtMs = spawnAtMs;
    }

    public int objectId() {
        return objectId;
    }

    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    public Player owner() {
        return owner;
    }

    public int itemType() {
        return itemType;
    }

    public boolean expired(final long nowMs) {
        return nowMs - spawnAtMs >= LIFETIME_MS;
    }

    /** {@code ObjectData} für {@code Update.newObjs} — Inhalt als INVENTORY-Stat. */
    public ObjectData toObjectData() {
        final ObjectData data = new ObjectData();
        data.objectType = SOULBOUND_LOOT_BAG;
        data.status.objectId = objectId;
        data.status.pos.x = x;
        data.status.pos.y = y;
        data.status.stats.add(StatData.of(8, itemType));   // INVENTORY_0
        return data;
    }

    /** {@code ObjectStatusData} für {@code NewTick} (Position + Inhalt). */
    public ObjectStatusData status() {
        final ObjectStatusData status = new ObjectStatusData();
        status.objectId = objectId;
        status.pos.x = x;
        status.pos.y = y;
        status.stats.add(StatData.of(8, itemType));        // INVENTORY_0
        return status;
    }
}