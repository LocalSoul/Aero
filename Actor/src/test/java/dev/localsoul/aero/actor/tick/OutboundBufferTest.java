package dev.localsoul.aero.actor.tick;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.TestRefs;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboundBufferTest {

    @Test
    @DisplayName("40 send() an einen Client ergeben eine Nachricht mit 40 Paketen")
    void aggregatesPerClient() {
        OutboundBuffer buffer = new OutboundBuffer();
        ActorRef client = new TestRefs.FakeRef("c1");
        for (int i = 0; i < 40; i++) {
            buffer.send(client, "delta-" + i);
        }
        assertThat(buffer.pendingClients()).isEqualTo(1);
        assertThat(buffer.pendingPackets()).isEqualTo(40);

        List<OutboundBuffer.Outbound> received = new ArrayList<>();
        ActorRef sink = new TestRefs.FakeRef("sink").onMessage(
                m -> received.add((OutboundBuffer.Outbound) m));
        OutboundBuffer forward = new OutboundBuffer();
        for (int i = 0; i < 40; i++) {
            forward.send(sink, "d" + i);
        }
        assertThat(forward.flush(null)).isEqualTo(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).packets()).hasSize(40);
        assertThat(received.get(0).packets().get(0)).isEqualTo("d0");
        assertThat(received.get(0).packets().get(39)).isEqualTo("d39");
    }

    @Test
    @DisplayName("je Client genau eine Nachricht — die Reihenfolge bleibt")
    void oneMessagePerClient() {
        List<OutboundBuffer.Outbound> gotA = new ArrayList<>();
        List<OutboundBuffer.Outbound> gotB = new ArrayList<>();
        ActorRef refA = new TestRefs.FakeRef("a").onMessage(m -> gotA.add((OutboundBuffer.Outbound) m));
        ActorRef refB = new TestRefs.FakeRef("b").onMessage(m -> gotB.add((OutboundBuffer.Outbound) m));

        OutboundBuffer buffer = new OutboundBuffer();
        for (int i = 0; i < 3; i++) {
            buffer.send(refA, "a" + i);
            buffer.send(refB, "b" + i);
        }
        assertThat(buffer.flush(OutboundBuffer.IDENTITY)).isEqualTo(2);
        assertThat(gotA).hasSize(1);
        assertThat(gotB).hasSize(1);
        assertThat(gotA.get(0).packets()).containsExactly("a0", "a1", "a2");
        assertThat(gotB.get(0).packets()).containsExactly("b0", "b1", "b2");
    }

    @Test
    @DisplayName("sendAll schickt an viele, je Client eine Nachricht")
    void sendAllFansOut() {
        List<ActorRef> sinks = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sinks.add(new TestRefs.FakeRef("s" + i).onMessage(m -> { }));
        }
        OutboundBuffer buffer = new OutboundBuffer();
        buffer.sendAll(sinks, "hallo");
        assertThat(buffer.pendingClients()).isEqualTo(5);
        assertThat(buffer.flush(null)).isEqualTo(5);
    }

    @Test
    @DisplayName("nach dem Flush ist der Puffer leer, der Client bleibt")
    void flushEmptiesBuffer() {
        ActorRef client = new TestRefs.FakeRef("c");
        OutboundBuffer buffer = new OutboundBuffer();
        buffer.send(client, "x");
        assertThat(buffer.has(client)).isTrue();
        assertThat(buffer.flush(null)).isEqualTo(1);
        assertThat(buffer.has(client)).isFalse();
        assertThat(buffer.pendingPackets()).isZero();
    }

    @Test
    @DisplayName("der Packer sieht den scratch des vorigen Ticks")
    void packerSeesScratch() {
        ActorRef client = new TestRefs.FakeRef("c");
        List<Object> scratches = new ArrayList<>();
        OutboundBuffer buffer = new OutboundBuffer();
        OutboundBuffer.Packer packer = (queued, scratch) -> {
            scratches.add(scratch);
            return "payload:" + queued;
        };
        buffer.send(client, "a");
        buffer.flush(packer);
        assertThat(scratches).hasSize(1);
        assertThat(scratches.get(0)).as("im ersten Tick noch kein scratch").isNull();
        assertThat(buffer.scratchOf(client)).isEqualTo("payload:[a]");

        buffer.send(client, "b");
        buffer.flush(packer);
        assertThat(scratches.get(1)).as("im zweiten Tick der letzte Payload")
                .isEqualTo("payload:[a]");
        assertThat(buffer.scratchOf(client)).isEqualTo("payload:[b]");
    }

    @Test
    @DisplayName("ein volles Postfach zaehlt nicht als geflusht")
    void fullMailboxIsNotFlushed() {
        ActorRef rejecting = new TestRefs.FakeRef("full").rejecting();
        ActorRef good = new TestRefs.FakeRef("good");
        OutboundBuffer buffer = new OutboundBuffer();
        buffer.send(rejecting, "x");
        buffer.send(good, "y");
        assertThat(buffer.flush(null)).as("nur der gute kam an").isEqualTo(1);
    }

    @Test
    @DisplayName("null-Ziel und leerer Puffer sind harmlos")
    void nullsAndEmpty() {
        OutboundBuffer buffer = new OutboundBuffer();
        buffer.send(null, "x");
        assertThat(buffer.pendingPackets()).isZero();
        assertThat(buffer.flush(null)).isZero();
    }

    @Test
    @DisplayName("clear leert auch die scratch-Puffer")
    void clearResetsScratch() {
        ActorRef client = new TestRefs.FakeRef("c");
        OutboundBuffer buffer = new OutboundBuffer();
        buffer.send(client, "a");
        buffer.flush(null);
        assertThat(buffer.scratchOf(client)).isNotNull();
        buffer.clear();
        assertThat(buffer.scratchOf(client)).isNull();
        assertThat(buffer.pendingClients()).isZero();
    }

    @Test
    @DisplayName("Outbound ist unveraenderlich")
    void outboundIsImmutable() {
        List<Object> source = new ArrayList<>(List.of("a", "b"));
        OutboundBuffer.Outbound outbound = new OutboundBuffer.Outbound(source, "p");
        source.add("c");
        assertThat(outbound.packets()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("der Default-Packer laesst die Pakete als Liste durch")
    void identityPacker() {
        Object packed = OutboundBuffer.IDENTITY.pack(List.of("a", "b"), null);
        assertThat(packed).isEqualTo(List.of("a", "b"));
    }

    @Test
    @DisplayName("Aufrufe zaehlen: 1000 send() bleiben 1 Nachricht")
    void highVolumeStaysOneMessage() {
        AtomicInteger deliveries = new AtomicInteger();
        ActorRef client = new TestRefs.FakeRef("c").onMessage(m -> deliveries.incrementAndGet());
        OutboundBuffer buffer = new OutboundBuffer();
        for (int i = 0; i < 1000; i++) {
            buffer.send(client, i);
        }
        buffer.flush(OutboundBuffer.IDENTITY);
        assertThat(deliveries.get()).isEqualTo(1);
    }
}
