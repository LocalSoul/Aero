package dev.localsoul.aero.actor.tick;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.session.Subscription;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;

/**
 * Fixed-Timestep-Akkumulator fuer alle Raeume eines {@link ActorSystem}s.
 *
 * <p><b>Robustheit: kein Subscriber darf den Treiber toeten.</b> Die
 * Tick-Zustellung ist der einzige Stellenwert, an dem ein Fehler <i>alle</i>
 * Raeume betrifft. Drei Vorkehrungen:
 * <ol>
 *   <li>{@code tryTell} statt {@code tell} — ein volles Postfach liefert
 *       {@code false} statt einer Ausnahme und wird in {@link #deliverFailures()}
 *       gezaehlt.</li>
 *   <li>{@code try/catch} je Subscriber — ein kaputter Raum reisst die anderen
 *       nicht mit.</li>
 *   <li>{@code try/catch} um die ganze Schleife — selbst ein Fehler in der
 *       Iterationslogik wird gezaehlt und uebersprungen.</li>
 * </ol>
 *
 * <p><b>Absicht:</b> der Treiber schweigt ueber Laeufer. Ein stillschweigendes
 * Nachlaufen von {@code deliverFailures()} ist besser als 60 × 1000
 * Exception-Stacktraces pro Sekunde; wer es ueberwachen will, pollt die Zahl.
 *
 * <p><b>Watchdog:</b> {@code TickDriver} ist kein Actor und hat keine
 * OTP-Supervision. Der Pump-Thread ist jedoch vollstaendig zustandslos, also ist
 * ein Neustart trivial. Ein zweiter Daemon-Thread prueft 1 Hz, ob noch Ticks
 * kommen, und startet die Pumpe bei Bedarf neu — sichtbar in
 * {@link #stalls()} und {@link #restarts()}.
 */
public final class TickDriver implements AutoCloseable {

    private final Duration interval;
    private final long intervalNanos;
    private final OverrunPolicy policy;
    private final int maxCatchUpTicks;
    private final long stallThresholdNanos;
    private final CopyOnWriteArrayList<ActorRef> subscribers = new CopyOnWriteArrayList<>();

    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong overruns = new AtomicLong();
    private final AtomicLong deliverFailures = new AtomicLong();
    private final AtomicLong driverErrors = new AtomicLong();
    private final AtomicLong stalls = new AtomicLong();
    private final AtomicLong restarts = new AtomicLong();
    private final AtomicLong lastTickAtNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastTickNanos = new AtomicLong(0);

    private volatile boolean closed;
    private volatile boolean running;
    private volatile boolean everStarted;
    private volatile Thread thread;
    private volatile Thread watchdog;
    private volatile BiConsumer<ActorRef, Throwable> onSubscriberError = (room, error) -> { };

    /** Watchdog-lokal: ob die laufende Stall-Episode schon geloggt wurde. */
    private boolean stallReported;

    private static final System.Logger LOG = System.getLogger(TickDriver.class.getName());

    // ------------------------------------------------------------------ Bau

    public TickDriver(Duration interval) {
        this(interval, OverrunPolicy.CLAMP);
    }

    public TickDriver(Duration interval, OverrunPolicy policy) {
        this(interval, policy, catchUpFor(policy), stallThresholdFor(interval, policy));
    }

    private static int catchUpFor(OverrunPolicy policy) {
        if (policy == null) {
            return 0;                        // die Validierung unten meldet es als IAE
        }
        return policy.catchesUp() ? 5 : 0;
    }

    private static long stallThresholdFor(Duration interval, OverrunPolicy policy) {
        long base = Math.max(5, interval.toMillis() * 5) * 1_000_000L;
        return policy != null && policy.catchesUp() ? base * 2 : base;
    }

    public TickDriver(Duration interval, OverrunPolicy policy, int maxCatchUpTicks,
                      long stallThresholdNanos) {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("tick interval must be > 0, was " + interval);
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (maxCatchUpTicks < 0) {
            throw new IllegalArgumentException("maxCatchUpTicks must be >= 0, was " + maxCatchUpTicks);
        }
        if (stallThresholdNanos < 0) {
            throw new IllegalArgumentException("stallThresholdNanos must be >= 0");
        }
        this.interval = interval;
        this.intervalNanos = interval.toNanos();
        this.policy = policy;
        this.maxCatchUpTicks = maxCatchUpTicks;
        this.stallThresholdNanos = stallThresholdNanos;
    }

