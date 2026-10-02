# Aero::Gameserver — Implementierungsplan (V2: Kampf-Demo)

> Der eigentliche RotMG-Gameserver: verwaltet Verbindungen (Netty), die
> Weltsimulation und die Spieler. Baut auf der Actor-Runtime (`Actor/`) auf.
>
> Modul: `dev.localsoul:Aero::Gameserver` · Paketwurzel: `dev.localsoul.aero.game`
> Abhängigkeiten: `Actor` (Runtime) + **Netty** (`netty-codec`, `netty-handler`).
>
> **V1 (fertig):** Verbindung, RC4-Framing, leere Nexus-Welt, Bewegung,
> Multiplayer-Sichtbarkeit. **V2 (fertig): Kampf** — Spieler schießen auf
> Monster, Monster schießen zurück, XP/Level, Loot-Bags, Monster-Respawn und
> ein `/give`-Chatbefehl. Dieses Dokument ist die Implementierungs-Referenz.

---

## 1. Abgrenzung und Ziel (V2)

| Modul | Rolle | Zuständig für |
|---|---|---|
| `Actor` | Runtime | Actor-Modell, Tick, Supervision, Mailboxen, `InterestSet` (fertig) |
| `Server` | HTTP/Appengine | Login, `/char/list`, `/app/init`, … (statische Ressourcen) |
| **`Gameserver`** | **Echtzeit-Welt** | TCP, Protokoll, Simulation, **Kampf** |

**V2-Ziel (Demo):** Der unmodifizierte Flash-Client (27.7.X2) spielt gegen
**Ghost Mages** in der Nexus-Welt:

- Spieler schießt mit der Startwaffe (**Energy Staff**, `0xa97`) → Client meldet
  Treffer per `EnemyHit` → Server validiert und schadet dem Monster.
- Monster (**Ghost Mage**, `0x664`) schießt periodisch → Server simuliert die
  Projektile selbst, sendet `EnemyShoot` und bei Treffer `Damage`.
- Monster-Tod → XP (`Notification`), **Soulbound Loot Bag** auf dem Boden,
  Respawn nach Verzögerung.
- Spieler-Tod → `Death`, Entity entfernen, Verbindung bleibt (Client zeigt den
  Death-Screen).
- Chatbefehl **`/give <type>`** legt ein Item ins Spieler-Inventar
  (Demo-Hilfe / Item-Verteilung).

**Nicht in V2:** Drops von Rüstungs-/Waffenfähigkeit über `ShowEffect`-Varianten,
Persistenz, Account-Kopplung mit `Server`, Reconnect, `Escape`-Logik
(Verbindungsabbau wie in V1), andere Waffen als der Energy Staff.

---

## 2. Leitentscheidungen (V2, aus dem Review)

1. **Beide Kampf-Richtungen** werden implementiert (Spieler→Monster **und**
   Monster→Spieler) — erst das ergibt einen echten Kampf-Loop.
2. **Autorität Spieler-Projektile: Client-Report + Server-Validierung.**
   Der Client erkennt Treffer visuell und meldet `EnemyHit`; der Server
   rekonstruiert den Schuss aus dem vorherigen `PlayerShoot` und validiert
   Plausibilität (Reichweite/Winkel). Das ist das klassische RotMG-Modell und
   entspricht dem Client. *Keine* Server-Simulation von Spieler-Projektilen.
3. **Autorität Monster-Projektile: volle Server-Simulation.** Der Server
   simuliert jeden `EnemyShoot`-Schuss (Plain-Object-`Projectile`, pro Tick
   vorgeschoben), kollidiert gegen Spieler und sendet `Damage`. `PlayerHit`
   wird geparst, ist aber **nicht** die Schadensquelle — kein Client kann sich
   damit selbst Schaden zufügen oder einreden.
4. **Monster & Projektile sind Plain Objects im Reich** (wie V1-Entities):
   reine Objekt-Iteration im `RealmActor`-Tick, kein Message-Passing pro
   Entity. Nur der Realm-Thread mutiert den Weltzustand. **Skalierung läuft
   später bewusst über Realm-Sharding/mehrere Realms, nicht über
   Entity-Actors** (Projektil pro Actor wäre bei 20 Hz × n Projektilen
   Message-Overhead ohne Nutzen) — das ist eine Design-Entscheidung, keine
   Abweichung vom Actor-Konzept.
5. **`NewTick`-Sichtbarkeit wird erweitert:** Spieler↔Spieler bleibt über den
   bestehenden `InterestSet`; Monster werden per einfachem **Radius-Scan über
   die Monster-Liste** je Viewer ergänzt (in V2 wenige Dutzend Monster — eine
   echte Entitäts-Spatialstruktur ist V3). Die V1-Tests bleiben unberührt.
