package dev.localsoul.aero.actor;

import dev.localsoul.aero.actor.internal.Envelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CoalescingMailbox")
class CoalescingMailboxTest {

    /** Latest-Wins-Nachricht. */
    record Position(int client, int x) implements Coalescible {
        @Override public Object coalesceKey() { return client; }
    }

    record Say(int line) {
    }

    private static Envelope user(Object message, ActorRef sender) {
        return Envelope.user(message, sender);
    }

    @Test
    @DisplayName("1000 Einreichungen, ein Schluessel -> genau eine Zustellung, der letzte Wert")
    void latestWinsPerKey() {
        CoalescingMailbox mailbox = new CoalescingMailbox(64);
        TestRefs.FakeRef sender = new TestRefs.FakeRef("client");

        for (int i = 0; i < 1000; i++) {
            assertThat(mailbox.offer(user(new Position(7, i), sender))).isTrue();
        }
        assertThat(mailbox.coalescedCount()).isEqualTo(999);

        Envelope delivered = mailbox.take();
        assertThat(delivered).isNotNull();
        assertThat(delivered.message()).isEqualTo(new Position(7, 999));
        assertThat(mailbox.isEmpty()).isTrue();
        assertThat(mailbox.size()).isZero();
    }

    @Test
    @DisplayName("verschiedene Schluessel ueberleben alle")
    void distinctKeysSurvive() {
        CoalescingMailbox mailbox = new CoalescingMailbox(64);
        TestRefs.FakeRef sender = new TestRefs.FakeRef("client");
        for (int i = 0; i < 20; i++) {
            assertThat(mailbox.offer(user(new Position(i, i * 10), sender))).isTrue();
        }
        List<Position> delivered = drain(mailbox, 20);
        assertThat(delivered).hasSize(20);
        for (int i = 0; i < 20; i++) {
            assertThat(delivered.get(i)).isEqualTo(new Position(i, i * 10));
        }
    }

    @Test
    @DisplayName("size() ist exakt: zaeht logische Nachrichten, nicht Queue-Dateien")
    void sizeIsExactWithDuplicateKeys() {
        CoalescingMailbox mailbox = new CoalescingMailbox(256);
        TestRefs.FakeRef sender = new TestRefs.FakeRef("client");

        // Fuenf Keys, jeder 200x nachgeliefert -> 5 logische Nachrichten,
        // aber 5 Queue-Dateien. Ein grober size()-Zaehler wuerde hier 200
        // melden und jeden Lag-Check ausloesen.
        for (int round = 0; round < 200; round++) {
            for (int key = 0; key < 5; key++) {
                assertThat(mailbox.offer(user(new Position(key, round), sender))).isTrue();
            }
            assertThat(mailbox.size()).isEqualTo(5);
            assertThat(mailbox.isEmpty()).isFalse();
        }
        List<Position> delivered = drain(mailbox, 5);
        assertThat(delivered).extracting(Position::x).containsExactly(199, 199, 199, 199, 199);
        assertThat(mailbox.size()).isZero();
    }

    @Test
    @DisplayName("size() stimmt auch bei gemischten coalescbaren und normalen Nachrichten")
    void sizeIsExactWithMixedTraffic() {
        CoalescingMailbox mailbox = new CoalescingMailbox(256);
        TestRefs.FakeRef sender = new TestRefs.FakeRef("client");
        int rounds = 50;
        for (int round = 0; round < rounds; round++) {
            mailbox.offer(user(new Position(1, round), sender));   // je 1 logisch
            mailbox.offer(user(new Position(2, round), sender));   // je 1 logisch
            mailbox.offer(user(new Say(round), sender));           // 1 logisch
            // 2 Positionen (verschmolzen) + (round + 1) Chat-Zeilen
            assertThat(mailbox.size()).as("runde %d", round).isEqualTo(2 + round + 1);
        }
        List<Object> delivered = new ArrayList<>();
        for (int i = 0; i < rounds + 2; i++) {
            delivered.add(mailbox.take().message());
        }
        // Verschmolzene Positionen kommen an erster Stelle (ihre Datei war die
        // erste), danach die Chat-Zeilen in Einreichungsreihenfolge.
        assertThat(delivered).hasSize(rounds + 2);
        assertThat(delivered.subList(0, 3)).containsExactly(
                new Position(1, rounds - 1), new Position(2, rounds - 1), new Say(0));
        assertThat(delivered.get(delivered.size() - 1)).isEqualTo(new Say(rounds - 1));
        assertThat(mailbox.isEmpty()).isTrue();
        assertThat(mailbox.size()).isZero();
    }

