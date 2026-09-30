# Aero::Gameserver — Implementierungsplan (V1: Demo-Welt)

> Der eigentliche RotMG-Gameserver: verwaltet Verbindungen (Netty), die
> Weltsimulation und die Spieler. Baut auf der Actor-Runtime (`Actor/`) auf.
>
> Modul: `dev.localsoul:Aero::Gameserver` · Paketwurzel: `dev.localsoul.aero.game`
> Abhängigkeiten: `Actor` (Runtime) + **Netty** (`netty-transport`, `-codec`,
> `-handler`). Erste Version ist eine **Minimal-Demo**: Verbindung, leere Welt,
> Bewegung. Kampf, Drops, Dungeons folgen in späteren Stufen.

---

## 1. Abgrenzung und Ziel (V1)

| Modul | Rolle | Zuständig für |
|---|---|---|
| `Actor` | Runtime | Actor-Modell, Tick, Supervision, Mailboxen, `InterestSet` (fertig) |
| `Server` | HTTP/Appengine | Login, `/char/list`, `/app/init`, … (statische Ressourcen, Skeleton) |
| **`Gameserver`** | **Echtzeit-Welt** | TCP-Verbindung, Protokoll, Weltsimulation (neu) |

**V1-Ziel (Demo):** Der unmodifizierte Flash-Client (27.7.X2) verbindet sich,
ein Spieler erscheint in einer leeren Welt und bewegt sich; die Bewegung wird
per `NewTick` an ihn selbst und an Mitspieler im selben Raum verteilt.
**Kein** Account-Check, **kein** Speichern, **kein** Kampf in V1.

HTTP-Modul und Gameserver sind in V1 **entkoppelt** (kein geteilter Zustand);
ein gemeinsamer Login-Token/Session-Store ist ein späterer Schritt.

---

## 2. Leitentscheidungen (aus dem Review)

1. **Neues Maven-Modul** `Gameserver`; `Server` bleibt der HTTP-Teil.
2. **Byte-exaktes 27.7.X2-Protokoll** — der unveränderte Client spielt direkt.
   RC4-Keys sind im Client **fest verdrahtet**, es gibt **keinen Handshake**
   (bewusst akzeptiert statt „verbessert", Grundlage der Byte-Kompatibilität).
3. **Entities sind Plain Objects im Reich — von Anfang an, nicht erst „L4".**
   Der `RealmActor` iteriert intern über eine `Int2ObjectMap<Entity>` und ruft
   `entity.simulate(dt)` direkt auf. **Kein Message-Passing pro Entity, kein
   Antwort-Sammeln.** Grund: Ein Tick betrifft alle Entities gleichzeitig
   (Fan-out); das über Mailboxen zu schicken ist reiner Overhead (20 Hz × 200
   Spieler = 8 000 Msg/s nur fürs Bewegen). Der **Nexus ist der Worst Case**
   (ein geteilter Raum, höchste Spielerzahl auf einem Carrier) — dort darf die
   Simulation reine Objekt-Iteration sein. **Actors bleiben für zwei Zwecke:**
   - **Sessions** — wirklich unabhängig/asynchron (Client-Input kommt jederzeit
     rein), brauchen Isolation + Supervision.
   - **Realms** — parallel zueinander, **die eigentliche Skalierungsachse**
     (viele Realms auf vielen Carrier-Threads).
4. **Netty decodiert, Session-Actor verarbeitet.** Event-Loop blockiert nie;
   Outbound ist **gebündelt** (Flush pro Tick) mit **Backpressure + Kick**
   (s. §6) — kein `writeAndFlush` pro Paket.
5. **`Hello`/`Load` akzeptieren + Demo-Welt:** syntaktische Validierung, keine
   Auth gegen das HTTP-Modul.
6. **`InterestSet` (Spatial Hash Grid) ist in V1 ein echter Mechanismus**, kein
   Platzhalter: `NewTick`/`Update` werden aus `interestSet.near(...)` gebaut,
   nicht als „alle Objekte". In der leeren Welt sehen sich alle, aber das
   Delta-Format ist von Tag 1 sichtbarkeitsgetrieben.
7. **Server-Tick 20 Hz + `NewTick`:** `Move` ist Eingabe, der Server ist
   autoritativ und validiert sanft (maximale Distanz pro Tick).
8. **Persistenz V1: In-Memory**, kein Speichern; Disconnect verwirft den Spieler.
9. **Supervision:** `RealmSupervisor` über die Realms; **`SessionSupervisor` je
   Verbindung** (ein Spieler ohne aktive Verbindung existiert in V1 nicht).
   Entities sind Plain Objects → Realm-interne Entity-Fehler werden im Tick
   abgefangen, nicht supervized.
10. **„Nexus" ist keine Singleton, sondern eine Shard-Menge (bewusste
   Entscheidung).** Der `Nexus` ist strukturell single-threaded (ein
   Realm-Actor = ein Carrier-Thread) — die Kapazitätsgrenze liegt dort auf
   einer Core-Leistung von `simulate` + `InterestSet.near` + Batch-Bau. Das
   Original löst das durch mehrere Nexus-Instanzen (Sharding). Deshalb bildet
   eine **`RoomRegistry`** `gameId → Shard-Menge` ab; V1 startet eine Instanz,
   aber ein zweiter Nexus-Shard (Last-verteilt) ist eine Konfigurations-,
   keine Struktur-Änderung. Skalierung ist damit zweistufig: **Shards pro
   Raum-Typ** + **Realms** (Dungeons/Instanzen).
