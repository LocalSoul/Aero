package dev.localsoul.aero.game.world;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.game.protocol.ObjectStatusData;
import dev.localsoul.aero.game.protocol.StatData;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Spieler-Entity. Plain Object im Realm; simuliert die Bewegung Richtung
 * Ziel-Position (aus {@code Move}) mit sanfter Distanz-Validierung
 * ({@code speed * dt}) plus HP-Regeneration (§8.4). Hält Inventar, XP/Level,
 * die Schuss-Spur (Ringpuffer, §5.1) und den Sichtbarkeits-Zustand des
 * Clients ({@code knownObjectIds}, §7).
 */
public final class Player extends Entity {

    public static final short WIZARD = 782;

    /** Energy Staff — Startwaffe des Wizard (PlayersCXML, Slot 0). */
    public static final int ENERGY_STAFF = 0xa97;
    public static final int ENERGY_STAFF_MIN_DAMAGE = 10;
    public static final int ENERGY_STAFF_MAX_DAMAGE = 25;
    public static final int ENERGY_STAFF_NUM_PROJECTILES = 2;
    public static final int ENERGY_STAFF_SPEED = 180;
    public static final int ENERGY_STAFF_LIFETIME_MS = 475;

    private static final int SHOT_BUFFER_SIZE = 16;
    private static final int EQUIP_SLOTS = 12;              // 0–3 Equip, 4–11 Inventar
    private static final int FIRST_INVENTORY_SLOT = 4;

    private float targetX;
    private float targetY;
    private float speed = 4.0f;                 // Einheiten pro Sekunde
    private String name = "Player";
    private int charId = 1;
    private int level = 1;
    private int hp = 100;
    private int maxHp = 100;
    private int mp = 50;
    private int maxMp = 50;
    private int exp;
    private int nextLevelExp = 100;             // bewusste Vereinfachung (§6.1)
    private int hpRegen = 12;                   // Wizard-HpRegen
    private int attack = 12;
    private int defense;
    private int speedStat = 10;
    private int dexterity = 15;
    private int vitality = 12;
    private int wisdom = 12;
    private int skinType;
    private boolean dead;
    private float hpRegenAccumulator;
    private final Map map;
    private ActorRef session;
    private final int[] inventory = new int[EQUIP_SLOTS];
    private final ArrayDeque<Shot> shots = new ArrayDeque<>();
    private final Set<Integer> knownObjectIds = new HashSet<>();

    public Player(final int objectId, final Map map, final float x, final float y) {
        this(objectId, map, x, y, WIZARD);
    }

    public Player(final int objectId, final Map map, final float x, final float y,
                  final short objectType) {
        super(objectId, objectType, x, y);
        this.map = map;
        targetX = x;
        targetY = y;
        for (int i = 0; i < inventory.length; i++) {
            inventory[i] = -1;
        }
        inventory[0] = ENERGY_STAFF;            // Default-Ausrüstung (PlayersCXML)
        inventory[1] = 0xa2e;                   // Fire Spray Spell
        inventory[4] = 0xa22;                   // Health Potion
    }

    /** Die Session des Spielers (für Outbound/Kick-Ack); nur vom Realm gesetzt. */
    public void setSession(final ActorRef session) {
        this.session = session;
    }

    public ActorRef session() {
        return session;
    }

    public void setCharId(final int charId) {
        this.charId = charId;
    }

