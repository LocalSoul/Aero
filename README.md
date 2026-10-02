# Aero — RotMG-Private-Server (Client 27.7.X2)

**Aero** ist ein hoch-paralleler Private-Server für **Realm of the Mad God** — Version **27.7.X2** des Spiels. Der Server baut auf einer eigenen, **BEAM/OTP-nahen Actor-Runtime** für Java 25 auf: Jeder Spieler, jede Session, jeder Raum ist ein Actor mit eigener Mailbox und eigenem Virtual Thread. So wird hohe Nebenläufigkeit durch das **Actor-Modell** garantiert — ohne Locks, ohne geteilten veränderlichen Zustand, mit eingebauter Fehlertoleranz durch Supervision.

Das Monorepo besteht aus drei Modulen: der fertigen Actor-Runtime, einem HTTP-Server für die Login-/Account-/Charakter-Endpunkte des Clients und einem Echtzeit-Gameserver (V1-Demo mit Multiplayer), der das eigentliche Spiel über das byte-exakte 27.7.X2-Wire-Protokoll anbietet.

```
┌────────────────────────────────────────────────────────────────────────────┐
│  Modul Actor (fertig)   Modul Server (fertig)   Modul Gameserver (V1-Demo) │
│  dev.localsoul:Actor    dev.localsoul:Server    dev.localsoul:Gameserver   │
└────────────────────────────────────────────────────────────────────────────┘
```

---

## Inhalt