6. **Schaden nach der Client-Formel** `GameObject.damageWithDefense`
   (§8): `max(dmg*3/20, dmg - defense)` — kein Armor-Piercing, keine
   Condition-Effekte in V2.
7. **`/give` ist ein Serverbefehl über `PlayerText`.** Der Client sendet jeden
   Chattext (außer `/help`) als `PlayerText`. **Hinweis:** Der Client mappt
   das eingehende `Text`-Paket (ID 34) **nicht** — Server-Chat wird also nicht
   gerendert; als Erfolgs-Feedback dient optional eine `Notification` über dem
   Spieler (QueuedStatusText, §9).
8. **Loot-Bag-Pickup ist server-seitig auf Nähe.** Kein Client-Paket nötig:
   Steht der **Berechtigte** nah genug an der Bag, überträgt der Server die
   Items in die ersten freien Inventar-Slots und entfernt die Bag. Die
   Soulbound-Bindung ist in V2 rein server-seitig (s. §6.2).
9. **Respawn hält die Demo am Leben:** Tote Ghost Mages respawnen nach
   `RESPAWN_MS` (z. B. 10 s) an einer zufälligen Position auf einem
   **begehbaren Tile** (in V2 ist die komplette 50×50-Map Grund `0x02`; beim
   späteren Map-Import Walkability prüfen).
10. **Keine Persistenz in V2.** Disconnect verwirft Inventar/XP.

---

## 3. Wire-Protokoll 27.7.X2 (unverändert aus V1, am Client verifiziert)

Quelle: `RotMG_Client_27.7.X2/src/kabam/rotmg/messaging/impl/...`.

### Frame-Format

```
+0                    +4                    +5                     +5+payload
┌─────────────────────┬─────────────────────┬──────────────────────────────────┐
│ length (int, clear) │ type (byte, clear)  │ payload (RC4-verschluesselt)     │
└─────────────────────┴─────────────────────┴──────────────────────────────────┘
```

- **length** = `payload.size + 5` (4 Bytes Längenfeld + 1 Byte Typ), im Klartext.
- **type** = 1 Byte Message-ID, im Klartext.
- **payload** = die Paketfelder, **RC4-verschlüsselt**.

### Verschlüsselung (kein Handshake!)

| Richtung | RC4-Key (hex) |
|---|---|
| Client → Server (Server entschlüsselt) | `311f80691451c71d09a13a2a6e` |
| Server → Client (Server verschlüsselt) | `72c5583cafb6818995cdd74b80` |

RC4 ist **zustandsbehaftet**: eine `Rc4Cipher`-Instanz **pro Richtung pro
Verbindung**, über alle Pakete weiterlaufend (der Client macht das genauso).

---

## 4. Paketsatz V2

### Vom Client kommend (outgoing)

| Paket | ID | Felder (Reihenfolge) |
|---|---|---|
| `Hello` | 86 | unverändert aus V1 |
| `Load` | 63 | unverändert aus V1 |
| `Move` | 24 | unverändert aus V1 |
| `Escape` | 16 | – (leer) |
| `PlayerShoot` | 41 | `writeInt time`, `writeByte bulletId`, `writeShort containerType`, `WorldPosData startingPos`, `writeFloat angle` |
| `EnemyHit` | 94 | `writeInt time`, `writeByte bulletId`, `writeInt targetId`, `writeBoolean kill` |
| `PlayerHit` | 37 | `writeByte bulletId`, `writeInt objectId` (geparst, in V2 nicht schadensrelevant) |
| `PlayerText` | 9 | `writeUTF text` |

`ShootAck` (10), `UpdateAck` (96), `GotoAck` (99), `Pong` (83) werden in V2
entgegengenommen, aber nicht benötigt.

### Vom Server kommend (incoming)

| Paket | ID | Felder (Reihenfolge) |
|---|---|---|
| `MapInfo` | 28 | unverändert aus V1 |
| `CreateSuccess` | 58 | unverändert aus V1 |
| `Update` | 44 | unverändert aus V1 |
| `NewTick` | 31 | unverändert aus V1 |
| `ServerPlayerShoot` | 1 | `writeByte bulletId`, `writeInt ownerId`, `writeInt containerType`, `WorldPosData startingPos`, `writeFloat angle`, `writeShort damage` |
| `EnemyShoot` | 90 | `writeByte bulletId`, `writeInt ownerId`, `writeByte bulletType`, `WorldPosData startingPos`, `writeFloat angle`, `writeShort damage`, `writeByte numShots`, `writeFloat angleInc` |
| `Damage` | 52 | `writeInt targetId`, `writeByte effectsCount` + `effects[]` (0 in V2), `writeUnsignedShort damageAmount`, `writeBoolean kill`, `writeByte bulletId`, `writeInt objectId` |
| `Notification` | 20 | `writeInt objectId`, `writeUTF message` (JSON, s. §6), `writeInt color` |
| `Death` | 12 | `writeUTF accountId`, `writeInt charId`, `writeUTF killedBy`, `writeInt zombieType`, `writeInt zombieId` |