11. **Backpressure/Kick ist ein Nachrichtenfluss, kein direkter `close()`** aus
    dem Netty-Thread (s. §6): Backpressure → `BackpressureKick` → Realm
    entfernt die Entity im Tick → `KickConfirmed` → erst dann `channel.close()`.
    Invariante: **nur der Realm-Thread mutiert die Entity-Map.**

---

## 3. Wire-Protokoll 27.7.X2 (am Client verifiziert)

Quelle: `RotMG_Client_27.7.X2/src/kabam/lib/net/impl/SocketServer.as`,
`GameServerConnectionConcrete.as`.

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
- Der Client wartet erst 4 Bytes Länge, dann Typ + Payload, entschlüsselt den
  Payload, dann `parseFromInput`.

### Verschlüsselung (kein Handshake!)

Feste RC4-Keys aus dem Client (`GameServerConnectionConcrete.encryptConnection`,
nur aktiv bei `Parameters.ENABLE_ENCRYPTION`):

| Richtung | RC4-Key (hex) |
|---|---|
| Client → Server (Server entschlüsselt) | `311f80691451c71d09a13a2a6e` |
| Server → Client (Server verschlüsselt) | `72c5583cafb6818995cdd74b80` |

- **RC4 ist zustandsbehaftet:** eine Cipher-Instanz **pro Richtung pro
  Verbindung**, über alle Pakete hinweg weiterlaufend (der Client macht das
  genauso). Nicht pro Paket neu seeden!
- Java hat kein RC4 in der Standardbibliothek → kleine ARCFOUR-Implementierung
  (KSA + PRGA, ~30 Zeilen) im Gameserver.

### Verbindungsziel des Clients

Der Client verbindet auf `server.address : server.port`. Für die Demo gilt der
`LocalhostServerModel` (`localhost : Parameters.PORT`). Der Gameserver-Port ist
als Parameter konfigurierbar (Default z. B. `2050`).

---

## 4. Paketsatz V1

### Vom Client kommend (outgoing)

| Paket | Zweck | Felder (Reihenfolge) |
|---|---|---|
| `Hello` | Verbindung starten | `writeUTF buildVersion`, `writeInt gameId`, `writeUTF guid`, `writeInt (random)`, `writeUTF password`, `writeInt (random)`, `writeUTF secret`, `writeInt keyTime`, `writeShort key.len`, `key bytes`, `writeInt mapJSON.len`, `mapJSON bytes`, `writeUTF entrytag`, `writeUTF gameNet`, `writeUTF gameNetUserId`, `writeUTF playPlatform`, `writeUTF platformToken` |
| `Load` | Charakter in Welt laden | `writeInt charId`, `writeBoolean isFromArena` |
| `Move` | Bewegung | `writeInt tickId`, `writeInt time`, `WorldPosData newPosition`, `writeShort records.len`, je Record `MoveRecord(time, x, y)` |
| `Escape` | Reich verlassen | – (leer) |

Acks (`UpdateAck`, `GotoAck`, `Pong`, `ShootAck`) werden in V1 entgegengenommen,
aber nicht benötigt.

### Vom Server kommend (incoming)

