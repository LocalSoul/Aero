package dev.localsoul.aero.actor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Mailbox;
import dev.localsoul.aero.actor.MailboxOverflow;
import dev.localsoul.aero.actor.Mailboxes;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression fuer implement.md 6.4/16.1: {@code drainInbox} darf beim losen
 * {@code poll() == null} nicht vorzeitig abbrechen. {@code poll()} ist
 * verlustbehaftet — ein Producer kann einen Slot reserviert haben, seinen Wert
 * aber noch nicht publiziert haben. Die exakte Abbruchbedingung ist
 * {@code isEmpty()}, sonst wird gesponnen, bis der Slot publiziert ist.
 */
@DisplayName("DrainInbox im Producer-Publish-Fenster")
class DrainInboxPublishWindowTest {

    /**
     * Simuliert das Publish-Fenster: {@code poll()} antwortet eine Weile mit
     * {@code null}, obwohl Nachrichten angenommen wurden ({@code isEmpty()}
     * bleibt korrekt {@code false}).
     */
    private static final class StallingMailbox implements Mailbox {

        private final Mailbox delegate;
        private volatile int stalls;

        StallingMailbox(Mailbox delegate) {
            this.delegate = delegate;
        }

        /** Die naechsten {@code n} {@code poll()}-Aufrufe liefern {@code null}. */
        void armStall(int n) {
            stalls = n;
        }

        @Override public Envelope poll() {
            int s = stalls;
            if (s > 0) {
                stalls = s - 1;
                return null;
            }
            return delegate.poll();
        }

        @Override public boolean offer(Envelope envelope) { return delegate.offer(envelope); }
        @Override public Envelope take() { return delegate.take(); }
        @Override public boolean isEmpty() { return delegate.isEmpty(); }
        @Override public int size() { return delegate.size(); }
        @Override public int capacity() { return delegate.capacity(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public void close() { delegate.close(); }
        @Override public void kill() { delegate.kill(); }
        @Override public long rejected() { return delegate.rejected(); }
        @Override public long blocks() { return delegate.blocks(); }
    }

    private record Gate(CountDownLatch blocked, CountDownLatch release) {
        static Gate create() {
            return new Gate(new CountDownLatch(1), new CountDownLatch(1));
        }
    }

    @Test
    @DisplayName("drainInbox spult das Publish-Fenster ab und verarbeitet das volle Budget")
    void drainWaitsOutThePublishWindow() throws Exception {
        ActorSystem system = new ActorSystem("drain");
        try {
            StallingMailbox mailbox =
                    new StallingMailbox(Mailboxes.bounded(16, MailboxOverflow.FAIL, null));
            Gate gate = Gate.create();
            AtomicInteger drained = new AtomicInteger(-1);
            AtomicInteger handled = new AtomicInteger();

            Actor actor = new Actor("drain") {
                @Override protected Behavior onStart(ActorContext ctx) {
                    return Receive.of(
                            Clause.on(String.class, (m, c) -> {
                                gate.blocked().countDown();
                                try {
                                    gate.release().await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                mailbox.armStall(2);         // Publish-Fenster simulieren
                                drained.set(c.drainInbox(8));
                                return Behavior.NEXT;
                            }),
                            Clause.on(Integer.class, (i, c) -> {
                                handled.incrementAndGet();
                                return Behavior.NEXT;
                            })
                    );
                }
            };
            ActorCell cell = new ActorCell(actor, system, "drain", mailbox);
            cell.start();

            // Erste Nachricht festfahren, dann Eingaben hinter dem Handler einreihen.
            cell.postUser(Envelope.user("go", null));
            assertThat(gate.blocked().await(5, TimeUnit.SECONDS)).as("Handler blockiert").isTrue();
            for (int i = 1; i <= 5; i++) {
                cell.postUser(Envelope.user(i, null));
            }
            gate.release().countDown();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (drained.get() < 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertThat(drained.get()).as("alle 5 trotz Publish-Fenster").isEqualTo(5);
            assertThat(handled.get()).isEqualTo(5);
        } finally {
            system.close();
        }
    }
}