### Datenklassen (StatData-Referenz, aus `StatData.as`)

Für `NewTick`/`ObjectData`-Status maßgeblich — nur die in V2 genutzten IDs:

| Stat | ID | Typ |
|---|---|---|
| `MAX_HP` | 0 | int |
| `HP` | 1 | int |
| `MAX_MP` | 3 | int |
| `MP` | 4 | int |
| `NEXT_LEVEL_EXP` | 5 | int |
| `EXP` | 6 | int |
| `LEVEL` | 7 | int |
| `INVENTORY_0` … `INVENTORY_11` | 8 … 19 | int |
| `ATTACK` | 20 | int |
| `DEFENSE` | 21 | int |
| `SPEED` | 22 | int |
| `NAME` | 31 | **String** (`writeUTF`) |

**String-Stat** (sonst Int): `31 NAME`. Die V1-`StatData`-Logik
(`isStringStat`) trifft das bereits korrekt.

---

## 5. Kampf-Loop (Spezifikation)

### 5.1 Spieler → Monster (Client-Report + Validierung)

1. **`PlayerShoot`** trifft ein (SessionActor → Realm, `PlayerShootMsg`).
   Der Realm:
   - legt eine **Schuss-Spur** in einen **Ringpuffer der letzten N Schüsse**
     je Spieler an (`Shot`: bulletId-Basis, `containerType`, startPos, angle,
     firedAtMs, numProjectiles, gerollter Schaden, verbrauchte bulletIds). Ein
     einzelnes `lastShot` reicht nicht — bei hoher Feuerrate kommt der
     `EnemyHit` zum alten Schuss oft erst nach dem nächsten `PlayerShoot`.
   - **rollt den Schaden** der Waffe (Energy Staff: `[MinDamage, MaxDamage]`)
     und speichert ihn in der Spur — **derselbe Wert** fließt in
     `ServerPlayerShoot.damage` an die anderen Clients und in die
     Monster-Schadensrechnung (kein Neu-Rollen bei `EnemyHit`);
   - broadcastet `ServerPlayerShoot(bulletId, ownerId, containerType,
     startingPos, angle, damage)` an **alle anderen** sichtbaren Spieler
     (der Schütze sieht sein Projektil bereits lokal).
2. **`EnemyHit(time, bulletId, targetId, kill)`** trifft ein → Realm validiert:
   - der Spieler hat eine **frische** Schuss-Spur, deren bulletId-Bereich
     (`base … base+numProjectiles-1`, **modulo 256** — `bulletId` ist ein
     Byte, der Client zählt über) den `bulletId` abdeckt und deren
     Fired-Zeitpunkt innerhalb der Waffen-Lebensdauer liegt;
   - der `bulletId` dieser Spur wurde **noch nicht verbraucht**
     (pro Schuss wird jede bulletId genau einmal akzeptiert — verhindert,
     dass ein manipulierter Client denselben Schuss mehrfach meldet);
   - das `targetId` ist ein lebendes Monster im Realm;
   - Distanz `startPos → monster.pos` ≤ Reichweite der Waffe
     (`speed * lifetime / 10000` + Toleranz).
   - **Gültig:** Treffer-`bulletId` als verbraucht markieren, Monster-HP
     abziehen (gespeicherter Schaden, `damageWithDefense`).
     **Ungültig:** ignorieren (kein Kick in V2, nur Log).
   - **Kein `Damage`-Paket fürs Monster:** Der Client wendet den Schuss-
     Treffer auf das Monster **selbst** lokal an (`Projectile.update` →
     `enemyHit(...)` **und** `_local_6.damage(...)`); ein bestätigendes
     Server-`Damage` würde den Schaden **doppelt** anwenden. Die autoritative
     Monster-HP kommt über die `HP`-Stat im nächsten `NewTick`/`Update`.
3. **Monster-Tod:** §6. Die Entfernung passiert **im Inbox-Drain (Tick-
   Schritt 1)** — ein in diesem Tick getötetes Monster feuert nicht mehr
   (Schritt 2/3, s. §7).