    public int charId() {
        return charId;
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

    public int hp() {
        return hp;
    }

    public int defense() {
        return defense;
    }

    public boolean isDead() {
        return dead;
    }

    /** Schaden (autoritativ vom Realm). HP ≤ 0 markiert den Spieler tot. */
    public void damage(final int amount) {
        hp -= amount;
        if (hp <= 0) {
            hp = 0;
            dead = true;
        }
    }

    /** XP addieren und Level-Ups abarbeiten (§6.1, vereinfachte Kurve). */
    public void addExp(final int amount) {
        exp += amount;
        while (exp >= nextLevelExp) {
            exp -= nextLevelExp;
            level++;
            nextLevelExp = 100 + (level - 1) * 50;
        }
    }

    /** Item ins erste freie Inventar-Slot (4–11) legen. */
    public boolean addItem(final int itemType) {
        for (int i = FIRST_INVENTORY_SLOT; i < inventory.length; i++) {
            if (inventory[i] == -1) {
                inventory[i] = itemType;
                return true;
            }
        }
        return false;
    }

    /** Sichtbarkeits-Zustand des Clients (§7). */
    public Set<Integer> knownObjectIds() {
        return knownObjectIds;
    }

    // ------------------------------------------------------- Schuss-Spur

    /**
     * Ein Schuss des Spielers. {@code bulletId} ist ein Byte (0–255); die
     * Projektile eines Schusses decken {@code base … base+numProjectiles-1}
     * ab — der Wraparound wird modulo 256 behandelt.
     */
    public static final class Shot {
        final int bulletIdBase;
        final float startX;
        final float startY;
        final int damage;
        final int numProjectiles;
        final long firedAtMs;
        final long lifetimeMs;
        final boolean[] consumed;

        public Shot(final int bulletIdBase, final float startX, final float startY,
             final int damage, final int numProjectiles, final long firedAtMs,
             final long lifetimeMs) {
            this.bulletIdBase = bulletIdBase;
            this.startX = startX;
            this.startY = startY;
            this.damage = damage;
            this.numProjectiles = numProjectiles;
            this.firedAtMs = firedAtMs;
            this.lifetimeMs = lifetimeMs;
            consumed = new boolean[numProjectiles];
        }

        boolean covers(final int bulletId) {
            return ((bulletId - bulletIdBase) & 0xFF) < numProjectiles;
        }

        /** Markiert die bulletId als verbraucht; {@code false} wenn schon. */
        boolean consume(final int bulletId) {
            final int index = (bulletId - bulletIdBase) & 0xFF;
            if (index >= consumed.length || consumed[index]) {
                return false;
            }
            consumed[index] = true;
            return true;
        }

        boolean isFresh(final long nowMs) {
            return nowMs - firedAtMs <= lifetimeMs;
        }

        public int damage() {
            return damage;
        }

        public float startX() {
            return startX;
        }

        public float startY() {
            return startY;
        }
    }

    /** Schuss registrieren (neueste zuerst); alte Schüsse werden verworfen. */
    public void registerShot(final Shot shot) {
        shots.addFirst(shot);
        while (shots.size() > SHOT_BUFFER_SIZE) {
            shots.removeLast();
        }
    }

    /**
     * Sucht die frische, noch nicht verbrauchte Schuss-Spur für eine bulletId
     * und markiert sie als verbraucht. {@code null} wenn ungültig (§5.1).
     */
    public Shot consumeShot(final int bulletId, final long nowMs) {
        for (final Shot shot : shots) {
            if (shot.isFresh(nowMs) && shot.covers(bulletId) && shot.consume(bulletId)) {
                return shot;
            }
        }
        return null;
    }

    // ------------------------------------------------------- Simulation

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

        // HP-Regeneration (§8.4, server-seitig; der Client regeneriert nicht
        // selbst). Akkumulator, damit 12 HP/s nicht durch Int-Rundung verloren
        // gehen (ceil je Tick würde überschwingen).
        if (!dead && hp < maxHp) {
            hpRegenAccumulator += hpRegen * dt;
            final int whole = (int) hpRegenAccumulator;
            if (whole > 0) {
                hpRegenAccumulator -= whole;
                hp = Math.min(maxHp, hp + whole);
            }
        }
    }

    /** {@code ObjectStatusData} für {@code NewTick} (Vollstat inkl. Inventar). */
    public ObjectStatusData status() {
        final ObjectStatusData status = new ObjectStatusData();
        status.objectId = objectId;
        status.pos.x = pos.x;
        status.pos.y = pos.y;
        status.stats.add(StatData.of(0, maxHp));        // MAX_HP
        status.stats.add(StatData.of(1, hp));           // HP
        status.stats.add(StatData.of(3, maxMp));        // MAX_MP
        status.stats.add(StatData.of(4, mp));           // MP
        status.stats.add(StatData.of(5, nextLevelExp)); // NEXT_LEVEL_EXP
        status.stats.add(StatData.of(6, exp));          // EXP
        status.stats.add(StatData.of(7, level));        // LEVEL
        for (int slot = 0; slot < EQUIP_SLOTS; slot++) {
            status.stats.add(StatData.of(8 + slot, inventory[slot]));   // INVENTORY_0..11
        }
        status.stats.add(StatData.of(20, attack));      // ATTACK
        status.stats.add(StatData.of(21, defense));     // DEFENSE
        status.stats.add(StatData.of(22, speedStat));   // SPEED
        status.stats.add(StatData.of(26, vitality));    // VITALITY
        status.stats.add(StatData.of(27, wisdom));      // WISDOM
        status.stats.add(StatData.of(28, dexterity));   // DEXTERITY
        status.stats.add(StatData.of(31, name));        // NAME
        return status;
    }
}