package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultMailbox")
class DefaultMailboxTest {

    private static Envelope env(int i) {
        return Envelope.user(i, null);
    }

    @Test
    @DisplayName("FIFO, kein Verlust (4 Producer x 10k)")
    void fifoPerProducer() {
        DefaultMailbox mailbox = new DefaultMailbox(1 << 16, MailboxOverflow.FAIL, null);
        int producers = 4;
        int perProducer = 10_000;
        List<List<Integer>> byProducer = new ArrayList<>();
        for (int i = 0; i < producers; i++) {
            byProducer.add(new ArrayList<>());
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < producers; p++) {
                int id = p;
                pool.submit(() -> {
                    for (int i = 0; i < perProducer; i++) {
                        assertThat(mailbox.offer(env(id * 100_000 + i))).isTrue();
                    }
                });
            }
        }
        int total = producers * perProducer;
        for (int i = 0; i < total; i++) {
            Envelope envelope = mailbox.poll();
            assertThat(envelope).isNotNull();
            int value = (Integer) envelope.message();
            byProducer.get(value / 100_000).add(value % 100_000);
        }
        for (int p = 0; p < producers; p++) {
            assertThat(byProducer.get(p)).isEqualTo(range(perProducer));
        }
        assertThat(mailbox.isEmpty()).isTrue();
        assertThat(mailbox.size()).isZero();
    }

    @Test
    @DisplayName("FAIL: offer liefert false und zaehlt Rejects, tell-Exception existiert nicht")
    void failPolicyRejectsSilently() {
        DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.FAIL, null);
        assertThat(mailbox.offer(env(1))).isTrue();
        assertThat(mailbox.offer(env(2))).isTrue();
        assertThat(mailbox.offer(env(3))).isFalse();
        assertThat(mailbox.rejected()).isEqualTo(1);
        // Die wartenden Nachrichten sind unberuehrt.
        assertThat(mailbox.poll().message()).isEqualTo(1);
        assertThat(mailbox.poll().message()).isEqualTo(2);
    }

    @Test
    @DisplayName("DROP_NEWEST: Neue Nachricht wird verworfen, der Rest bleibt unveraendert")
    void dropNewestKeepsOlder() {
        DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.DROP_NEWEST, null);
        assertThat(mailbox.offer(env(1))).isTrue();
        assertThat(mailbox.offer(env(2))).isTrue();
        assertThat(mailbox.offer(env(3))).isFalse();
        assertThat(mailbox.rejected()).isEqualTo(1);
        assertThat(mailbox.poll().message()).isEqualTo(1);
        assertThat(mailbox.poll().message()).isEqualTo(2);
    }

    @Test
    @DisplayName("BLOCK + Self-Send wirft IllegalStateException statt zu haengen")
    @Timeout(10)
    void blockSelfSendThrowsInsteadOfDeadlocking() {
        ActorRef owner = new TestRefs.FakeRef("self");
        DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.BLOCK, owner);
        assertThat(mailbox.offer(env(1))).isTrue();
        assertThat(mailbox.offer(env(2))).isTrue();

        assertThatThrownBy(() -> mailbox.offer(Envelope.user("nachricht", owner)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deadlock")
                .hasMessageContaining("self");

        // Fremder Absender blockiert wie vorgesehen — und kommt nach drain durch.
        AtomicBoolean offered = new AtomicBoolean();
        Thread producer = Thread.ofVirtual().start(() -> {
            assertThat(mailbox.offer(Envelope.user("fremd", new TestRefs.FakeRef("other")))).isTrue();
            offered.set(true);
        });
        waitUntil(() -> mailbox.size() == 2);
        assertThat(mailbox.poll()).isNotNull();
        assertThat(mailbox.poll()).isNotNull();
        try {
            producer.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(offered.get()).isTrue();
    }

    @Test
    @DisplayName("take() liefert null nur bei close + leer")
    @Timeout(20)
    void takeReturnsNullOnlyWhenClosedAndEmpty() throws Exception {
        // Unbegrenztes Postfach: sonst testet man hier Backpressure statt des
        // take()-Vertrags (ein ungebremster Producer laeuft in eine bounded
        // Mailbox natuerlich in Rueckweisungen).
        LinkedMailbox mailbox = new LinkedMailbox();
        AtomicInteger prematureNulls = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();

        Thread consumer = Thread.ofVirtual().start(() -> {
            while (true) {
                Envelope envelope = mailbox.take();
                if (envelope == null) {
                    // take() darf nur null liefern, wenn wirklich nichts mehr
                    // kommt — nie, solange noch etwas anliegt.
                    if (!mailbox.isEmpty()) {
                        prematureNulls.incrementAndGet();
                    }
                    return;
                }
                taken.incrementAndGet();
            }
        });

        for (int i = 0; i < 20_000; i++) {
            assertThat(mailbox.offer(env(i))).isTrue();
        }
        // Last, ohne close: take() darf nicht zurueckkommen.
        waitUntil(() -> taken.get() == 20_000);
        Thread.sleep(150);
        assertThat(prematureNulls.get()).isZero();
        assertThat(consumer.isAlive()).as("take() blockiert weiter").isTrue();

        mailbox.close();
        consumer.join(5_000);
        assertThat(consumer.isAlive()).isFalse();
        assertThat(prematureNulls.get()).isZero();
        assertThat(taken.get()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("take() liefert bei bounded Mailbox nie null, solange etwas anliegt")
    @Timeout(30)
    void boundedTakeNeverReturnsNullWhileBusy() throws Exception {
        DefaultMailbox mailbox = new DefaultMailbox(64, MailboxOverflow.FAIL, null);
        AtomicInteger prematureNulls = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        AtomicInteger retrying = new AtomicInteger();

        Thread consumer = Thread.ofVirtual().start(() -> {
            while (true) {
                Envelope envelope = mailbox.take();
                if (envelope == null) {
                    if (!mailbox.isEmpty()) {
                        prematureNulls.incrementAndGet();
                    }
                    return;
                }
                taken.incrementAndGet();
            }
        });

        // Producer beachtet die Kapazitaet — genau das ist Rueckmeldung.
        Thread producer = Thread.ofVirtual().start(() -> {
            for (int i = 0; i < 50_000; i++) {
                while (!mailbox.offer(env(i))) {
                    retrying.incrementAndGet();
                    Thread.onSpinWait();
                }
            }
        });
        producer.join(20_000);
        assertThat(producer.isAlive()).isFalse();
        assertThat(taken.get()).isEqualTo(50_000);
        assertThat(prematureNulls.get()).isZero();
        assertThat(consumer.isAlive()).isTrue();
        mailbox.close();
        consumer.join(5_000);
    }

    @Test
    @DisplayName("close() drainet: bereits wartende Nachrichten kommen noch raus")
    @Timeout(20)
    void closeDrainsPending() throws Exception {
        DefaultMailbox mailbox = new DefaultMailbox(64, MailboxOverflow.FAIL, null);
        for (int i = 0; i < 10; i++) {
            assertThat(mailbox.offer(env(i))).isTrue();
        }
        mailbox.close();
        assertThat(mailbox.isClosed()).isTrue();
        for (int i = 0; i < 10; i++) {
            Envelope envelope = mailbox.take();
            assertThat(envelope).as("drain %d", i).isNotNull();
            assertThat(envelope.message()).isEqualTo(i);
        }
        assertThat(mailbox.take()).isNull();
    }

    @Test
    @DisplayName("kill() verwirft sofort")
    @Timeout(20)
    void killDiscards() throws Exception {
        DefaultMailbox mailbox = new DefaultMailbox(64, MailboxOverflow.FAIL, null);
        for (int i = 0; i < 10; i++) {
            mailbox.offer(env(i));
        }
        mailbox.kill();
        assertThat(mailbox.take()).isNull();
        assertThat(mailbox.offer(env(99))).isFalse();
    }

    @Test
    @DisplayName("interrupt des wartenden take()-Threads fuehrt zu sauberem Ende")
    @Timeout(20)
    void takeRespondsToInterrupt() throws Exception {
        DefaultMailbox mailbox = new DefaultMailbox(8, MailboxOverflow.FAIL, null);
        Thread consumer = Thread.ofVirtual().start(() -> assertThat(mailbox.take()).isNull());
        Thread.sleep(50);
        consumer.interrupt();
        consumer.join(5_000);
        assertThat(consumer.isAlive()).isFalse();
    }

    @Test
    @DisplayName("offer wirft nie — auch nicht bei kaputter Policy")
    void offerNeverThrows() {
        assertThatCode(() -> {
            DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.FAIL, null);
            for (int i = 0; i < 100; i++) {
                mailbox.offer(env(i));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Kapazitaet unter 2 wird abgelehnt")
    void rejectsTinyCapacity() {
        assertThatThrownBy(() -> new DefaultMailbox(1, MailboxOverflow.FAIL, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Mailboxes.requireCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("BLOCK auf geschlossener Mailbox liefert false — keine angenommene Luege")
    @Timeout(10)
    void blockPolicyOnClosedMailboxReturnsFalse() {
        DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.BLOCK, null);
        mailbox.close();
        assertThat(mailbox.offer(env(1))).as("geschlossen: nicht angenommen").isFalse();
        assertThat(mailbox.rejected()).isEqualTo(1);
    }

    @Test
    @DisplayName("BLOCK: ein wartender Producer wird beim close mit false entlassen")
    @Timeout(10)
    void blockPolicyReleasesBlockedProducerOnClose() throws Exception {
        DefaultMailbox mailbox = new DefaultMailbox(2, MailboxOverflow.BLOCK, null);
        mailbox.offer(env(1));
        mailbox.offer(env(2));
        AtomicBoolean returned = new AtomicBoolean();
        AtomicBoolean accepted = new AtomicBoolean(true);
        Thread producer = Thread.ofVirtual().start(() -> {
            accepted.set(mailbox.offer(env(3)));      // blockiert, bis ein Slot frei wird
            returned.set(true);
        });
        waitUntil(() -> mailbox.blocks() > 0);
        mailbox.close();                              // entlassen, nicht annehmen
        producer.join(5_000);
        assertThat(returned.get()).isTrue();
        assertThat(accepted.get()).as("geschlossen: nicht angenommen").isFalse();
        assertThat(mailbox.rejected()).isEqualTo(1);
    }

    private static List<Integer> range(int n) {
        List<Integer> values = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            values.add(i);
        }
        return values;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new IllegalStateException("condition not met within 5s");
    }
}