### 5.2 Monster → Spieler (volle Server-Simulation)

1. **AI im Realm-Tick (§7):** Das Monster wählt den nächsten Spieler im
   Aggro-Radius und feuert nach Ablauf seiner `attackPeriod`.
2. Der Realm erzeugt ein `Projectile`-Plain-Object (owner=Monster-Id,
   bulletId=monster-lokaler Zähler, bulletType=`0`, startPos=Monster-Position,
   angle=zum Ziel, damage=40, speed=`50`, lifetime=`3000 ms`) und broadcastet
   `EnemyShoot(...)` an alle Spieler, die das Monster sehen.
3. **Projektile werden pro Tick vorgeschoben** (§8: `pos = start +
   ageMs * speed / 10000` entlang angle, speed als **Rohwert**) und gegen
   **alle Spieler** kollidiert (Kreis/Kreis, Hit-Radius konstant ~0.6; s. §8).
   **Trifft ein Projektil mehrere Spieler im selben Tick, bekommt nur der
   erste Schaden** (Projektil wird sofort entfernt).
4. **Treffer:** `Damage(targetId, effects=[], damageAmount, kill=false,
   bulletId, objectId=monsterId)` an den getroffenen Spieler; HP abziehen;
   Projektil entfernen. `PlayerHit` wird geparst, aber ignoriert.
5. **Spieler-HP ≤ 0:** `Death(accountId, charId, killedBy="Ghost Mage",
   zombieType=-1, zombieId=-1)` an den Spieler, Entity entfernen (Despawn für
   andere über den Sichtbarkeits-Diff, §7). **Bereits tote Spieler werden in
   diesem Tick nicht erneut getroffen** (kein zweites `Death`). `killedBy`
   ist im `Death`-Paket nur informativ — die „Killed by"-Zeile des
   Death-Screens stammt aus dem Fame-HTTP-Endpoint (nicht in V2).

---

## 6. Tod, XP, Loot, Respawn

### 6.1 Monster-Tod

Wenn ein Monster-HP ≤ 0:

1. **Entfernen:** Monster aus der Welt nehmen. Das `Update.drops` an die
   Zuschauer erzeugt **der Sichtbarkeits-Diff** (§7, Schritt 6) — kein
   separates Drops-Paket im Kill-Pfad, sonst Doppel-Drops.
2. **XP:** dem Killer `exp += round(maxHp * XpMult)` (= `130 * 0.1 = 13`)
   geben; `Notification(objectId=killerId, message={"key":"server.plus_symbol",
   "tokens":{"amount":"13"}}, color=0xFFFFFF)`.
   **Killer fehlt** (im selben Drain zuvor `PlayerLeave`/`KickPlayer`/
   Spieler-Tod verarbeitet) → **kein XP, keine Bag** (null-sicher, kein
   Absturz).
   Level-Up: `exp >= nextLevelExp` → `level++`, `exp -= nextLevelExp`,
   `nextLevelExp = 100 + (level-1)*50`. **Bewusste Vereinfachung** — die echte
   RotMG-Kurve (`50 + (level-2)*100 + …`) ist für die Demo nicht nötig. Der
   Client rendert Level-Up/Exp aus den Stat-Änderungen in `NewTick` selbst
   (`handleLevelUp`/`handleExpUp`) — nur `LEVEL`/`EXP`/`NEXT_LEVEL_EXP` als
   Stats senden.
3. **Loot-Bag:** `Soulbound Loot Bag` (`0x0503`, Container mit `<Loot/>`,
   8 Slots) an der Monster-Position erzeugen, **Eigentümer = Killer-Spieler**.
   Inhalt: **1 Waffe** aus
   `{Wand of Death 0xa07, Fire Wand 0xa04, Energy Staff 0xa97}` als
   `INVENTORY_0`-Stat im `ObjectStatusData` der Bag. Sichtbar für alle via
   Sichtbarkeits-Diff.
4. **Respawn-Planung:** Monster-Auferstehung nach `RESPAWN_MS` (10 s) an
   zufälliger **begehbarer** Position. Beim Respawn gilt **keine
   Sofort-Schuss**: `nextAttackAt = now + attackPeriodMs` (§8.1).

### 6.2 Bag-Lebensdauer & Pickup (server-seitig, nur Eigentümer)

- **Lebensdauer:** Jede Bag läuft nach `BAG_LIFETIME_MS` (~60 s, entspricht
  dem Original) ab und wird im Bag-Schritt des Ticks despawned — egal ob der
  Killer noch da ist (Disconnect/Death hinterlassen keine ewigen Bags).
