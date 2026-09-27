package dev.localsoul.aero.actor.session;

/**
 * Interessenvolumen eines Clients: eine Kugel um {@code (x, y, z)} mit Radius
 * {@code radius}.
 *
 * <p>Der Raum eines Spiels ist eine Wolke von Positionen; was ein Client
 * sehen will, ist eine Kugel darum. {@code Interest} ist <b>nur</b> die
 * Geometrie — welche Nachrichten daraus entstehen, entscheidet das Spiel im
 * {@code TickActor}.
 *
 * <p>{@link #UNBOUNDED} deaktiviert das Filtern: Wer global publiziert
 * (Lobby-Broadcast, Systemnachrichten), setzt diesen Wert, und wird von
 * keiner Distanzpruefung erfasst.
 *
 * <p>Der Datentyp ist unveraenderlich und damit gefahrlos zwischen Threads
 * teilbar. Es werden <b>keine</b> allozierten Vektoren benutzt: der
 * Distanzvergleich rechnet direkt, damit der AoI-Filter im Tickpfad
 * allokationsfrei bleibt.
 */
public record Interest(double x, double y, double z, double radius) {

    /**
     * Globales Interesse ohne Filter. Der Radius ist {@code Double.MAX_VALUE},
     * damit auch Koordinaten beliebig weit ausserhalb des Raums erfasst werden.
     */
    public static final Interest UNBOUNDED = new Interest(0, 0, 0, Double.MAX_VALUE);

    public Interest {
        // Hinweis: NaN erfuellt `!(radius >= 0)`, ein separater NaN-Check ist ueberfluessig.
        if (!(radius >= 0)) {
            throw new IllegalArgumentException("radius must be >= 0, was " + radius);
        }
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            throw new IllegalArgumentException("coordinates must not be NaN");
        }
    }

    /** Kugel an der angegebenen Position. */
    public static Interest at(double x, double y, double z, double radius) {
        return new Interest(x, y, z, radius);
    }

    /** Punkt-Interesse (Radius 0): genau diese Position. */
    public static Interest point(double x, double y, double z) {
        return new Interest(x, y, z, 0);
    }

    /** interest is global: {@link #UNBOUNDED} oder ein sehr grosser Radius. */
    public boolean isUnbounded() {
        return radius >= InterestSet.UNBOUNDED_RADIUS;
    }

    /** Liegt der Punkt innerhalb dieser Kugel? */
    public boolean contains(double px, double py, double pz) {
        if (isUnbounded()) {
            return true;
        }
        return squaredDistance(px, py, pz) <= radius * radius;
    }

    /**
     * Liegt das <b>Zentrum</b> von {@code other} in dieser Kugel?
     *
     * <p>Absichtlich Zentrum statt Vollstaendigkeit: der AoI-Filter entscheidet
     * ueber Sichtbarkeit von <i>Positionen</i>, nicht ueber Ueberlappung von
     * Volumen. Eine exakte Volumenpruefung wuerde einem Spieler Sichtbarkeit
     * geben, nur weil eine Kugel minimal in die andere ragt.
     */
    public boolean contains(Interest other) {
        if (other == null) {
            return false;
        }
        if (isUnbounded() || other.isUnbounded()) {
            return isUnbounded() && other.isUnbounded();
        }
        return contains(other.x, other.y, other.z);
    }

    /** Abstand zum Mittelpunkt; {@code sqrt} nur bei Bedarf. */
    public double distanceTo(double px, double py, double pz) {
        return Math.sqrt(squaredDistance(px, py, pz));
    }

    public double squaredDistance(double px, double py, double pz) {
        double dx = x - px;
        double dy = y - py;
        double dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Achsparalleler Quader, der diese Kugel umschliesst — Basis des Uniform Grids. */
    public Aabb aabb() {
        return new Aabb(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    }

    /** Kuerzester Weg zwischen zwei Kugeln: {@code true}, wenn sie sich schneiden. */
    public boolean overlaps(Interest other) {
        if (isUnbounded() || other.isUnbounded()) {
            return true;
        }
        return squaredDistance(other.x, other.y, other.z)
                <= (radius + other.radius) * (radius + other.radius);
    }

    public Interest movedTo(double nx, double ny, double nz) {
        return new Interest(nx, ny, nz, radius);
    }

    public Interest withRadius(double newRadius) {
        return new Interest(x, y, z, newRadius);
    }

    /** Achsparalleler Quader. */
    public record Aabb(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ) {

        public boolean contains(double px, double py, double pz) {
            return px >= minX && px <= maxX
                    && py >= minY && py <= maxY
                    && pz >= minZ && pz <= maxZ;
        }
    }
}
