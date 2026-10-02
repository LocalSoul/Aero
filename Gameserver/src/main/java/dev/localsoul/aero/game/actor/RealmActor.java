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
import dev.localsoul.aero.game.actor.GameMessages.EnemyHitMsg;
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
import dev.localsoul.aero.game.actor.GameMessages.PlayerShootMsg;
import dev.localsoul.aero.game.actor.GameMessages.PlayerTextMsg;
import dev.localsoul.aero.game.protocol.CreateSuccess;
import dev.localsoul.aero.game.protocol.Damage;
import dev.localsoul.aero.game.protocol.Death;
import dev.localsoul.aero.game.protocol.EnemyShoot;
import dev.localsoul.aero.game.protocol.GroundTileData;
import dev.localsoul.aero.game.protocol.MapInfo;
import dev.localsoul.aero.game.protocol.NewTick;
import dev.localsoul.aero.game.protocol.Notification;
import dev.localsoul.aero.game.protocol.ObjectData;
import dev.localsoul.aero.game.protocol.OutgoingMessage;
import dev.localsoul.aero.game.protocol.ServerPlayerShoot;
import dev.localsoul.aero.game.protocol.Update;
import dev.localsoul.aero.game.world.LootBag;
import dev.localsoul.aero.game.world.Map;
import dev.localsoul.aero.game.world.Monster;
import dev.localsoul.aero.game.world.Player;
import dev.localsoul.aero.game.world.Projectile;
import dev.localsoul.aero.game.world.Entity;
import io.netty.util.collection.IntObjectHashMap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Das Reich — ein {@link TickActor}, der seine Entities als **Plain Objects**
 * in IntObjectHashMap/Listen hält und pro Tick direkt simuliert (§2, §7 der
 * gameserver_implement.md V2). Nur der Realm-Thread mutiert den Weltzustand.
 *
 * <p>V2 ergänzt: Monster (Ghost Mages) mit AI, server-simulierte
 * Monster-Projektile, Loot-Bags, XP/Level, Spieler-Tod und den
 * {@code /give}-Befehl. Die Sichtbarkeit je Spieler läuft über einen
 * pro-Tick-{@link Set}-Diff ({@code knownObjectIds}, §7): er ist die einzige
 * Quelle für {@code Update.newObjs}/{@code drops}.
 */
public final class RealmActor extends TickActor {

    /** Demo-Grund: {@code 0x02} Light Cobblestone (embedded GroundsCXML). */
    private static final int GROUND_TYPE = 0x02;
    private static final float VISIBLE_RADIUS = 100.0f;
    private static final int[] DROP_TABLE = {0xa07, 0xa04, 0xa97};   // Wand of Death, Fire Wand, Energy Staff

    private final Map map;
    private final IntObjectHashMap<Player> players = new IntObjectHashMap<>();
    private final java.util.Map<ActorRef, Player> bySession = new java.util.HashMap<>();
    private final IntObjectHashMap<Monster> monsters = new IntObjectHashMap<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<LootBag> lootBags = new ArrayList<>();
    private final InterestSet interest = InterestSet.forRadius(VISIBLE_RADIUS);
    private final List<ActorRef> scratch = new ArrayList<>(64);
    private final Set<Integer> visibleScratch = new HashSet<>();
    private int nextObjectId = 1;
    private long nowMs;

    private final Behavior messages = Receive.of(
            Clause.on(PlayerHello.class, this::onPlayerHello),
            Clause.on(PlayerJoin.class, this::onPlayerJoin),
            Clause.on(PlayerCreate.class, this::onPlayerCreate),
            Clause.on(PlayerMove.class, this::onPlayerMove),
            Clause.on(PlayerShootMsg.class, this::onPlayerShoot),
            Clause.on(EnemyHitMsg.class, this::onEnemyHit),
            Clause.on(PlayerTextMsg.class, this::onPlayerText),
            Clause.on(PlayerLeave.class, this::onPlayerLeave),
            Clause.on(KickPlayer.class, this::onKickPlayer)
    );

    public RealmActor(final String name, final TickDriver driver, final Map map) {
        this(name, driver, map, 0);
    }

    /**
     * @param initialMonsters Anzahl der Ghost Mages, die deterministisch um
     *                        die Map-Mitte gesetzt werden (nahe am
     *                        Spieler-Spawn, damit sie in V2 auch gekämpft werden).
     */
    public RealmActor(final String name, final TickDriver driver, final Map map,
                      final int initialMonsters) {
        super(name, driver);
        this.map = map;
        spawnInitialMonsters(initialMonsters);
    }

