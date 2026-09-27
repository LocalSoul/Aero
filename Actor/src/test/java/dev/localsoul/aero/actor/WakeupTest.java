package dev.localsoul.aero.actor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.internal.Wakeup;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regressionstests fuer den Weck-Mechanismus. Beide Fehler waren dort, wo ein
 * Signal im falschen Moment eintrifft: einmal wurde geparkt, obwohl Arbeit
 * wartete (verlorenes Signal), einmal gar nicht geparkt (Busy-Spin).
 */
class WakeupTest {

    /** Zaehlt {@code false}-Rueckgaben: das war der verpasste Weck-Mechanismus. */
    private static final int ROUNDS = 20_000;

    @Test
    @DisplayName("ein Signal im kritischen Fenster geht nie verloren")
    void signalIsNeverLost() throws Exception {
        Wakeup wakeup = new Wakeup();
        AtomicInteger lost = new AtomicInteger();
        AtomicInteger work = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        Thread consumer = Thread.ofVirtual().start(() -> {
            wakeup.bind(Thread.currentThread());
            for (int i = 0; i < ROUNDS; i++) {
                if (!wakeup.await(() -> work.get() > 0)) {
                    lost.incrementAndGet();
                }
                work.decrementAndGet();
            }
            done.countDown();
        });

        for (int i = 0; i < ROUNDS; i++) {
            work.incrementAndGet();
            wakeup.signal();
        }

        assertThat(done.await(30, TimeUnit.SECONDS)).as("consumer fertig").isTrue();
        assertThat(lost.get()).as("verlorene Weckrufe").isZero();
        assertThat(work.get()).isZero();
        consumer.join(5_000);
    }

    @Test
    @DisplayName("der Consumer parkt wirklich, wenn nichts zu tun ist")
    void parksWhenIdle() throws Exception {
        Wakeup wakeup = new Wakeup();
        CountDownLatch parked = new CountDownLatch(1);
        AtomicReference<Boolean> result = new AtomicReference<>();
        // Der Supplier liest den echten Zustand — wie eine Mailbox, die erst
        // durch das Signal Arbeit bekommt.
        java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();

        Thread consumer = Thread.ofVirtual().start(() -> {
            wakeup.bind(Thread.currentThread());
            result.set(wakeup.await(pending::get));
            parked.countDown();
        });

        // Der Consumer muss im Park sein, bevor der Producer liefert.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && consumer.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        assertThat(consumer.getState())
                .as("Consumer liegt im LockSupport.park, nicht im Busy-Loop")
                .isEqualTo(Thread.State.WAITING);
        assertThat(parked.getCount()).isEqualTo(1);

        pending.set(true);
        wakeup.signal();
        assertThat(parked.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isTrue();
    }

    @Test
    @DisplayName("stop beendet das Warten, auch wenn nichts anliegt")
    void stopEndsAwait() throws Exception {
        Wakeup wakeup = new Wakeup();
        AtomicReference<Boolean> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread consumer = Thread.ofVirtual().start(() -> {
            wakeup.bind(Thread.currentThread());
            result.set(wakeup.await(() -> false));
            finished.countDown();
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && consumer.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        wakeup.stop();

        assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isFalse();
        assertThat(wakeup.stopped()).isTrue();
    }

    @Test
    @DisplayName("ohne Bindung blockiert ein fremder Thread nicht")
    void foreignThreadDoesNotBlock() {
        Wakeup wakeup = new Wakeup();
        Thread other = Thread.ofPlatform().start(() -> { });   // fremder Thread
        wakeup.bind(other);
        assertThat(wakeup.await(() -> false)).as("falscher Thread parkt nicht").isTrue();
    }

    @Test
    @DisplayName("Interrupt bricht das Warten ab")
    void interruptEndsAwait() throws Exception {
        Wakeup wakeup = new Wakeup();
        AtomicReference<Boolean> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);

        Thread consumer = Thread.ofVirtual().start(() -> {
            wakeup.bind(Thread.currentThread());
            result.set(wakeup.await(() -> false));
            finished.countDown();
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && consumer.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        consumer.interrupt();

        assertThat(finished.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isFalse();
    }
}
