package dev.localsoul.aero.game.world;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.game.protocol.ObjectStatusData;
import dev.localsoul.aero.game.protocol.StatData;

/**
 * Spieler-Entity. Plain Object im Realm; simuliert die Bewegung Richtung
 * Ziel-Position (aus {@code Move}) mit sanfter Distanz-Validierung
 * ({@code speed * dt}).
 */
public final class Player extends Entity {

    public static final short WIZARD = 782;

    private float targetX;
    private float targetY;
    private float speed = 4.0f;                 // Einheiten pro Sekunde
    private String name = "Player";
    private int level = 1;
    private int hp = 100;
    private int maxHp = 100;
    private int mp = 50;
    private int maxMp = 50;
    private int skinType;
    private final Map map;
    private ActorRef session;

    public Player(final int objectId, final Map map, final float x, final float y) {
        this(objectId, map, x, y, WIZARD);
    }

    public Player(final int objectId, final Map map, final float x, final float y,
                  final short objectType) {
        super(objectId, objectType, x, y);
        this.map = map;
        targetX = x;
        targetY = y;
    }

    /** Die Session des Spielers (für Kick-Ack); nur vom Realm gesetzt. */
    public void setSession(final ActorRef session) {
        this.session = session;
    }

    public ActorRef session() {
        return session;
    }

    public void setTarget(final float x, final float y) {
        targetX = x;
        targetY = y;
    }

    public void setSpeed(final float speed) {
        this.speed = speed;
    }

    public void setSkinType(final int skinType) {
        this.skinType = skinType;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @Override
    public void simulate(final float dt) {
        final float dx = targetX - pos.x;
        final float dy = targetY - pos.y;
        final float dist = (float) Math.sqrt(dx * dx + dy * dy);
        final float step = speed * dt;
        if (dist <= step) {
            pos.x = targetX;
            pos.y = targetY;
        } else if (dist > 0f) {
            pos.x += dx / dist * step;
            pos.y += dy / dist * step;
        }
        pos.x = map.clampX(pos.x);
        pos.y = map.clampY(pos.y);
    }

    /** {@code ObjectStatusData} für {@code NewTick} (minimaler Statsatz). */
    public ObjectStatusData status() {
        final ObjectStatusData status = new ObjectStatusData();
        status.objectId = objectId;
        status.pos.x = pos.x;
        status.pos.y = pos.y;
        status.stats.add(StatData.of(31, name));        // NAME_STAT
        status.stats.add(StatData.of(7, level));        // LEVEL_STAT
        status.stats.add(StatData.of(1, hp));           // HP_STAT
        status.stats.add(StatData.of(0, maxHp));        // MAX_HP_STAT
        status.stats.add(StatData.of(4, mp));           // MP_STAT
        status.stats.add(StatData.of(3, maxMp));        // MAX_MP_STAT
        for (int slot = 0; slot < 12; slot++) {
            status.stats.add(StatData.of(8 + slot, -1)); // INVENTORY_0..11 (leer)
        }
        return status;
    }
}