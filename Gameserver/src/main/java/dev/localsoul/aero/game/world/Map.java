package dev.localsoul.aero.game.world;

/**
 * Die leere V1-Welt: nur Breite/Höhe und Bounds-Check. Grund-/Objekt-Daten
 * (GroundLibrary/ObjectLibrary via MapInfo) folgen in V3.
 */
public final class Map {

    private final int width;
    private final int height;

    public Map(final int width, final int height) {
        this.width = width;
        this.height = height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean inBounds(final float x, final float y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    /** Position in die Map klemmen (sanfte Validierung). */
    public float clampX(final float x) {
        return Math.max(0, Math.min(width - 1, x));
    }

    public float clampY(final float y) {
        return Math.max(0, Math.min(height - 1, y));
    }
}