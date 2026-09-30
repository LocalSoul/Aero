package dev.localsoul.aero.game.actor;

import dev.localsoul.aero.actor.Actor;
import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.game.actor.GameMessages.BackpressureKick;
import dev.localsoul.aero.game.actor.GameMessages.ChannelClosed;
import dev.localsoul.aero.game.actor.GameMessages.JoinConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.KickConfirmed;
import dev.localsoul.aero.game.actor.GameMessages.KickPlayer;
import dev.localsoul.aero.game.actor.GameMessages.MapInfoReady;
import dev.localsoul.aero.game.actor.GameMessages.OutboundBatch;
import dev.localsoul.aero.game.actor.GameMessages.PlayerCreate;
import dev.localsoul.aero.game.actor.GameMessages.PlayerHello;
import dev.localsoul.aero.game.actor.GameMessages.PlayerJoin;
import dev.localsoul.aero.game.actor.GameMessages.PlayerLeave;
import dev.localsoul.aero.game.actor.GameMessages.PlayerMove;
import dev.localsoul.aero.game.net.NettyClient;
import dev.localsoul.aero.game.protocol.Create;
import dev.localsoul.aero.game.protocol.Escape;
import dev.localsoul.aero.game.protocol.Hello;
import dev.localsoul.aero.game.protocol.Load;
import dev.localsoul.aero.game.protocol.Move;
import dev.localsoul.aero.game.protocol.OutgoingMessage;

/**
 * Eine Netty-Verbindung als Actor (§7 der gameserver_implement.md). Reiner
 * Transport: prüft Pakete **syntaktisch**, leitet semantische Nachrichten an
 * den Realm weiter und schreibt Realm-Outbound in den Channel (gebündelt,
 * §6). Hält keinen Spielzustand — nur die vom Realm vergebene objectId.
 */
public final class SessionActor extends Actor {

    private final ActorRef realm;
    private final NettyClient client;
    private int objectId = -1;

    public SessionActor(final String name, final ActorRef realm, final NettyClient client) {
        super(name);
        this.realm = realm;
        this.client = client;
    }

    @Override
    protected Behavior onStart(final ActorContext ctx) {
        return Receive.of(
                Clause.on(Hello.class, this::onHello),
                Clause.on(Load.class, this::onLoad),
                Clause.on(Create.class, this::onCreate),
                Clause.on(Move.class, this::onMove),
                Clause.on(Escape.class, this::onEscape),
                Clause.on(MapInfoReady.class, (msg, c) -> {
                    client.send(msg.mapInfo());
                    client.flush();
                    return Behavior.NEXT;
                }),
                Clause.on(JoinConfirmed.class, (msg, c) -> {
                    objectId = msg.objectId();
                    // WICHTIG: CreateSuccess VOR Update — der Client setzt
                    // map.player_ nur, wenn playerId_ (aus CreateSuccess) schon
                    // gesetzt ist, wenn das Spieler-Objekt im Update kommt.
                    client.send(msg.createSuccess());
                    client.send(msg.update());
                    client.flush();
                    return Behavior.NEXT;
                }),
                Clause.on(OutboundBatch.class, (msg, c) -> {
                    for (final OutgoingMessage packet : msg.messages()) {
                        client.send(packet);
                    }
                    client.flush();
                    return Behavior.NEXT;
                }),
                Clause.on(BackpressureKick.class, (msg, c) -> {
                    if (objectId >= 0) {
                        c.tell(realm, new KickPlayer(objectId, msg.reason()));
                    } else {
                        client.close();
                    }
                    return Behavior.NEXT;
                }),
                Clause.on(KickConfirmed.class, (msg, c) -> {
                    client.close();
                    c.stop();
                    return Behavior.NEXT;
                }),
                Clause.on(ChannelClosed.class, (msg, c) -> {
                    if (objectId >= 0) {
                        c.tell(realm, new PlayerLeave(objectId));
                    }
                    c.stop();
                    return Behavior.NEXT;
                })
        );
    }

    private Behavior onHello(final Hello hello, final ActorContext ctx) {
        if (hello.buildVersion.isEmpty() && hello.guid.isEmpty()) {
            return Behavior.NEXT;                   // syntaktisch ungültig → ignorieren
        }
        ctx.tell(realm, new PlayerHello(ctx.self(), hello.gameId));
        return Behavior.NEXT;
    }

    private Behavior onLoad(final Load load, final ActorContext ctx) {
        ctx.tell(realm, new PlayerJoin(ctx.self(), load.charId));
        return Behavior.NEXT;
    }

    private Behavior onCreate(final Create create, final ActorContext ctx) {
        ctx.tell(realm, new PlayerCreate(ctx.self(), create.classType, create.skinType));
        return Behavior.NEXT;
    }

    private Behavior onMove(final Move move, final ActorContext ctx) {
        if (objectId < 0) {
            return Behavior.NEXT;                   // vor dem Join: ignorieren
        }
        ctx.tell(realm, new PlayerMove(objectId, move.newPosition.x, move.newPosition.y));
        return Behavior.NEXT;
    }

    private Behavior onEscape(final Escape escape, final ActorContext ctx) {
        if (objectId >= 0) {
            ctx.tell(realm, new PlayerLeave(objectId));
        }
        ctx.stop();
        return Behavior.NEXT;
    }
}