- Jede Bag merkt sich den **Killer-Spieler** (server-seitig). Pro Tick wird
  nur geprüft, ob **dieser** Spieler ≤ `PICKUP_RADIUS` (~1.0) entfernt steht →
  Items in die **ersten freien Inventar-Slots (4–11)**. Inventar voll oder
  falscher Spieler → Bag bleibt liegen.
- **Einschränkung V2:** Die Soulbound-Bindung ist rein server-seitig
  (Pickup-Autorität). Der Client rendert Bags für alle sichtbaren Spieler —
  die Klau-Prävention kommt über den Server, nicht über ein `OWNER_ACCOUNT_ID`-
  Stat (das ist V4 mit Account-System).
- Bag entfernen → Despawn-Drop via Sichtbarkeits-Diff; der Spieler bekommt die
  neuen `INVENTORY`-Stats im nächsten `NewTick`.

### 6.3 Spieler-Tod

`Death` senden, Entity entfernen (Despawn-Drop für andere via
Sichtbarkeits-Diff). Der SessionActor behält die Verbindung (Client zeigt den
Death-Screen); ein Reconnect/`Escape` ist V3+.

---

## 7. Realm-Erweiterungen (`RealmActor`)

```
RealmActor (TickActor, 20 Hz)
├─ IntObjectHashMap<Player>  players      (unverändert aus V1; + knownObjectIds)
├─ IntObjectHashMap<Monster> monsters     (neu, Plain Objects)
├─ List<Projectile>          projectiles  (neu, nur Monster-Bullets)
├─ List<LootBag>             lootBags     (neu)
└─ InterestSet               interest     (Spieler↔Spieler, unverändert)
```

**Sichtbarkeits-Zustand je Spieler:** Jeder `Player` hält `knownObjectIds:
Set<Integer>` — die Objekt-IDs, die sein Client gerade kennt (eigenes Objekt,
Spieler, Monster, Bags). Der **Sichtbarkeits-Diff** (Schritt 6) ist die
**einzige** Quelle für `newObjs`/`drops` pro Tick. Beim Join wird das Set aus
dem Join-`Update` befüllt; Spawn/Despawn (Kill, Tod, Respawn, Bag) laufen
danach automatisch über den Diff — kein separates `notifyOthers`.

**Tick-Reihenfolge (V2, erweitert um §8):**

1. `ctx.drainInbox(budget)` — Nachrichten: `PlayerHello`, `PlayerJoin`,
   `PlayerCreate`, `PlayerMove`, `PlayerLeave`, `KickPlayer` (V1) plus neu:
   `PlayerShootMsg`, `EnemyHitMsg`, `PlayerTextMsg`.
   **Hier passieren die Kills:** Monster-HP ≤ 0 (aus `EnemyHit`) wird jetzt
   entfernt (inkl. XP/Loot/Respawn-Planung, s. §6.1 — Killer-null-sicher) —
   damit feuert ein in diesem Tick getötetes Monster in Schritt 2 nicht mehr.
   Join: Player anlegen, `knownObjectIds` aus dem Join-`Update` (Tiles +
   sichtbare Objekte) befüllen.
2. **Monster-AI:** für jedes **lebende** Monster `monster.simulate(dt)` (ggf.
   Ziel wählen, feuern). Neu gespannte `Projectile`s der Liste hinzufügen.
3. **Spieler:** `player.simulate(dt)` — Bewegung Richtung `Move`-Ziel (max.
   `speed*dt` je Tick + Map-Clamping, V1) **plus HP-Regeneration** (§8.4);
   anschließend `interest.move(...)`.
4. **Projektile:** vorschieben, kollidieren (→ `Damage`/`Death`), abgelaufene
   entfernen. Läuft **vor** der Sichtbarkeit (6), damit `NewTick` im selben
   Tick schon die geänderten HP/Death-Stände trägt.
5. **Bags:** Nähe-Pickup prüfen (→ Items in Inventar, Bag entfernen);
   **abgelaufene Bags (`BAG_LIFETIME_MS`) despawnen.**
6. **Sichtbarkeit bauen (Diff):** pro Spieler
   - `NewTick` aus `interestSet.near` + **Monster-Radius-Scan** (HP, POS,
     SIZE) + **Bags im Radius**;
   - `visible = {sichtbare Objekt-IDs}` mit `knownObjectIds` **diffen**:
     `visible \ known` → `Update.newObjs`, `known \ visible` →
     `Update.drops`; danach `knownObjectIds = visible`. Für neue Spieler
     (Join in Schritt 1) ist der Diff im selben Tick quasi leer (Set ist
     frisch befüllt).
   - alles als `OutboundBatch`.