| Paket | Zweck | Felder (Reihenfolge) |
|---|---|---|
| `MapInfo` | Weltdaten | `readInt width`, `readInt height`, `readUTF name`, `readUTF displayName`, `readUnsignedInt fp`, `readInt background`, `readInt difficulty`, `readBoolean allowPlayerTeleport`, `readBoolean showDisplays`, `readShort clientXML.len` (je: `readInt len` + UTF-Bytes), `readShort extraXML.len` (je: `readInt len` + UTF-Bytes) |
| `CreateSuccess` | Spieler ist drin | `readInt objectId`, `readInt charId` |
| `Update` | Objekte/Tiles | `readShort tiles.len` (je `GroundTileData: short x, short y, int type`), `readShort newObjs.len` (je `ObjectData: short objectType + ObjectStatusData`), `readShort drops.len` (je `readInt`) |
| `NewTick` | Tick-Status | `readInt tickId`, `readInt tickTime`, `readShort statuses.len` (je `ObjectStatusData`) |
| `Ping` | Latenz | – (V1 optional) |

**Datenklassen** (`WorldPosData`, `ObjectData`, `ObjectStatusData`,
`GroundTileData`, `MoveRecord`, `StatData`): Feldreihenfolgen **per Skript aus
den Client-Klassen extrahieren** (`kabam/rotmg/messaging/impl/data/*`), nicht
manuell abtippen — Off-by-one in `ObjectStatusData` ist sehr mühsam zu debuggen.

**Message-IDs:** Ebenfalls per Skript aus dem Client-Mapping
(`GameServerConnectionConcrete`/`MessageCenter`) extrahieren und als
`enum MessageType { id }` ablegen. Beides **vor dem ersten Codec-Test**
vollständig erledigen (s. §14).

---

## 5. Netty-Schicht

```
Netty Bootstrap (NIO/Epoll, ein Port)
└─ ChannelInitializer
   └─ Pipeline (inbound):
       ConnectionLimiter (pro-IP-Limit, z. B. 5 aktive Verbindungen/IP)
       IdleStateHandler (readerIdle, z. B. 30 s; plus Hello-Timeout 5 s nach Connect)
       LengthFieldBasedFrameDecoder(lengthFieldOffset=0, lengthFieldLength=4,
                                    lengthAdjustment=-4, initialBytesToStrip=0,
                                    maxFrameLength=1 MiB, failFast=true)
       └─ PacketDecoder (ByteToMessageDecoder)
            • 1 Byte Typ (clear) lesen
            • Rest = Payload, RC4-entschluesseln (Key 311f…)
            • MessageType → Paketklasse, parseFromInput
            • FloodGuard: Pakete/s pro Verbindung zaehlen (z. B. > 500/s → Kick)
            → session.tell(packet)          [Netty-Thread → Actor-Mailbox]
   Pipeline (outbound):
       PacketEncoder (MessageToByteEncoder)
            • Payload schreiben, RC4-verschluesseln (Key 72c558…)
            • writeInt(payload+5), writeByte(type), writeBytes(payload)
```

### Security von Tag 1 (keine „später"-Lücken)

- **`maxFrameLength` ist Pflicht** — ohne sie puffert
  `LengthFieldBasedFrameDecoder` ein beliebig großes Längenfeld unbegrenzt
  (OOM durch Portscanner). Default **1 MiB**, konfigurierbar, `failFast=true`.
- **`IdleStateHandler`:** `readerIdleTime` (z. B. 30 s) schließt Verbindungen,
  die keinerlei Traffic senden; zusätzlich ein **Hello-Timeout** (5 s nach
  Connect, sonst Close) — verhindert offene Leichen-Verbindungen.
- **`ConnectionLimiter`:** max. aktive Verbindungen **pro IP** (z. B. 5);
  Zähler im ConcurrentHashMap. **Das Dekrement muss auf *jedem* Exit-Pfad
  laufen** — `channelInactive`, `exceptionCaught`, Flood/Idle-Kick **und** die
  selbst abgelehnte Verbindung (Zähler nicht erhöhen oder sofort zurückgeben).
  Ein vergessener Pfad leckt den Zähler und sperrt IPs irgendwann fälschlich —
  deshalb eigener Unit-Test `ConnectionLimiterTest` (s. §13), nicht nur der
  Security-Integrationstest.
- **`FloodGuard`:** Pakete/s pro Verbindung begrenzen (z. B. > 500/s → Kick).
  Verhindert, dass ein Client die Realm-Mailbox mit `Move`-Spam flutet.