    /** 60 Hz mit Default-Policy. */
    public static TickDriver start(ActorSystem system, Duration interval) {
        return start(system, interval, OverrunPolicy.CLAMP);
    }

    /** Startet den Treiber direkt — haeufigster Fall. */
    public static TickDriver start(ActorSystem system, Duration interval, OverrunPolicy policy) {
        TickDriver driver = new TickDriver(interval, policy);
        driver.start();
        return driver;
    }

    public static TickDriver at60Hz(ActorSystem system) {
        return start(system, Duration.ofNanos(16_666_667L));
    }

    // ------------------------------------------------------------------ Konfiguration

    /** Optionaler Logger-Anschluss; Default: schweigen (s. Klassenkommentar). */
    public TickDriver onSubscriberError(BiConsumer<ActorRef, Throwable> handler) {
        this.onSubscriberError = handler == null ? (room, error) -> { } : handler;
        return this;
    }

    public Duration interval() {
        return interval;
    }

    public OverrunPolicy policy() {
        return policy;
    }

    public int maxCatchUpTicks() {
        return maxCatchUpTicks;
    }

    // ------------------------------------------------------------------ Abonnements

    /**
     * {@code target} bekommt ab jetzt <b>eine</b> Nachricht pro Tick.
     *
     * @return Handle mit {@link Subscription#cancel()} — der Raum ruft das im
     *         {@code postStop} auf, sonst empfaenge er Ticks bis in alle Ewigkeit
     */
    public Subscription subscribe(ActorRef target) {
        if (closed) {
            throw new IllegalStateException("tick driver already closed");
        }
        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }
        subscribers.addIfAbsent(target);
        return Subscription.of(target, () -> subscribers.remove(target));
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    public Set<ActorRef> subscribers() {
        return Set.copyOf(subscribers);
    }

    // ------------------------------------------------------------------ Lebenszyklus

    public void start() {
        ensureRunning();
        startWatchdog();
    }

    /**
     * Startet die Pumpe, falls sie nicht laeuft. Idempotent; ein echter Neustart
     * nach einem Thread-Tod zaehlt in {@link #restarts()}.
     */
    public synchronized void ensureRunning() {
        if (closed || running) {
            return;
        }
        if (everStarted) {
            restarts.incrementAndGet();
            LOG.log(System.Logger.Level.WARNING, "tick driver restarted");
        }
        everStarted = true;
        Thread t = Thread.ofPlatform().daemon().name("aero-tick").unstarted(this::pump);
        thread = t;
        running = true;
        lastTickAtNanos.set(System.nanoTime());
        t.start();
    }