7. Einmal `flush` je Verbindung (§6 der V1-Doku bleibt gültig).

**Message-Fluss (erweitert um §7 der V1-Doku):**

```
SessionActor.onInbound(PlayerShoot)  → realm.tell(PlayerShootMsg(...))
SessionActor.onInbound(EnemyHit)     → realm.tell(EnemyHitMsg(...))
SessionActor.onInbound(PlayerText)   → realm.tell(PlayerTextMsg(text))

RealmActor (Tick):  Zustandsübergänge ausschließlich hier.
RealmActor          → session.tell(OutboundBatch(...))  (Pakete inkl. Damage/Death)
```

Der `SessionActor` bleibt reiner Transport (kein Spielzustand).

---

## 8. Welt, Damage & Bewegung

### 8.1 `Monster` (Plain Object, `world/`)

Felder: `objectId`, `objectType` (`0x664` Ghost Mage), `pos`, `hp=130`,
`maxHp`, `defense=0`, `size=100` (Radius = `size/200` = 0.5 Einheiten für
Kollision), `attackPeriodMs=2000`, `nextAttackAtMs`, `bulletId`-Zähler,
`respawnAtMs` (nach Tod), `name="Ghost Mage"`.

`simulate(dt)`:
- tot und Respawn-Zeit erreicht → **nahe der ursprünglichen Spawn-Position**
  (`spawn ± ~1 Einheit`, deterministisch, hält die Monster im Geschehen),
  `hp=maxHp`, zurück in die Live-Liste. **Kein Sofort-Schuss beim Respawn:**
  `nextAttackAt = now + attackPeriodMs` — ein direkt neben dem Spieler
  respawnendes Monster feuert nicht sofort.
- Initialer Spawn: deterministisches Raster um die Map-Mitte (alle Monster
  nahe am Spieler-Spawn, damit sie gekämpft werden), `nextAttackAt` gestaffelt
  (`FIRST_SHOT_DELAY + i*500ms`).
- nächster Spieler im Aggro-Radius (z. B. 10 Einheiten) → wenn
  `now >= nextAttackAt`: `EnemyShoot` bauen, Projektil erzeugen, Spielfeld
  markieren (Broadcast-Liste), `nextAttackAt = now + attackPeriodMs`.

### 8.2 `Projectile` (Plain Object)

Felder: `ownerId`, `bulletId`, `bulletType=0`, `startX/Y`, `angle`, `damage`,
`speed` (**Rohwert** `50` — die Einheit ist „Einheiten pro 10 s", Division
durch 10000 nur in der Positionsformel), `lifetimeMs=3000`, `ageMs`.

```
pos = start + (ageMs * speed / 10000) * (cos(angle), sin(angle))
```

`ageMs * speed / 10000` bei Speed `50` und `3000 ms` → `15` Einheiten Reichweite
(konsistent zur Waffen-Reichweite in §8.3). Kollision vs. Spieler: Distanz
`pos → player.pos` ≤ `player.radius + 0.6` (Spieler-Radius ~0.5). Treffer →
`Damage`, entfernen. `ageMs > lifetimeMs` → entfernen (auch ohne Treffer).

### 8.3 Damage-Formel (Client-Ident, `GameObject.damageWithDefense`)

```
effDef = defense                      // kein ArmorPiercing/Condition in V2
final  = max(damage * 3 / 20, damage - effDef)
```

- Spieler→Monster: Energy Staff `0xa97`, `[MinDamage=10, MaxDamage=25]`,
  `NumProjectiles=2`, Speed `180`, Lifetime `475 ms` → Reichweite
  `180 * 475 / 10000 ≈ 8.55` Einheiten.
- Monster→Spieler: Ghost-Mage-Bolt Damage `40`, Defense der Spieler `0` →
  `max(40*3/20, 40-0) = 40`.

### 8.4 Waffe/Inventar & HP-Regeneration

Der `Player` bekommt beim Spawn die Default-Ausrüstung des Wizard
(`PlayersCXML`): Slot 0 = Energy Staff `0xa97`, Slot 1 = `0xa2e`, Slot 4 =
`0xa22`; übrige Slots `-1`. `INVENTORY_0`-Stat im `ObjectStatusData` wird
entsprechend gesetzt (V1 sendet bisher überall `-1`).

