package dev.localsoul.aero.game;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.ActorSystem;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.tick.Tick;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.OutboundBatch;
import dev.localsoul.aero.game.actor.GameMessages.PlayerCreate;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.RealmActor;
import dev.localsoul.aero.game.protocol.OutgoingMessage;
import dev.localsoul.aero.game.world.Map;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Gemeinsame Helfer für die Realm-/Kampf-Tests (V2): ein {@code CaptureActor}
 * als Session-Seite, {@code await(...)} mit 5-s-Deadline und Join-Werkzeuge.
 * Muster wie {@code RealmActorTest}: {@code driver = null}, Ticks manuell.
 */
final class RealmTestKit {

    private RealmTestKit() {
    }

    /** Fängt alle Nachrichten der Session-Seite auf. */
    static final class CaptureActor extends Actor {
        final List<Object> messages = new CopyOnWriteArrayList<>();
        private volatile ActorRef self;

        CaptureActor(final String name) {
            super(name);
        }

        @Override
        protected Behavior onStart(final ActorContext ctx) {
            self = ctx.self();
            return Receive.of(Receive.Clause.any((m, c) -> {
                messages.add(m);
                return Behavior.NEXT;
            }));
        }

        /** Eigene ActorRef (aus {@code onStart}). */
        ActorRef ref() {
            return self;
        }
    }

    /** Spieler in den Realm holen (Hello + Create), objectId des Joins zurück. */
    static int join(final ActorRef realmRef, final CaptureActor cap) throws Exception {
        awaitTrue(() -> cap.ref() != null);              // onStart ist gelaufen
        realmRef.tell(new PlayerHello(cap.ref(), 0));
        realmRef.tell(new PlayerCreate(cap.ref(), 782, 0));
        await(() -> cap.messages.stream().anyMatch(JoinConfirmed.class::isInstance));
        final JoinConfirmed joined = (JoinConfirmed) cap.messages.stream()
                .filter(JoinConfirmed.class::isInstance).findFirst().orElseThrow();
        return joined.objectId();
    }

    /** N Ticks mit gegebener Dauer in den Realm schicken (jetzt + ms je Tick). */
    static void ticks(final ActorRef realmRef, final int count, final long msPerTick) {
        for (int i = 1; i <= count; i++) {
            realmRef.tell(new Tick(i, Duration.ofMillis(msPerTick), false));
        }
    }

    /** Erste Paket-Instanz eines Typs in allen bisherigen Batches finden. */
    static <T extends OutgoingMessage> T firstPacket(final CaptureActor cap,
                                                     final Class<T> type) {
        for (final Object msg : cap.messages) {
            if (msg instanceof OutboundBatch batch) {
                for (final OutgoingMessage packet : batch.messages()) {
                    if (type.isInstance(packet)) {
                        return type.cast(packet);
                    }
                }
            }
        }
        return null;
    }

    /** Alle Paket-Instanzen eines Typs in allen bisherigen Batches sammeln. */
    static <T extends OutgoingMessage> List<T> packets(final CaptureActor cap,
                                                       final Class<T> type) {
        final List<T> result = new ArrayList<>();
        for (final Object msg : cap.messages) {
            if (msg instanceof OutboundBatch batch) {
                for (final OutgoingMessage packet : batch.messages()) {
                    if (type.isInstance(packet)) {
                        result.add(type.cast(packet));
                    }
                }
            }
        }
        return result;
    }

    static boolean await(final BooleanSupplier condition) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(1);
        }
        return false;
    }

    static void awaitTrue(final BooleanSupplier condition) throws Exception {
        if (!await(condition)) {
            throw new AssertionError("Bedingung nicht innerhalb 5s erfüllt");
        }
    }

    static <T extends OutgoingMessage> T awaitPacket(final CaptureActor cap, final Class<T> type,
                                                     final Predicate<T> predicate) throws Exception {
        awaitTrue(() -> messages(cap).anyMatch(m -> type.isInstance(m) && predicate.test(type.cast(m))));
        return messages(cap).filter(type::isInstance).map(type::cast).filter(predicate)
                .findFirst().orElseThrow();
    }

    private static java.util.stream.Stream<OutgoingMessage> messages(final CaptureActor cap) {
        return cap.messages.stream()
                .filter(OutboundBatch.class::isInstance)
                .flatMap(m -> ((OutboundBatch) m).messages().stream());
    }

    static RealmActor realmWith(final ActorSystem system, final int monsters) {
        return new RealmActor("realm", null, new Map(50, 50), monsters);
    }
}