    private synchronized void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = Thread.ofPlatform().daemon().name("aero-tick-watchdog")
                .unstarted(this::watch);
        watchdog.start();
    }

    /** 1 Hz: Stall erkennen, tote Pumpe ersetzen. */
    private void watch() {
        while (!closed) {
            LockSupport.parkNanos(1_000_000_000L);
            if (closed) {
                return;
            }
            checkLiveness();
        }
    }

    private void checkLiveness() {
        long age = System.nanoTime() - lastTickAtNanos.get();
        Thread t = thread;
        boolean dead = t == null || !t.isAlive();
        if (age <= stallThresholdNanos && !dead) {
            stallReported = false;                       // wieder im Takt
            return;
        }
        // Jede Pruefung mit Ueberschreitung zaehlt — auch wenn der Thread lebt.
        // lastTickAtNanos wird hier bewusst NICHT zurueckgesetzt: ein lebender,
        // aber haengender Pump-Thread (Fremd-Lock, blockierter Subscriber)
        // erzeugt so weiterhin einen stalls()-Eintrag pro Watchdog-Periode,
        // statt nach dem ersten Log fuer immer als "ok" zu gelten. Der Zaehler
        // ist damit ein ehrliches Mass fuer "seit X Sekunden haengt der Driver".
        stalls.incrementAndGet();
        if (dead) {
            LOG.log(System.Logger.Level.WARNING, "tick pump thread is dead — restarting");
            ensureRunning();                             // setzt lastTickAtNanos selbst zurueck
            stallReported = false;
        } else if (!stallReported) {
            // Log gedrosselt (einmal je Episode), der Zaehler bleibt ehrlich.
            LOG.log(System.Logger.Level.WARNING,
                    "tick driver alive but no tick for {0}ms — likely stuck in deliver()",
                    age / 1_000_000L);
            stallReported = true;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        running = false;
        LockSupport.unpark(thread);
        LockSupport.unpark(watchdog);
        subscribers.clear();
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isClosed() {
        return closed;
    }

    /** Der Pump-Thread (Diagnose: {@code thread().isAlive()} + {@link #tickCount()}). */
    public Thread thread() {
        return thread;
    }

    // ------------------------------------------------------------------ Zaehler

    public long tickCount() {
        return tickCount.get();
    }

    public long overruns() {
        return overruns.get();
    }

    public long deliverFailures() {
        return deliverFailures.get();
    }

    public long driverErrors() {
        return driverErrors.get();
    }

    public long stalls() {
        return stalls.get();
    }

    public long restarts() {
        return restarts.get();
    }

    public long lastTickAtNanos() {
        return lastTickAtNanos.get();
    }

    // ------------------------------------------------------------------ Schleife

    private void pump() {
        long deadline = System.nanoTime();               // erster Tick sofort
        long previousTickAt = deadline;
        while (!closed) {
            parkUntil(deadline);
            if (closed) {
                break;
            }
            long now = System.nanoTime();
            if (now > deadline) {
                overruns.incrementAndGet();              // Deadline verpasst
            }
            long count = tickCount.incrementAndGet();
            long fired = System.nanoTime();
            Tick tick = new Tick(count, Duration.ofNanos(fired - previousTickAt), count == 1);
            previousTickAt = fired;
            lastTickAtNanos.set(fired);
            lastTickNanos.set(fired);
            try {
                deliver(tick);
            } catch (Throwable t) {
                driverErrors.incrementAndGet();
                LOG.log(System.Logger.Level.ERROR, "tick driver loop error", t);
            }
            deadline = advance(deadline, System.nanoTime());
        }
        running = false;
    }

    private void parkUntil(long deadline) {
        long delay = deadline - System.nanoTime();
        while (delay > 0 && !closed) {
            LockSupport.parkNanos(delay);
            delay = deadline - System.nanoTime();
        }
    }

    /**
     * Zustellung an alle Abonnenten, isoliert je Abonnent. Ein Tick pro
     * Subscriber: Nachholen geschieht ueber den Zeitplan, nicht ueber wiederholte
     * Zustellung derselben Nummer.
     */
    private void deliver(Tick tick) {
        ActorRef[] targets = subscribers.toArray(new ActorRef[0]);
        for (ActorRef room : targets) {
            try {
                if (!room.tryTell(tick)) {
                    deliverFailures.incrementAndGet();    // Raum ueberlastet -> onLagged dort
                }
            } catch (Throwable t) {
                driverErrors.incrementAndGet();           // NICHT den Treiber beenden
                try {
                    onSubscriberError.accept(room, t);
                } catch (Throwable ignored) {
                    // Ein kaputter Error-Handler darf auch nicht toeten.
                }
            }
        }
    }

    /**
     * Naechste Deadline. Hier entscheidet die Policy, was mit verpasster Zeit
     * passiert; {@code maxCatchUpTicks} begrenzt das Nachholen unabhaengig
     * davon (Death-Spiral-Schutz).
     */
    private long advance(long deadline, long now) {
        long next = deadline + intervalNanos;
        long behind = now - next;
        if (behind <= 0) {
            return next;
        }
        return switch (policy) {
            case DROP -> now + intervalNanos;                        // alles verwerfen
            case CLAMP -> next + (behind / intervalNanos + 1) * intervalNanos;
            case STEP -> {
                long maxDebt = maxCatchUpTicks * intervalNanos;
                yield behind > maxDebt ? now - maxDebt + intervalNanos : next;   // aufholen, gedeckelt
            }
        };
    }
}