---

## 6. Backpressure & Outbound-Batching

Ziel: aus vielen `send(...)` pro Tick wird **ein** Flush pro Client pro Tick,
und ein langsamer Client kann den Raum **nicht** blockieren oder aufblähen
(Head-of-Line-Schutz).

- **RealmActor bündelt pro Spieler pro Tick:** Alle Outbound-Messages
  (`NewTick`, ggf. `Update`, `Notification`) eines Ticks werden je Spieler in
  eine Batch gepackt und als **eine** Nachricht an den `SessionActor` gesendet
  (`session.tell(batch)`). Analog zum `OutboundBuffer`-Konzept der Runtime.
- **`NettyClient.send(msg)` schreibt ohne Flush** in den Channel
  (`channel.write(...)`), **`flush()` einmal am Tick-Ende** — nicht
  `writeAndFlush` pro Paket (Syscall-Reduktion beim Broadcast an viele Spieler).
- **Backpressure/Kick statt unbegrenztem Puffer:**
  - Bounded Outbound-Queue pro Verbindung (z. B. 1024 Batches oder Byte-Limit).
  - Ist die Queue voll **oder** `channel.isWritable() == false` über eine
    Schwelle (z. B. mehrere Ticks), wird der Spieler **gekickt**.
  - Damit kann ein hängender Client nicht den Sende-Puffer eines Realms
    aufblähen — er fliegt raus statt den Tick zu blockieren.
- Die Netty-`write`-Operation ist asynchron (Rückkanal über
  `channel.isWritable()` + `ChannelWritabilityChanged`), blockiert also nie den
  Realm-Thread.

### Kick-Pfad ist eine Nachricht, kein direkter `close()` (Invariante!)

Die `Int2ObjectMap<Entity>` gehört **exklusiv dem Realm-Thread** — das ist der
Witz an Plain Objects. Die Backpressure-Erkennung (`isWritable()`,
Queue-Füllstand) läuft aber im `NettyClient`/auf dem Netty-Thread. Deshalb ist
**der Kick als Nachrichtenfluss modelliert** — nie `channel.close()` direkt aus
der Backpressure-Prüfung, sonst gäbe es eine Race (Realm schreibt an einen
toten Channel) oder eine verzögerte Entfernung:

```
NettyClient (Netty-Thread)
  erkennt: Queue voll | !isWritable() über Schwelle
  └─ session.tell(BackpressureKick(reason))        [Netty-Thread → Session-Mailbox]
SessionActor (Session-Thread)
  leitet weiter: realm.tell(KickPlayer(objectId, reason))
RealmActor (Realm-Tick, nächster Tick)
  1. Player-Entity aus Int2ObjectMap entfernen      ← einzige Stelle, die die Map mutiert
  2. Mitspielern Update (Objekt entfernt) senden
  3. session.tell(KickConfirmed(reason))            ← Realm hat die Entity geräumt
SessionActor
  └─ nettyClient.close() → channel.close()          ← erst JETZT schließen
```

Regel: **Nur der Realm-Thread mutiert die Entity-Map.** `channel.close()`
passiert erst, nachdem der Realm die Entfernung bestätigt hat (`KickConfirmed`).
Für den Netty-Thread heißt das: Backpressure → melden, nicht schließen.
Disconnects von außen (`channelInactive`) sind davon unabhängig — sie laufen
über denselben `PlayerLeave`-Pfad in den Realm (s. §7-Datenfluss).

---

## 7. Actor-Mapping

```
ActorSystem "game"
└─ RoomRegistry                        (lookup: gameId → Shard-Menge, kein Singleton)
   └─ RealmSupervisor (pro Realm, ONE_FOR_ONE)
      └─ RealmActor (TickActor, 20 Hz) — besitzt Map + InterestSet + Int2ObjectMap<Entity>
         ├─ Entity (Plain Object)      ← je Objekt (Spieler; spaeter Monster, Portal)
         └─ SessionSupervisor (je Verbindung, ONE_FOR_ONE)
            └─ SessionActor (Actor)    ← eine Netty-Verbindung
```

