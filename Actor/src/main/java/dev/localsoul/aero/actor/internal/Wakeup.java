package dev.localsoul.aero.actor.internal;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Verlustfreies Signal/Warten zwischen Producer(n) und dem einzelnen Consumer.
 *
 * <p><b>Vertrag:</b> {@code hasWork.getAsBoolean()} == {@code true} heisst
 * "es gibt Arbeit" — nicht "die Warteschlange ist leer". Ein Aufrufer, der
 * {@code mailbox::isEmpty} uebergibt, kehrt sofort zurueck und dreht im
 * Aufrufer eine Busy-Spin-Schleife.
 *
 * <p>Der Consumer schlaeft nur, wenn wirklich nichts zu tun ist, und prueft nach
 * jedem Wecken erneut. Damit ist der Fast Path des leeren Postfachs ein
 * CAS-Versuch statt eines Warteschlafens — und ein Signal, das im falschen
 * Moment eintrifft, kann trotzdem nichts verschlucken:
 *
 * <ol>
 *   <li>{@link #signal()} raeumt <b>immer</b> auf. {@link LockSupport}s Permit ist
 *       binaer und sticky: trifft das Signal vor dem {@code park}, liefert der
 *       naechste {@code park} sofort zurueck, und die Pruefschleife sieht die
 *       Arbeit.</li>
 *   <li>{@link #await} ist eine Schleife. Sie kehrt nur zurueck, wenn Arbeit
 *       da ist, der Weck-Mechanismus gestoppt wurde oder der Aufrufer-Thread
 *       unterbrochen ist. Ein "verbrauchtes" Permit kostet damit hoechstens
 *       eine Iteration, nie eine Nachricht.</li>
 * </ol>
 *
 * <p>Der Consumer-Thread bindet sich beim ersten {@link #await} selbst, wenn
 * niemand vorher {@link #bind(Thread)} aufgerufen hat. Das ist wichtig fuer
 * {@code take()} ohne Actor-Zelle: ohne implizite Bindung wuerde der Aufrufer
 * {@code LockSupport.park} auf {@code null} ausfuehren — ein unbegrenzter
 * Busy-Loop, der zudem einen Interrupt nie bemerkt.
 */
public final class Wakeup {

    private final AtomicBoolean parked = new AtomicBoolean();
    private volatile Thread thread;
    private volatile boolean stopped;

    /** Der Consumer-Thread registriert sich vor der ersten Schleife. */
    public void bind(Thread consumer) {
        this.thread = consumer;
    }

    /**
     * Blockiert, solange {@code hasWork} {@code false} liefert.
     *
     * <p>Benutzung:
     * {@code if (mailbox.isEmpty() && !wakeup.await(() -> !mailbox.isEmpty())) break; }
     *
     * @return {@code false}, wenn der Consumer enden soll — der Aufrufer-Thread
     *         wurde unterbrochen, oder der Weck-Mechanismus wurde gestoppt
     */
    public boolean await(BooleanSupplier hasWork) {
        if (stopped) {
            return false;
        }
        if (hasWork.getAsBoolean()) {
            return true;
        }
        Thread me = Thread.currentThread();
        Thread bound = thread;
        if (bound == null) {
            thread = me;                             // implizite Bindung
        } else if (bound != me) {
            return true;                            // falscher Thread: nicht blockieren
        }
        for (;;) {
            if (stopped) {
                parked.set(false);
                return false;
            }
            if (hasWork.getAsBoolean()) {
                parked.set(false);
                return true;                        // Arbeit da: nicht schlafen
            }
            parked.set(true);
            // Re-Check NACH dem Setzen: ab hier sieht der Producer "parked".
            if (hasWork.getAsBoolean() || stopped) {
                parked.set(false);
                return !stopped;
            }
            if (Thread.interrupted()) {             // Interrupt: aufgeben statt drehen
                parked.set(false);
                return false;
            }
            LockSupport.park(this);
            // Permit verbraucht oder echt geweckt: erneut pruefen, nie blind
            // zurueck in den Park.
            parked.set(false);
        }
    }

    /**
     * Aufraeumen: Consumer-Thread endet, alle spaeteren {@link #signal()} tun nichts.
     */
    public void unparkAll() {
        Thread me = thread;
        thread = null;
        parked.set(false);
        if (me != null) {
            LockSupport.unpark(me);
        }
    }

    /**
     * Producer-Seite: bedingungslos aufwecken. Idempotent, weil das Permit nur
     * ein Bit ist — mehrere Signale hintereinander fuehren hoechstens zu einer
     * Iteration ohne Arbeit in {@link #await}.
     */
    public void signal() {
        Thread me = thread;
        parked.set(false);
        if (me != null) {
            LockSupport.unpark(me);
        }
    }

    public void stop() {
        stopped = true;
        unparkAll();
    }

    public boolean stopped() {
        return stopped;
    }

    /** Nur fuer Diagnose und Tests. */
    public boolean parked() {
        return parked.get();
    }
}
