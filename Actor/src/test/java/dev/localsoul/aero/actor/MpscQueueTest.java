package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.MpscQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MpscQueue")
class MpscQueueTest {

    @Test
    @DisplayName("FIFO pro Producer, kein Verlust, keine Duplikate (4 Producer x 25k)")
    void concurrentProducersKeepOrderAndLoseNothing() throws Exception {
        int producers = 4;
        int perProducer = 25_000;
        MpscQueue<Integer> queue = new MpscQueue<>(producers * perProducer);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        List<List<Integer>> byProducer = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            byProducer.add(new ArrayList<>());
        }

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < producers; p++) {
                int id = p;
                pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < perProducer; i++) {
                        assertThat(queue.offer(id * 1_000_000 + i)).isTrue();
                    }
                });
            }
            pool.submit(() -> {
                await(start);
                int total = 0;
                while (total < producers * perProducer) {
                    Integer value = queue.poll();
                    if (value == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    byProducer.get(value / 1_000_000).add(value % 1_000_000);
                    total++;
                }
                done.countDown();
            });
            start.countDown();
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.size()).isZero();
        for (int p = 0; p < producers; p++) {
            assertThat(byProducer.get(p)).as("producer %d FIFO", p).isEqualTo(range(perProducer));
        }
    }

    @Test
    @DisplayName("size()/isEmpty() bleiben unter Producer-Druck korrekt, nichts geht verloren")
    void sizeAndIsEmptyStayConsistentUnderPressure() throws Exception {
        MpscQueue<Integer> queue = new MpscQueue<>(1024);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger consumed = new AtomicInteger();
        AtomicInteger negativeSize = new AtomicInteger();

        CountDownLatch stop = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < 3; p++) {
                pool.submit(() -> {
                    int i = 0;
                    while (stop.getCount() > 0) {
                        if (queue.offer(i++)) {
                            accepted.incrementAndGet();
                        }
                    }
                });
            }
            pool.submit(() -> {
                while (stop.getCount() > 0) {
                    Integer value = queue.poll();
                    if (value != null) {
                        consumed.incrementAndGet();
                    }
                    // Ein Snapshot: size() und isEmpty() sind zwei getrennte
                    // atomare Reads, dazwischen darf ein Producer zuschlagen.
                    // Aussagebar ist nur: size() ist nie negativ.
                    if (queue.size() < 0) {
                        negativeSize.incrementAndGet();
                    }
                }
            });
            Thread.sleep(200);
            stop.countDown();
        }

        assertThat(negativeSize.get()).as("size() war negativ").isZero();
        assertThat(accepted.get()).isGreaterThan(1000);
        // Am Ende muss alles Angebotene auch wieder abholbar sein.
        while (queue.poll() != null) {
            consumed.incrementAndGet();
        }
        assertThat(consumed.get()).isEqualTo(accepted.get());
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("volle Queue lehnt ab, nach drain wieder offen")
    void rejectsWhenFull() {
        MpscQueue<Integer> queue = new MpscQueue<>(2);
        assertThat(queue.offer(1)).isTrue();
        assertThat(queue.offer(2)).isTrue();
        assertThat(queue.offer(3)).isFalse();
        assertThat(queue.size()).isEqualTo(2);
        assertThat(queue.isFull()).isTrue();
        assertThat(queue.poll()).isEqualTo(1);
        assertThat(queue.offer(3)).isTrue();
    }

    @Test
    @DisplayName("closed: keine Annahme mehr, Rest bleibt lesbar")
    void closedRejectsButKeepsRest() {
        MpscQueue<Integer> queue = new MpscQueue<>(4);
        queue.offer(1);
        queue.close();
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue.poll()).isEqualTo(1);
        assertThat(queue.poll()).isNull();
        assertThat(queue.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("Kapazitaet wird auf Zweierpotenz aufgerundet")
    void roundsCapacityUp() {
        assertThat(MpscQueue.roundUpToPowerOfTwo(1)).isEqualTo(2);
        assertThat(MpscQueue.roundUpToPowerOfTwo(2)).isEqualTo(2);
        assertThat(MpscQueue.roundUpToPowerOfTwo(3)).isEqualTo(4);
        assertThat(MpscQueue.roundUpToPowerOfTwo(1000)).isEqualTo(1024);
        assertThat(new MpscQueue<>(1000).capacity()).isEqualTo(1024);
    }

    @Test
    @DisplayName("Buchfuehrung stimmt ueber 2000 Annahmen/Zyklen (Index-Wraparound-Logik)")
    void accountingStaysExactOverManyCycles() {
        MpscQueue<Integer> queue = new MpscQueue<>(8);
        int offered = 0;
        int polled = 0;
        for (int round = 0; round < 2000; round++) {
            while (!queue.offer(offered)) {           // voll -> drainen
                assertThat(queue.poll()).isNotNull();
                polled++;
            }
            offered++;
            assertThat(queue.size()).isBetween(0, 8);
        }
        while (queue.poll() != null) {
            polled++;
        }
        assertThat(offered).isEqualTo(2000);
        assertThat(polled).isEqualTo(2000);
        assertThat(queue.size()).isZero();
        assertThat(queue.isEmpty()).isTrue();
    }

    private static List<Integer> range(int n) {
        List<Integer> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            values.add(i);
        }
        return values;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