- **`RoomRegistry` — „Nexus" ist keine Singleton, sondern eine Shard-Menge.**
  Der Lookup bildet `gameId` (bzw. Raum-Typ, z. B. `Nexus`) auf eine Menge
  austauschbarer `RealmActor`-Instanzen ab; ein Load/Join wählt einen Shard
  (z. B. geringste Last oder Round-Robin). **V1 startet genau eine Instanz pro
  Raum-Typ**, aber die Struktur ist von Anfang an auf mehrere Nexus-Kopien
  ausgelegt (das Original sharded den Nexus über mehrere Instanzen) — das
  nachträglich einzuziehen wäre unangenehmer. Die Skalierungsachse ist damit
  bewusst zweistufig: mehrere Shards pro Raum-Typ **und** mehrere Realms.
- **`RealmActor extends TickActor`** — besitzt die leere `Map` (width×height),
  einen `InterestSet` und die `Int2ObjectMap<Entity>`. Pro Tick (§8): Eingaben
  drainen, Entities simulierten, Sichtbarkeit abfragen, Outbound bündeln.
  **Es gibt kein `Simulate`-Message-Passing zu Entities** — die Iteration ist
  interner Objektaufruf.
- **`Entity` (Plain Object)** — Zustand + `simulate(dt)`; `Player` erweitert
  `Entity` um Zielposition, Validierung und Status (→ `ObjectStatusData`).
  Kein Thread, kein Actor.
- **`SessionActor extends Session`** (aus `actor.session`) — eine Verbindung;
  Outbound über den `NettyClient` (gebündelt, s. §6). `client.send(...)` =
  Channel-Write.

### Datenfluss Session ↔ Realm (wer ändert was)

Die Verantwortung ist **eine einzige, explizite Kette** — es gibt keine zweite
Stelle, die Spielzustand mutiert:

1. **Netty-Pipeline** decodiert Bytes → Paket (`Hello`, `Load`, `Move`, …).
2. **`SessionActor.onInbound`** prüft nur **syntaktisch/formal** (Plausibilität,
   Hello-Felder vorhanden, Load-charId int) und leitet dann **semantische
   Nachrichten** an den Realm weiter: `PlayerHello(session, gameId)`,
   `PlayerJoin(session, charId)`, `PlayerMove(objectId, pos, records)`,
   `PlayerLeave(objectId)`.
3. **`RealmActor`** verarbeitet diese in seinem Tick (`ctx.drainInbox`) — dort
   passiert **jeder Zustandsübergang** (Entity anlegen, Zielposition setzen,
   Entity entfernen). Nur der Realm-Thread mutiert Map/Entities/InterestSet.
4. **Rückkanal (symmetrisch zum Kick):** Was der Realm erzeugt, geht als
   Nachricht zurück an die Session: `MapInfoReady`, `JoinConfirmed`,
   `KickConfirmed`. **Auch die Outbound-Pakete baut der Realm** (er kennt Map
   und Entity-Zustand) — die Session schreibt sie nur in den Channel.

Der `SessionActor` hält **keinen Spielzustand** (kein Entity, keine Position,
keine objectId); er ist reiner Transport für eine Verbindung.

### Join-Pfad (symmetrisch zum Kick)

`CreateSuccess` (§4) trägt die `objectId`, und die wird **im Realm-Tick**
vergeben, wenn das Entity angelegt wird. Deshalb hat der Join denselben
Ack-Kanal wie der Kick. Der Client-Flow ist **zweiphasig** (am Client
verifiziert): `Hello` → `MapInfo` → (Client sendet `Load`) → `Update` +
`CreateSuccess`.

```
Phase 1 — Hello → MapInfo (kein Entity)
  SessionActor: onInbound(Hello) → syntaktisch ok
    └─ realm.tell(PlayerHello(session, gameId))
  RealmActor (Tick): Shard binden, MapInfo bauen
    └─ session.tell(MapInfoReady(mapInfo))
  SessionActor: MapInfo in den Channel schreiben + einmalig flushen
    Client: bekommt MapInfo → sendet Load

Phase 2 — Load → Update + CreateSuccess (Entity entsteht)
  SessionActor: onInbound(Load) → syntaktisch ok
    └─ realm.tell(PlayerJoin(session, charId))
  RealmActor (Tick):
    1. Player-Entity in Int2ObjectMap anlegen, objectId vergeben   ← einzige Stelle
    2. Update (create player) + CreateSuccess bauen
    3. session.tell(JoinConfirmed(objectId, update, createSuccess))
  SessionActor: Update → CreateSuccess der Reihe nach schreiben + flushen
```

