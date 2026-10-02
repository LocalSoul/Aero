package dev.localsoul.aero.game.world;

import dev.localsoul.aero.game.protocol.ObjectStatusData;
import dev.localsoul.aero.game.protocol.StatData;

/**
 * Ein Gegner (Ghost Mage, {@code 0x664}) als **Plain Object** im Reich
 * (§8.1 der gameserver_implement.md). Steht still, feuert periodisch auf den
 * nächsten Spieler im Aggro-Radius und respawnt nach dem Tod mit einer
 * Schonfrist (kein Sofort-Schuss). Nur der Realm-Thread mutiert ihn.
 */
public final class Monster extends Entity {

    public static final short GHOST_MAGE = 0x664;
    public static final int MAX_HP = 130;
    public static final int DEFENSE = 0;
    public static final int SIZE = 100;
    public static final int PROJECTILE_DAMAGE = 40;
    public static final int PROJECTILE_SPEED = 50;
    public static final int PROJECTILE_LIFETIME_MS = 3000;
    public static final int XP_PER_KILL = 13;               // round(130 * XpMult 0.1)
    public static final int AGGRO_RADIUS = 10;
    public static final int ATTACK_PERIOD_MS = 2000;
    public static final int RESPAWN_MS = 10_000;
    public static final int FIRST_SHOT_DELAY_MS = 1000;

    private final float spawnX;
    private final float spawnY;
    private int hp = MAX_HP;
    private int bulletId;
    private long nextAttackAtMs;
    private boolean alive = true;
    private long respawnAtMs;

    public Monster(final int objectId, final float x, final float y) {
        super(objectId, GHOST_MAGE, x, y);
        spawnX = x;
        spawnY = y;
        nextAttackAtMs = FIRST_SHOT_DELAY_MS;
    }

    public boolean alive() {
        return alive;
    }

    /** Für die gestaffelte Erst-Schuss-Verzögerung beim Spawn (§8.1). */
    public void setNextAttackAt(final long ms) {
        nextAttackAtMs = ms;
    }

    public long nextAttackAtMs() {
        return nextAttackAtMs;
    }

    public int hp() {
        return hp;
    }

    public int defense() {
        return DEFENSE;
    }

    public void damage(final int amount) {
        hp -= amount;
    }

    public boolean readyToFire(final long nowMs) {
        return alive && nowMs >= nextAttackAtMs;
    }

    public void markFired(final long nowMs) {
        nextAttackAtMs = nowMs + ATTACK_PERIOD_MS;
    }

    /** Nächste bulletId (modulo 256, der Client liest ein Byte). */
    public int nextBulletId() {
        final int id = bulletId;
        bulletId = (bulletId + 1) & 0xFF;
        return id;
    }

    /** Monster tot; Auferstehung zu {@code respawnAt}. */
    public void markDead(final long respawnAt) {
        alive = false;
        respawnAtMs = respawnAt;
    }

    /** Respawn-Logik: nach der Frist wieder lebend, HP voll, Schonfrist. */
    public void simulate(final float dt, final long nowMs) {
        if (!alive && nowMs >= respawnAtMs) {
            alive = true;
            hp = MAX_HP;
            pos.x = spawnX + (float) (Math.random() * 2.0 - 1.0);
            pos.y = spawnY + (float) (Math.random() * 2.0 - 1.0);
            nextAttackAtMs = nowMs + ATTACK_PERIOD_MS;      // kein Sofort-Schuss
        }
    }

    /** {@code ObjectStatusData} für {@code NewTick} (HP-Balken, Größe, Name). */
    public ObjectStatusData status() {
        final ObjectStatusData status = new ObjectStatusData();
        status.objectId = objectId;
        status.pos.x = pos.x;
        status.pos.y = pos.y;
        status.stats.add(StatData.of(0, MAX_HP));           // MAX_HP
        status.stats.add(StatData.of(1, Math.max(0, hp)));  // HP
        status.stats.add(StatData.of(2, SIZE));             // SIZE
        status.stats.add(StatData.of(21, DEFENSE));         // DEFENSE
        status.stats.add(StatData.of(31, "Ghost Mage"));    // NAME
        return status;
    }
}