    /** Deterministisches Raster um die Mitte; Spieler-Spawn liegt mittig. */
    private void spawnInitialMonsters(final int count) {
        final float cx = map.width() / 2f;
        final float cy = map.height() / 2f;
        final int perRow = Math.max(1, (int) Math.ceil(Math.sqrt(count) * 2));
        for (int i = 0; i < count; i++) {
            final int col = i % perRow;
            final int row = i / perRow;
            final float x = clamp(cx + (col - (perRow - 1) / 2f) * 2.5f, 1, map.width() - 2);
            final float y = clamp(cy + (row - 0.5f) * 2.5f, 1, map.height() - 2);
            final Monster monster = new Monster(nextObjectId++, x, y);
            monster.setNextAttackAt(nowMs + Monster.FIRST_SHOT_DELAY_MS + i * 500L);
            monsters.put(monster.objectId(), monster);
        }
    }

    private static float clamp(final float v, final float min, final float max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    protected Behavior onMessage(final Object message, final ActorContext ctx) {
        return messages.invoke(message, ctx);
    }

    // ------------------------------------------------------- Nachrichten

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

    /** Entity anlegen, objectId vergeben, Startpakete → JoinConfirmed. */
    private void spawnPlayer(final ActorRef session, final int charId, final short objectType) {
        final int objectId = nextObjectId++;
        final Player player = new Player(objectId, map, map.width() / 2f, map.height() / 2f,
                objectType);
        player.setSession(session);
        player.setCharId(charId);
        players.put(objectId, player);
        bySession.put(session, player);
        interest.put(session, Interest.at(player.x(), player.y(), 0, VISIBLE_RADIUS));

        // Join-Update: Boden + ALLE sichtbaren Objekte (Spieler, Monster, Bags)
        final Update update = new Update();
        fillTiles(update);
        final Set<Integer> visible = new HashSet<>();
        visible.add(objectId);
        interest.near(Interest.at(player.x(), player.y(), 0, VISIBLE_RADIUS), scratch);
        for (final ActorRef visibleSession : scratch) {
            final Player visiblePlayer = bySession.get(visibleSession);
            if (visiblePlayer != null) {
                update.newObjs.add(buildObjectData(visiblePlayer));
                visible.add(visiblePlayer.objectId());
            }
        }
        scratch.clear();
        for (final Monster monster : monsters.values()) {
            if (monster.alive() && inRadius(player.x(), player.y(), monster.x(), monster.y())) {
                update.newObjs.add(buildMonsterObjectData(monster));
                visible.add(monster.objectId());
            }
        }
        for (final LootBag bag : lootBags) {
            if (inRadius(player.x(), player.y(), bag.x(), bag.y())) {
                update.newObjs.add(bag.toObjectData());
                visible.add(bag.objectId());
            }
        }
        player.knownObjectIds().addAll(visible);

        final CreateSuccess success = new CreateSuccess();
        success.objectId = objectId;
        success.charId = charId;
        context().tell(session, new JoinConfirmed(objectId, update, success));
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

    private Behavior onPlayerMove(final PlayerMove move, final ActorContext ctx) {
        final Player player = players.get(move.objectId());
        if (player != null) {
            player.setTarget(move.x(), move.y());
        }
        return Behavior.NEXT;
    }

    private Behavior onPlayerShoot(final PlayerShootMsg msg, final ActorContext ctx) {
        final Player player = players.get(msg.objectId());
        if (player == null) {
            return Behavior.NEXT;
        }
        final int damage = ThreadLocalRandom.current()
                .nextInt(Player.ENERGY_STAFF_MIN_DAMAGE, Player.ENERGY_STAFF_MAX_DAMAGE + 1);
        player.registerShot(new Player.Shot(msg.bulletId(), msg.x(), msg.y(), damage,
                Player.ENERGY_STAFF_NUM_PROJECTILES, nowMs, Player.ENERGY_STAFF_LIFETIME_MS));

        // Andere sichtbare Spieler sehen das Projektil (der Schütze lokal selbst).
        final ServerPlayerShoot shoot = new ServerPlayerShoot();
        shoot.bulletId = msg.bulletId();
        shoot.ownerId = player.objectId();
        shoot.containerType = Player.ENERGY_STAFF;
        shoot.startingPos.x = msg.x();
        shoot.startingPos.y = msg.y();
        shoot.angle = msg.angle();
        shoot.damage = damage;
        interest.near(Interest.at(player.x(), player.y(), 0, VISIBLE_RADIUS), scratch);
        for (final ActorRef session : scratch) {
            if (session != player.session()) {
                ctx.tell(session, new OutboundBatch(0, List.of(shoot)));
            }
        }
        scratch.clear();
        return Behavior.NEXT;
    }

    private Behavior onEnemyHit(final EnemyHitMsg msg, final ActorContext ctx) {
        final Player player = players.get(msg.objectId());
        if (player == null) {
            return Behavior.NEXT;
        }
        final Player.Shot shot = player.consumeShot(msg.bulletId(), nowMs);
        if (shot == null) {
            return Behavior.NEXT;                       // keine frische, freie Schuss-Spur
        }
        final Monster monster = monsters.get(msg.targetId());
        if (monster == null || !monster.alive()) {
            return Behavior.NEXT;
        }
        final float range = Player.ENERGY_STAFF_SPEED * Player.ENERGY_STAFF_LIFETIME_MS
                / 10_000f + 1.0f;
        final float dx = shot.startX() - monster.x();
        final float dy = shot.startY() - monster.y();
        if (dx * dx + dy * dy > range * range) {
            return Behavior.NEXT;                       // außer Reichweite → ungültig
        }
        monster.damage(Entity.damageWithDefense(shot.damage(), monster.defense()));
        if (monster.hp() <= 0) {
            killMonster(monster, player, ctx);
        }
        return Behavior.NEXT;
    }

    /** Monster töten: aus dem Weltzustand, XP/Loot an den Killer, Respawn planen. */
    private void killMonster(final Monster monster, final Player killer, final ActorContext ctx) {
        monster.markDead(nowMs + Monster.RESPAWN_MS);
        if (killer == null) {
            return;                                     // Killer weg → kein XP, keine Bag
        }
        killer.addExp(Monster.XP_PER_KILL);
        final Notification note = new Notification();
        note.objectId = killer.objectId();
        note.message = "{\"key\":\"server.plus_symbol\",\"tokens\":{\"amount\":\""
                + Monster.XP_PER_KILL + "\"}}";
        note.color = 0xFFFFFF;
        ctx.tell(killer.session(), new OutboundBatch(0, List.of(note)));

        final int drop = DROP_TABLE[ThreadLocalRandom.current().nextInt(DROP_TABLE.length)];
        lootBags.add(new LootBag(nextObjectId++, monster.x(), monster.y(), killer, drop, nowMs));
    }

    private Behavior onPlayerText(final PlayerTextMsg msg, final ActorContext ctx) {
        final Player player = players.get(msg.objectId());
        if (player == null || !msg.text().startsWith("/give")) {
            return Behavior.NEXT;
        }
        final int itemType = parseItemType(msg.text().substring(6).trim());
        if (itemType >= 0) {
            player.addItem(itemType);
        }
        return Behavior.NEXT;
    }

    private static int parseItemType(final String text) {
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                return Integer.parseInt(text.substring(2), 16);
            }
            return Integer.parseInt(text);
        } catch (final NumberFormatException e) {
            return -1;
        }
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

    /** Nur aus dem Realm-Thread aufrufen; Despawn-Drops erzeugt der Diff (§7). */
    private Player removePlayer(final int objectId) {
        final Player player = players.remove(objectId);
        if (player != null) {
            bySession.remove(player.session());
            interest.remove(player.session());
        }
        return player;
    }

    // ------------------------------------------------------- Simulation (Tick)

    @Override
    protected void onTick(final Tick tick, final TickContext tc) {
        final float dt = (float) tick.elapsed().toNanos() / 1_000_000_000f;
        final long dtMs = tick.elapsed().toMillis();
        nowMs += dtMs;

        // 2) Monster: Respawn + AI (nächstes Ziel, Feuern)
        for (final Monster monster : monsters.values()) {
            try {
                monster.simulate(dt, nowMs);
            } catch (final Throwable t) {
                monster.markDead(nowMs + Monster.RESPAWN_MS);
            }
        }
        for (final Monster monster : monsters.values()) {
            if (!monster.alive()) {
                continue;
            }
            final Player target = nearestPlayer(monster.x(), monster.y(), Monster.AGGRO_RADIUS);
            if (target != null && monster.readyToFire(nowMs)) {
                monster.markFired(nowMs);
                fireMonsterShot(monster, target, tc);
            }
        }

        // 3) Spieler: Bewegung + HP-Regeneration, Interessen nachziehen
        for (final Player player : players.values()) {
            try {
                player.simulate(dt);
            } catch (final Throwable t) {
                removePlayer(player.objectId());
                continue;
            }
            interest.move(player.session(), Interest.at(player.x(), player.y(), 0, VISIBLE_RADIUS));
        }

        // 4) Monster-Projektile: vorschieben, kollidieren, ablaufen lassen
        final Iterator<Projectile> projectilesIt = projectiles.iterator();
        while (projectilesIt.hasNext()) {
            final Projectile projectile = projectilesIt.next();
            projectile.simulate(dtMs);
            if (projectile.expired()) {
                projectilesIt.remove();
                continue;
            }
            final Player hit = firstPlayerHitBy(projectile);
            if (hit != null) {
                projectilesIt.remove();
                damagePlayer(hit, projectile, tc);
            }
        }

        // 5) Bags: Lebensdauer + Nähe-Pickup (nur Eigentümer)
        final Iterator<LootBag> bagsIt = lootBags.iterator();
        while (bagsIt.hasNext()) {
            final LootBag bag = bagsIt.next();
            if (bag.expired(nowMs)) {
                bagsIt.remove();
                continue;
            }
            final Player owner = players.get(bag.owner().objectId());
            if (owner != null && inRadius(owner.x(), owner.y(), bag.x(), bag.y())
                    && distSq(owner.x(), owner.y(), bag.x(), bag.y())
                    <= LootBag.PICKUP_RADIUS * LootBag.PICKUP_RADIUS) {
                if (owner.addItem(bag.itemType())) {
                    bagsIt.remove();
                }
            }
        }

        // 6) Sichtbarkeits-Diff + NewTick je Spieler
        final int tickId = (int) tick.number();
        final int tickTime = (int) tick.elapsed().toMillis();
        for (final Player viewer : players.values()) {
            buildViewerBatch(viewer, tickId, tickTime, tc);
        }
    }

    private void fireMonsterShot(final Monster monster, final Player target, final TickContext tc) {
        final float angle = (float) Math.atan2(target.y() - monster.y(),
                target.x() - monster.x());
        final int bulletId = monster.nextBulletId();
        projectiles.add(new Projectile(monster.objectId(), bulletId, 0,
                monster.x(), monster.y(), angle, Monster.PROJECTILE_DAMAGE,
                Monster.PROJECTILE_SPEED, Monster.PROJECTILE_LIFETIME_MS));

        final EnemyShoot shoot = new EnemyShoot();
        shoot.bulletId = bulletId;
        shoot.ownerId = monster.objectId();
        shoot.bulletType = 0;
        shoot.startingPos.x = monster.x();
        shoot.startingPos.y = monster.y();
        shoot.angle = angle;
        shoot.damage = Monster.PROJECTILE_DAMAGE;
        shoot.numShots = 1;
        shoot.angleInc = 0;
        interest.near(Interest.at(monster.x(), monster.y(), 0, VISIBLE_RADIUS), scratch);
        for (final ActorRef session : scratch) {
            if (bySession.containsKey(session)) {
                tc.actor().tell(session, new OutboundBatch(0, List.of(shoot)));
            }
        }
        scratch.clear();
    }

    private void damagePlayer(final Player player, final Projectile projectile,
                              final TickContext tc) {
        if (player.isDead()) {
            return;
        }

        final int amount = Entity.damageWithDefense(projectile.damage(), player.defense());
        player.damage(amount);

        final Damage damage = new Damage();
        damage.targetId = player.objectId();
        damage.damageAmount = amount;
        damage.kill = player.isDead();
        damage.bulletId = projectile.bulletId();
        damage.objectId = projectile.ownerId();
        tc.actor().tell(player.session(), new OutboundBatch(0, List.of(damage)));

        if (player.isDead()) {
            killPlayer(player, tc);
        }
    }

    private void killPlayer(final Player player, final TickContext tc) {
        final int objectId = player.objectId();
        removePlayer(objectId);
        final Death death = new Death();
        death.accountId = "";
        death.charId = player.charId();
        death.killedBy = "Ghost Mage";
        death.zombieType = -1;
        death.zombieId = -1;
        tc.actor().tell(player.session(), new OutboundBatch(0, List.of(death)));
    }

    // ------------------------------------------------------- Sichtbarkeit

    private void buildViewerBatch(final Player viewer, final int tickId, final int tickTime,
                                  final TickContext tc) {
        visibleScratch.clear();
        final NewTick newTick = new NewTick();
        newTick.tickId = tickId;
        newTick.tickTime = tickTime;

        interest.near(Interest.at(viewer.x(), viewer.y(), 0, VISIBLE_RADIUS), scratch);
        for (final ActorRef session : scratch) {
            final Player visiblePlayer = bySession.get(session);
            if (visiblePlayer != null) {
                newTick.statuses.add(visiblePlayer.status());
                visibleScratch.add(visiblePlayer.objectId());
            }
        }
        scratch.clear();
        for (final Monster monster : monsters.values()) {
            if (monster.alive()
                    && inRadius(viewer.x(), viewer.y(), monster.x(), monster.y())) {
                newTick.statuses.add(monster.status());
                visibleScratch.add(monster.objectId());
            }
        }
        for (final LootBag bag : lootBags) {
            if (inRadius(viewer.x(), viewer.y(), bag.x(), bag.y())) {
                newTick.statuses.add(bag.status());
                visibleScratch.add(bag.objectId());
            }
        }

        // Diff gegen knownObjectIds (einzige Quelle für newObjs/drops)
        final Update diff = new Update();
        final Set<Integer> known = viewer.knownObjectIds();
        for (final int id : visibleScratch) {
            if (!known.contains(id)) {
                final ObjectData data = objectDataFor(id);
                if (data != null) {
                    diff.newObjs.add(data);
                }
            }
        }
        for (final int id : known) {
            if (!visibleScratch.contains(id)) {
                diff.drops.add(id);
            }
        }
        known.clear();
        known.addAll(visibleScratch);

        final List<OutgoingMessage> batch = new ArrayList<>(2);
        batch.add(newTick);
        if (!diff.newObjs.isEmpty() || !diff.drops.isEmpty()) {
            batch.add(diff);
        }
        tc.actor().tell(viewer.session(), new OutboundBatch(tickId, batch));
    }

    private ObjectData objectDataFor(final int objectId) {
        final Player player = players.get(objectId);
        if (player != null) {
            return buildObjectData(player);
        }
        final Monster monster = monsters.get(objectId);
        if (monster != null) {
            return buildMonsterObjectData(monster);
        }
        for (final LootBag bag : lootBags) {
            if (bag.objectId() == objectId) {
                return bag.toObjectData();
            }
        }
        return null;
    }

    private static ObjectData buildMonsterObjectData(final Monster monster) {
        final ObjectData obj = new ObjectData();
        obj.objectType = monster.objectType();
        final var status = monster.status();
        obj.status.objectId = status.objectId;
        obj.status.pos.x = status.pos.x;
        obj.status.pos.y = status.pos.y;
        obj.status.stats.addAll(status.stats);
        return obj;
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

    private Player nearestPlayer(final float x, final float y, final float radius) {
        Player best = null;
        float bestDistSq = radius * radius;
        for (final Player player : players.values()) {
            if (player.isDead()) {
                continue;
            }
            final float d = distSq(x, y, player.x(), player.y());
            if (d <= bestDistSq) {
                bestDistSq = d;
                best = player;
            }
        }
        return best;
    }

    private Player firstPlayerHitBy(final Projectile projectile) {
        for (final Player player : players.values()) {
            if (player.isDead()) {
                continue;
            }
            final float hitRadius = 1.1f;               // 0.6 + Spieler-Radius ~0.5 (§8.2)
            if (distSq(projectile.x(), projectile.y(), player.x(), player.y())
                    <= hitRadius * hitRadius) {
                return player;
            }
        }
        return null;
    }

    private static float distSq(final float ax, final float ay, final float bx, final float by) {
        final float dx = ax - bx;
        final float dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static boolean inRadius(final float ax, final float ay,
                                    final float bx, final float by) {
        return distSq(ax, ay, bx, by) <= VISIBLE_RADIUS * VISIBLE_RADIUS;
    }

    // ------------------------------------------------------- Diagnose (Tests)

    public int playerCount() {
        return players.size();
    }

    public int monsterCount() {
        return monsters.size();
    }

    public int bagCount() {
        return lootBags.size();
    }

    public int projectileCount() {
        return projectiles.size();
    }

    /** Live-Zugriff (Test/Diagnose); nur lesend verwenden. */
    public Monster monster(final int objectId) {
        return monsters.get(objectId);
    }
}