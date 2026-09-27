package dev.localsoul.aero.actor.tick;

import java.time.Duration;

/**
 * Ein Tick: laufende Nummer, vergangene Zeit seit dem vorigen Tick und die
 * Marke "erster Tick".
 *
 * <p>Unveraenderlich und allokationsfrei erzeugt — 60 Stueck pro Sekunde je
 * Raum, bei 1000 Raeumen 60 000 pro Sekunde.
 *
 * @param number   fortlaufende Tick-Nummer, beginnend bei 1
 * @param elapsed  Zeit seit dem vorigen Tick (beim ersten: seit dem Start)
 * @param first    {@code true} beim allerersten Tick
 */
public record Tick(long number, Duration elapsed, boolean first) {

    public static final Tick ZERO = new Tick(0L, Duration.ZERO, false);

    public Tick {
        if (number < 0) {
            throw new IllegalArgumentException("number must be >= 0, was " + number);
        }
    }

    /** Wie viele Ticks zwischen {@code previousNumber} und diesem fehlen. */
    public long skipped(long previousNumber) {
        return Math.max(0, number - previousNumber - 1);
    }

    public double millis() {
        return elapsed.toNanos() / 1_000_000.0;
    }
}
