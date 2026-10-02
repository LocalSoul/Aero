package dev.localsoul.aero.game.world;

/**
 * Ein Monster-Projektil als **Plain Object** (§8.2 der gameserver_implement.md).
 * Der Server simuliert es (kein Message-Passing): Position wird pro Tick aus
 * {@code start + ageMs * speed / 10000} entlang {@code angle} neu berechnet —
 * {@code speed} ist der **Rohwert** aus der Client-XML (Ghost Mage: 50).
 */
public final class Projectile {

    private final int ownerId;
    private final int bulletId;
    private final int bulletType;
    private final float startX;
    private final float startY;
    private final float angle;
    private final int damage;
    private final int speed;
    private final long lifetimeMs;
    private long ageMs;

    public Projectile(final int ownerId, final int bulletId, final int bulletType,
                      final float startX, final float startY, final float angle,
                      final int damage, final int speed, final long lifetimeMs) {
        this.ownerId = ownerId;
        this.bulletId = bulletId;
        this.bulletType = bulletType;
        this.startX = startX;
        this.startY = startY;
        this.angle = angle;
        this.damage = damage;
        this.speed = speed;
        this.lifetimeMs = lifetimeMs;
    }

    public void simulate(final long dtMs) {
        ageMs += dtMs;
    }

    public boolean expired() {
        return ageMs >= lifetimeMs;
    }

    public float x() {
        return startX + traveled() * (float) Math.cos(angle);
    }

    public float y() {
        return startY + traveled() * (float) Math.sin(angle);
    }

    private float traveled() {
        return ageMs * speed / 10_000f;
    }

    public int ownerId() {
        return ownerId;
    }

    public int bulletId() {
        return bulletId;
    }

    public int bulletType() {
        return bulletType;
    }

    public int damage() {
        return damage;
    }
}