**HP-Regeneration (server-seitig, in `player.simulate`):** Regen ist im
Client **nicht** lokal implementiert (`vitality_` = `HpRegen` der Klasse, nur
angezeigt) — der Server treibt sie und sendet die HP-Stat je `NewTick`.
Wizard-Basis `HpRegen=12` (`PlayersCXML`):
`hp = min(maxHp, hp + HpRegen * dt)` — bewusste Vereinfachung, macht die Demo
spielbar, ohne dass drei Ghost-Mage-Treffer (à 40) unvermeidbar töten. `VITALITY`
-Stat (27) = `HpRegen` mitsenden.

---

## 9. Chatbefehl `/give`

- **Syntax:** `/give <objectType>` — hex (z. B. `/give 0xa07`) oder dezimal.
- **Weg:** `PlayerText` → SessionActor (syntaktisch: fängt `/`-Zeilen ab) →
  `PlayerTextMsg(text)` → Realm-Tick.
- **Wirkung:** erstes freies Inventar-Slot (4–11) mit dem Item belegen; kein
  Item wenn voll oder unbekannter Type. Inventar-Stat kommt über `NewTick`.
- **Feedback:** Das eingehende `Text`-Paket (34) ist im Client **nicht**
  gemappt — Server-Chat wird nicht gerendert. Optionales Feedback über eine
  **`Notification`** über dem Spieler (`objectId=playerId`, QueuedStatusText):
  dafür einen im Client vorhandenen TextKey nutzen (die Keys liegen im
  Language-File, eigene Keys werden nicht aufgelöst) oder ganz auf das
  sichtbare Item im Inventar setzen. (Einschränkung dokumentiert.)

---

## 10. Supervision & Fehler

Unverändert aus V1 (§10): `RealmSupervisor` (ONE_FOR_ONE) startet Realms neu;
`SessionSupervisor` je Verbindung; **Entities sind Plain Objects** — Fehler in
`monster.simulate`/Projektil-Kollision werden im Realm-Tick abgefangen, das
Entity entfernt, der Tick läuft weiter.

---

## 11. Projektstruktur (`Gameserver/`)

```
Gameserver/src/main/java/dev/localsoul/aero/game/
├── Main.java · GameServer.java                  (unverändert)
├── net/                                         (unverändert)
├── protocol/
│   ├── MessageType.java                         (um PlayerShoot/EnemyHit/… erweitert)
│   ├── IncomingMessage/OutgoingMessage.java     (unverändert)
│   ├── ServerPlayerShoot.java · EnemyShoot.java · Damage.java
│   ├── Notification.java · Death.java           (neu, out)
│   ├── PlayerShoot.java · EnemyHit.java · PlayerHit.java · PlayerText.java (neu, in)
│   └── … bestehende V1-Klassen                   (Hello/Load/Move/MapInfo/…)
├── actor/
│   ├── RealmActor.java                           (erweitert, §7)
│   ├── SessionActor.java                         (um die 4 neuen In-Pakete erweitert)
│   ├── GameMessages.java                         (PlayerShootMsg, EnemyHitMsg, PlayerTextMsg)
│   └── RoomRegistry.java                         (unverändert)
└── world/
    ├── Map.java · Entity.java                    (unverändert)
    ├── Player.java                               (Inventar, Shot-Ringpuffer, Defense, XP)
    ├── Monster.java                              (neu, §8.1)
    ├── Projectile.java                           (neu, §8.2)
    └── LootBag.java                              (neu, Container: objectType + Items)
```

---

## 12. Roadmap

- [x] **V1 – Demo:** Netty (Security + Backpressure), RC4-Framing, leere Welt,
      Bewegung, Multiplayer-Sichtbarkeit.
- [x] **V2 – Kampf:** `PlayerShoot`/`EnemyHit` (validiert), `EnemyShoot` +
      Server-Projektilsimulation, `Damage`, `Death`, XP/Level,
      Soulbound Loot Bags (Nähe-Pickup, Lebensdauer), Monster-Respawn,
      `/give`. **46 Tests grün** (davon 35 neu in V2).
- [ ] **V3 – Inhalt:** Map-Import, Reiche mit mehreren Räumen, Portale,
      `Reconnect`, `Escape`-Ziel.
- [ ] **V4 – Persistenz:** Account-Token-Kopplung mit `Server`, Charakter-
      Speicherung über den `PersistenceActor`, Vault.
- [ ] **Skalierung (bei Bedarf):** Nexus-Shards über die `RoomRegistry`.

---

## 13. Verifikation

- **Build:** `mvn -pl Gameserver -am install`
- **Start:** `java -cp "Gameserver/target/classes:Actor/target/classes:$(cat
  <netty-cp>)" dev.localsoul.aero.game.Main <port>`