1. [Idee & Motivation](#1-idee--motivation)
2. [Architektur](#2-architektur)
3. [Die Actor-Runtime (`Actor/`)](#3-die-actor-runtime-actor)
4. [Der HTTP-Server (`Server/`)](#4-der-http-server-server)
5. [Der Gameserver (`Gameserver/`)](#5-der-gameserver-gameserver)
6. [Roadmap](#6-roadmap)
7. [Schnellstart](#7-schnellstart)
8. [Client-Integration](#8-client-integration)
9. [Tests & Qualität](#9-tests--qualität)
10. [Mitwirken](#10-mitwirken)
11. [Lizenz & Credits](#11-lizenz--credits)

---

## 1. Idee & Motivation

Ein RotMG-Server muss **sehr viele gleichzeitige Aktivitäten** verwalten: tausende Spieler, die sich durch Reiche bewegen, kämpfen, lagern und handeln — neben Login, Accounts und Charakterverwaltung. Klassisches nebenläufiges Programmieren (Locks, `synchronized`, geteilte Maps) skaliert hier nicht gut und ist fehleranfällig.

Das **Actor-Modell** löst das strukturell:

| Problem | Lösung durch Aero |
|---|---|
| Geteilter Zustand / Races | **Single-Writer:** Ein Actor besitzt exakt einen Virtual Thread und seinen Zustand. Es gibt nichts zu synchronisieren. |
| Nebeneinanderlaufende Aktivitäten | **1 Virtual Thread pro Actor**, geparkt statt blockiert — kostet nur wenige hundert Bytes Stack, keinen Kernel-Thread. |
| Fehlertoleranz | **Supervision (OTP):** Räume, Sessions und Dienste werden überwacht und bei Abstürzen automatisch neu gestartet. |
| Hot Path ohne Locking | Räume sind **Actors mit festem Tick** (`TickDriver`), nicht systemweiter Broadcast. |

Warum **RotMG 27.7.X2**? Es ist die Version, für die ein vollständig dekompilierter, lauffähiger Flash-Client existiert (siehe [Client-Integration](#8-client-integration)) — die ideale Referenz für die Server-Implementierung.

---

## 2. Architektur

Das Repository ist ein **Maven-Monorepo** (`dev.localsoul:Aero`) mit drei Modulen:

| Modul | Zweck | Status |
|---|---|---|
| [`Actor/`](Actor/) | Actor-Runtime: Kern, Supervision, Tick-, Session-Layer | **fertig**, 173 Tests |
| [`Server/`](Server/) | RotMG-HTTP-Endpunkte (Login, Charakterliste, Pakete) | **fertig** (statische Antworten) |
| [`Gameserver/`](Gameserver/) | Echtzeit-Spiel: Nexus, Bewegung, **Kampf** (Monster, XP, Loot) | **V2**, 46 Tests |

`Server` und `Gameserver` importieren die Runtime, nicht umgekehrt. Die Runtime hat **kein** Wissen über RotMG.

### Schichten der Actor-Runtime

```
┌──────────────────────────────────────────────────────────────────────┐
│ dev.localsoul.aero.actor.session          SPIEL-CODE / CLIENTS        │
│   Session · Subscription · Interest · InterestSet · Client            │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.tick             SPIEL-LOOP                  │
│   Tick · OverrunPolicy · TickDriver · TickActor                       │
│   TickContext · OutboundBuffer                                        │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor                  BEAM-KERN (öffentlich)      │
│   Actor · ActorRef · ActorContext · ActorSystem · Behavior            │
│   Receive · ExitReason · Mailbox · DefaultMailbox · CoalescingMailbox │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.supervision      OTP-KERN                    │
│   Supervisor · SupervisorSpec · ChildSpec · RestartStrategy           │
│   RestartType · UserSupervisor · …                                    │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.internal         INTERN (keine Stabilität)   │
│   ActorCell · LocalActorRef · MpscQueue · Wakeup · Envelope           │
│   MessageKind · Signals · Call · NameRegistry · CurrentActor          │
└──────────────────────────────────────────────────────────────────────┘
```

Die Detail-Referenz für alle Design-Entscheidungen der Runtime liegt in [`Actor/implement.md`](Actor/implement.md); die des Gameservers in [`Gameserver/gameserver_implement.md`](Gameserver/gameserver_implement.md).

---

## 3. Die Actor-Runtime (`Actor/`)

Die Runtime ist eine schlanke, **BEAM/OTP-nahe** Actor-Laufzeit für Java 25. Kernideen:

- **1 Virtual Thread pro Actor.** Ein geparkter Virtual Thread kostet ~wenige hundert Bytes und keinen OS-Thread; der Carrier-Thread wird nie blockiert.
- **Untypisierter, BEAM-naher Kern.** Nachrichten sind `Object`; `Receive` bildet `receive … clause … end` mit *first-match-wins* inklusive Guards ab.
- **Getrennter System-Kanal.** `Exit`, `Terminate` und `CallResult` sind Systemnachrichten und erreichen **nie** eine Benutzerklausel.
- **Bounded Mailboxes** (Default 1024, Policy `FAIL`) mit Overflow-Politiken — die Postfach-Tiefe ist das primäre Lag-Signal.
- **Handler blockieren nie.** `ctx.runBlocking(...)` lagert Rare-Pfade (Login, DB, HTTP) auf einen bounded Pool aus; das Ergebnis kommt als Systemnachricht zurück.
- **Supervision ist Standard.** Top-Level-Spawns werden vom impliziten `UserSupervisor` überwacht.
- **Spiel-Engine vorbereitet:** Räume = `TickActor` mit festem Tick, `CoalescingMailbox` (latest-wins für Input), `OutboundBuffer` (ein Paket pro Client pro Tick), `InterestSet` (AoI-Uniform-Grid), `TickDriver` mit Watchdog.

### Kleines Beispiel

```java
// Ein Echo-Actor: beantwortet Strings über `ask`.
public final class EchoActor extends Actor {

    public EchoActor() {
        super("echo");
    }

    @Override
    protected Behavior onStart(ActorContext ctx) {
        return Receive.of(
            Receive.Clause.on(String.class, (message, c) -> {
                c.reply("echo: " + message);
                return Behavior.NEXT;
            }),
            Receive.Clause.any((message, c) -> Behavior.UNHANDLED)
        );
    }
}

// … irgendwo im Main:
ActorSystem system = new ActorSystem("demo");
ActorRef echo = system.spawn(new EchoActor());

Object antwort = echo.ask("Hallo Welt", Duration.ofSeconds(5)).get();
System.out.println(antwort);   // "echo: Hallo Welt"

system.close();                // beendet alle Actors geordnet (Drain)
```

---

## 4. Der HTTP-Server (`Server/`)

**Status:** Das Modul liefert die HTTP-Seite des RotMG-Protokolls. `AeroHttpServer` (JDK-eigenes `com.sun.net.httpserver`, keine externe Abhängigkeit) läuft auf einem Virtual-Thread-Executor, loggt jede Anfrage vollständig über `System.out.println` (Methode, URI, Header, Body) und beantwortet die Client-Endpunkte byte-identisch mit statischen Classpath-Ressourcen.

**RotMG-HTTP-Protokoll** (am Client-Quelltext verifiziert):

- Client-Requests sind **POST** mit **form-urlencoded** Body (`URLVariables`), nicht JSON.
- Erfolg/Fehler: Antworten, die mit `<Error>` oder `<FatalError>` beginnen, wertet der Client als Fehler (z. B. `<Error>Account credentials not valid</Error>`); alles andere ist Erfolg.

| Route | Ressource | Content-Type |
|---|---|---|
| `POST /app/getLanguageStrings` | `languages/lang_en.json` — JSON-Array `[key,value,lang]` | `application/json` |
| `POST /app/init` | `app/init.xml` — `<AppSettings>`, vom Client per `XML(body)` geparst | `application/xml` |
| `POST /char/list` | `app/char_list.xml` — `<Chars>` mit `<Account>`, `<Char>`, `<News>` … | `application/xml` |
| `POST /package/getPackages` | `app/packages.xml` — `<Request><Packages>` | `application/xml` |
| `POST /app/globalNews` | `app/global_news.json` — JSON-Array | `application/json` |

**Quirks:**

- `/package/getPackages`: Die Wurzel darf **nicht** `<Packages>` heißen — der Client prüft `xml.Packages.length()`, `<Packages>` muss ein Kindelement sein (Wrapper-Wurzel verwenden).
- `/app/init` und `/char/list` benötigen zwingend Elemente wie `<News>`, `<Account><Stats>` usw., sonst wirft die AS3-XML-Parsung.

Entry-Point: `dev.localsoul.aero.server.Main` (Port als Argument, Default **8080**).

---

## 5. Der Gameserver (`Gameserver/`)

**Status:** Echtzeit-Gameserver (**V2: Kampf**) auf Basis der Actor-Runtime. Eine leere 50×50-Nexus-Welt mit Bewegung, **gegenseitiger Spieler-Sichtbarkeit** (Multiplayer) und **Kampf**: Ghost Mages (`0x664`) greifen an, Spieler schießen zurück, XP/Level, Loot-Bags und ein `/give`-Chatbefehl. Angebunden über eine Netty-Schicht mit byte-exaktem 27.7.X2-Wire-Protokoll.

### Kampf (V2, am Client verifiziert)

- **Spieler→Monster:** `PlayerShoot` → Schuss-Spur (Ringpuffer, bulletId modulo 256) + `ServerPlayerShoot` an Mitspieler; `EnemyHit` wird validiert (Reichweite, Verbrauchs-Markierung) und schadet dem Monster — die autoritative HP kommt über `NewTick` (kein `Damage` fürs Monster, der Client wendet den Treffer lokal an).
- **Monster→Spieler:** der Server simuliert Monster-Projektile selbst (`EnemyShoot` + `pos = start + age*speed/10000`), bei Treffer `Damage`; `PlayerHit` wird geparst, aber ignoriert. HP ≤ 0 → `Death`.
- **Tod/XP/Loot:** Monster-Kill → XP-`Notification` + Soulbound Loot Bag (`0x0503`, 60 s Lebensdauer, Nähe-Pickup nur für den Eigentümer) + Respawn nach 10 s mit Schonfrist.
- **Sichtbarkeit:** pro-Tick-`knownObjectIds`-Diff je Spieler — einzige Quelle für `Update.newObjs`/`drops` (Join, Kill, Respawn, Despawn).
- **`/give`:** Chatbefehl über `PlayerText` legt ein Item ins Inventar.

### Wire-Protokoll

- **Framing:** 4-Byte-Länge + 1-Byte-Typ im Klartext, Payload RC4-verschlüsselt.
- **Keys:** feste 27.7-Keys (kein Handshake) — Client→Server `311f80…`, Server→Client `72c558…`; zustandsbehaftete ARCFOUR-Implementierung (`Rc4Cipher`).
- **Paketsatz V2:** ein `Hello`/`Load`/`Create`/`Move`/`Escape`/`PlayerShoot`/`EnemyHit`/`PlayerHit`/`PlayerText`; aus `MapInfo`/`CreateSuccess`/`Update`/`NewTick`/`Ping`/`Failure`/`ServerPlayerShoot`/`EnemyShoot`/`Damage`/`Notification`/`Death`, plus Datenklassen (`WorldPosData`, `ObjectStatusData`, `StatData` inkl. String-Stat-Logik, `ObjectData`, `GroundTileData`, `MoveRecord`).
- `MessageType`-Enum mit der vollständigen ID-Tabelle aus `GameServerConnection.as`.

### Netty-Schicht (`net/`)

- Pipeline mit `maxFrameLength` 1 MiB (OOM-Schutz), `IdleStateHandler`, `PacketDecoder`/`PacketEncoder` (Framing + RC4), `GameChannelHandler`.
- **Security von Tag 1:** `ConnectionLimiter` (pro-IP, Release auf jedem Exit-Pfad), `FloodGuard` (Pakete/s → Kick).
- **Backpressure:** `NettyClient` schreibt ohne Flush (Flush pro Tick) und bricht bei Überlauf als Nachricht ab (`BackpressureKick` → `KickPlayer` → `KickConfirmed`). Es wird nie aus dem Netty-Thread `close()`d — Invariante: nur der Realm-Thread mutiert die Entity-Map.

### Actor-Mapping (`actor/`)

- **`RealmActor`** (`TickActor`, 20 Hz): Entities als Plain Objects (`IntObjectHashMap<Player>`/`<Monster>`), reine Objekt-Iteration im Tick, kein Message-Passing pro Entity. Sichtbarkeit über `knownObjectIds`-Diff + `InterestSet`.
- **Join als zweiphasiger Ack-Flow:** `PlayerHello`→`MapInfoReady`, `PlayerCreate`/`PlayerJoin`→`JoinConfirmed(objectId)`. Wichtig: der Client setzt `map.player_` nur, wenn `playerId_` (aus `CreateSuccess`) schon gesetzt ist, wenn das Spieler-Objekt im `Update` kommt — deshalb `CreateSuccess` vor `Update`.
- **`SessionActor`:** reiner Transport, prüft syntaktisch, leitet semantische Nachrichten weiter.
- **`RoomRegistry`:** „Nexus" als Shard-Menge, keine Singleton.
- **Multiplayer:** Join-`Update` enthält alle sichtbaren Spieler/Monster; Spawn/Despawn laufen über den Sichtbarkeits-Diff; `PlayerLeave` entfernt über den Diff.

### World (`world/`)

`Map` (leere Welt, Bounds-Clamping), `Entity`, `Player` (Bewegungs-Validierung `speed*dt`, Inventar, XP/Level, HP-Regeneration, Schuss-Spur), `Monster` (Ghost Mage, AI, Respawn), `Projectile`, `LootBag`.

Entry-Point: `dev.localsoul.aero.game.Main` (Port als Argument, Default **2050**).

---

## 6. Roadmap

- [x] Actor-Runtime: Kern, Supervision, Tick-, Session-Layer — **173 Tests grün**
- [x] Actor-Runtime: Mailboxen (MPSC, Coalescing, Linked), `ask`/`reply`, Links/Monitore
- [x] Actor-Runtime: `TickDriver` mit Overrun-Politiken und Watchdog
- [x] Server: HTTP-Endpunkte des Login-Orbits (statische Antworten, byte-identisch)
- [x] Gameserver V1: Wire-Protokoll 27.7.X2 (RC4), Netty, Nexus mit Bewegung & Multiplayer
- [x] Gameserver V2: **Kampf** — `PlayerShoot`/`EnemyHit` (validiert), Monster-Projektilsimulation, `Damage`/`Death`, XP/Level, Loot-Bags, Respawn, `/give` — **46 Tests grün**
- [ ] Server: echte Account-/Charakter-Datenhaltung (Login statt statischer Antworten)
- [ ] Gameserver: Login-Handshake-Anbindung, persistente Accounts/Charaktere
- [ ] Gameserver: Realm-/Welt-Simulation mit weiteren Räumen, Portalen, Quests
- [ ] Client-Anbindung End-to-End (Login → Charakterauswahl → Reich)

---

## 7. Schnellstart

**Voraussetzungen:** JDK 25 und Maven 3.9+.

```bash
# Alle drei Module bauen und die Testsuite ausführen:
mvn verify
```

- Baut `Actor`, `Server` **und** `Gameserver`.
- Führt 173 Tests im Actor- und 46 Tests im Gameserver-Modul aus (Stress-Tests für MPSC-Queue, Coalescing, Park-Protokoll, Supervisor, Tick-Pfad, RC4/Codec, Kampf-Flow, Sichtbarkeits-Diff).
- Der Produktionscode des Actor-Moduls hat **keine Laufzeit-Abhängigkeiten** — nur das JDK 25 (Test-Abhängigkeiten: JUnit 6, AssertJ). `Server` ebenso; `Gameserver` nutzt Netty (codec/handler) und Lombok.

### HTTP-Server (Login-Orbit)

```bash
mvn -pl Server -am install
java -cp Server/target/classes:Actor/target/classes dev.localsoul.aero.server.Main 8080
# Anfragen loggt der Server via System.out.println (Methode, URI, Header, Body)
```

### Gameserver (Echtzeit)

```bash
mvn -pl Gameserver -am install
mvn -pl Gameserver dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
java -cp "Gameserver/target/classes:Actor/target/classes:$(cat /tmp/cp.txt)" dev.localsoul.aero.game.Main 2050
```

---

## 8. Client-Integration

Der Server spricht mit dem dekompilierten Flash-Client von Realm of the Mad God **Version 27.7.X2**:

- **[`LocalSoul/RotMG_Client_27.7.X2`](https://github.com/LocalSoul/RotMG_Client_27.7.X2)** — AS3-Client, dekompiliert mit Action Script Viewer, in lauffähigen Quelltext konvertiert.
- Fork von [`kaos00723/RotMG_Client_27.7.X2`](https://github.com/kaos00723/RotMG_Client_27.7.X2).
- Entwicklungswerkzeuge: IntelliJ IDEA (oder ein vergleichbares Flash-IDE), Flex SDK 4.9.1, Air SDK 15.

Ziel: Der Client verbindet sich direkt mit dem Aero-Server (Login → Charakterauswahl → Reich), ohne Modifikation des Protokolls.

---

## 9. Tests & Qualität

Grundsatz: **jede Race Condition braucht einen Stress-Test.** Die Korrektheit der lock-freien MPSC-Queue, der Delta-Queue und des Park-Protokolls ist nicht durch Lesen bewiesen, sondern durch Last.

### Actor (`Actor/src/test/java`)

- `DefaultMailboxTest` / `MpscQueueTest` / `LinkedMailboxTest` / `CoalescingMailboxTest` — FIFO, kein Verlust, Overflow-Politiken, exaktes `size()`/`isEmpty()`, `take()`-Vertrag (`null` nur bei `close` + leer), BLOCK-Self-Send-Schutz.
- `ActorSystemTest` — Lebenszyklus, `ask`/`reply`, Timeout, Links/Monitore, `runBlocking`, Shutdown.
- `SupervisorTest` — Restart-Strategien, Intensität/Eskalation, Kind-Dynamik.
- `TickDriverTest` / `TickActorTest` — Tick-Rate, Overrun-Politiken, Watchdog, `drainInbox`-Budget, Outbound-Batching.
- `InterestTest` / `SessionTest` / `OutboundBufferTest` — AoI-Grid, Session-Pfad, Scratch-Recycling.

### Gameserver (`Gameserver/src/test/java`)

- `Rc4CipherTest` — RC4-Vektoren, laufender Zustand.
- `CodecTest` / `V2CodecTest` — Framing + RC4-Roundtrip, alle V2-Pakete gegen die Client-Feldreihenfolgen.
- `ConnectionLimiterTest` — Zähler-Leak auf allen Exit-Pfaden.
- `RealmActorTest` / `MultiplayerTest` — Join/Move-Flow, zwei Spieler sehen sich.
- `CombatFlowTest` / `DeathTest` / `DamageFormulaTest` / `ProjectileSimulationTest` — Kill-Flow, Spieler-Tod, Rüstungsformel, Projektil-Bewegung.
- `VisibilityDiffTest` — `knownObjectIds`-Diff (Join, Kill-Drop, Respawn).
- `LootBagTest` — Nähe-Pickup (nur Eigentümer), volles Inventar, Lebensdauer.
- `KillWithoutOwnerTest` / `RespawnGraceTest` / `GiveCommandTest` / `PlayerTest` — Edge-Cases, Regen, Schuss-Spur (bulletId-Wraparound), `/give`.
- `RealmPerfTest` — 500 Monster: Leistungsgrenze des O(Spieler × Monster)-Scans.

Zusätzlich ist das Wire-Protokoll **per echtem Socket** verifiziert: Hello→MapInfo, Create→CreateSuccess→Update (2500 Tiles), Move→NewTick, und zwei parallele Clients mit gegenseitiger Sichtbarkeit.

Asynchrone Assertions nutzen einen `awaitUntil(...)`-Helfer statt fester `Thread.sleep`-Margen; Zeitmargen sind großzügig.

---

## 10. Mitwirken

Beiträge sind willkommen. Damit es reibungslos läuft:

1. **Referenz lesen:** [`Actor/implement.md`](Actor/implement.md) beschreibt Design und bewusste Abweichungen von BEAM/OTP; [`Gameserver/gameserver_implement.md`](Gameserver/gameserver_implement.md) das Wire-Protokoll und die Architektur des Gameservers.
2. **Konventionen beachten:** Nachrichten als `record` (unveränderlich); Handler blockieren nie; Mailbox-Kapazität bewusst wählen; neue Race-Pfade bekommen einen Stress-Test.
3. **Bauen & testen:** `mvn verify` muss grün bleiben.
4. **Issue/PR öffnen** — am besten mit reproduzierbarem Fall (Stress-Test), wenn es um Nebenläufigkeit geht.

---

## 11. Lizenz & Credits

- **RotMG-Client-Quellen (27.7.X2):** dekompiliert mit Action Script Viewer und in vollständig lauffähigen Quelltext konvertiert. Credits an **kaos00723 (Kaos)** und **cp-nilly (nilly)** für die ursprüngliche Aufbereitung; bereitgestellt über den Fork [`LocalSoul/RotMG_Client_27.7.X2`](https://github.com/LocalSoul/RotMG_Client_27.7.X2).
- **Aero** ist ein unabhängiges, nicht-offizielles Fan-Projekt. Realm of the Mad God und alle zugehörigen Marken gehören ihren jeweiligen Rechteinhabern.