    @Test
    @DisplayName("nicht-coalescbare Nachrichten behalten ihre Reihenfolge")
    void nonCoalescableKeepOrder() {
        CoalescingMailbox mailbox = new CoalescingMailbox(256);
        TestRefs.FakeRef sender = new TestRefs.FakeRef("client");
        for (int i = 0; i < 100; i++) {
            assertThat(mailbox.offer(user(new Say(i), sender))).isTrue();
        }
        List<Say> delivered = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            delivered.add((Say) mailbox.take().message());
        }
        List<Say> expected = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            expected.add(new Say(i));
        }
        assertThat(delivered).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("verschiedene Absender werden nie verschmolzen")
    void differentSendersAreNotMerged() {
        CoalescingMailbox mailbox = new CoalescingMailbox(64);
        TestRefs.FakeRef alice = new TestRefs.FakeRef("alice");
        TestRefs.FakeRef bob = new TestRefs.FakeRef("bob");
        // Beide benutzen Key 1 — das sind fuer den Raum zwei verschiedene Spieler.
        assertThat(mailbox.offer(user(new Position(1, 100), alice))).isTrue();
        assertThat(mailbox.offer(user(new Position(1, 200), bob))).isTrue();
        assertThat(mailbox.offer(user(new Position(1, 300), bob))).isTrue();
        assertThat(mailbox.size()).isEqualTo(2);
        assertThat(drain(mailbox, 2)).containsExactly(new Position(1, 100), new Position(1, 300));
    }

    @Test
    @DisplayName("4 Producer-Stress: kein Verlust distinct, latest wins je (Sender,Key)")
    @Timeout(30)
    void concurrentProducersCoalesce() {
        int producers = 4;
        int rounds = 20_000;
        CoalescingMailbox mailbox = new CoalescingMailbox(1 << 12);
        AtomicInteger rejected = new AtomicInteger();

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < producers; p++) {
                TestRefs.FakeRef sender = new TestRefs.FakeRef("c" + p);
                pool.submit(() -> {
                    for (int i = 0; i < rounds; i++) {
                        // Fuenf Keys je Producer: 4 * 5 logische Nachrichten am Ende
                        if (!mailbox.offer(user(new Position(i % 5, i), sender))) {
                            rejected.incrementAndGet();
                        }
                    }
                });
            }
        }
        List<Position> delivered = drain(mailbox, producers * 5);
        assertThat(rejected.get()).as("Kapazitaet reichte nicht").isZero();
        assertThat(delivered).hasSize(producers * 5);
        for (Position position : delivered) {
            assertThat(position.x()).as("letzter Wert je Key").isEqualTo(rounds - 5 + position.client());
        }
        assertThat(mailbox.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("close drainet, kill verwirft")
    void closeDrainsAndKillDiscards() {
        TestRefs.FakeRef sender = new TestRefs.FakeRef("c");

        CoalescingMailbox closed = new CoalescingMailbox(64);
        for (int i = 0; i < 5; i++) {
            closed.offer(user(new Position(i, i), sender));
        }
        closed.close();
        assertThat(closed.offer(user(new Position(9, 9), sender))).as("geschlossen lehnt ab").isFalse();
        assertThat(drain(closed, 5)).hasSize(5);
        assertThat(closed.take()).isNull();
        assertThat(closed.isEmpty()).isTrue();

        CoalescingMailbox killed = new CoalescingMailbox(64);
        for (int i = 0; i < 5; i++) {
            killed.offer(user(new Position(i, i), sender));
        }
        assertThat(killed.size()).isEqualTo(5);
        killed.kill();
        assertThat(killed.take()).as("kill verwirft sofort").isNull();
        assertThat(killed.isEmpty()).isTrue();
        assertThat(killed.size()).isZero();
    }

    @Test
    @DisplayName("Systemnachrichten werden nie verschmolzen")
    void systemMessagesAreNeverCoalesced() {
        CoalescingMailbox mailbox = new CoalescingMailbox(64);
        assertThat(mailbox.offer(Envelope.system(new Say(1)))).isTrue();
        assertThat(mailbox.offer(Envelope.system(new Say(2)))).isTrue();
        assertThat(mailbox.size()).isEqualTo(2);
        assertThat(mailbox.poll().message()).isEqualTo(new Say(1));
        assertThat(mailbox.poll().message()).isEqualTo(new Say(2));
    }

    private static <T> List<T> drain(Mailbox mailbox, int count) {
        List<T> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Envelope envelope = mailbox.take();
            assertThat(envelope).as("Element %d", i).isNotNull();
            @SuppressWarnings("unchecked")
            T message = (T) envelope.message();
            messages.add(message);
        }
        return messages;
    }

}
