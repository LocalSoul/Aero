package dev.localsoul.aero.game.actor;

import dev.localsoul.aero.actor.ActorContext;
import dev.localsoul.aero.actor.ActorRef;
import dev.localsoul.aero.actor.Behavior;
import dev.localsoul.aero.actor.Receive;
import dev.localsoul.aero.actor.Receive.Clause;
import dev.localsoul.aero.actor.session.Interest;
import dev.localsoul.aero.actor.session.InterestSet;
import dev.localsoul.aero.actor.tick.Tick;
import dev.localsoul.aero.actor.tick.TickActor;
import dev.localsoul.aero.actor.tick.TickContext;
import dev.localsoul.aero.actor.tick.TickDriver;
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
import dev.localsoul.aero.game.protocol.CreateSuccess;
import dev.localsoul.aero.game.protocol.GroundTileData;
import dev.localsoul.aero.game.protocol.MapInfo;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.ObjectData;
import dev.localsoul.aero.game.protocol.OutgoingMessage;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.Map;
import dev.localsoul.aero.game.world.Player;
import io.netty.util.collection.IntObjectHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Reich — ein {@link TickActor}, der seine Entities als **Plain Objects**
 * in einer {@code IntObjectHashMap} hält und pro Tick direkt simuliert
 * (§2.3, §8). Kein Message-Passing pro Entity. Nur der Realm-Thread mutiert
 * die Entity-/Interessen-Struktur; Join/Kick sind als Nachrichtenfluss mit
 * Ack modelliert (§7).
 */
public final class RealmActor extends TickActor {

    /** Demo-Grund: {@code 0x02} Light Cobblestone (embedded GroundsCXML). */
    private static final int GROUND_TYPE = 0x02;

    private final Map map;
    private final IntObjectHashMap<Player> players = new IntObjectHashMap<>();
    private final java.util.Map<ActorRef, Player> bySession = new java.util.HashMap<>();
    private final InterestSet interest = InterestSet.forRadius(100.0);
    private final List<ActorRef> scratch = new ArrayList<>(64);
    private int nextObjectId = 1;

    private final Behavior messages = Receive.of(
            Clause.on(PlayerHello.class, this::onPlayerHello),
            Clause.on(PlayerJoin.class, this::onPlayerJoin),
            Clause.on(PlayerCreate.class, this::onPlayerCreate),
            Clause.on(PlayerMove.class, this::onPlayerMove),
            Clause.on(PlayerLeave.class, this::onPlayerLeave),
            Clause.on(KickPlayer.class, this::onKickPlayer)
    );

    public RealmActor(final String name, final TickDriver driver, final Map map) {
        super(name, driver);
        this.map = map;
    }

    @Override
    protected Behavior onMessage(final Object message, final ActorContext ctx) {
        return messages.invoke(message, ctx);
    }

    private Behavior onPlayerHello(final PlayerHello hello, final ActorContext ctx) {
        final MapInfo mapInfo = new MapInfo();
        mapInfo.width = map.width();
        mapInfo.height = map.height();
        mapInfo.name = "Nexus";
        mapInfo.displayName = "Aero Nexus";
        mapInfo.background = 2;                 // NEXUS_BACKGROUND (Sternenfeld)
        ctx.tell(hello.session(), new MapInfoReady(mapInfo));
        return Behavior.NEXT;
    }

    private Behavior onPlayerJoin(final PlayerJoin join, final ActorContext ctx) {
        spawnPlayer(join.session(), join.charId(), Player.WIZARD);
        return Behavior.NEXT;
    }

    private Behavior onPlayerCreate(final PlayerCreate create, final ActorContext ctx) {
        spawnPlayer(create.session(), 1, (short) create.classType());
        return Behavior.NEXT;
    }

    /** Entity anlegen, objectId vergeben, Startpakete bauen → JoinConfirmed. */
    private void spawnPlayer(final ActorRef session, final int charId, final short objectType) {
        final int objectId = nextObjectId++;
        final Player player = new Player(objectId, map, map.width() / 2f, map.height() / 2f,
                objectType);
        player.setSession(session);
        players.put(objectId, player);
        bySession.put(session, player);
        interest.put(session, Interest.at(player.x(), player.y(), 0, 100.0));

        // Join-Update: Boden + ALLE sichtbaren Objekte (auch bestehende Spieler)
        final Update update = new Update();
        fillTiles(update);
        interest.near(Interest.at(player.x(), player.y(), 0, 100.0), scratch);
        for (final ActorRef visibleSession : scratch) {
            final Player visible = bySession.get(visibleSession);
            if (visible != null) {
                update.newObjs.add(buildObjectData(visible));
            }
        }
        scratch.clear();

        final CreateSuccess success = new CreateSuccess();
        success.objectId = objectId;
        success.charId = charId;
        context().tell(session, new JoinConfirmed(objectId, update, success));

        // Bestehende Spieler über den Neuzugang informieren (sonst sehen sie ihn nie)
        final Update newPlayerUpdate = new Update();
        newPlayerUpdate.newObjs.add(buildObjectData(player));
        notifyOthers(player, newPlayerUpdate);
    }

