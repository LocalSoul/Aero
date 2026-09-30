package dev.localsoul.aero.game.world;

import dev.localsoul.aero.game.protocol.WorldPosData;

/**
 * Ein Objekt in der Welt — ein **Plain Object**, das exklusiv vom Realm-Thread
 * simuliert und mutiert wird (§2.3, §7 der gameserver_implement.md). Kein
 * Actor, kein Thread.
 */
public class Entity {

    protected final int objectId;
    protected final short objectType;
    protected final WorldPosData pos = new WorldPosData();

    public Entity(final int objectId, final short objectType, final float x, final float y) {
        this.objectId = objectId;
        this.objectType = objectType;
        pos.x = x;
        pos.y = y;
    }

    public int objectId() {
        return objectId;
    }

    public short objectType() {
        return objectType;
    }

    public float x() {
        return pos.x;
    }

    public float y() {
        return pos.y;
    }

    public WorldPosData pos() {
        return pos;
    }

    /** Pro Tick vom Realm-Thread aufgerufen (reine Objekt-Iteration). */
    public void simulate(final float dt) {
    }
}