**Reihenfolge ist Spezifikation und kommt aus dem Realm:** `MapInfo` vor
`Update` vor `CreateSuccess`; `CreateSuccess` erst, nachdem das Entity im
Welt-Snapshot liegt (es erscheint ab dem nächsten `NewTick`). Da der Realm alle
drei Pakete baut und der Client ohne `MapInfo` kein `Load` sendet, ist die
Reihenfolge strukturell garantiert — die Session kann sie nicht vertauschen.
`JoinConfirmed` ist zugleich der Zeitpunkt, ab dem die Session die
Per-Tick-Outbound-Batches des Realms weiterleitet.

- **Supervision:** `RealmSupervisor` (ONE_FOR_ONE) startet Realms neu
  (`spawnReplacing`, Namens-Wiederverwendung). `SessionSupervisor` je Verbindung
  startet eine Session neu. **Entities werden nicht supervized** — sie sind
  Plain Objects; ein Fehler in `entity.simulate` wird im Realm-Tick abgefangen
  (`try/catch` je Entity), geloggt und das Entity entfernt. Damit gibt es auch
  **kein Timeout-Problem** „Entity antwortet nicht" — es gibt keine Antworten.

> **Warum Sessions als Actors, Entities nicht:** Client-Input ist unabhängig
> und asynchron (jederzeit) und braucht Isolation; die Simulation ist synchron
> getaktet und betrifft alle Entities gleichzeitig (Fan-out) — dort ist
> Objekt-Iteration die richtige Struktur. Realms sind untereinander parallel
> und damit die Skalierungsachse (viele Realms, viele Carrier-Threads).

---

## 8. Simulation & Bewegung

- **Tick-Rate:** Default **20 Hz** (50 ms), konfigurierbar; `TickDriver` mit
  `CLAMP`.
- **Tick-Reihenfolge im `RealmActor` (Spezifikation):**
  1. `ctx.drainInbox(budget)` — eingehende Nachrichten aus dem Datenfluss (§7)
     verarbeiten: `PlayerHello` (MapInfo bauen → `MapInfoReady`),
     `PlayerJoin` (Entity anlegen, objectId vergeben → `JoinConfirmed`),
     `PlayerMove` (Ziel-/Wunsch-Position der `Player`-Entity setzen),
     `PlayerLeave`/`KickPlayer` (Entity entfernen → `KickConfirmed`).
     **Alle Zustandsübergänge passieren hier — nur der Realm-Thread.**
  2. `for (Entity e : entities.values()) { try { e.simulate(dt); }
     catch (Throwable t) { /* loggen, Entity entfernen */ } }` — interne
     Iteration, **keine Nachrichten**.
  3. Bewegungs-Validierung (s. u.), Positionen in `InterestSet` nachziehen
     (`move`).
  4. Pro Spieler: `interestSet.near(player.interest, out)` → `NewTick`-Status
     aus den sichtbaren Entities bauen; Outbound pro Spieler bündeln.
  5. Einmal `flush` je Verbindung (§6).
- **`Move`-Validierung (sanft):** Pro Tick prüft der Server, dass die neue
  Position die maximale Lauf-Distanz (`speed * dt + Toleranz`) nicht
  überschreitet und innerhalb der Map liegt. Verstoß → Position korrigieren/
  ignorieren (kein Kick in V1).
- **Autoritativ:** Positionen kommen aus der Server-Simulation, `Move` ist nur
  Eingabe. `NewTick` trägt die Server-Status.
- **Sichtbarkeit:** `NewTick`/`Update` entstehen **ausschließlich** über
  `InterestSet.near(...)` (echtes Spatial Hash Grid aus der Runtime). In der
  leeren V1-Welt sehen sich alle Spieler (Radius deckt die kleine Map ab),
  aber der Code ist von Tag 1 sichtbarkeitsgetrieben — für größere Welten
  ändert sich das Delta-Format nicht mehr.

---

## 9. Lebenszyklus & Persistenz (V1)

- **Character-Daten:** In-Memory. Beim ersten `Load` legt der Server einen
  Standard-Charakter an (Level 1, Standard-Klasse/Textur). Kein Speichern.
- **Disconnect:** `channelInactive` → `SessionActor.stop(...)` →
  `PlayerLeave`-Nachricht an den Realm → `Player`-Entity im Tick entfernen →
  Mitspieler bekommen `Update` (Objekt entfernt). Persistenz/Retry
  (`Reconnect`-Paket) folgt später.