    /** Update an alle sichtbaren Spieler außer {@code source} schicken. */
    private void notifyOthers(final Player source, final Update update) {
        if (update.newObjs.isEmpty() && update.drops.isEmpty()) {
            return;
        }
        interest.near(Interest.at(source.x(), source.y(), 0, 100.0), scratch);
        for (final ActorRef visibleSession : scratch) {
            final Player visible = bySession.get(visibleSession);
            if (visible != null && visible != source) {
                context().tell(visible.session(), new OutboundBatch(0, List.of(update)));
            }
        }
        scratch.clear();
    }

    private void fillTiles(final Update update) {
        for (short x = 0; x < map.width(); x++) {
            for (short y = 0; y < map.height(); y++) {
                final GroundTileData tile = new GroundTileData();
                tile.x = x;
                tile.y = y;
                tile.type = GROUND_TYPE;
                update.tiles.add(tile);
            }
        }
    }

    private static ObjectData buildObjectData(final Player player) {
        final ObjectData obj = new ObjectData();
        obj.objectType = player.objectType();
        final var status = player.status();
        obj.status.objectId = status.objectId;
        obj.status.pos.x = status.pos.x;
        obj.status.pos.y = status.pos.y;
        obj.status.stats.addAll(status.stats);
        return obj;
    }

    private Behavior onPlayerMove(final PlayerMove move, final ActorContext ctx) {
        final Player player = players.get(move.objectId());
        if (player != null) {
            player.setTarget(move.x(), move.y());
        }
        return Behavior.NEXT;
    }

    private Behavior onPlayerLeave(final PlayerLeave leave, final ActorContext ctx) {
        removePlayer(leave.objectId());
        return Behavior.NEXT;
    }

    private Behavior onKickPlayer(final KickPlayer kick, final ActorContext ctx) {
        final Player player = removePlayer(kick.objectId());
        if (player != null) {
            ctx.tell(player.session(), new KickConfirmed(kick.reason()));
        }
        return Behavior.NEXT;
    }

    /** Nur aus dem Realm-Tick aufrufen. Entfernt aus allen Strukturen. */
    private Player removePlayer(final int objectId) {
        final Player player = players.remove(objectId);
        if (player != null) {
            bySession.remove(player.session());
            interest.remove(player.session());
            // Übrige Spieler informieren: Objekt entfernen (Update.drops)
            final Update removeUpdate = new Update();
            removeUpdate.drops.add(objectId);
            notifyOthers(player, removeUpdate);
        }
        return player;
    }

    @Override
    protected void onTick(final Tick tick, final TickContext tc) {
        final float dt = (float) tick.elapsed().toNanos() / 1_000_000_000f;

        for (final Player player : players.values()) {
            try {
                player.simulate(dt);
            } catch (Throwable t) {
                removePlayer(player.objectId());        // Entity-Fehler isolieren
            }
        }
        for (final Player player : players.values()) {
            interest.move(player.session(), Interest.at(player.x(), player.y(), 0, 100.0));
        }

        final int tickId = (int) tick.number();
        final int tickTime = (int) tick.elapsed().toMillis();
        for (final Player viewer : players.values()) {
            final NewTick newTick = new NewTick();
            newTick.tickId = tickId;
            newTick.tickTime = tickTime;
            interest.near(Interest.at(viewer.x(), viewer.y(), 0, 100.0), scratch);
            for (final ActorRef session : scratch) {
                final Player visible = bySession.get(session);
                if (visible != null) {
                    newTick.statuses.add(visible.status());
                }
            }
            scratch.clear();
            final List<OutgoingMessage> batch = new ArrayList<>(1);
            batch.add(newTick);
            tc.actor().tell(viewer.session(), new OutboundBatch(tickId, batch));
        }
    }

    public int playerCount() {
        return players.size();
    }
}