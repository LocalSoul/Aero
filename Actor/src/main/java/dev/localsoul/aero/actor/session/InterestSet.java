package dev.localsoul.aero.actor.session;

import dev.localsoul.aero.actor.ActorRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Area of Interest als <b>Uniform Grid</b>.
 *
 * <p>Ziel: kein O(N²)-Broadcast. Pro Tick will der Raum fuer jeden Spieler alle
 * Interessenten in Sichtweite finden. Bei 500 Spielern waeren das 250 000
 * Distanzpruefungen pro Tick (15 M/s bei 60 Hz) — zu viel. Ein Grid mit
 * Zellgroesse = maximalem Interessenradius bringt O(N).
 *
 * <p><b>Zellzuordnung:</b> eingetragen wird nach dem <i>Zentrum</i> eines
 * Interesses, nicht nach seinem AABB. Das ist Absicht: der AoI-Filter
 * entscheidet ueber Sichtbarkeit von <i>Positionen</i> ({@link Interest#contains(Interest)}
 * prueft das Zentrum), nicht ueber Volumenueberlappung. Ein Spieler soll nicht
 * gesehen werden, nur weil seine Kugel minimal in die des Fragenden ragt. Fuer
 * die Abfrage heisst das: alle Zellen vom Min- bis zum Max-Eck des
 * Query-Quaders durchlaufen, danach exakt filtern — das funktioniert fuer
 * <b>beliebige</b> Zellgroessen, auch wenn {@code cellSize < radius}.
 *
 * <p><b>Interessen, die groesser als eine Zelle sind</b>, liegen in keiner
 * sinnvollen Zelle. Sie stehen in {@link #oversized} und werden bei jeder
 * Abfrage mitgeprueft; im vorgesehenen Betrieb ({@link #forRadius(double)}) ist
 * diese Liste leer.
 *
 * <p><b>Parallelitaet:</b> die Strukturen werden ausschliesslich vom Raum-Thread
 * mutiert (der Raum besitzt sie). {@link #near} darf parallel zu
 * {@code tell}-Aufrufen laufen, weil nur der Raum-Thread schreibt — aber nicht
 * parallel zu {@link #put}/{@link #move}/{@link #remove}.
 *
 * <p><b>Allokation:</b> {@link #near} schreibt in eine vom Aufrufer
 * wiederverwendete Liste und alloziert im Normalfall nichts. Das ist der
 * eigentliche Grund fuer diese Klasse: sie laeuft 60-mal pro Sekunde je Raum.
 */
public final class InterestSet {

    /** Ab dieser Groesse gilt ein Interesse als {@link Interest#UNBOUNDED}. */
    static final double UNBOUNDED_RADIUS = 1.0e15;

    private static final int COORD_BITS = 21;
    private static final long COORD_BIAS = 1L << 20;          // erlaubt negative Zellen
    private static final long COORD_MASK = (1L << COORD_BITS) - 1;

    private final double cellSize;
    private final int dimension;
    private final Map<Long, List<ActorRef>> cells = new HashMap<>();
    private final Map<ActorRef, Interest> areas = new HashMap<>();
    private final List<ActorRef> globals = new ArrayList<>();  // Interest.UNBOUNDED
    private final List<ActorRef> oversized = new ArrayList<>(); // radius > cellSize

    public InterestSet(double cellSize, int dimension) {
        if (!(cellSize > 0)) {
            throw new IllegalArgumentException("cellSize must be > 0, was " + cellSize);
        }
        if (dimension != 2 && dimension != 3) {
            throw new IllegalArgumentException("dimension must be 2 or 3, was " + dimension);
        }
        this.cellSize = cellSize;
        this.dimension = dimension;
    }

    /** Einflussradius als Zellgroesse: der naechstliegende sinnvolle Wert. */
    public static InterestSet forRadius(double maxRadius) {
        if (!(maxRadius > 0)) {
            throw new IllegalArgumentException("maxRadius must be > 0, was " + maxRadius);
        }
        return new InterestSet(maxRadius, 3);
    }

    // ------------------------------------------------------------------ Aenderung

    /** Neu aufnehmen. Ein vorhandenes Interesse wird ersetzt (kein Duplikat). */
    public void put(ActorRef ref, Interest interest) {
        if (ref == null || interest == null) {
            throw new IllegalArgumentException("ref and interest must not be null");
        }
        remove(ref);                                  // Index neu setzen
        areas.put(ref, interest);
        if (interest.isUnbounded()) {
            globals.add(ref);
            return;
        }
        if (interest.radius() > cellSize) {
            oversized.add(ref);
            return;
        }
        cells.computeIfAbsent(cellKey(interest), k -> new ArrayList<>(4)).add(ref);
    }

    /**
     * Position aktualisieren. Das alte Interessen-Gebiet wird verlassen, das neue
     * betreten — ohne Duplikate in den Zellen.
     *
     * @throws IllegalArgumentException wenn {@code ref} nicht bekannt ist
     */
    public void move(ActorRef ref, Interest interest) {
        if (!areas.containsKey(ref)) {
            throw new IllegalArgumentException("unknown ref: " + ref);
        }
        put(ref, interest);
    }

    /** Aus dem Index nehmen. Unbekannte Referenzen sind kein Fehler. */
    public void remove(ActorRef ref) {
        Interest old = areas.remove(ref);
        if (old == null) {
            return;
        }
        if (old.isUnbounded()) {
            globals.remove(ref);
            return;
        }
        if (old.radius() > cellSize) {
            oversized.remove(ref);
            return;
        }
        List<ActorRef> bucket = cells.get(cellKey(old));
        if (bucket != null && bucket.remove(ref) && bucket.isEmpty()) {
            cells.remove(cellKey(old));
        }
    }

    public void clear() {
        cells.clear();
        areas.clear();
        globals.clear();
        oversized.clear();
    }

    // ------------------------------------------------------------------ Abfrage

    /**
     * Alle Referenzen, deren Interesse in {@code area} liegt.
     *
     * <p>{@link Interest#UNBOUNDED} bedeutet: alle — deshalb ist das der
     * <b>globale Broadcast</b>-Pfad und nicht der Distanzpfad.
     *
     * @param out vom Aufrufer wiederverwendete Liste; wird vorher geleert und
     *            nicht zurueckgegeben
     * @return Anzahl der Treffer
     */
    public int near(Interest area, List<ActorRef> out) {
        out.clear();
        if (area == null) {
            return 0;
        }
        if (area.isUnbounded()) {
            out.addAll(areas.keySet());   // globals sind in areas enthalten
            return out.size();
        }
        scanOversized(area, out);
        double r = area.radius();
        int fromX = cell(area.x() - r);
        int toX = cell(area.x() + r);
        int fromY = cell(area.y() - r);
        int toY = cell(area.y() + r);
        int fromZ = cell(area.z() - r);
        int toZ = cell(area.z() + r);
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    List<ActorRef> bucket = cells.get(key(x, y, z));
                    if (bucket == null) {
                        continue;
                    }
                    for (ActorRef ref : bucket) {
                        // Exakte Distanzpruefung, keine Zell-Approximation:
                        // Sichtbarkeit muss genau sein, sonst sieht ein Spieler
                        // Gegner durch Waende.
                        if (area.contains(areas.get(ref))) {
                            out.add(ref);
                        }
                    }
                }
            }
        }
        return out.size();
    }

    /** Bequemlichkeit fuer Tests und Rare-Pfade: alloziert eine neue Liste. */
    public List<ActorRef> near(Interest area) {
        List<ActorRef> out = new ArrayList<>();
        near(area, out);
        return out;
    }

    public int size() {
        return areas.size();
    }

    public boolean contains(ActorRef ref) {
        return areas.containsKey(ref);
    }

    public Interest areaOf(ActorRef ref) {
        return areas.get(ref);
    }

    public double cellSize() {
        return cellSize;
    }

    public int dimension() {
        return dimension;
    }

    /** Anzahl belegter Zellen — Diagnose fuer die Grid-Groesse. */
    public int cellCount() {
        return cells.size();
    }

    public List<ActorRef> globals() {
        return List.copyOf(globals);
    }

    /** Interessen, die groesser als eine Zelle sind. Normalerweise leer. */
    public int oversizedCount() {
        return oversized.size();
    }

    // ------------------------------------------------------------------ Innen

    private void scanOversized(Interest area, List<ActorRef> out) {
        for (int i = 0; i < oversized.size(); i++) {
            ActorRef ref = oversized.get(i);
            if (area.contains(areas.get(ref))) {
                out.add(ref);
            }
        }
    }

    private long cellKey(Interest interest) {
        return key(cell(interest.x()), cell(interest.y()), cell(interest.z()));
    }

    private int cell(double coordinate) {
        if (Double.isInfinite(coordinate)) {
            return 0;
        }
        double scaled = coordinate / cellSize;
        if (scaled >= Integer.MAX_VALUE) {
            return MAX_CELL;
        }
        if (scaled <= Integer.MIN_VALUE) {
            return MIN_CELL;
        }
        return clamp((int) Math.floor(scaled));
    }

    private static final int MAX_CELL = (int) COORD_BIAS - 1;   // + Bias = 2^21 - 1
    private static final int MIN_CELL = -(int) COORD_BIAS;      // + Bias = 0

    private static int clamp(int cell) {
        return Math.max(MIN_CELL, Math.min(MAX_CELL, cell));
    }

    /** {@code cx | cy << 21 | cz << 42} (3D) bzw. {@code cx | cy << 21} (2D). */
    private long key(int cx, int cy, int cz) {
        long x = (cx - MIN_CELL) & COORD_MASK;
        long y = (cy - MIN_CELL) & COORD_MASK;
        long key = x | (y << COORD_BITS);
        if (dimension == 3) {
            long z = (cz - MIN_CELL) & COORD_MASK;
            key |= z << (2 * COORD_BITS);
        }
        return key;
    }
}