- **`Reconnect`** (vom Server initiiert) wird in V1 nicht gesendet.
- **Persistenz blockiert nie den Realm-Thread (V4, aber Design von Anfang an):**
  Sobald gespeichert wird, passiert das über einen **eigenen
  `PersistenceActor`** (separater Actor, `runBlocking`-Offload für DB/IO). Der
  Realm sendet Save-Aufträge nur **fire-and-forget** (`persistence.tell(Save(...))`)
  — nie blockierend im Realm-Tick. Auch im Carrier-Kontext: kein `synchronized`
  auf fremde Locks im Realm-Thread (Pinnt den Carrier; Diagnose mit
  `-Djdk.tracePinnedThreads=full`).

---

## 10. Supervision & Fehler

- **`RealmSupervisor` (ONE_FOR_ONE):** startet ein Realm nach Crash neu;
  Namens-Wiederverwendung via `spawnReplacing`.
- **`SessionSupervisor` je Verbindung (Entscheidung, nicht offen):** startet
  die Session neu; ist die Verbindung tot, wird die Session heruntergefahren
  statt neu verbunden. Ein Spieler ohne aktive Verbindung existiert in V1
  nicht.
- **Entities (Plain Objects):** Fehler in `simulate` werden im Realm-Tick
  abgefangen; das Entity wird entfernt, der Tick läuft weiter. Kein
  Supervisor-Aufwand pro Entity.
- **Netty:** Exceptions pro Verbindung isolieren (`try/catch` im Handler,
  `channelInactive`-Pfad), nie den Boss-EventLoop killen. Abgelehnte/
  gekickte Verbindungen (Flood, Idle, IP-Limit) werden sauber geschlossen.

---

## 11. Projektstruktur (`Gameserver/`)

```
Gameserver/pom.xml                      (abhängt von Actor + Netty)
Gameserver/src/main/java/dev/localsoul/aero/game/
├── Main.java                            (Entry-Point: Port, Tick-Rate)
├── GameServer.java                      (Facade: ActorSystem + Netty starten)
├── net/
│   ├── NettyServer.java                 (Bootstrap, ChannelInitializer)
│   ├── PacketDecoder.java               (Frame → IncomingMessage)
│   ├── PacketEncoder.java               (OutgoingMessage → Frame)
│   ├── Rc4Cipher.java                   (ARCFOUR, stateful)
│   ├── NettyClient.java                 (implementiert actor.session.Client,
│   │                                     send ohne Flush + Backpressure/Kick)
│   ├── ConnectionLimiter.java           (pro-IP-Limit)
│   └── FloodGuard.java                  (Pakete/s pro Verbindung)
├── protocol/
│   ├── MessageType.java                 (Typ-ID ↔ Paketklasse)
│   ├── IncomingMessage.java             (Basis, parseFromInput)
│   ├── OutgoingMessage.java             (Basis, writeToOutput)
│   ├── Hello.java · Load.java · Move.java · Escape.java
│   └── MapInfo.java · CreateSuccess.java · Update.java · NewTick.java · Ping.java
├── actor/
│   ├── RoomRegistry.java                 (gameId → Shard-Menge, kein Singleton)
│   ├── RealmActor.java                   (TickActor, besitzt Map/Entities)
│   ├── SessionActor.java                 (Session)
│   ├── RealmSupervisor.java · SessionSupervisor.java
│   ├── PersistenceActor.java             (V4: Save-Offload, nie im Realm-Tick)
│   └── messages (records, Datenfluss §7):
│       PlayerHello · PlayerJoin · PlayerMove · PlayerLeave ·
│       BackpressureKick · KickConfirmed · MapInfoReady · JoinConfirmed
└── world/
    ├── Map.java                         (leere Welt: Breite/Höhe, Grund-Typ)
    ├── Entity.java                      (Plain Object, simulate(dt))
    └── Player.java                      (Entity + Bewegung/Validierung/Status)
```

---

## 12. Roadmap

- [ ] **V1 – Demo:** Modul, Netty (mit Security + Backpressure, s. §5/§6),
      RC4-Framing, `Hello`/`Load`, leere Welt, Plain-Object-Entities im
      Realm-Tick, `NewTick`-Broadcast über `InterestSet`, `RoomRegistry` mit
      einer Instanz pro Raum-Typ (Nexus-Sharding vorbereitet). Client auf
      `localhost:PORT` spielt.