- **Client:** Flash-Client (27.7.X2, `LocalhostServerModel`); Killerfahrung:
  Monster spawnen, schießen, sterben; XP/Loot; `/give` füllt das Inventar.
- **Automatisiert:**
  - `PlayerShootTest`/`EnemyHitTest` — Schuss-Spur → Treffervalidierung
    (gültig/ungültig: außer Reichweite, fremde bulletId, totes Ziel),
    **bulletId-Wraparound (255 → 0)** wird noch als gültiger Schuss erkannt,
    **doppelter `EnemyHit` auf dieselbe bulletId** wird verworfen.
  - `ProjectileSimulationTest` — Bewegung (`pos = start + age*speed/10000`),
    Kollision, Ablauf (Lifetime), **Projektil trifft zwei Spieler im selben
    Tick → nur der erste bekommt `Damage`**.
  - `DamageFormulaTest` — `max(dmg*3/20, dmg-defense)` gegen Client-Fälle.
  - `MonsterAITest` — Feuert nur im Aggro-Radius und mit `attackPeriod`;
    **Monster, das im selben Tick stirbt, feuert nicht mehr** (Kill im
    Inbox-Drain vor der AI).
  - `DeathTest` — **Spieler stirbt und ein weiteres Projektil trifft ihn im
    selben Tick → kein zweites `Death`**, kein `Damage` an Tote.
  - `CombatFlowTest` (Integration über Fake-Socket): Kill eines Monsters →
    `Update.drops` (via Diff) + XP-`Notification` + Loot-Bag; Spieler-Tod →
    `Death`.
  - `LootBagTest` — Nähe-Pickup füllt Inventar (nur Eigentümer), volle
    Inventare bleiben liegen, **`BAG_LIFETIME_MS`-Despawn** ohne Eigentümer.
  - `VisibilityDiffTest` — Neuankömmling sieht bestehende Monster/Bags
    (`newObjs`); Monster/Bag verlässt den Radius → `drops`; **kein Doppel-Drop**
    (Kill-Despawn + Diff greifen nicht beide); Respawn mit neuer objectId →
    erneutes `newObjs`.
  - `KillWithoutOwnerTest` — Killer verlässt im selben Drain (oder stirbt):
    kein Absturz, **kein XP, keine Bag**.
  - `PlayerRegenTest` — `hp` steigt in `simulate` um `HpRegen*dt`, gedeckelt
    auf `maxHp`; `VITALITY`-Stat wird gesendet.
  - `RespawnGraceTest` — respawntes Monster feuert nicht sofort
    (`nextAttackAt = now + attackPeriodMs`).
  - `GiveCommandTest` — `/give 0xa07` belegt freies Slot, unbekannter Type
    wirkungslos, volles Inventar wirkungslos.
  - `RealmPerfTest` — **500 Monster**: Tick-Zeit und `NewTick`-Bau bleiben
    im Rahmen (Leistungsgrenze des O(Spieler × Monster)-Scans vor V3 kennen).
  - Bestehende V1-Tests bleiben grün (Interest/Join/Move/Kick).

---

## 14. Offene Punkte (bei Implementierung zu klären)

- **Aggro-/Feuer-Parameter** (`attackPeriodMs=2000`, Aggro-Radius 10,
  `PICKUP_RADIUS=1.0`, `RESPAWN_MS=10 000`, `BAG_LIFETIME_MS=60 000`,
  Regen `HpRegen*dt`) im Realbetrieb nachmessen.
- **EnemyHit-Validierungsstärke:** Schuss-Spur-Ringpuffer (modulo 256,
  Verbrauchs-Markierung) ist bewusst sanft; ein harter Anti-Cheat
  (Zeit/`time`-Feld, Winkelkegel) ist V3+.
- **Ringpuffer-Größe** (Anzahl Schüsse je Spieler) und
  **Reichweiten-Toleranz** beim `EnemyHit` ausmessen — letztere wird nötig,
  sobald Monster sich bewegen (V3; V2 stehen sie still, Position beim
  `EnemyHit` reicht).
- **Sichtbarkeits-Diff:** `Set`-Pflege je Spieler ist O(visible) pro Tick;
  die O(Spieler × Monster)-Grenze steckt im Scan (s. `RealmPerfTest`) — bei
  großen Welten echte Spatialstruktur (V3).
- **Kollisionsradius** der Projektile: konstanter Hit-Radius vs. exakte
  Client-Mathe (`size/200` je Objekt) — V3-Feinschliff.
- **Bag-Eigentum optisch:** `OWNER_ACCOUNT_ID`-Stat für den Client, sobald es
  ein Account-System gibt (V4).