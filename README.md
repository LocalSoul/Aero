# Aero — RotMG-Private-Server (Client 27.7.X2)

**Aero** ist ein hoch-paralleler Private-Server für **Realm of the Mad God** — Version **27.7.X2** des Spiels. Der Server baut auf einer eigenen, **BEAM/OTP-nahen Actor-Runtime** für Java 25 auf: Jeder Spieler, jede Session, jeder Raum ist ein Actor mit eigener Mailbox und eigenem Virtual Thread. So wird hohe Nebenläufigkeit durch das **Actor-Modell** garantiert — ohne Locks, ohne geteilten veränderlichen Zustand, mit eingebauter Fehlertoleranz durch Supervision.

```
┌────────────────────────────────────────────────────────────────┐
│  Modul Actor (fertig)            Modul Server (in Arbeit)       │
│  dev.localsoul:Actor             dev.localsoul:Server           │
└────────────────────────────────────────────────────────────────┘
```

---

## Inhalt

1. [Idee & Motivation](#1-idee--motivation)
2. [Architektur](#2-architektur)
3. [Die Actor-Runtime (`Actor/`)](#3-die-actor-runtime-actor)
4. [Der Server (`Server/`) — Status & Konzept](#4-der-server-server--status--konzept)
5. [Roadmap](#5-roadmap)
6. [Schnellstart](#6-schnellstart)
7. [Client-Integration](#7-client-integration)
8. [Tests & Qualität](#8-tests--qualität)
9. [Mitwirken](#9-mitwirken)
10. [Lizenz & Credits](#10-lizenz--credits)

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

Warum **RotMG 27.7.X2**? Es ist die Version, für die ein vollständig dekompilierter, lauffähiger Flash-Client existiert (siehe [Client-Integration](#7-client-integration)) — die ideale Referenz für die Server-Implementierung.

---

## 2. Architektur

Das Repository ist ein **Maven-Monorepo** (`dev.localsoul:Aero`) mit zwei Modulen:

| Modul | Zweck | Status |
|---|---|---|
| [`Actor/`](Actor/) | Actor-Runtime: Kern, Supervision, Tick-, Session-Layer | **fertig**, 173 Tests |
| [`Server/`](Server/) | RotMG-Server: Login, Reiche, Welt-Simulation | **in Arbeit** (Skeleton) |

Der Server importiert die Runtime, nicht umgekehrt. Die Runtime hat **kein** Wissen über RotMG.

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

Die Detail-Referenz für alle Design-Entscheidungen liegt in [`Actor/implement.md`](Actor/implement.md).

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

## 4. Der Server (`Server/`) — Status & Konzept

**Status:** Das Modul ist ein Skeleton — `AeroServer` und `Main` sind Platzhalter, die eigentliche Server-Logik wird auf der Actor-Runtime aufgebaut.

**Geplantes Konzept** (hoch-abstrakt, wird beim Implementieren konkretisiert):

- **Netzwerk-/Protokollschicht** verbindet sich mit dem Flash-Client (Packet-Serialisierung, Verschlüsselung — Details der 27.7-X2-Epoche folgen bei der Umsetzung).
- **Login & Account** als überwachte Actors (eigener `Actor`, `runBlocking` für DB-Zugriffe).
- **Charakterauswahl & Vault** als Actor-basierte Dienste.
- **Reich/Welt** als `TickActor`: feste Tick-Rate, Spieler-Sessions als `Session`-Actors, Sichtbarkeit über `InterestSet`, Outbound-Batching über `OutboundBuffer`.
- **Supervision-Baum:** Ein Spieler-Crash startet die Session neu, ohne das Reich zu stören; ein Reich-Crash wird von seinem Supervisor neu gestartet.

---

## 5. Roadmap

- [x] Actor-Runtime: Kern, Supervision, Tick-, Session-Layer — **173 Tests grün**
- [x] Actor-Runtime: Mailboxen (MPSC, Coalescing, Linked), `ask`/`reply`, Links/Monitore
- [x] Actor-Runtime: `TickDriver` mit Overrun-Politiken und Watchdog
- [ ] Server: Netzwerk-/Protokollschicht (Packets, Verschlüsselung)
- [ ] Server: Login, Account, Charakterauswahl
- [ ] Server: Reich-/Welt-Simulation auf `TickActor`-Basis
- [ ] Server: Monster, Begegnungen, Quests
- [ ] Client-Anbindung End-to-End (Login → Charakterauswahl → Reich)

---

## 6. Schnellstart

**Voraussetzungen:** JDK 25 und Maven 3.9+.

```bash
# Alle Module bauen und die Testsuite ausführen:
mvn verify
```

- Baut `Actor` **und** `Server` (der Server hängt als Maven-Modul an `Actor`).
- Führt 173 Tests im Actor-Modul aus (Stress-Tests für MPSC-Queue, Coalescing, Park-Protokoll, Supervisor, Tick-Pfad).
- Der Produktionscode des Actor-Moduls hat **keine Laufzeit-Abhängigkeiten** — nur das JDK 25 (Test-Abhängigkeiten: JUnit 6, AssertJ).

---

## 7. Client-Integration

Der Server spricht mit dem dekompilierten Flash-Client von Realm of the Mad God **Version 27.7.X2**:

- **[`LocalSoul/RotMG_Client_27.7.X2`](https://github.com/LocalSoul/RotMG_Client_27.7.X2)** — AS3-Client, dekompiliert mit Action Script Viewer, in lauffähigen Quelltext konvertiert.
- Fork von [`kaos00723/RotMG_Client_27.7.X2`](https://github.com/kaos00723/RotMG_Client_27.7.X2).
- Entwicklungswerkzeuge: IntelliJ IDEA (oder ein vergleichbares Flash-IDE), Flex SDK 4.9.1, Air SDK 15.

Ziel: Der Client verbindet sich direkt mit dem Aero-Server (Login → Charakterauswahl → Reich), ohne Modifikation des Protokolls.

---

## 8. Tests & Qualität

Grundsatz: **jede Race Condition braucht einen Stress-Test.** Die Korrektheit der lock-freien MPSC-Queue, der Delta-Queue und des Park-Protokolls ist nicht durch Lesen bewiesen, sondern durch Last.

Abgedeckt durch die Suite (`Actor/src/test/java`):

- `DefaultMailboxTest` / `MpscQueueTest` / `LinkedMailboxTest` / `CoalescingMailboxTest` — FIFO, kein Verlust, Overflow-Politiken, exaktes `size()`/`isEmpty()`, `take()`-Vertrag (`null` nur bei `close` + leer), BLOCK-Self-Send-Schutz.
- `ActorSystemTest` — Lebenszyklus, `ask`/`reply`, Timeout, Links/Monitore, `runBlocking`, Shutdown.
- `SupervisorTest` — Restart-Strategien, Intensität/Eskalation, Kind-Dynamik.
- `TickDriverTest` / `TickActorTest` — Tick-Rate, Overrun-Politiken, Watchdog, `drainInbox`-Budget, Outbound-Batching.
- `InterestTest` / `SessionTest` / `OutboundBufferTest` — AoI-Grid, Session-Pfad, Scratch-Recycling.

Asynchrone Assertions nutzen einen `awaitUntil(...)`-Helfer statt fester `Thread.sleep`-Margen; Zeitmargen sind großzügig.

---

## 9. Mitwirken

Beiträge sind willkommen. Damit es reibungslos läuft:

1. **Referenz lesen:** [`Actor/implement.md`](Actor/implement.md) beschreibt Design und bewusste Abweichungen von BEAM/OTP.
2. **Konventionen beachten:** Nachrichten als `record` (unveränderlich); Handler blockieren nie; Mailbox-Kapazität bewusst wählen; neue Race-Pfade bekommen einen Stress-Test.
3. **Bauen & testen:** `mvn verify` muss grün bleiben.
4. **Issue/PR öffnen** — am besten mit reproduzierbarem Fall (Stress-Test), wenn es um Nebenläufigkeit geht.

---

## 10. Lizenz & Credits

- **RotMG-Client-Quellen (27.7.X2):** dekompiliert mit Action Script Viewer und in vollständig lauffähigen Quelltext konvertiert. Credits an **kaos00723 (Kaos)** und **cp-nilly (nilly)** für die ursprüngliche Aufbereitung; bereitgestellt über den Fork [`LocalSoul/RotMG_Client_27.7.X2`](https://github.com/LocalSoul/RotMG_Client_27.7.X2).
- **Aero** ist ein unabhängiges, nicht-offizielles Fan-Projekt. Realm of the Mad God und alle zugehörigen Marken gehören ihren jeweiligen Rechteinhabern.