- [ ] **V2 – Kampf:** `PlayerShoot`/`EnemyShoot`, Treffer-Validierung,
      `Damage`, `Notification`, Monster als Entities, XP/Drops.
- [ ] **V3 – Inhalt:** Map-Import aus Client-Daten (Ground/Object-XML), Reiche
      mit mehreren Räumen, Portale, `Reconnect`.
- [ ] **V4 – Persistenz:** Account-Token-Kopplung mit `Server` (HTTP),
      Charakter-Speicherung über den `PersistenceActor` (nie im Realm-Tick),
      Vault.
- [ ] **Skalierung (bei Bedarf):** Mehrere **Nexus-Shards** über die
      `RoomRegistry` (Last-verteilt), mehrere Realms pro Prozess,
      `InterestSet`-Parameter justieren; ggf. mehrere Gameserver-Prozesse
      hinter einem Load-Balancer.

---

## 13. Verifikation

- **Build:** `mvn -pl Gameserver -am install`
- **Starten:** `java -cp Gameserver/target/classes:Actor/target/classes:<netty-jars> dev.localsoul.aero.game.Main <port>`
- **Client:** Flash-Client (27.7.X2, `LocalhostServerModel`) auf
  `localhost:<port>`; Erscheinen + Bewegung sichtbar.
- **Automatisiert (V1):**
  - Unit: `Rc4CipherTest` (bekannte Klartext→Chiffre-Vektoren),
    `PacketDecoderTest`/`PacketEncoderTest` (Roundtrip inkl. RC4),
    `MessageTypeTest` (ID↔Klasse eindeutig).
  - `ConnectionLimiterTest` (eigener Unit-Test): Zähler kehrt nach **jedem**
    Exit-Pfad auf 0 zurück — `channelInactive`, `exceptionCaught`, Flood-/
    Idle-Kick, abgelehnte Verbindung. Kein Zähler-Leak (sonst werden IPs
    fälschlich gesperrt).
  - Security: `maxFrameLength`-Überschreitung → Verbindung wird geschlossen;
    Idle-Timeout → Close; FloodGuard → Kick; pro-IP-Limit → Ablehnung.
  - Backpressure: Client mit vollem/`!writable`-Kanal wird gekickt, andere
    Spieler im Reich bekommen weiterhin Ticks. **Kick-Pfad-Test:** prüft die
    Reihenfolge `BackpressureKick → KickPlayer → (Entity entfernt) →
    KickConfirmed → channel.close()` — die Entity-Map wird genau einmal (im
    Realm) mutiert, kein Schreiben an einen toten Channel.
  - **Join-Pfad-Test:** `Hello` → `MapInfoReady` (MapInfo) → Client `Load` →
    `JoinConfirmed(objectId, update, createSuccess)` — Reihenfolge
    `MapInfo → Update → CreateSuccess` garantiert, `objectId` kommt aus dem
    Realm und wird der Session über den Ack übergeben (keine zweite Stelle
    vergibt IDs).
  - Integration: Fake-Client über eine `Socket`, die das Frame-Format spricht;
    prüft `MapInfo → CreateSuccess → NewTick`-Reihenfolge und Bewegung;
    Sichtbarkeit via `InterestSet` (zwei Spieler außerhalb des Radius sehen
    sich nicht).
  - `RoomRegistryTest`: `gameId` → Instanz(en); zweiter Nexus-Shard kann ohne
    Struktur-Änderung dazukommen.
- **Konventionen:** deutsch kommentieren; `final`-Parameter (Server-Stil);
  unveränderliche `record`-Nachrichten; neue Race-Pfade → Stress-Test.

---

## 14. Offene Punkte (bei Implementierung zu klären)

- **Message-ID-Tabelle und Feldreihenfolgen der Datenklassen vollständig per
  Skript aus dem Client extrahieren, bevor der erste Codec-Test läuft** — kein
  manuelles Abtippen (Off-by-one-Risiko in `ObjectStatusData`).
- Netty-Version + exakte Artefakte festlegen (`netty-transport`, `-codec`,
  `-handler`; Epoll/NIO je nach Plattform).
- Tick-Rate final (20 Hz Default) und ob `Ping`/`Pong` in V1 mitlaufen.
- Batching-/Backpressure-Parameter (Outbound-Queue-Limit, Writability-Schwelle,
  Kick-Schwellen in Ticks) ausmessen.