# Aero::Actor — Implementierungsplan und Referenz

> BEAM/OTP-nahe Actor-Laufzeit für Java 25 auf Virtual Threads,
> ausgelegt auf **Game-Server** (authoritative Simulation, feste Tick-Rate,
> Raum-getriebenes Tick-Modell, hierarchische Supervision).
>
> Modul: `dev.localsoul:Aero::Actor` · Paketwurzel: `dev.localsoul.aero.actor`
> Produktionscode: **0 Abhängigkeiten, nur JDK 25.**

---

## Inhalt

1. [Zweck und Leitentscheidungen](#1-zweck-und-leitentscheidungen)
2. [Architektur in Schichten](#2-architektur-in-schichten)
3. [Vollständiges BEAM/OTP-Mapping](#3-vollständiges-beamotp-mapping)
4. [Threading-Modell](#4-threading-modell)
5. [Paketstruktur und Datei-Inventar](#5-paketstruktur-und-datei-inventar)
6. [Kern-Detailentwurf](#6-kern-detailentwurf)
   6.1 [Actor-Lebenszyklus](#61-actor-lebenszyklus)
   6.2 [Receive-Loop](#62-receive-loop)
   6.3 [Nachrichtenkanäle und Envelope](#63-nachrichtenkanäle-und-envelope)
   6.4 [Mailbox: MPSC-Array-Queue](#64-mailbox-mpsc-array-queue)
   6.5 [Park/Wake-Protokoll](#65-parkwake-protokoll)
   6.6 [CoalescingMailbox als Delta-Queue](#66-coalescingmailbox-als-delta-queue)
   6.7 [ask/reply und Call-Semantik](#67-askreply-und-call-semantik)
   6.8 [Links, Monitore, Exit-Propagation](#68-links-monitore-exit-propagation)
   6.9 [runBlocking-Offload](#69-runblocking-offload)
   6.10 [Supervisor-Zustandsautomat](#610-supervisor-zustandsautomat)
   6.11 [TickDriver](#611-tickdriver)
   6.12 [TickActor](#612-tickactor)
   6.13 [OutboundBuffer](#613-outboundbuffer)
   6.14 [InterestSet](#614-interestset)
7. [Öffentliche API-Referenz](#7-öffentliche-api-referenz)
8. [Spiel-Integration: Leitbeispiel](#8-spiel-integration-leitbeispiel)
9. [Build und Konfiguration](#9-build-und-konfiguration)
10. [Teststrategie](#10-teststrategie)
11. [Beispiele und Benchmark](#11-beispiele-und-benchmark)
12. [Performance-Leitregeln und Anti-Patterns](#12-performance-leitregeln-und-anti-patterns)
13. [Bewusste Abweichungen von BEAM/OTP](#13-bewusste-abweichungen-von-beamotp)
14. [Build-Blocker: Parent-POM](#14-build-blocker-parent-pom)
15. [Umsetzungsreihenfolge](#15-umsetzungsreihenfolge)
16. [Präzisierungen aus dem Review](#16-präzisierungen-aus-dem-review)

---

## 1. Zweck und Leitentscheidungen

### 1.1 Zweck

Eine schlanke Actor-Laufzeit, die zwei Eigenschaften gleichzeitig liefert, die
Ottots Actor-Modell und reines Java Virtual-Thread-Programmieren jeweils nur
halb liefern:

| | BEAM/OTP | Virtual-Thread-Java | **Aero::Actor** |
|---|---|---|---|
| Fehlertoleranz | eingebaut (Supervision) | manuell | Supervisor-Baum, OTP-Semantik |
| Millionen nebenläufiger Einheiten | Scheduler | eine VThread pro Task | 1 VThread pro Actor, geparkt |
| Isolation / Single-Writer | Prozess | keine | Actor = 1 Thread, kein Locking |
| Ökosystem | BEAM | JVM | JVM + vorhandene Libs (Netty, Ktor, JPA) |

### 1.2 Die sieben Leitentscheidungen

**L1 — Ein Virtual Thread pro Actor.**
Kein BEAM-Scheduler. Jeder Actor besitzt exakt einen Virtual Thread, der auf
seiner eigenen Mailbox parkt. Ein geparkter Virtual Thread kostet wenige hundert
Bytes Stack-Speicher und keinen Kernel-Thread. Der Carrier-Thread (Plattform-
Thread) wird nie blockiert, solange Actor-Code nicht selbst blockiert.
*Begründung:* Für < 1 000 Spieler pro Raum ist die Skalierung völlig ausreichend;
ein Scheduler hätte Komplexität ohne messbaren Gewinn gebracht.

**L2 — Untypisierter, BEAM-naher Kern.**
Nachrichten sind `Object`. `Receive` bildet `receive … clause … end` mit
"first match wins" ab, inklusive Guards. Es gibt keine `Actor<M>`-Generics.
*Begründung:* Das Actor-Modell lebt von der Trennung „Nachricht senden" /
„Nachricht interpretieren". Generics würden den Vorteil (Supervisor, `become`,
dynamische Klauseln) mit Compile-Zeit-Kopplung erkaufen.

**L3 — Getrennter System-Nachrichten-Kanal.**
`MessageKind.USER` und `MessageKind.SYSTEM`. `Exit`, `Down`, `Terminate` und
`CallResult` sind Systemnachrichten, die **nie** von Benutzerklauseln getroffen
werden. *Begründung:* Sonst könnte eine Benutzerklausel `case Object o ->
ctx.stop()` unbeabsichtigt Supervisions-Signale abfangen.

**L4 — Räume sind Actors, kein Broadcast.**
Ein Raum (Match, Lobbyspace, Zone) = 1 Actor = 1 Thread. Der Raum simuliert
seine Spieler in **einem** Handler pro Tick. Kein systemweiter Tick-Broadcast.
*Begründung:* 60 Hz × 100 000 Spieler wären 6 M Tick-Nachrichten/s. Bei
1 Raum pro 100 Spielern sind es 6 000 Nachrichten/s — Faktor 1000 weniger.
Zusätzlich: Single-Writer auf dem kompletten Raumzustand (keine Locks, keine
`ConcurrentHashMap`, keine CAS) und beste Cache-Lokalität.

**L5 — Bounded Mailboxes mit Overflow-Policy.**
Default 1024 Plätze, Policy `FAIL`. Die Tiefe ist über `ActorRef.mailboxSize()`
introspektierbar und ist das primäre Lag-Signal.
*Begründung:* Eine unbounded Mailbox ist bei einem hängenden Client ein
Memory-Leak mit Latenz-Amplitude. Wer mehr Kapazität braucht, deklariert es.

**L6 — Handler dürfen nie blockieren.**
`ctx.runBlocking(…)` lagert Rare-Pfade (Login, DB, HTTP) auf einen bounded Pool
aus; das Ergebnis kommt als Systemnachricht in die eigene Mailbox zurück.
*Begründung:* Ein blockierender Handler friert den gesamten Raum-Tick ein, weil
alle Spieler an einem Thread hängen.

**L7 — Supervision ist die Standard-Lebenszyklus-Semantik.**
Der implizite `UserSupervisor` überwacht jeden Top-Level-`spawn`.
*Begründung:* Ein Spielserver ohne Restart-Policy fällt beim ersten
`NullPointerException` in einer Game-Logik-Routine aus.

### 1.3 Nicht-Ziele (bewusst nicht gebaut)

Hot-Code-Reloading, Remote-/Cluster-`ActorRef`s, `gen_event`, `gen_statem`,
`pg`/`at`-Registry, persistenter Zustand, Socket-Transport, Metrik-Framework.
Siehe [Kapitel 13](#13-bewusste-abweichungen-von-beamotp).

---

## 2. Architektur in Schichten

```
┌──────────────────────────────────────────────────────────────────────┐
│ dev.localsoul.aero.actor.session          SPIEL-CODE                │
│   Session · Subscription · Interest · InterestSet · Client          │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.tick             SPIEL-LOOP                │
│   Tick · OverrunPolicy · TickDriver · TickActor                     │
│   TickContext · OutboundBuffer                                       │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor                  BEAM-KERN (öffentlich)    │
│   Actor · ActorRef · ActorContext · ActorSystem · Behavior          │
│   Receive · ExitReason · Mailbox · DefaultMailbox                   │
│   CoalescingMailbox · LinkedMailbox · MailboxOverflow · ActorPath   │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.supervision      OTP-KERN                 │
│   Supervisor · SupervisorSpec · ChildSpec · RestartStrategy        │
│   RestartType · UserSupervisor · SupervisorIntensitiesExceeded…     │
├──────────────────────────────────────────────────────────────────────┤
│ dev.localsoul.aero.actor.internal         INTERN (keine Stabilität)  │
│   ActorCell · LocalActorRef · MpscQueue · Wakeup · Envelope         │
│   MessageKind · Signals · Call · NameRegistry · CurrentActor        │
└──────────────────────────────────────────────────────────────────────┘
```

**Schichtregeln**

1. `internal` importiert `Actor`, nicht umgekehrt. Öffentliche Typen importieren
   `internal`, aber exponieren sie in eigenen Signaturen nicht (außer
   `Mailbox`, das bewusst `Envelope` im `offer` verwendet — siehe [6.4](#64-mailbox-mpsc-array-queue)).
2. `tick` und `session` kennen den BEAM-Kern, nicht umgekehrt. Der Kern hat
   **kein** Wissen über Ticks, Sessions oder Interessen.
3. `supervision` ist eine Spezialisierung von `Actor` und benutzt nur
   `ActorContext`/`ActorRef`.
4. Keine Schicht importiert eine über ihr liegende Schicht.

---

## 3. Vollständiges BEAM/OTP-Mapping

| BEAM / OTP | Aero::Actor | Datei | Bemerkung |
|---|---|---|---|
| `process()` / `self()` | `ActorSystem.spawn(…)`, `ctx.self()` | `ActorSystem`, `ActorContext` | |
| `Pid` | `ActorRef` (Interface) | `ActorRef` | Later `LocalActorRef`/`RemoteActorRef` |
| `{Name, Node}` | `ActorRef.name()`, `ActorSystem.whereIs(name)` | `NameRegistry` | |
| private Mailbox | `Mailbox` (MPSC) | `Mailbox`, `DefaultMailbox` | |
| `send/2`, `!` | `ref.tell(msg)` | `ActorRef#tell` | Absender wird aus dem laufenden Actor automatisch gesetzt |
| `erlang:send_after/3` | `ctx.runBlocking(…)` + Timeout-Completion | `ActorContext#runBlocking` | |
| `spawn/1,2,3,4` | `system.spawn`, `ctx.spawn` | | ohne Link |
| `spawn_link/1,2,3` | `ctx.spawn(…)` (Default) | `ActorContext#spawn` | Link, **non-trapping** |
| `spawn_monitor` | `ctx.monitor(ref)` | | `Future<ExitReason>` |
| `link/1`, `unlink/1` | `ctx.link/unlink` | | non-trapping |
| `trap_exit` | Supervisor-Link (trapping) | `ActorCell#link(…, trap=true)` | |
| `exit(Pid, Reason)` | Systemnachricht `Signals.Exit` | `internal/Signals` | |
| `normal` propagiert nicht | `ExitReason.Normal` wird nicht an non-trapping Links gesendet | [6.8](#68-links-monitore-exit-propagation) | |
| `{'DOWN',Ref,process,Reason,Info}` | `ctx.monitor(ref)` → `CompletableFuture<ExitReason>` | | |
| `process_info(Pid)` | `ActorRef.isAlive()`, `mailboxSize()` | | |
| `exit(Pid, kill)` | `ActorCell.kill()` | | sofort, ohne Drain |
| `receive … end` | `Receive.of(Case…)` | `Receive` | first match wins + Guards |
| `noreply` | `Behavior.NEXT` | `Behavior` | |
| `{reply, Reply, State}` | `ctx.reply(v)` | | liefert `Behavior.NEXT` |
| `handle_info/2` Fallback | `Actor.onUnhandled(…)` | | **Abweichung**, s. [13](#13-bewusste-abweichungen-von-beamotp) |
| `become/1,2` | `ctx.become(behavior)` | | entspricht `gen_statem`-Zustandswechsel |
| `sys:get_state` | `ctx.currentBehavior()` | | |
| `sys:put_state` | `ctx.become(…)` | | |
| `make_ref`, `demonitor` | `ctx.monitor` + `Future.cancel` | | |
| `timer:sleep` in Handler | verboten → `runBlocking` | | s. [12](#12-performance-leitregeln-und-anti-patterns) |
| `gen_server:call` | `ref.ask(msg, timeout)` + `ctx.reply` | | s. [6.7](#67-askreply-und-call-semantik) |
| `gen_server:cast` | `ref.tell(msg)` | | |
| `gen_server:stop` | `ctx.stop()` (Drain) | | |
| `supervisor` | `Supervisor` | | |
| `one_for_one` | `RestartStrategy.ONE_FOR_ONE` | | |
| `one_for_all` | `RestartStrategy.ONE_FOR_ALL` | | |
| `rest_for_one` | `RestartStrategy.REST_FOR_ONE` | | |
| `restart` = `permanent` | `RestartType.PERMANENT` | | |
| `transient` | `RestartType.TRANSIENT` | | |
| `temporary` | `RestartType.TEMPORARY` | | |
| Intensity `{N,T}` | `SupervisorSpec.maxRestarts` / `within` | | Eskalation an Parent |
| `start_type` = `worker` | `ChildSpec.worker(…)` | | |
| `start_type` = `supervisor` | `ChildSpec.supervisor(…)` | | |
| `user`-Supervisor | `UserSupervisor` (implizit) | | alle Top-Level-Spawns |
| `shutdown`-Timeout | `SupervisorSpec.shutdownTimeout` | | |
| `application` Master | `ActorSystem` (implementiert `AutoCloseable`) | | |
| `timer:send_interval` | `TickDriver` | | aber raum-getrieben, s. L4 |
| `ets` | **nicht vorhanden** | | Actor-Zustand ist die Datenbank; s. [13](#13-bewusste-abweichungen-von-beamotp) |

---

## 4. Threading-Modell

### 4.1 Thread-Budget

| Thread | Anzahl | Typ | Lebensdauer |
|---|---|---|---|
| Actor-Thread | 1 pro Actor | **Virtual Thread** | Lebensdauer des Actors |
| `TickDriver` | 1 pro `TickDriver` | Platform Thread (daemon) | Lebensdauer des Drivers |
| Scheduler (ask-Timeouts, `runBlocking`-Dispatch) | 1 pro `ActorSystem` | Platform Thread (daemon) | Lebensdauer des Systems |
| `runBlocking`-Pool | Default `2 × cores` (Pool-Thread, keine VT) | Platform Threads | Lebensdauer des Systems |
| Carrier (ForkJoinPool) | JVM-Default | Platform | JVM |

### 4.2 Warum ein Platform Thread für `TickDriver`?

`LockSupport.parkNanos` in einem **Virtual Thread** geht über den
`ForkJoinPool`-Scheduler und hat typischerweise 1–5 ms Jitter. Bei 60 Hz sind
16,67 ms pro Tick Budget — Jitter ist hier kein Detail, sondern Summierung von
Simulation und Netz-I/O. Ein dedizierter Platform Thread mit `parkNanos` hat
µs-Präzision und kostet einen Thread, der zu 99,9 % schläft.

### 4.3 Abbruch- und Interrupt-Semantik

Der Virtual Thread eines Actors wird **ausschließlich** beim Hard-Kill
(`kill()`) unterbrochen. Weder `ask`-Timeouts noch `Supervisor`-Intensität noch
`ActorSystem.shutdown()` unterbrechen Actor-Code.

```
Soft-Stop  : Terminate(reason) als Systemnachricht ans Mailbox-Ende
             → alle schon gequeue-Nachrichten werden noch verarbeitet (Drain)
             → Actor-Code läuft nie unterbrochen
Hard-Kill  : killed = true; mailbox.close(); thread.interrupt()
             → geparkter Actor wacht sofort auf
             → blockierender Handler (IO, Lock) sieht InterruptedException
             → laufender Handler wird an einer beliebigen Stelle abgerissen
```

Warum Drain statt Discard? BEAM verwirft die Mailbox, wenn ein Prozess stirbt.
Für einen Spielserver ist das kontraproduktiv: ein geordneter Shutdown soll
laufende Eingaben noch verarbeiten, damit Spieler sauber abgemeldet werden
können. Für den Hard-Kill gilt weiterhin Discard (sofort).

### 4.4 ThreadLocal für den Absender

`tell` muss den Absender kennen, ohne ihn als Parameter überall durchzuschleppen:

```java
// internal/CurrentActor.java
final class CurrentActor {
    private static final ThreadLocal<ActorRef> CURRENT = new ThreadLocal<>();
    static void set(ActorRef ref) { CURRENT.set(ref); }
    static ActorRef get() { return CURRENT.get(); }
    static void clear() { CURRENT.remove(); }
}
```

Gesetzt wird er im Actor-Thread vor `start()`, geleert im `finally` nach
`postStop()`. Weil **genau ein** Virtual Thread einen Actor ausführt, ist das
korrekt ohne Synchronisation.

> **Fußnote / Stolperfalle:** Ein `thenApply`-Callback einer `ask`-Future läuft
> auf dem Thread, der die Future vervollständigt. Bei `ask` aus einem Actor ist
> das der **Aufrufer-**Actor (siehe [6.7](#67-askreply-und-call-semantik)), also
> korrekt. Aus einem Nicht-Actor-Thread (z. B. `main`) heraus läuft der Callback
> auf dem Thread des **Antwortenden** — dort keine Actor-Zustände anfassen.

---

## 5. Paketstruktur und Datei-Inventar

Insgesamt **43 Dateien** in `src/main/java` plus **2** in `src/main/resources`-Nutzung
(keine) und **16** in `src/test/java`.

### 5.1 `dev.localsoul.aero.actor` — BEAM-Kern (18 Dateien)

| # | Datei | Art | Verantwortung |
|---|---|---|---|
| 1 | `Actor.java` | abstract | Basisklasse. `start(ctx)`, `receive(msg,ctx)`, `onUnhandled`, `postStop`. |
| 2 | `ActorRef.java` | interface | Handle: `tell`, `ask`, `isAlive`, `isTerminated`, `mailboxSize`, `name`, `path`. |
| 3 | `ActorContext.java` | interface | `self`, `sender`, `reply`, `forward`, `become`, `spawn`, `link`, `monitor`, `runBlocking`, `drainInbox`, `stop`. |
| 4 | `ActorSystem.java` | final | Wurzel-Scope, `AutoCloseable`, Executor, Scheduler, Registry, Root-Supervisor, `runBlocking`-Pool. |
| 5 | `Behavior.java` | interface | Funktionale Verhaltensbeschreibung + Sentinels `NEXT`, `UNHANDLED`, `HALT`. |
| 6 | `Receive.java` | final | Unveränderliche Klauselliste, first-match-wins. Enthält `Receive.Clause` und `Receive.Body`. |
| 7 | `ExitReason.java` | sealed | `Normal`, `Shutdown`, `Failure(Throwable)`, `Terminated` + `abnormal()`. |
| 8 | `ActorPath.java` | record | `aero://<system>/<name oder ~id>`. |
| 9 | `Mailbox.java` | interface | `offer`, `poll`, `take`, `size`, `close`, `capacity`. |
| 10 | `DefaultMailbox.java` | final | Bounded MPSC-Array-Queue, Overflow-Policies, Park-Protokoll. |
| 11 | `LinkedMailbox.java` | final | `ConcurrentLinkedQueue`-Variante, unbounded. Für Low-Traffic-Actoren. |
| 12 | `CoalescingMailbox.java` | final | Delta-Queue: „latest wins" pro Key, Position bleibt erhalten. |
| 13 | `MailboxOverflow.java` | enum | `FAIL`, `BLOCK`, `DROP_NEWEST`. |
| 14 | `Mailboxes.java` | final | Factory: `bounded(n, policy)`, `coalescing(n, keyFn)`, `unbounded()`. |
| 15 | `ActorTerminatedException.java` | | Angefragter Actor starb vor der Antwort. |
| 16 | `AskTimeoutException.java` | | `ask` lief in den Timeout. |
| 17 | `MailboxFullException.java` | | Policy `FAIL`, Mailbox voll. |
| 18 | `ActorNameTakenException.java` | | `badarg` — Name bereits registriert. |

### 5.2 `dev.localsoul.aero.actor.supervision` — OTP (7 Dateien)

| # | Datei | Art | Verantwortung |
|---|---|---|---|
| 19 | `Supervisor.java` | abstract | OTP-Logik + `onMessage`-Hook. `ChildSpec`-Liste, Restart, Intensity, Shutdown. |
| 20 | `SupervisorSpec.java` | final + Builder | Strategie, `maxRestarts`, `within`, `shutdownTimeout`. |
| 21 | `ChildSpec.java` | record | `name`, `starter`, `supervisor`, `restart`. Factories `worker`/`supervisor`. |
| 22 | `RestartStrategy.java` | enum | `ONE_FOR_ONE`, `ONE_FOR_ALL`, `REST_FOR_ONE`. |
| 23 | `RestartType.java` | enum | `PERMANENT`, `TRANSIENT`, `TEMPORARY`. |
| 24 | `SupervisorIntensitiesExceededException.java` | | Restart-Fenster überschritten. |
| 25 | `UserSupervisor.java` | final | Impliziter Root-Supervisor des `ActorSystem`. |

### 5.3 `dev.localsoul.aero.actor.tick` — Spiel-Loop (6 Dateien)

| # | Datei | Art | Verantwortung |
|---|---|---|---|
| 26 | `Tick.java` | record | `number`, `elapsed`, `first`. |
| 27 | `OverrunPolicy.java` | enum | `CLAMP` (Default), `STEP`, `DROP`. |
| 28 | `TickDriver.java` | final | Fixed-Timestep-Akkumulator, Subscription-Liste, Overrun-Schutz. |
| 29 | `TickActor.java` | abstract | Der Raum: abonniert, drainet, simuliert, flusht, misst. |
| 30 | `TickContext.java` | final | `tick()`, `elapsed()`, `actor()`, `outbound()`, `subscribe()`. |
| 31 | `OutboundBuffer.java` | final | Pro-Client-Aggregation, ein Payload pro Client pro Tick. |

### 5.4 `dev.localsoul.aero.actor.session` — Clients (5 Dateien)

| # | Datei | Art | Verantwortung |
|---|---|---|---|
| 32 | `Session.java` | abstract | Actor pro Client: Inbound ↔ Outbound, Interest-Bindung, Supervision-Anker. |
| 33 | `Subscription.java` | final | Pub/Sub-Ziel pro Client, hält `ActorRef` + aktuelles `Interest`. |
| 34 | `Interest.java` | record | Volumen um `(x,y,z)` mit Radius. `UNBOUNDED`. |
| 35 | `InterestSet.java` | final | Uniform Grid (AoI). `put/move/remove/near` allokationsarm. |
| 36 | `Client.java` | interface | `void send(Object packet)` — vom Spiel mit Netty/Ktor implementiert. |

### 5.5 `dev.localsoul.aero.actor.internal` — Intern (8 Dateien)

| # | Datei | Art | Verantwortung |
|---|---|---|---|
| 37 | `ActorCell.java` | final | Receive-Loop, Exit-Propagation, Pending-Call-Abbruch, Reentrant-Dispatch. |
| 38 | `LocalActorRef.java` | final | Implementierung von `ActorRef`; hält `ActorCell` + Name. |
| 39 | `MpscQueue.java` | final | Lock-freie bounded Array-Queue, ein Consumer, N Producer. |
| 40 | `Wakeup.java` | final | Park/Wake-Handoff ohne Lock. |
| 41 | `Envelope.java` | record | `message`, `sender`, `kind`, `call`. |
| 42 | `MessageKind.java` | enum | `USER`, `SYSTEM`. |
| 43 | `Signals.java` | final | Verschachtelte Records: `Exit`, `Down`, `Terminate`, `CallResult`. |
| 44 | `Call.java` | final | Pending-`ask` mit Timeout- und Abbruch-Logik. |
| 45 | `NameRegistry.java` | final | `ConcurrentHashMap`-basierte Namensauflösung. |
| 46 | `CurrentActor.java` | final | ThreadLocal-Absenderauflösung. |

> Zählung inkl. verschachtelter Typen; die 7 Beispiel-/Benchmark-Dateien liegen
> zusätzlich in `src/test/java`.

---

## 6. Kern-Detailentwurf

### 6.1 Actor-Lebenszyklus

```
                    system.spawn(actor, name)
                              │
                              ▼
                  ┌───────────────────────┐
                  │        NEW            │  Name registriert,
                  └───────────┬───────────┘  ActorThread submitted
                              │ start(ctx) läuft
                              ▼
                  ┌───────────────────────┐
        ┌────────▶│       RUNNING         │◀──┐
        │         └───────────┬───────────┘   │ kein Terminierungs-
        │                     │               │ wunsch
        │   ctx.stop()  → Terminate in Mailbox│
        │   exit(Pid,..) → Exit in Mailbox   │
        │   kill()      → killed = true      │
        ▼                     │               │
   ┌────────────────────────┐  │               │
   │      TERMINATING        │  │               │
   │  Mailbox wird gedraint  │  │               │
   └───────────┬────────────┘  │               │
               │               │               │
               ▼               │               │
      ┌─────────────────┐      │               │
      │   TERMINATED    │◀─────┴───────────────┘
      │  Name gelöst    │   (killed → postStop
      │  postStop()     │    mit ExitReason.Terminated,
      │  Monore→Future  │    kein Drain)
      │  Links→Exit     │
      │  Calls→Fehler   │
      └─────────────────┘
```

**Zustandsfelder in `ActorCell`**

| Feld | Typ | Bedeutung |
|---|---|---|
| `state` | `volatile State` | `NEW`, `RUNNING`, `TERMINATED` |
| `exitReason` | `volatile ExitReason` | Grund, gesetzt im `finally` |
| `killed` | `volatile boolean` | Hard-Kill-Flag, unterscheidet Kill von Soft-Stop |
| `behavior` | `volatile Behavior` | aktuelles Verhalten (`become` ersetzt es) |
| `thread` | `volatile Thread` | eigener Virtual Thread (für `interrupt`/`unpark`) |
| `current` | `Envelope`-Stack | Kontext für `reply`/`sender`/`currentMessage`, reentrant |
| `pendingCalls` | `ConcurrentHashMap<Call,Boolean>` | eigene laufenden `ask` |

**Reihenfolge in `terminate(reason)`** (bewusst, weil das OTP entspricht):

1. `state = TERMINATED`, `exitReason = reason`
2. **Namen freigeben** (muss vor dem Restart durch den Supervisor passieren, sonst
   scheitert der Neustart am `ActorNameTakenException`)
3. `actor.postStop(reason, ctx)` — Cleanup des Benutzercodes
4. Monore auflösen (Futures mit `reason` vervollständigen)
5. Pending `ask` dieser Zelle mit `ActorTerminatedException` fehlschlagen
6. Links benachrichtigen (Exit-Signal, s. [6.8](#68-links-monitore-exit-propagation))
7. Aus `ActorSystem` deregistrieren

Schritt 2 vor 3/6 ist nicht verhandelbar: ein Supervisor, der seinen Kindern
einen Restart gibt, muss den Namen frei haben, und die Exit-Signale anderer
Links sollen nicht gegen einen noch lebenden Actor laufen.

### 6.2 Receive-Loop

```java
void run() {
    Thread.currentThread().setName("aero-actor-" + name);
    CurrentActor.set(self);
    ExitReason reason = ExitReason.normal();
    try {
        Behavior initial = actor.start(context);
        behavior = initial == Behavior.UNHANDLED ? Behavior.NEXT : initial;
        if (behavior == Behavior.HALT) { finish(ExitReason.normal()); return; }

        while (state == State.RUNNING) {
            Envelope env = mailbox.take();
            if (env == null) {
                // Mailbox geschlossen oder Interrupt: Kill oder Soft-Stop?
                if (killed) { finish(ExitReason.terminated()); return; }
                break;                                   // graceful drain fertig
            }
            Behavior result = dispatch(env);
            switch (result) {
                case null, case Behavior.NEXT -> { }       // Behavior bleibt
                case Behavior.HALT -> { finish(ExitReason.normal()); return; }
                default -> behavior = result;              // become / neues Behavior
            }
        }
    } catch (Throwable t) {
        reason = ExitReason.failure(t);
    } finally {
        finish(reason);
        CurrentActor.clear();
    }
}
```

**`dispatch(env)`** — die eine Stelle, an der Nachrichten ausgewertet werden:

```java
private Behavior dispatch(Envelope env) {
    Envelope saved = current;               // reentrant-sicher für drainInbox()
    current = env;
    try {
        if (env.kind() == MessageKind.SYSTEM) return handleSystem(env);
        Behavior result = behavior.invoke(env.message(), context);
        if (result == Behavior.UNHANDLED) {
            actor.onUnhandled(env.message(), context);
            return Behavior.NEXT;
        }
        return result;
    } finally {
        current = saved;
    }
}
```

**`handleSystem(env)`**

| Systemnachricht | Wirkung |
|---|---|
| `Signals.Terminate(reason)` | `Behavior.HALT` nach Setzen von `stopReason = reason` |
| `Signals.Exit(target, reason)` | Link-Signal: `ActorCell#onLinkExit` (S. [6.8](#68-links-monitore-exit-propagation)) |
| `Signals.CallResult(call, value, error)` | `call.complete(value)` / `call.fail(error)` — läuft im Thread des Aufrufer-Actors |
| `Tick` (als USER gesendet) | wird an `Actor.receive` durchgereicht → `TickActor` |

> **`Signals.Down` existiert nicht.** `monitor` liefert direkt die `deathFuture`
> der Ziel-Zelle (S. [6.8](#68-links-monitore-exit-propagation)). Damit gibt es
> weder eine Registrierungs-Race noch eine zusätzliche Nachricht; ein
> `CompletableFuture` ist von Natur aus Multi-Consumer.

**`take() == null` ist eine Zusage, keine Vermutung.** `mailbox.take()` gibt
`null` **nur** zurück, wenn die Mailbox geschlossen **und** vollständig geleert
ist (Vertrag in [6.4](#64-mailbox-mpsc-array-queue), Präzisierung 1 in
[16](#16-präzisierungen-aus-dem-review)). Der Empfänger-Loop beendet sich auf
`null` deshalb gefahrlos: `null` ⟺ `closed ∧ producerIndex == consumerIndex`.

**`HALT` vs. `EXIT`:** BEAM bricht die Schleife nicht ab, wenn ein `receive`
`HALT` liefert; `HALT` ist ein Sentinel, das die Zelle dazu bringt, den Actor
mit `normal` zu beenden. Deshalb kein `break`, sondern `finish` + `return`.

### 6.3 Nachrichtenkanäle und Envelope

```java
public record Envelope(Object message, ActorRef sender, MessageKind kind, Call call) {
    public static Envelope user(Object message, ActorRef sender) { … }
    public static Envelope call(Object message, ActorRef sender, Call call) { … }
    public static Envelope system(Object signal) { return new Envelope(signal, null, SYSTEM, null); }
}
```

Das `call`-Feld trägt die Korrelation zwischen Anfrage und Antwort. Es ist
bewusst am Envelope und nicht in einer Map am Ziel gespeichert:

- Ein Actor verarbeitet immer genau **eine** Nachricht zur Zeit → genau **ein**
  `ask`-Kontext ist aktiv.
- Dadurch kann `ctx.reply(v)` ohne Schlüssel-Suche antworten, auch bei 10 000
  parallelen `ask` an denselben Actor.
- Timeout und Tod des Aufrufers müssen den Aufrufer erreichen, nicht das Ziel →
  die Pending-Registry liegt deshalb beim **Aufrufer** (`pendingCalls`).

### 6.4 Mailbox: MPSC-Array-Queue

**Vertrag — drei Operationen, drei verschiedene Zusicherungen.** Diese
Unterscheidung ist die wichtigste Spezifikation der Mailbox (Präzisierung 1 in
[16](#16-präzisierungen-aus-dem-review)):

```java
public interface Mailbox extends AutoCloseable {

    /**
     * ANNAHME. Blockiert nie (außer Policy BLOCK, s. u.). @return false, wenn die
     * Nachricht abgelehnt wurde (geschlossen, voll, DROP_NEWEST).
     * Wirft NIE.  Ein Producer darf sich auf `false` verlassen.
     */
    boolean offer(Envelope envelope);

    /**
     * VERLUSTBEHAFTETES POLLEN. Blockiert nie.
     *
     * @return die nächste Nachricht, oder {@code null} wenn gerade keine
     *         *verfügbar* ist. ACHTUNG: {@code null} heisst NICHT "leer"!
     *         Ein Producer kann den Slot `c` bereits reserviert haben, seinen
     *         Wert aber noch nicht publiziert haben; dann wird begrenzt gesponnen
     *         und danach trotzdem {@code null} geliefert.
     *
     * Wer "leer" belastbar feststellen will, nutzt {@link #isEmpty()}.
     */
    Envelope poll();

    /**
     * DIE EINZIGE VERLASSLOSE OPERATION. Blockiert (parkt) bis eine Nachricht
     * verfügbar ist.
     *
     * @return die nächste Nachricht, oder {@code null} genau dann, wenn die
     *         Mailbox geschlossen UND vollständig geleert ist
     *         ({@code closed ∧ producerIndex == consumerIndex}), oder wenn der
     *         Thread unterbrochen wurde.
     *
     * Zusicherung: Rückgabe von {@code null} bedeutet „nichts mehr kommt".
     * Genau darauf baut der Empfänger-Loop auf.
     */
    Envelope take();

    /**
     * EXAKT. @return true, wenn keine Nachrichtung angenommen wurde, die noch
     *         wartet. Reservierte, aber noch nicht publizierte Slots zählen als
     *         „wartet" — die Mailbox ist also nie zu früh als leer gemeldet.
     */
    boolean isEmpty();

    /**
     * OBERE SCHÄTZEUNG der wartenden Nachrichten (reserved slots mitgerechnet,
     * bei CoalescingMailbox exakt, s. 6.6). Nur als Lag-Signal verwenden.
     */
    int size();

    int capacity();                     // -1 = unbounded
    @Override void close();             // nimmt nichts mehr an, weckt den Consumer
}
```

**Daraus folgt für Aufrufer:**

| Wer | Was | Warum nicht `poll()` |
|---|---|---|
| `ActorCell`-Loop | `take()` | Muss `null` als „definitiv fertig" lesen dürfen |
| `drainInbox(budget)` (Tick) | `poll()` + Abbruch bei `isEmpty()` | Lossy-`null` darf den Budget-Durchlauf nicht vorzeitig beenden |
| Lag-Messung | `size()` / `isEmpty()` | Nie aus `poll()` ableiten |

```java
// drainInbox in ActorCell — bricht NICHT bei losem poll()==null ab
int drained = 0;
while (drained < budget) {
    Envelope env = mailbox.poll();
    if (env != null) { dispatch(env); drained++; }
    else if (mailbox.isEmpty()) break;   // exakte Abbruchbedingung
    else Thread.onSpinWait();            // Producer publiziert gerade
}
```

**Datenstruktur** (`internal/MpscQueue`) — Vyukov-Style, fester Cache, eine
Potenz von zwei:

```java
final AtomicReferenceArray<Object> buffer;   // Größe = nächstgrößere Potenz von zwei
final int mask;
final AtomicInteger producerIndex;           // CAS von N Producenten
final AtomicInteger consumerIndex;           // nur vom Consumer geschrieben
```

`offer`:

```java
for (;;) {
    if (closed.get()) return false;
    int p = producerIndex.get();
    int c = consumerIndex.get();
    if (p - c >= capacity) return onFull();            // Policy
    if (producerIndex.compareAndSet(p, p + 1)) {
        buffer.lazySet(p & mask, value);
        wakeup.signal();
        return true;
    }
}
```

`poll`:

```java
int c = consumerIndex.get();
int p = producerIndex.get();
if (c >= p) return null;                     // leer
Object v = buffer.get(c & mask);
if (v == null) {                             // Slot reserviert, Wert noch nicht publiziert
    if (++spins <= MAX_SPINS) { Thread.onSpinWait(); c = consumerIndex.get(); /* retry */ }
    else return null;                        // seltener Fall: Producer hängt
}
buffer.lazySet(c & mask, null);
consumerIndex.lazySet(c + 1);
return v;
```

Der `null`-Fall ist der klassische Store-Load-Reordering des Vyukov-Designs und
wird durch **begrenztes Spinnen** aufgelöst (gleiche Strategie wie JCTools
`MpscArrayQueue.poll`): Der Consumer sieht den inkrementierten Producer-Index,
liest aber den noch nicht geschriebenen Slot. Ein begrenzter Spin löst das in
Praxis immer auf; danach wird `null` gemeldet, was für `size()`/`isEmpty()`
konservativ (leer) und damit sicher ist — der `poll()`-Rückgabewert `null` ist
also nicht als „leer" interpretierbar (Präzisierung 1 in
[16](#16-präzisierungen-aus-dem-review)). `isEmpty()` liest deshalb direkt die
Indizes:

```java
@Override public boolean isEmpty() { return producerIndex.get() == consumerIndex.get(); }
```

Ein reservierter, aber noch nicht publizierter Slot hat den Producer-Index
**schon** erhöht, `isEmpty()` ist in diesem Fenster also `false`. Daraus folgt
die entscheidende Zusage:

> `take()` gibt `null` zurück ⟹ `closed ∧ producerIndex == consumerIndex` ⟹ alle
> angenommenen Nachrichten wurden verarbeitet. **Es gibt kein Fenster, in dem
> sich der Actor verfrüht beendet.**

Ein Producer, der nach `CAS` und vor `lazySet` stirbt (OOM, `Thread.stop`),
hängt den Actor dagegen — dafür gibt es in einer lock-freien Queue keinen
atomaren Ersatz, und der Fall ist auf ein unkontrolliertes JVM-Kill beschränkt.

> Der Spin-Zähler ist **pro Aufruf** lokal (nicht im Objekt), damit er auf einem
> einzigen Thread wächst und dort zurückgesetzt wird. Mehrere Konsumenten sind
> nicht erlaubt (wird nicht geprüft, ist eine Vertragsverletzung).

**Overflow-Policies** (`MailboxOverflow`)

| Policy | Verhalten | Typischer Einsatz |
|---|---|---|
| `FAIL` (Default) | `offer` → `false`; der Absender prüft mit `tryTell` und kickt den Spieler | Raum-/Session-Inbox |
| `BLOCK` | `Semaphore(capacity)`-Permits; Producer wartet, Consumer gibt frei | Sender-seitige Producer mit eigener Flusskontrolle |
| `DROP_NEWEST` | `offer` → `false`, Nachricht verworfen | Telemetrie, unkritische Status-Updates |

**`offer` wirft nie, `tell` wirft nie.** `ActorRef.tell` ist BEAMs `!` und
wirft nicht — auch dann nicht, wenn das Postfach voll oder der Actor tot ist.
Wer auf die Annahme reagieren will, verwendet `tryTell` (boolean). `ask`
scheitert mit einer exceptional Future (`MailboxFullException` bzw.
`ActorTerminatedException`). Damit kann insbesondere der `TickDriver` nicht
abstürzen (Präzisierung 3 in [16](#16-präzisierungen-aus-dem-review)).

**`BLOCK` + Self-Send ist ein Deadlock und wird erkannt.** Sendet ein Actor sich
selbst eine Nachricht (`ctx.self().tell(…)`, üblich für internes Scheduling) und
ist seine eigene Mailbox mit Policy `BLOCK` voll, wartet der einzige
Consumer-Thread auf sich selbst. `DefaultMailbox` kennt seinen Besitzer und
lehnt das ab:

```java
private boolean offerBlocking(Envelope env) {
    if (self != null && env.sender() == self) {
        throw new IllegalStateException(
            "BLOCK-Mailbox: Self-Send bei vollem Postfach — Deadlock. " +
            "Nutze ctx.stop()/become() oder CoalescingMailbox bzw. FAIL statt BLOCK.");
    }
    permits.acquireUninterruptibly();
    // … regulärer CAS-Versuch
}
```

Der Aufrufer kann die Ausnahme abfangen; wichtiger ist, dass daraus ein
**definierter Fehler** wird und nicht ein Hänger. Zyklische Blockier-Deadlocks
(A wartet auf B, B wartet auf A, beide Postfächer voll) sind damit *nicht*
erkennbar und stehen als verbotenes Muster in
[12](#12-performance-leitregeln-und-anti-patterns).

**Warum kein `DROP_OLDEST`?** Es ist strukturell nicht sauber in eine lock-freie
MPSC-Array-Queue zu bauen: der Producer müsste den Consumer-Index per CAS
vorziehen und dabei den Slot räumen, ohne dass der Consumer je einen
`null`-Slot liest (Sichtbarkeits-Lücke zwischen Producer-Index und
Wert-Publikation). Der Anwendungsfall, den `DROP_OLDEST` abdecken würde
(veraltete Frequenz-Updates), wird besser und ohne diese Race Condition von
`CoalescingMailbox` gelöst: dort überlebt die **neueste** Nachricht und behält
ihre FIFO-Position. `DROP_OLDEST` ist deshalb bewusst nicht implementiert.

**`LinkedMailbox`** — `ConcurrentLinkedQueue`-basiert, unbounded, gleiche
Policies (`BLOCK` ohne Semaphore, da nie voll). Für Actors mit geringem
Nachrichtenvolumen, wo die Allokationskosten eines Slots egal sind und
Kapazitätsfehler nie auftreten sollen.

### 6.5 Park/Wake-Protokoll

Ziel: ein geparkter Consumer darf **keine** Nachricht verpassen, und N Produzenten
dürfen den Consumer nicht mit `unpark`-Stürmen wecken, wenn er gar nicht
schläft.

```java
final class Wakeup {
    private final AtomicBoolean parked = new AtomicBoolean();
    private volatile Thread thread;

    /** Producer: nach erfolgreichem enqueue. */
    void signal() {
        if (parked.compareAndSet(true, false)) {     // Consumer schläft (oder ist dabei)
            Thread t = thread;
            if (t != null) LockSupport.unpark(t);
        }
    }

    /** Consumer: warten, solange `available()` false ist. */
    void await(BooleanSupplier available) {
        for (;;) {
            if (available.getAsBoolean()) return;
            if (Thread.interrupted()) return;                 // Flag wird hier geleert
            if (parked.compareAndSet(false, true)) {          // Parkplatz beanspruchen
                thread = Thread.currentThread();
                if (available.getAsBoolean()) { parked.set(false); return; }   // Re-Check
                LockSupport.park();
                parked.set(false);
            } else {
                Thread.onSpinWait();                          // Producer ist gerade aktiv
            }
        }
    }
}
```

**Beweis der Korrektheit**

1. *Kein Lost Wakeup, wenn Producer vor dem Parken sendet:*
   `LockSupport.unpark(t)` setzt ein Permit für Thread `t`; das nächste
   `park()` kehrt sofort zurück. Das Permit geht nicht verloren.
2. *Kein Lost Wakeup, wenn Producer während des Park-Platz-Anforderns sendet:*
   Producer gewinnt nur `parked.cas(true,false)`, also nachdem der Consumer
   `true` geschrieben hat. Der Consumer schreibt danach `thread` und prüft
   `available()` erneut — findet er die Nachricht, parkt er gar nicht.
3. *`thread` ist niemals `null` beim Unpark:*
   Der `volatile`-Write `thread = currentThread()` folgt im Consumer auf den
   `volatile`-RMW `parked.cas(false,true)`; der Producer folgt mit seinem
   `volatile`-RMW `parked.cas(true,false)` und liest danach `thread`. Bei
   volatile-Sequenzkonsistenz sieht er den Write. (Die Variable ist zusätzlich
   `volatile`, damit der Beweis nicht von der Reihenfolge im Bytecode abhängt.)
4. *Kein Weck-Sturm:* Nach dem ersten Producer, der `parked` zurückgesetzt hat,
   sieht jeder weitere `compareAndSet(true,false)` `false` und unparkt nicht.
5. *Interrupt wird nicht verschluckt:* `Thread.interrupted()` leert das Flag, und
   `LockSupport.park()` kehrt bei gesetztem Interrupt-Flag sofort zurück. Der
   `take()`-Vertrag garantiert: Rückgabe innerhalb von einer Warteperiode.

### 6.6 CoalescingMailbox als Delta-Queue

Ziel: bei 30 Hz Input pro Spieler dürfen nicht 30 Queue-Einträge entstehen —
die neueste Eingabe ersetzt wartende ältere, **ohne** die FIFO-Position zu
verändern (Reihenfolge gegenüber anderen Nachrichtentypen bleibt stabil).

Struktur: FIFO über **Keys**, Werte in `ConcurrentHashMap<Object, AtomicReference<Object>>`.

```java
public final class CoalescingMailbox implements Mailbox {
    private final MpscQueue<Object> order;                                 // FIFO der Keys
    private final ConcurrentHashMap<Object, AtomicReference<Object>> slots;
    private final Object NO_VALUE = new Object();                          // „Slot frei"

    public boolean offer(Envelope env) {
        Object key = keyFn.apply(env.message());
        if (key == null) return tail.offer(env);        // nicht coalescable → normal einreihen
        for (;;) {
            AtomicReference<Object> slot = slots.computeIfAbsent(key, k -> new AtomicReference<>(NO_VALUE));
            Object cur = slot.get();
            if (cur == NO_VALUE) {
                if (slot.compareAndSet(NO_VALUE, env.message())) { order.offer(key); return true; }
                continue;                                              // CAS verloren → neu
            }
            if (slot.compareAndSet(cur, env.message())) return true;    // „latest wins"
        }
    }

    public Envelope poll() {
        Object key;
        while ((key = order.poll()) != null) {
            AtomicReference<Object> slot = slots.get(key);
            if (slot == null) continue;                                // Slot kann nicht fehlen
            Object v = slot.get();
            if (v == NO_VALUE) continue;                               // bereits konsumiert
            if (slot.compareAndSet(v, NO_VALUE)) {
                return Envelope.user(v, key);                          // CAS = Release
            }
        }
        return null;
    }
}
```

**Beweis: keine verlorenen Updates.** Producer und Consumer benutzen
ausschließlich `compareAndSet` auf demselben Slot.

- Producer ersetzt (`cur` → neu): schlägt fehl, wenn der Consumer zwischenzeitlich
  auf `NO_VALUE` released hat. Der Producer retry't und sieht `NO_VALUE` → nimmt
  den Fresh-Pfad (Wert setzen **dann** den Key in die FIFO). Der Key ist dann
  zweimal in der FIFO; die erste Verarbeitung liefert den neuen Wert, der
  Consumer released, die zweite findet `NO_VALUE` und skippt. Kein Verlust.
- Consumer released (`v` → `NO_VALUE`): schlägt fehl, wenn ein Producer genau
  jetzt ersetzt hat. Der Consumer retry't, liest den neuen Wert und released
  erneut. Kein Verlust.

**`size()` ist exakt und kein Dublettenzähler.** Ein Key kann nach einem
verlorenen CAS zweimal in `order` liegen (siehe Beweis oben). `order.size()` wäre
damit eine **Zahl wartender Queue-Einträge**, nicht die Zahl wartender
*logischer Nachrichten* — als Lag-Signal also zu ungenau (Präzisierung 4 in
[16](#16-präzisierungen-aus-dem-review)). Deshalb hält die Mailbox einen
zweiten, exakten Zähler:

```java
private final AtomicInteger occupied = new AtomicInteger();   // belegte Slots

// Producer, Fresh-Pfad (CAS null → value erfolgreich):
occupied.incrementAndGet();

// Consumer, Release (CAS value → null erfolgreich):
occupied.decrementAndGet();

@Override public int size()        { return (int) order.size() + nonCoalesced; }
@Override public boolean isEmpty() { return occupied.get() == 0 && nonCoalesced == 0; }
```

Kosten: ein `incrementAndGet` bzw. `decrementAndGet` pro coalescbarer
Nachricht — ca. 5 ns, im Vergleich zu `computeIfAbsent` + CAS vernachlässigbar.
Damit ist `ActorRef.mailboxSize()` auch für diese Mailbox ein **exaktes** Lag-Signal
und kein „höchstens"-Wert. Für `DefaultMailbox` bleibt `size()`
(`producerIndex − consumerIndex`) eine obere Schätzung, die reservierte Slots
mitzählt; die Abweichung ist auf das Publizierungsfenster beschränkt und damit
für Backpressure-Entscheidungen in der sicheren Richtung.

**Key-Einträge werden bewusst nicht entfernt.** Ein `slots.remove(key)` könnte
einen Producer treffen, der gerade auf einem alten Slot per CAS arbeitet → sein
Wert ginge verloren, weil niemand mehr den Key in die FIFO nachlegt. Der
Key-Space ist durch die Spieler-/Entity-Zahl begrenzt (typisch < 1000 pro
Raum), das Wachstum ist also unkritisch. Wer das nicht garantieren kann, nutzt
`LinkedMailbox`.

**Key-Funktion.** `Function<Object,Object>`; `null` = nicht coalescable, geht
normal in die FIFO (z. B. Cmd-Pakete, während Position- und Input-Updates
coalescen).

**Beispiel**

```java
record Input(long playerId, double x, double y, int seq) {}

Mailbox mb = Mailboxes.coalescing(1024, m -> m instanceof Input i ? i.playerId() : null);
// 30 Hz Input für Spieler 7: 29 werden ersetzt, die neueste wird ausgeliefert.
```

### 6.7 ask/reply und Call-Semantik

```java
// BEAM: gen_server:call(Pid, Req, Timeout)
CompletableFuture<Object> ask(Object message, Duration timeout) {
    Call call = new Call(currentCellOrNull(), timeout);
    tell(Envelope.call(message, this, call));
    system.schedule(() -> call.timeout(), timeout);
    return call.promise();
}
```

```java
final class Call {
    private final ActorCell owner;                       // null = Aufrufer ist kein Actor
    private final CompletableFuture<Object> promise = new CompletableFuture<>();
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile boolean timedOut;

    void resolve(Object value) {
        if (!done.compareAndSet(false, true)) return;
        if (owner != null && owner.isAlive()) {
            owner.postSystem(new Signals.CallResult(this, value, null));   // Callback im Aufrufer-Thread
        } else {
            promise.complete(value);
        }
    }
    void fail(Throwable t) { … analog mit promise.completeExceptionally(t) … }
    void timeout() { timedOut = true; fail(new AskTimeoutException(…)); }
}
```

**Die entscheidende Eigenschaft:** Der Abschluss der Future läuft auf dem
**Virtual Thread des Aufrufer-Actors**, nicht auf dem des Antwortenden. Damit
hat ein `thenAccept`-Block im Aufrufer-Actorkontext dieselben Garantien wie ein
normaler Handler-Aufruf (Single-Writer, kein geteilter Zustand, `ctx` gültig).
Das ist die BEAM-Semantik: Der Aufrufer-**Prozess** führt das Ergebnis aus.

Voraussetzung: Der Aufrufer muss den Abschluss als Nachricht durch seine
eigene Mailbox schicken — sonst würde der Abschluss im Thread des *Antwortenden*
laufen. Genau das tut `Call.resolve`. Beim Tod des Aufrufers (Schritt 5 in
[6.1](#61-actor-lebenszyklus)) werden alle offenen Calls fehlgeschlagen, damit
keine Future hängen bleibt.

**`ctx.reply(v)`-Regeln**

1. Aktueller Envelope hat `call != null` → Call auflösen, `Behavior.NEXT`.
2. Sonst `sender() != null` → `sender.tell(v, self())` (fire-and-forget, OTP `!`).
3. Sonst (Top-Level `tell`, kein Absender) → verworfen.

Antworten auf eine `ask` **außerhalb** des Handlers ist bewusst nicht möglich
(kein `reply`-Handle im Context). Wer asynchron antworten will, hält sich den
`ActorRef` des Absenders (`ctx.sender()`) und nutzt `tell`.

**Ausfallszenarien**

| Ereignis | Ergebnis |
|---|---|
| Timeout abgelaufen | `AskTimeoutException`; eine spätere Antwort wird verworfen (`done == true`) |
| Ziel-Actor stirbt während `ask` | `ActorTerminatedException(reason)` — `ActorCell` kennt den laufenden Call |
| Ziel-Name frei, Actor nie gestartet | `AskTimeoutException` |
| Aufrufer stirbt | Future wird nie/irrelevant vervollständigt; Peer-Calls failen mit `ActorTerminatedException` |
| Kein Timeout angegeben | Future bleibt bis zur Antwort offen (Timeout `Duration.ZERO` = keiner) |

### 6.8 Links, Monitore, Exit-Propagation

**Zwei Mechanismen, kein gemeinsames Signalformat.** (Korrektur gegenüber der
ersten Fassung: es gibt **keine** `DOWN`-Nachricht.)

- `monitor(ref)` → gibt die **`deathFuture` der Ziel-Zelle** direkt zurück:
  `CompletableFuture<ExitReason>`, das beim Tod von `ref` **immer** vervollständigt
  wird (auch bei `normal`).
- `link(ref)` → der Tod von `ref` erzeugt ein `Exit`-Systemsignal **im eigenen**
  Actor, abhängig vom Exit-Reason.

**Warum `deathFuture` statt einer Monitor-Registry.** Jede Zelle hält genau eine
`deathFuture`, die in `finish()` **nach** Namensfreigabe und `postStop` einmalig
vervollständigt wird. Damit:

- **keine Registrierungs-Race.** Die klassische Race „Ziel stirbt, bevor man
  den Monitor registriert" existiert nicht: ist die Zelle schon tot, ist die
  Future bereits abgeschlossen. Eine Map + Re-Check wäre überflüssig.
- **keine zusätzliche Nachricht** im Hot Path, kein `Signals.Down`.
- **Multi-Consumer gratis.** `CompletableFuture` wird von N Beobachtern geteilt;
  ein Supervisor mit 500 Kindern hat trotzdem keinen Overhead pro Kind.
- **Kostenloses `join`.** `cell.ref -> deathFuture().get(timeout)` ist das
  primitive Shutdown-Warten (s. [6.10](#610-supervisor-zustandsautomat)).

```java
public CompletableFuture<ExitReason> monitor(ActorRef other) { return cellOf(other).deathFuture(); }
```

**Propagationsmatrix.** `trap` = Ziel ist Supervisor (immer) oder hat explizit
`linkTrapping` benutzt.

| Exit-Reason des sterbenden Actors | an `link`-Peer (`trap=false`) | an trapping Peer (Supervisor) |
|---|---|---|
| `Normal` | **nein** | ja |
| `Shutdown` | **nein** | ja |
| `Failure(t)` | ja | ja |
| `Terminated` | ja | ja |

Das ist exakt BEAMs `link/1` mit `trap_exit = false`; ein Prozess mit
`trap_exit = true` sieht jedes Signal. Supervisor „trappen" implizit, weil sie
sonst beim Tod ihres Kindes mit sterben würden. Die Filterung passiert am
**sendenden** Ende in `finish()` — ein Actor, der ein `Exit` empfängt, ist also
sicher abnormal (oder trapping) und muss die Frage nicht selbst prüfen.

**Nicht-trappende Actors** behandeln ein erhaltenes `Exit` wie BEAM: bei
abnormalem Grund sterben sie selbst mit demselben Grund. Implementiert in
`ActorCell#onLinkExit`:

```java
// Filterung passiert bereits beim Senden; hier ist abnormal garantiert
if (actor instanceof ChildOwner owner) return owner.onChildExit(e.target(), e.reason());
stopReason = e.reason();                 // abnormal → selbst mit demselben Grund sterben
return Behavior.HALT;
```

`ChildOwner` ist ein Ein-Methoden-Interface im Kern-Paket. So weiß `ActorCell`,
ob ein Actor ein Supervisor ist, **ohne** die Schichtregel
„`internal` importiert `supervision` nicht" zu verletzen.

**`Monitor` vs. `Link` in der Praxis**

| | `link` | `monitor` |
|---|---|---|
| Benachrichtigung | `Exit`-Nachricht in der Mailbox | `CompletableFuture<ExitReason>` |
| Bei `normal` | nein (non-trapping) | ja |
| Kaskadierend | ja (abnormal) | nein |
| Zweck | Teilfehlerhaftes Verhalten propagieren, Aufrufer informieren | „Ist er noch da?", sauberes Warten, `join` |

### 6.9 runBlocking-Offload

```java
<T> CompletableFuture<T> runBlocking(Callable<T> work) { … }
```

Ablauf:

1. `work` wird auf einem bounded `ThreadPoolExecutor` (Default
   `2 × Runtime.availableProcessors()`, `CallerRunsPolicy` **nicht**, sondern
   `AbortPolicy` → `RejectedExecutionException`, damit der Actor-Thread nie
   Arbeit übernimmt) ausgeführt.
2. Das Ergebnis wird als `Signals.CallResult` **in die eigene Mailbox** gelegt.
3. Der Handler des Actors verarbeitet es wie eine normale Nachricht — im
   eigenen Thread, mit gültigem `ctx`.

**Regeln**

- Nur für Rare-Pfade: Login, DB, HTTP, Datei-I/O, Admin-Befehle.
- **Nie** im Per-Tick-Pfad, nie für Input, nie für Simulation.
- Der Pool ist pro `ActorSystem` und **geteilt**: 1000 Räume mit je einem
  Login-Call bei Pool-Größe 32 → die Calls warten. Deshalb ist
  `CallerRunsPolicy` verboten (dort würde der Raum-Tick die DB-Abfrage
  ausführen) und der Pool bewusst klein.
- Kein Thread-Pool für Actor-Code: Actor-Threads sind Virtual Threads; ein
  klassischer Pool würde die 1-VThread-pro-Actor-Regel aushebeln.

### 6.10 Supervisor-Zustandsautomat

`Supervisor` ist ein `Actor`, der Systemnachrichten **vor** dem Benutzer-Code
sieht. Der Nutzer implementiert nur `onMessage`.

```
                   ┌──────────────────────────────┐
   start()  ──────▶│  Children starten (Reihenfolge)│
                   └───────────────┬──────────────┘
                                   │ läuft
              ┌────────────────────┼────────────────────┐
              │                    │                    │
        Exit(Kind)           OnMessage               Terminate
              │                    │                    │
              ▼                    ▼                    ▼
   ┌─────────────────────┐   Benutzer-Code      ┌────────────────────┐
   │ Restart-Entscheidung│                      │ Kinder in UMGEKEHR-│
   └──────────┬──────────┘                      │ ter Reihenfolge    │
              │                                 │ stoppen + joinen   │
   ┌──────────┴──────────┐                      └──────────┬─────────┘
   │  Intensität prüfen  │                                 │
   └──┬──────────────────┘                                 ▼
      │ ja                              ┌──────────────────────────────┐
      ▼                                  │ Supervisor stirbt mit dem    │
  ┌───────────────────────────────┐      │ ursprünglichen Grund        │
  │ ESKALATION an Parent:         │      └──────────────────────────────┘
  │ finish(SupervisorIntensities  │
  │   ExceededException)          │  Starter wirft Exception:
  └───────────────────────────────┘  → finish(Failure(t))   [Eskalation]
```

**Restart-Entscheidung** (OTP `do_restart`, wortgetreu):

| `RestartType` | `Normal` | `Shutdown` | abnormal (`Failure`/`Terminated`) |
|---|---|---|---|
| `PERMANENT` | Kind entfernen, Supervisor lebt | Kind entfernen, Supervisor lebt | **Restart** |
| `TRANSIENT` | Kind entfernen, Supervisor lebt | Kind entfernen, Supervisor lebt | **Restart** |
| `TEMPORARY` | Kind entfernen | Kind entfernen | Kind entfernen, **kein Restart, Supervisor lebt** |

Ein Restart, dessen `starter` wirft, beendet den **Supervisor** mit
`ExitReason.failure(t)` — das ist der wichtigste Eskalationspfad, weil er
Fehler in der Startlogik sichtbar macht statt sie zu verschlucken.

**Strategien** (Reihenfolge = Reihenfolge im `LinkedHashMap` der Kinder)

| Strategie | Aktion bei Restart eines Kindes `c_i` |
|---|---|
| `ONE_FOR_ONE` | nur `c_i` |
| `ONE_FOR_ALL` | alle Kinder, in Startreihenfolge |
| `REST_FOR_ONE` | `c_i` und alle Kinder, die **nach** `c_i` gestartet wurden |

**Intensität** (`SupervisorSpec.maxRestarts` / `within`, Default `3` in `5s`)

```java
private final ArrayDeque<Long> restarts = new ArrayDeque<>();   // Zeitstempel in ns

private boolean exceedsIntensity(long now) {
    restarts.addLast(now);
    while (restarts.size() > spec.maxRestarts()) restarts.removeFirst();
    long cutoff = now - spec.within().toNanos();
    while (!restarts.isEmpty() && restarts.peekFirst() < cutoff) restarts.removeFirst();
    return restarts.size() > spec.maxRestarts();
}
```

Der Supervisor verarbeitet Kontrollnachrichten in **einem** Thread, also ist
`ArrayDeque` hier ohne Lock korrekt. Die Deque ist auf `maxRestarts + 1`
Elemente begrenzt (kein Memory-Problem).

**Shutdown**

1. `shutdownTimeout` (Default 5 s) als Gesamtfenster
2. Kinder in **umgekehrter** Startreihenfolge `stop(Shutdown)` senden
3. Auf die `Down`-Futures warten (`get` mit Restbudget) — blockiert den
   Supervisor-Thread, aber das ist eine Virtual Thread: parkt statt OS-Thread zu
   belegen
4. `onShutdown()`-Hook
5. `ctx.stop(reason)` — die Exit-Signale der Kinder liegen dann in der eigenen
   Mailbox und werden nicht mehr ausgewertet, was korrekt ist

**`ChildSpec`**

```java
public record ChildSpec(String name, Supplier<ActorRef> starter,
                        boolean supervisor, RestartType restart) {
    public static ChildSpec worker(String name, Supplier<ActorRef> starter) {
        return new ChildSpec(name, starter, false, RestartType.PERMANENT);
    }
    public static ChildSpec worker(String name, Supplier<ActorRef> starter, RestartType t) { … }
    public static ChildSpec supervisor(String name, Supplier<ActorRef> starter) { … }
}
```

Die `starter`-Supplier-Funktion erlaubt beliebige Spawn-Formen (Registrierung,
Mailbox-Wahl, `spawnLink`) und ist die Grundlage für restartbare Kind-Starts.
Fehlt in einem Restart ein Name, wird der Kindname nicht neu registriert
(Der Starter registriert selbst via `system.spawn(actor, name)`).

### 6.11 TickDriver

```java
public final class TickDriver implements AutoCloseable {
    public static TickDriver start(ActorSystem system, Duration interval) { … }
    public static TickDriver start(ActorSystem system, Duration interval, OverrunPolicy policy) { … }

    public Subscription subscribe(ActorRef target);   // 1 Nachricht pro Target pro Tick
    public long tickCount();
    public long overruns();
    public long deliverFailures();                    // vollgelaufene Postfächer
    public long stalls();                             // vom Watchdog erkannte Stallzustände
    public long restarts();                           // vom Watchdog neu gestartete Pumpen
    public Duration interval();
    public boolean isRunning();
    public void close();
}
```

**Robustheit: kein Subscriber darf den Driver töten.** Die Tick-Zustellung ist
der einzige Stellenwert, an dem ein Fehler *alle* Räume betrifft. Drei
Vorkehrungen (Präzisierung 3 in [16](#16-präzisierungen-aus-dem-review)):

1. **`tryTell` statt `tell`** — ein volles Postfach liefert `false` statt einer
   Ausnahme.
2. **Pro Subscriber isoliert** — `try/catch` je Abonnent, damit auch ein
   unerwarteter Fehler (Bug in `tell`, kaputte Queue) nur diesen Raum betrifft.
3. **Top-Level-`try/catch` um die ganze Schleife** — selbst ein Fehler in der
   Iterationslogik wird gezählt und übersprungen, statt den Thread zu beenden.

```java
for (ActorRef room : subscribers) {
    try {
        if (!room.tryTell(tick)) {
            deliverFailures++;                  // Raum ist überlastet → onLagged dort
        }
    } catch (Throwable t) {
        driverErrors++;                        // NICHT den Driver beenden
        onSubscriberError.accept(room, t);      // Default: keine Ausgabe (s. unten)
    }
}
```

> **Absicht:** Der Driver schweigt über Läufer. Ein stillschweigendes
> Nachlaufen von `deliverFailures()` ist besser als 60 × 1000 Exception-Stacktraces
> pro Sekunde; wer es überwachen will, pollt die Zahl. `onSubscriberError` ist
> ein optionales BiConsumer pro Driver für Logger-Anbindung.

**Der Driver ist selbst überwacht (Watchdog).** `TickDriver` ist kein Actor und
hat keine OTP-Supervision — die Lücke, die ohne Gegenmaßnahme den ganzen
Spielserver stillstehen ließe. Der Pump-Thread ist jedoch **vollständig
zustandslos** (alle Zähler im `TickDriver`-Objekt, `thread` als Feld), also ist
ein Neustart trivial und sicher:

```java
// einmalig beim Start
watchdog = Thread.ofPlatform().daemon().name("aero-tick-watchdog").start(this::watch);

private void watch() {                    // 1 Hz
    while (running) {
        sleep(1s);
        long age = (System.nanoTime() - lastTickAtNanos) / 1_000_000;
        if (age > stallThresholdMillis) {                 // Default 5 × interval
            stalls++;
            lastTickAtNanos = System.nanoTime();           // Watchdog übernimmt
        }
        ensureRunning();                                  // Thread tot? neu starten
    }
}

synchronized void ensureRunning() {
    if (thread != null && thread.isAlive()) return;
    thread = Thread.ofPlatform().daemon().name("aero-tick").start(this::pump);
    restarts++;
}
```

Der Watchdog ist **ein weiterer Daemon-Thread** (2 Plattform-Threads pro Driver,
beide zu 99 % schlafend). Bewusst *kein* Actor über dem Thread: die Supervision
eines Threads durch einen Actor, der selbst keinen Thread hat, wäre mehr
Infrastruktur als der Thread selbst. Die Zählung `restarts() > 0` macht den Fall
sichtbar.

**Fixed-Timestep-Akkumulator** (Platform-Thread):

```java
long intervalNanos = interval.toNanos();
long deadline = System.nanoTime();      // erster Tick sofort
long next = 0;
for (;;) {
    parkUntil(deadline);
    if (closed) break;

    long now = System.nanoTime();
    if (now > deadline) {                                   // Deadline verpasst
        overruns++;
        long late = now - deadline;
        long missed = late / intervalNanos;
        switch (policy) {
            case DROP -> { deadline = now; break; }                       // alles verwerfen
            case CLAMP -> { deadline = now + intervalNanos; break; }     // Default
            case STEP -> { deadline += intervalNanos;                     // aufholen
                           if (missed >= maxCatchUpSteps) {                // …aber gedeckelt
                               overruns++;
                               deadline = now + intervalNanos;
                           }
                           break; }
        }
    }
    tickCount++;
    Tick tick = new Tick(tickCount, Duration.ofNanos(System.nanoTime() - lastTickAt), tickCount == 1);
    lastTickAt = System.nanoTime();
    for (ActorRef room : subscribers) {
        try {
            if (!room.tryTell(tick)) deliverFailures++;
        } catch (Throwable t) {
            driverErrors++;
        }
    }
    deadline += intervalNanos;   // CLAMP/DROP haben deadline bereits gesetzt
    if (deadline < System.nanoTime() - intervalNanos) deadline = System.nanoTime() + intervalNanos;
}
// Gesamtschleife zusaetzlich umschlossen, damit ein Fehler in der Iterationslogik
// den Driver nicht beendet:
try { … } catch (Throwable t) { driverErrors++; }
```

**Death-Spiral-Schutz** ist die Essenz: `STEP` ohne Deckel erzeugt nach einem
2-Sekunden-GC-Pause 120 Ticks, die alle sofort laufen müssen, und produziert
dadurch eine weitere Pause. `maxCatchUpSteps` (Default 5) verhindert das;
`CLAMP` verwirft verpasste Ticks komplett, was für Game-Logik meist die
richtige Wahl ist (der Client interpoliert).

**Subscriptions** sind eine `CopyOnWriteArrayList<ActorRef>`: Anmelden und
Abmelden (selten, bei Raum-Start/Ende) erzeugen eine Kopie (bis zu ~1000
Einträge, unkritisch), die Iteration pro Tick (60 Hz × 1000) ist lock-frei.

**Tick-Zustellung als `tell`:** `Tick` ist eine ganz normale `USER`-Nachricht,
damit `TickActor` sie in seinem normalen `receive` sieht und keine Sonderbehandlung
nötig ist. Der Treiber-Thread hat keinen `CurrentActor`, `sender()` ist also
`null`.

### 6.12 TickActor

```java
public abstract class TickActor extends Actor {

    protected TickActor(TickDriver driver) { … }

    @Override protected Behavior start(ActorContext ctx) {
        subscription = driver.subscribe(ctx.self());
        return onStart(ctx);                     // Hook
    }
    @Override protected final Behavior receive(Object message, ActorContext ctx) {
        if (message instanceof Tick tick) return runTick(tick, ctx);
        return onMessage(message, ctx);
    }
    @Override protected void postStop(ExitReason reason, ActorContext ctx) {
        subscription.cancel();
        onStop(reason, ctx);
    }

    protected abstract void onTick(Tick tick, TickContext tc);
    protected Behavior onMessage(Object message, ActorContext ctx) { return Behavior.UNHANDLED; }
    protected Behavior onStart(ActorContext ctx) { return Behavior.NEXT; }
    protected void onStop(ExitReason reason, ActorContext ctx) { }
    protected int inboxBudget()    { return 64; }
    protected int backlogLimit()   { return 256; }
    protected void onLagged(int drained, int backlog) { }
    protected OutboundBuffer.Packer packer() { return OutboundBuffer.IDENTITY; }

    private Behavior runTick(Tick tick, ActorContext ctx) {
        int drained = ctx.drainInbox(inboxBudget());        // 1) Eingaben verarbeiten
        TickContext tc = new TickContext(tick, ctx, outbound);
        long t0 = System.nanoTime();
        onTick(tick, tc);                                    // 2) Simulation
        outbound.flush(packer());                            // 3) ein Paket pro Client
        tickNanos = System.nanoTime() - t0;                  // 4) messen
        int backlog = ctx.inboxSize();                       // 5) Lag prüfen
        if (backlog > 0 && backlog >= backlogLimit()) onLagged(drained, backlog);
        if (backlog > 0) tickBacklog = backlog;
        return Behavior.NEXT;
    }
}
```

**Reihenfolge ist die Spezifikation:** Eingaben → Simulation → Outbound → Messen.
Eingaben, die während der Simulation für den *nächsten* Tick eintreffen, bleiben im
Postfach und werden dort verarbeitet — das ist die gewünschte 1-Tick-Latenz.

**`drainInbox` ist reentrant.** Der Zell-Loop hat genau eine Nachricht entnommen
(`Tick`); `drainInbox` entnimmt bis `budget` weitere und dispatcht sie über
denselben Pfad. `ActorCell` sichert dabei `current` (den aktuellen Envelope) auf
einem kleinen Stack, damit `reply`/`sender`/`currentMessage` auch für
verschachtelte Dispatches korrekt sind. Verschachtelung ist auf die Budget-Tiefe
begrenzt.

**Was `onLagged` in der Praxis tut** (Spielcode, nicht Framework):

```java
@Override protected void onLagged(int drained, int backlog) {
    if (backlog > 512) {
        // Spieler kicken: Absender der ältesten Mailbox-Einträge hat keine Chance mehr
        ctx.stop(ExitReason.failure(new OverloadedException(backlog)));
    }
}
```

Der Rahmen stellt zusätzlich die `MailboxOverflow.FAIL`-Exception bereit, die der
Absender (Lobby/Netzschicht) sieht — damit kann der Spieler schon im Sender
weggeschickt werden, nicht erst im Raum.

### 6.13 OutboundBuffer

Ziel: aus 40 `send(client, delta)`-Aufrufen pro Client wird **ein** Paket pro
Client pro Tick.

```java
public final class OutboundBuffer {
    private final Map<ActorRef, Outbox> clients = new LinkedHashMap<>();

    public void send(ActorRef client, Object packet) {
        clients.computeIfAbsent(client, k -> new Outbox()).packets.add(packet);
    }
    public void sendAll(Iterable<? extends ActorRef> targets, Object packet) { … }
    public int flush(Packer packer) {              // liefert Anzahl geflushte Clients
        int flushed = 0;
        for (Map.Entry<ActorRef, Outbox> e : clients.entrySet()) {
            Outbox o = e.getValue();
            if (o.packets.isEmpty()) continue;
            List<Object> copy = List.copyOf(o.packets);       // unveränderlich weitergeben
            o.packets.clear();
            e.getKey().tell(new Outbound(copy, packer.pack(copy, o.payload)));
            flushed++;
        }
        clients.clear();                          // nur die in diesem Tick aktiven Clients
        return flushed;
    }
    @FunctionalInterface public interface Packer { Object pack(List<Object> queued, Object scratch); }
}
```

**Zustellung an `Session`:** `Outbound` (`record Outbound(List<Object> packets,
Object payload)`) geht per `tell` an die Session; deren `receive` erkennt
`Outbound` und ruft `client.send(payload)` auf. Pro Client und Tick entsteht
genau **eine** Nachricht in der Session-Mailbox, unabhängig von der Anzahl der
`send`-Aufrufe.

**`payload`-Recycling:** `packer.pack(queued, scratch)` bekommt den letzten
Payload dieses Clients, damit ein Encoder seinen `ByteBuffer` wiederverwenden
kann (`payload`-Feld in `Outbox`). Das ist der Grund für den `scratch`-Parameter
und der eigentliche Performance-Gewinn: **ein** `ByteBuffer` pro Client pro
JVM-Lebensdauer statt eines pro Paket.

**Was der Spieler bestimmt:** Das `Packer`-Interface. Identität
(`OutboundBuffer.IDENTITY`) ist der Default; ein Beispiel-Encoder schreibt
Längenpräfix + Payload in einen `ByteBuffer` (s. `examples/`).

### 6.14 InterestSet

Ziel: kein O(N²)-Broadcast. Pro Tick: für jeden Spieler alle Interessenten in
Sichtweite finden. Bei 500 Spielern wären das 250 000 Distanzprüfungen pro Tick
(15 M/s bei 60 Hz) — zu viel. Ein Uniform Grid mit Zellgröße = maximaler
Interessenradius bringt O(N).

```java
public final class InterestSet {
    private final double cellSize;
    private final int dimension;                                    // 2 oder 3
    private final Map<Long, List<ActorRef>> cells = new HashMap<>();
    private final Map<ActorRef, Interest> areas = new HashMap<>();

    public void put(ActorRef ref, Interest interest);
    public void move(ActorRef ref, Interest interest);              // Index neu setzen
    public void remove(ActorRef ref);
    public int near(Interest area, List<ActorRef> out);             // füllt out, allokationsarm
    public int size();
    public void clear();
}
```

- Zell-Schlüssel: `(cx, cy, cz)` in einen `long` gepackt (`cx | cy<<21 | cz<<42`).
- `near` besucht die Zellen im um `ceil(radius / cellSize)` erweiterten Würfel
  und filtert mit `Interest.contains` (echte Distanzprüfung, keine
  Zell-Approximation — Sichtbarkeit muss exakt sein, sonst sieht ein Spieler
  Gegner durch Wände).
- `out` wird vom Aufrufer wiederverwendet → keine Allokation pro Query.
- Alle Strukturen werden **nur aus dem Raum-Thread** mutiert (der Raum besitzt
  sie). Keine Synchronisation nötig, und `near` kann gefahrlos parallel zu
  `tell`-Aufrufen laufen, weil der Raum-Thread der einzige Schreiber ist.

`Interest.UNBOUNDED` (Radius `Double.MAX_VALUE`) deaktiviert das Filtern für
Instanzen, die global publizieren.

---

## 7. Öffentliche API-Referenz

### 7.1 `Actor`

```java
public abstract class Actor {

    /** Einmalig, im Actor-Thread. Default: keine Vorbedingungen. */
    protected Behavior start(ActorContext ctx) { return Behavior.NEXT; }

    /** Pro Nachricht. Rückgabe: Behavior (become) oder NONE/UNHANDLED/HALT. */
    protected Behavior receive(Object message, ActorContext ctx) { return Behavior.UNHANDLED; }

    /** Keine Klausel passte. Default: stillschweigend verworfen. */
    protected void onUnhandled(Object message, ActorContext ctx) { }

    /** Nach dem letzten Handler, vor der Signal-Propagation. */
    protected void postStop(ExitReason reason, ActorContext ctx) { }
}
```

### 7.2 `Behavior`

```java
@FunctionalInterface
public interface Behavior {

    /** Sentinel: aktuelles Behavior beibehalten (BEAM `noreply`). */
    Behavior NEXT      = …;

    /** Sentinel: keine Klausel passte; nächste Behavior-Stufe versuchen. */
    Behavior UNHANDLED = …;

    /** Sentinel: Actor mit ExitReason.normal() beenden. */
    Behavior HALT      = …;

    Behavior invoke(Object message, ActorContext ctx);
}
```

### 7.3 `Receive`

```java
public final class Receive implements Behavior {

    public static Behavior of(Clause... clauses);
    public static Behavior of(List<Clause> clauses);
    public static Behavior empty();
    public List<Clause> clauses();

    @FunctionalInterface public interface Body      { Behavior apply(Object message, ActorContext ctx); }
    @FunctionalInterface public interface TypedBody<T> { Behavior apply(T message, ActorContext ctx); }

    public static final class Clause {
        public static Clause of(Class<?> type, Body body);
        public static <T> Clause on(Class<T> type, TypedBody<T> body);   // typisierte Body
        public static Clause any(Body body);
        public static Clause when(Predicate<Object> guard, Body body);
        public Clause and(Predicate<Object> additionalGuard);
        public boolean matches(Object message, ActorContext ctx);
        public Behavior apply(Object message, ActorContext ctx);
    }
}
```

**Semantik:** `Receive.invoke` probiert die Klauseln **in Reihenfolge**. Die
erste passende Klausel gewinnt; passt keine, kommt `UNHANDLED` zurück. Ein
Guard, der `false` liefert, gilt als **nicht passend** (BEAM-Klauseln mit
Guards verhalten sich so).

**Drei Schreibweisen, alle äquivalent:**

```java
// (a) Pattern-Switch — kompakt, verlangt Compiler-Unterstützung für Preview-Muster
switch (msg) {
    case Ping ignored        -> ctx.reply("pong");
    case Deposit d when d.amount() > 0 -> ctx.reply(accept(d));
    case Deposit d           -> ctx.reply(reject(d));
    default                  -> Behavior.UNHANDLED;
}

// (b) Klauselliste — deklarativ, kein Pattern-Matching nötig, Guards eingebaut
Receive.of(
    Clause.on(Ping.class,    (Ping p,   ActorContext c) -> c.reply("pong")),
    Clause.when(m -> m instanceof Deposit d && d.amount() > 0,
                             (Object m, ActorContext c) -> c.reply(accept((Deposit) m))),
    Clause.on(Deposit.class, (Deposit d, ActorContext c) -> c.reply(reject(d))),
    Clause.any(              (Object m, ActorContext c) -> c.reply("unbekannt: " + m))
);

// (c) Zustandsmaschine via become
case String s -> ctx.become(parsing);
```

### 7.4 `ActorRef`

```java
public interface ActorRef {

    /** Asynchron senden (BEAM {@code !}). Wirft NIE, auch nicht bei vollem Postfach. */
    void tell(Object message);
    void tell(Object message, ActorRef sender);

    /**
     * ANGENOMMEN? @return false, wenn das Postfach voll oder der Actor tot ist.
     * Nur hier erfährt der Absender, dass sein Client hängt — Netzschicht und
     * TickDriver nutzen das, um gezielt zu kicken statt eine Exception zu werfen.
     */
    boolean tryTell(Object message);

    /** Anfrage mit Antwort; Result Future wird im Thread des Aufrufer-Actors erfüllt. */
    CompletableFuture<Object> ask(Object message, Duration timeout);
    default <T> CompletableFuture<T> askAs(Class<T> type, Object message, Duration timeout) {
        return ask(message, timeout).thenApply(type::cast);
    }

    String name();                    // registrierter Name oder "~<id>"
    ActorPath path();                 // aero://<system>/<name>
    boolean isAlive();
    boolean isTerminated();
    int mailboxSize();                // primäres Lag-Signal
    java.util.Optional<ExitReason> exitReason();
}
```

### 7.5 `ActorContext`

```java
public interface ActorContext {

    ActorRef self();
    ActorRef sender();                          // null außerhalb ask/tell von außen
    Object currentMessage();
    Behavior currentBehavior();
    String path();
    ActorSystem system();

    Behavior reply(Object value);               // beantwortet ask ODER tell an sender
    Behavior forward(ActorRef target);          // Nachricht + Absender weiterleiten
    Behavior become(Behavior next);
    Behavior onError(Runnable)                  // Fehlerbehandlung ohne try/catch im Handler
            { return Behavior.NEXT; }

    Behavior stop();                            // graceful: Drain
    Behavior stop(ExitReason reason);

    ActorRef spawn(Supplier<ActorRef> starter, String name);   // Link, non-trapping (BEAM spawn_link)
    ActorRef spawnUnlinked(Actor actor, String name);
    <T> CompletableFuture<T> monitor(ActorRef other);           // DOWN
    Behavior link(ActorRef other);
    Behavior unlink(ActorRef other);
    Behavior linkTrapping(ActorRef other);                     // sieht jedes Exit

    <T> CompletableFuture<T> runBlocking(Callable<T> work);    // Offload, s. 6.9

    int drainInbox(int maxMessages);             // für Tick-Budget, reentrant
    int inboxSize();
}
```

### 7.6 `ActorSystem`

```java
public final class ActorSystem implements AutoCloseable {

    public static ActorSystem create(String name);

    public ActorRef spawn(Actor actor);                          // anonym
    public ActorRef spawn(Actor actor, String name);             // registriert, badarg bei Duplikat
    public ActorRef spawn(Supplier<ActorRef> starter, String name);
    public ActorRef spawn(Actor actor, String name, Mailbox mailbox);
    public ActorRef spawnLinked(Actor actor, String name);
    public ActorRef spawn(Actor actor, String name, ActorRef linkTo);

    public Optional<ActorRef> whereIs(String name);
    public List<ActorRef> actors();
    public int actorCount();

    public <T> CompletableFuture<T> monitor(ActorRef ref);
    public void schedule(Runnable task, Duration delay);         // ask-Timeouts
    public void executeBlocking(Runnable task);                  // runBlocking-Pool

    public void shutdown();                                      // Default-Timeout 10 s
    public void shutdown(Duration timeout);
    public boolean awaitTermination(Duration timeout);
    @Override public void close();                               // = shutdown()
    public boolean isRunning();
    public String name();
}
```

### 7.7 `ExitReason`

```java
public sealed interface ExitReason {
    boolean abnormal();                                 // Failure | Terminated

    record Normal()     implements ExitReason {}         // :normal
    record Shutdown()   implements ExitReason {}         // :shutdown
    record Failure(Throwable cause) implements ExitReason {}
    record Terminated() implements ExitReason {}         // :killed

    static ExitReason normal();
    static ExitReason shutdown();
    static ExitReason failure(Throwable cause);
    static ExitReason terminated();
    static ExitReason of(Throwable t);                   // unchecked → Failure, sonst Normal
}
```

---

## 8. Spiel-Integration: Leitbeispiel

```
ActorSystem "gameserver-1"
├── UserSupervisor (implizit, ONE_FOR_ONE)
│   ├── Lobby (TickActor, 20 Hz)
│   │   ├── Supervisor "lobby-sessions"
│   │   └── MatchmakerWorker × 4
│   ├── Room-1 (TickActor, 60 Hz)
│   │   ├── Supervisor "room-1-sessions"
│   │   │   ├── Session(player-7)   → Client
│   │   │   ├── Session(player-12)  → Client
│   │   │   └── …
│   │   └── InterestSet (500 Spieler, Grid 32 m)
│   └── Room-2 …
```

```java
final class BattleRoom extends TickActor {

    private final List<Player>          players   = new ArrayList<>();
    private final Map<Long, Player>     byId      = new HashMap<>();
    private final InterestSet           interest  = new InterestSet(32.0);
    private final List<ActorRef>        scratch   = new ArrayList<>(512);
    private final Map<Long, ActorRef>   sessions  = new HashMap<>();
    private final AtomicInteger         kicks     = new AtomicInteger();

    BattleRoom(TickDriver driver) { super(driver); }

    @Override protected Behavior start(ActorContext ctx) {
        // Sessions supervised: ein Spieler-Crash darf den Raum nicht killen.
        Supervisor sessions = system().spawn(new SessionSupervisor(this), "room-1-sessions");
        ctx.link(sessions);
        return Behavior.NEXT;
    }

    @Override protected int inboxBudget()  { return 256; }
    @Override protected int backlogLimit() { return 512; }

    @Override protected void onLagged(int drained, int backlog) {
        if (backlog > 1024) ctx.stop(ExitReason.failure(new OverloadedException(backlog)));
    }

    @Override protected void onTick(Tick tick, TickContext tc) {
        Duration dt = tick.elapsed();

        for (Player p : players) {
            p.simulate(dt);                                       // 1) Simulation
            interest.move(p.session(), p.interest());              // 2) AoI-Index aktualisieren
        }

        for (Player p : players) {
            tc.outbound().send(p.session(), new StateDelta(p));   // 3) Outbound puffern

            interest.near(p.interest(), scratch);                  // 4) Sichtbarkeit
            for (ActorRef viewer : scratch) {
                Player q = sessionPlayer.get(viewer);
                if (q != null && q != p) tc.outbound().send(viewer, new Seen(p));
            }
        }
        // 5) flush() passiert automatisch am Tick-Ende → 1 Paket pro Client
    }

    @Override protected OutboundBuffer.Packer packer() { return GameProtocol::encode; }
}
```

```java
final class PlayerSession extends Session {
    private final long      playerId;
    private final CountDownLatch joined = new CountDownLatch(1);

    PlayerSession(long playerId, Client client) { super(client); this.playerId = playerId; }

    @Override protected Behavior onMessage(Object message, ActorContext ctx) {
        return switch (message) {
            case LoginRequest(String token) -> { ctx.runBlocking(() -> auth.verify(token))
                                                    .whenComplete((claims, err) -> {
                                                        if (err == null) joined.countDown();
                                                    });
                                                yield Behavior.NEXT; }
            case String raw when raw.startsWith("in:") -> {      // Input, coalesced
                inputBuffer.accept(playerId, raw.substring(3));
                yield Behavior.NEXT;
            }
            case Outbound o -> { yield client.send(o.payload()); }  // 1 Paket pro Tick
            default -> Behavior.UNHANDLED;
        };
    }
}
```

**Warum das trägt:**

- Ein Raum = ein Thread = 500 Spieler ohne einen einzigen Lock.
- Pro Spieler und Tick: ~40 Outbound-`send`s werden zu **1** Paket.
- Interessenverwaltung: 500² = 250 000 → ~4 000 Gitterzellen-Treffer.
- Ein Spieler-Crash → Supervisor startet die Session neu, der Raum läuft weiter.
- Ein Raum-Crash → `UserSupervisor` startet den Raum neu; die Lobby sieht den
  `Exit` und räumt die Buchung auf.
- Ein hängender Client → Mailbox wächst auf 512 → `onLagged` → Raum wirft ihn raus.

---

## 9. Build und Konfiguration

### 9.1 `pom.xml` (nur dieses Modul)

```xml
<properties>
    <maven.compiler.source>25</maven.compiler.source>
    <maven.compiler.target>25</maven.compiler.target>
    <junit.version>6.0.3</junit.version>
    <assertj.version>3.27.7</assertj.version>
    <surefire.version>3.5.6</surefire.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.junit</groupId>
            <artifactId>junit-bom</artifactId>
            <version>${junit.version}</version>
            <type>pom</type><scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Nur Test! Produktionscode: 0 Abhängigkeiten. -->
    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.junit.platform</groupId><artifactId>junit-platform-launcher</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>${assertj.version}</version><scope>test</scope></dependency>
</dependencies>

<build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire.version}</version>
</plugin></plugins></build>
```

Surefire wird explizit gepinnt, weil JUnit Platform 6 einen aktuellen Surefire
braucht und die Parent-Version nicht einsehbar ist.

### 9.2 Empfohlene JVM-Flags für den Spielserver

```
-XX:+UnlockExperimentalVMOptions
-XX:+EnableDynamicAgentLoading        # nur falls Byte-Code-Instrumentation genutzt wird
```

Nicht nötig: `-Xmx` ist die eigentliche Stellschraube, aber mit bounded
Mailboxes ist der Actor-Speicherbedarf vorhersagbar. `jdk.tracePinnedThreads=full`
ist die wichtigste Diagnoseoption: jeder `synchronized`-Block in einem Actor
Handler, der auf einen anderen Thread wartet, pinnnt den Carrier und macht die
ganze Actor-Laufzeit unbrauchbar.

### 9.3 Build-Blocker

Der Parent-POM (`dev.localsoul:Aero`, **eine Ebene über diesem Modul**) deklariert
in Zeile 30 eine Abhängigkeit auf `dev.localsoul:Actor` — also auf dieses Modul.
Beim Bau dieses Moduls erbt der Actor die Selbst-Referenz und Maven bricht beim
POM-Lesen ab:

```
[FATAL] 'dependencies.dependency.[dev.localsoul:Actor:1.0-SNAPSHOT]' for
        dev.localsoul:Actor:1.0-SNAPSHOT is referencing itself.
        @ dev.localsoul:Aero:1.0-SNAPSHOT, ../pom.xml, line 30
```

Das ist unabhängig vom Quellcode dieses Moduls. Lösungen:

| Option | Wirkung |
|---|---|
| **A** (empfohlen) | Parent-Zeile 30 entfernen oder als echtes Modul-Dependency deklarieren, das existiert. Einzeiler im Parent. |
| **B** | Parent in `~/.m2` installieren und `<relativePath/>` leer setzen. |
| **C** | Verifikation ohne Maven: `javac` + JUnit-Platform-Launcher direkt (siehe [15](#15-umsetzungsreihenfolge)). |

---

## 10. Teststrategie

Grundsatz: **jede Race Condition braucht einen Stress-Test.** Korrektheit der
MPSC-Queue, der Delta-Queue und des Park-Protokolls ist nicht durch Lesen
bewiesen, nur durch Last.

| Testklasse | Beweist |
|---|---|
| `DefaultMailboxTest` | FIFO pro Producer (4 Producer × 250 k), kein Verlust, keine Duplikate, `size()`, `capacity()`, `close()`, `take()` bei leerem Postfach, alle Overflow-Policies, `FAIL` ⇒ `tryTell` liefert `false`, **`BLOCK` + Self-Send ⇒ `IllegalStateException` statt Hänger**, **`isEmpty()` nie `true`, solange ein Slot reserviert aber noch nicht publiziert ist** (Nadelöhr-Test: Producer pausiert zwischen CAS und Store, Consumer darf nicht `null` liefern) |
| `TakeContractTest` | `take()` liefert `null` **nur** bei `close` + leer; 100 k Nachrichten mit 1 Producer, der zwischen CAS und Store `Thread.onSpinWait()` bekommt → kein vorzeitiges Actor-Ende |
| `LinkedMailboxTest` | unbounded, Thread-Sicherheit, unbeschränkt |
| `CoalescingMailboxTest` | „latest wins", Position bleibt erhalten, keine verlorenen distinct Keys, 4-Producer-Stress, Nicht-coalescable Nachrichten gehen in Reihenfolge durch, Schlüssel-Recycling, **`size()`/`isEmpty()` exakt auch nach Dublettenschlüsseln im FIFO (vergleiche mit der Anzahl tatsächlich zugestellter Nachrichten)** |
| `ActorLifecycleTest` | `start`/`receive`/`postStop`, `become`, `HALT`, `onUnhandled`, Registry (Name doppelt → `ActorNameTakenException`), `path()`, `whereIs`, Exit-Reason in `postStop` |
| `AskReplyTest` | Antwort, `sender()`, Timeout → `AskTimeoutException`, Late-Reply wird verworfen, Tod des Ziels → `ActorTerminatedException`, 1 000 parallele Asks, Callback läuft im Aufrufer-Thread, `askAs` |
| `LinkMonitorTest` | Link + abnormaler Tod → Peer stirbt, Link + `normal` → Peer lebt, trapping-Link sieht `normal`, `monitor` liefert `ExitReason`, `unlink`, `kill` |
| `ReceiveTest` | first-match-wins, Guards (nicht passend → nächste Klausel), `any`, `on` mit Typ, verschachteltes `become`, `UNHANDLED`-Kette |
| `SupervisorTest` | `ONE_FOR_ONE`/`ONE_FOR_ALL`/`REST_FOR_ONE` (über Observable-Nachrichten geprüft), `PERMANENT`/`TRANSIENT`/`TEMPORARY` je Reason, Intensität → Eskalation an Parent, Starter-Wurf → Eskalation, Name nach Restart belegt, `shutdownTimeout` |
| `SupervisorShutdownTest` | Stopp in umgekehrter Reihenfolge, alle Kinder beendet vor Supervisor-Ende, `onShutdown` |
| `ActorSystemShutdownTest` | `close()`, alle Actors tot, `awaitTermination`, Root-Supervisor gestoppt, Executor beendet, Idempotenz |
| `RunBlockingTest` | Ergebnis landet in der Mailbox des Actors, Callback im Actor-Thread, Ausnahme → Failure in der Mailbox, RejectedExecution |
| `TickDriverTest` | Tick-Rate im Rahmen, `CLAMP` verwirft verpasste Ticks, `STEP` holt gedeckelt auf, `overruns()` zählt, `tickCount()` monoton, `close()` stoppt, **ein Subscriber mit `FAIL`-Mailbox auf 1 Element: `deliverFailures()` steigt, der Driver läuft weiter, alle anderen Subscriber erhalten weiter Ticks**, **`ensureRunning()` nach simuliertem Thread-Tod ⇒ `restarts() == 1` und Ticks laufen weiter** |
| `TickActorTest` | `onTick` läuft pro Tick, `drainInbox` respektiert Budget, `onLagged` ab Backlog-Limit, `outbound.flush` genau einmal pro Tick, Reihenfolge Eingabe→Simulation→Outbound, `Subscription` wird bei Stopp gecancelt, **`drainInbox` verarbeitet bei Producer-Publish-Fenster trotzdem das volle Budget** |
| `TickActorTest` | `onTick` läuft pro Tick, `drainInbox` respektiert Budget, `onLagged` ab Backlog-Limit, `outbound.flush` genau einmal pro Tick, Reihenfolge Eingabe→Simulation→Outbound, `Subscription` wird bei Stopp gecancelt |
| `OutboundBufferTest` | Aggregation pro Client, ein `Outbound` pro Client pro Tick, `packer`-Aufruf mit `scratch`, Reihenfolge erhalten, `sendAll` |
| `InterestTest` | `contains`, `UNBOUNDED`, `near` findet alle und nur Sichtbare, `move` aktualisiert den Index (kein Duplikat), `remove`, allokationsarmes `out` |
| `SessionTest` | `Outbound` → `client.send`, `onMessage` für Fremdnachrichten, Stopp löst Interest-Bindung |

**Muster für asynchrone Assertions:** `Await` (Test-Helfer) mit
`awaitUntil(() -> …, Duration.ofSeconds(5))` statt `Thread.sleep`. Kein Test
darf von Zufall und Timing leben; Zeitmargen sind großzügig (Faktor 10 über der
erwarteten Dauer).

**Test-Zeitbudget:** < 30 s gesamt. Stress-Tests laufen mit festen
Nachrichtenzahlen, nicht mit Zeitfenstern.

---

## 11. Beispiele und Benchmark

| Datei | Zeigt |
|---|---|
| `examples/BattleRoomExample` | Vollständiger Raum: 60 Hz, 200 Spieler, Input-Coalescing, Interest-Broadcast, Outbound-Batching, Supervision, `main()` |
| `examples/SupervisedSessionExample` | `ONE_FOR_ONE` vs. `REST_FOR_ONE` an einem echten Raum, Eskalation bei Dauerfehler |
| `examples/OverloadKickExample` | Bounded Mailbox + `onLagged` → Spieler fliegt raus; zeigt `MailboxFullException` beim Absender |
| `examples/BlockingOffloadExample` | `runBlocking` für einen simulierten DB-Call im Login-Pfad, Rest der Simulation unbeeindruckt |
| `MailboxBenchmark` | 1/4/16 Producer-Threads, je 1 M Nachrichten: Durchsatz (M msg/s) und Latenz (p50/p99 gemessen im Consumer) für `DefaultMailbox`, `LinkedMailbox`, `CoalescingMailbox` |

Benchmark-Ausgabeformat:

```
DefaultMailbox   1 producer :   8.4 M msg/s   p50   0.3 us   p99   2.1 us
DefaultMailbox   4 producers:  11.9 M msg/s   p50   0.5 us   p99   3.8 us
CoalescingMailbox 4 producers:  9.1 M msg/s   (coalesced 812 k)
```

---

## 12. Performance-Leitregeln und Anti-Patterns

### 12.1 Regeln

1. **Handler dürfen nie blockieren.** Kein IO, kein `synchronized` auf
   fremde Locks, kein `Thread.sleep`, kein `Future.get()` auf einen anderen
   Actor. Ausnahme: `runBlocking`.
2. **`ask`/`CompletableFuture` nie im Per-Tick-Pfad.** `tell` ist eine
   volatil-ish CAS-Operation; `ask` allokiert ein `Call` + `Envelope` + Future
   + Timeout-Task.
3. **Unveränderliche Nachrichten** (`record`). Ein Actor teilt keinen Zustand;
   jede Nachricht ist eine Kopie. Kostet Allokation, kauft aber Eliminierung der
   gesamten Synchronisations-Diskussion.
4. **Kein geteilter veränderlicher Zustand.** Ein `static` Feld, das zwei Actors
   beschreiben, ist ein Datenrennen mit zusätzlichem Latenzrisiko.
5. **Coalescing für alles Frequente.** Input, Position, Velocity, Health-Bar.
6. **Bounded Mailbox mit bewusster Kapazität.** 1024 ist ein Startwert; die
   richtige Zahl ergibt sich aus „Wie viele Nachrichten verarbeitet der Actor
   pro Sekunde maximal, und wie lange darf ein Client maximal warten?".
7. **Batching outbound.** Alles pro Tick in `OutboundBuffer`, ein Paket pro Client.
8. **Skeptisch gegenüber `synchronized`.** `jdk.tracePinnedThreads=full` aktivieren.
   Ein gepinnter Carrier blockiert alle anderen Actors auf diesem Carrier.
9. **Nie `tell` mit `BLOCK`-Mailbox auf sich selbst.** `ctx.self().tell(…)` ist
   bei vollem eigenem Postfach ein Self-Deadlock (Präzisierung 2 in
   [16](#16-präzisierungen-aus-dem-review)). Das Framework wirft dafür
   `IllegalStateException` mit Hinweistext, aber die richtige Lösung ist eine
   andere: `ctx.stop()`, `ctx.become(…)`, oder Policy `FAIL` bzw.
   `CoalescingMailbox` verwenden.
10. **Nie zyklisches Blockieren mit `BLOCK`.** A wartet in seinem Handler auf ein
    `tell` an B, B gleichzeitig auf ein `tell` an A, beide Postfächer voll →
    A und B hängen für immer. Das ist **nicht** erkennbar (nur der Self-Send-Fall
    ist es), also gehört `BLOCK` ausschließlich auf Mailboxen, deren Absender
    garantiert nie auf den Empfänger warten.

### 12.2 Was der Framework bewusst NICHT tut

- **Keine Lock-Freiheits-Garantie im Benutzercode.** `Receive` ist single-threaded
  pro Actor, aber der Benutzer kann davon ausbrechen, indem er Zustand teilt.
- **Kein Work-Stealing zwischen Actors.** Ein Raum mit 500 Spielern, die alle
  10 % CPU nutzen, nutzt auch nur 10 % einer CPU. Entlastung: aufspalten in
  mehrere Räume, `runBlocking` nur für IO, oder den Raum als Supervisor mit
  spezialisierten Kindern.
- **Kein automatisches Sharding.** Zwei Räume in einem JVM konkurrieren um
  Carrier-Threads der JVM — das ist genau das, was die JVM-Scheduler gut kann.
  Räume gehören in verschiedene JVMs, wenn Skalierung gebraucht wird.

### 12.3 Erwartete Größenordnungen

| Operation | Kosten |
|---|---|
| `tell` in leere Mailbox | ~50–100 ns (eine CAS + ein Store) |
| `tell` mit Parkendem Consumer | ~150–400 ns (CAS + Unpark) |
| Handler-Aufruf (ohne Arbeit) | ~20 ns |
| `ask` + Antwort | ~1–2 µs (Future, Timeout-Task, 2 Envelopes) |
| `InterestSet.near` (500 Spieler, Grid 32 m) | ~2–8 µs |
| Tick mit 500 Spielern + Broadcast | ~1–4 ms |

---

## 13. Bewusste Abweichungen von BEAM/OTP

| Thema | BEAM/OTP | Aero::Actor | Warum |
|---|---|---|---|
| Unmatched message | bleibt im Postfach, `receive` wartet auf Selektiv-Empfang | wird verworfen, `onUnhandled` wird gerufen | Selektiv-Empfang erfordert, Nachrichten in der Box zu halten; das ist in Java ohne `receive`-Imperativ (mit `await`) nicht ausdrückbar. `onUnhandled` ist der Kompromiss. |
| Graceful Stop | Postfach wird **verworfen** | wird **gedraint** (`Terminate` am Ende) | Geordneter Shutdown soll Eingaben noch verarbeiten (Spieler sauber abmelden). Hard-Kill verwirft weiterhin. |
| `after` in `receive` | erstklassig | **nicht** vorhanden | Idle-Kick läuft über Tick-Zählung in der Session bzw. im Raum; ein wall-clock-Timeout wäre nicht replaybar. |
| `timer:sleep` | erlaubt (Yield) | verboten → `runBlocking` | Ein blockierender Handler friert den Raum ein. |
| Terminate-Signal | unbestätigt; `Postfach` verworfen | bestätigt, Zustand `TERMINATED` vor Signal-Propagation | Deterministisches `isAlive()`/`exitReason()` für Tests und Shutdown. |
| `mailbox_full` | `exit(Pid, {message_queue_data, full})` | `tryTell` liefert `false`; `ask` scheitert mit `MailboxFullException` | Kein Exception aus `tell`: ein volles Postfach darf keinen AbsenderThread töten — das ist beim `TickDriver` der Unterschied zwischen „ein Raum überlastet" und „alle Räume stehen" |
| `monitor` + `DOWN`-Nachricht | Nachricht in der Mailbox | `deathFuture` direkt | Keine Registrierungs-Race, keine zusätzliche Nachricht, Multi-Consumer gratis |
| Unbounded Mailbox | Default | Default **bounded 1024** | Hängender Client = Memory-Leak mit unbounded Amplitude. |
| `DROP_OLDEST` | n/a | nicht implementiert | Siehe [6.4](#64-mailbox-mpsc-array-queue); Aufgabe übernimmt `CoalescingMailbox`. |
| `ets`/`persistent_term` | Prozess-unabhängiger Zustand | **nicht vorhanden** | Widerspricht Single-Writer. Zustand gehört in einen Actor; für geteilten Zustand gibt es bewusst keinen Ersatz. |
| Code-Reload | `code_change/3` | nicht vorhanden | Kein Classloader-Management in einem Framework, das kein Hot-Deploy verspricht. |
| `ActorRef` | `pid()` vs. `{name,node}` | ein Interface | Erlaubt später Remote-Refs, ohne die API zu brechen. |
| Supervision von `spawn` | `spawn_link` = `trap_exit=false` | identisch | Absicht: Wer `ctx.spawn` nutzt, bekommt BEAM-Semantik. Für Restart braucht es `Supervisor`. |
| Deterministische Zeit | `erlang:monotonic_time`, `timestamp()` | `Tick.number()`, `Tick.elapsed()` | Spiellogik soll `System.nanoTime()` nie sehen; der Tick zählt. |

---

## 16. Präzisierungen aus dem Review

Fünf Beanstandungen an der ersten Fassung dieses Dokuments, mit Entscheidung.
Der jeweilige Ort im Text ist jeweils verlinkt.

### 16.1 `poll()`-`null` vs. `take()`-`null` — **spezifiziert**

**Problem.** `poll()` liefert nach begrenztem Spin `null`, auch wenn ein Producer
den Slot reserviert hat und den Wert erst noch publiziert. Der Empfänger-Loop
las `take() == null` aber als „Mailbox geschlossen oder Interrupt" und brach die
Schleife ab → ein Actor könnte sich beenden, während ein Producer gerade
schreibt.

**Entscheidung.** Drei Operationen mit drei verschiedenen Zusicherungen
([6.4](#64-mailbox-mpsc-array-queue)):

| | Zusicherung |
|---|---|
| `offer` | nie blockiert (außer `BLOCK`), nie wirft; `false` = nicht angenommen |
| `poll` | **verlustbehaftet**: `null` ⇏ leer. Nur mit anschließendem `isEmpty()`-Check als Abbruchbedingung verwenden |
| `take` | **verlustfrei**: `null` ⟹ `closed ∧ producerIndex == consumerIndex` |
| `isEmpty` | exakt aus den Indizes, nie aus `poll()` abgeleitet |

Weil ein reservierter Slot den Producer-Index schon erhöht hat, ist
`isEmpty()` im Publizierungsfenster `false`, und `take()` kann nicht
verfrüht `null` liefern. `drainInbox` bricht deshalb auf `isEmpty()` ab, nicht auf
`poll() == null`. Zusätzlicher Test `TakeContractTest` mit bewusst langsamem
Producer (Spin zwischen CAS und Store).

### 16.2 `BLOCK` + Self-Send — **verboten und erkannt**

**Problem.** `ctx.self().tell(…)` auf eine volle `BLOCK`-Mailbox: der einzige
Consumer-Thread wartet auf sich selbst. Hang statt Fehler.

**Entscheidung.** `DefaultMailbox` kennt seinen Besitzer (`self`) und wirft
`IllegalStateException` mit Handlungsanweisung, wenn der Absender der Besitzer
ist. Zusätzlich Anti-Pattern 9 und 10 in
[12.1](#121-regeln): Self-Send mit `BLOCK` sowie zyklisches Blockieren sind
verboten, weil letzteres **nicht** erkennbar ist. Empfohlene Ersatzlösungen:
`ctx.stop()`, `ctx.become()`, Policy `FAIL`, `CoalescingMailbox`.

### 16.3 TickDriver-Isolation — `tryTell` + `try/catch` je Subscriber

**Problem.** `room.tell(tick)` mit voller `FAIL`-Mailbox. Würfe der
`MailboxFullException` in die Treiber-Schleife, stürzt der `TickDriver`-Thread
ab — und damit die Ticks **aller** Räume.

**Entscheidung.** Drei Maßnahmen ([6.11](#611-tickdriver)):

1. `offer`/`tell` **wirft nie**; der Treiber nutzt `tryTell` und zählt
   `deliverFailures()`. Der Raum selbst reagiert über `onLagged` — die richtige
   Stelle, weil der Raum auch weiß, *welcher* Spieler hängt.
2. `try/catch` je Subscriber isoliert.
3. Top-Level-`try/catch` um die gesamte Schleife; Fehler werden gezählt
   (`driverErrors()`), nicht propagiert.

Der Treiber schweigt bewusst über Läufer — 60 000 Exceptions/s bei 1000 Räumen
wäre schlimmer als das Problem.

### 16.4 `CoalescingMailbox.size()` — exakter Zähler

**Problem.** Ein Key kann nach einem verlorenen CAS zweimal in der FIFO liegen.
`order.size()` zählt damit Queue-Einträge, nicht logische Nachrichten, und
`mailboxSize()` wäre als Lag-Signal zu ungenau (falscher Alarm).

**Entscheidung.** Zweiter Zähler `occupied` (belegte Slots), inkrementiert beim
Fresh-CAS, dekrementiert beim Release-CAS. `size()` und `isEmpty()` rechnen
darauf; Kosten ca. 5 ns pro coalescbarer Nachricht. `mailboxSize()` ist damit
auch für diese Mailbox exakt ([6.6](#66-coalescingmailbox-als-delta-queue)).
Für `DefaultMailbox` bleibt `size()` eine obere Schätzung (reservierte Slots
mitgezählt) — in der für Backpressure sicheren Richtung.

### 16.5 Kein Watchdog für den `TickDriver` — **ergänzt**

**Problem.** Der `TickDriver` ist ein Platform Thread ohne OTP-Supervision. Ein
unbehandelter Fehler dort (siehe 16.3) hätte keinen Restart-Mechanismus und
würde den Spielserver stillstehen lassen — ein Widerspruch zur sonst
durchgehenden Supervision.

**Entscheidung.** Der Pump-Thread wird **zustandsbehaftet im `TickDriver`-Objekt
gehalten** (Zähler + `thread`-Feld), `ensureRunning()` startet ihn bei Bedarf neu.
Ein zweiter Daemon-Thread (Watchdog, 1 Hz) prüft Liveness **und** Stall
(`lastTickAtNanos` älter als `5 × interval` → Stall zählen, Zeitstempel
zurücksetzen, damit der Driver weiterläuft) und ruft `ensureRunning()` auf.
Beide Threads schlafen 99 % der Zeit. Bewusst *kein* Actor über dem Thread: die
Supervision eines Threads durch einen Actor wäre mehr Infrastruktur als der
Thread selbst. Sichtbar über `restarts()` und `stalls()`.

**Nicht abgedeckt:** Ein Driver, der *lebt* und regelmäßig falsche Ticks
liefert (Logik-Bug) — dafür ist ein Assert im Spielcode zuständig, nicht die
Laufzeit.

### 16.6 Nebenbefund: `monitor` ohne `DOWN`-Nachricht

Ursprünglich war ein `Signals.Down` plus Monitor-Registry vorgesehen. Das ist
durch die `deathFuture` der Ziel-Zelle ersetzt: keine Registrierungs-Race,
keine zusätzliche Nachricht, `CompletableFuture` ist von Natur aus
Multi-Consumer, und `join` wird zum Einzeiler. Details in
[6.8](#68-links-monitore-exit-propagation).

### 16.7 Session/Tick-Umsetzung — drei Präzisierungen

Die Umsetzung des Session-/Tick-Pfads (6.11–6.14) hat drei Stellen präzisiert,
die das Ideal-Pseudocode nicht vorhergesehen hatte. Alle drei sind durch Tests
abgesichert.

**a) `drainInbox` darf Ticks nie verschachtelt dispatchen.** Der reentrante
Drain entnimmt bis `budget` Nachrichten *vor* der Simulation. Läge dort ein
`Tick`, würde er **innerhalb** des laufenden Ticks dispatcht — die Simulation
liefe zweimal pro Takt und die Tick-Reihenfolge (1, 2, 4, 3, 1 …) ginge
durcheinander. Lösung: `Actor.deferDuringDrain(Object)` (Default `false`),
`TickActor` meldet `Tick` an. Die Zelle stellt solche Nachrichten in eine
kleine `deferred`-Queue und der Dispatch-Loop nimmt sie *vor* dem Postfach
wieder auf — Reihenfolge bleibt streng FIFO. Kostet nichts für Nicht-Tick-Actors.

**b) Outbound-`scratch` überlebt den Flush.** Das `Outbox.payload`-Feld im
Pseudocode (6.13) widerspricht seiner eigenen Prosa: „Payload-Recycling — der
Payload des *vorigen* Ticks desselben Clients". Liegt der Scratch im Register,
ist er nach `flush()` weg. Die Umsetzung hält deshalb eine persistente
`scratch`-Map je Client, die `flush()` übersteht; `clear()` leert beides.
Außerdem liefert `flush` einen `tryTell` (nicht `tell`) — konsistent mit 16.3:
ein volles Session-Postfach zählt als `deliverFailures` statt den Raum zu
zerlegen.

**c) `InterestSet` indiziert nach Zentrum, nicht nach AABB.** Die Zellzuordnung
erfolgt über den *Mittelpunkt* eines Interesses, weil `Interest.contains(Interest)`
Sichtbarkeit über Positionen entscheidet (nicht über Volumenüberlappung). Die
Abfrage läuft alle Zellen vom Min- zum Max-Eck des Query-Quaders durch und
filtert exakt. Interessen mit `radius > cellSize` passen in keine sinnvolle
Zelle und stehen in einer `oversized`-Liste, die jede Query mitprüft — im
vorgesehenen Betrieb (`forRadius(maxRadius)`) ist sie leer. Das verhindert
Verluste bei beliebigen Zellgrößen. `near(UNBOUNDED)` liefert alle Einträge
ohne Duplikate (die `globals`-Liste ist Teil von `areas`).

---

## 14. Build-Blocker: Parent-POM

Siehe [9.3](#93-build-blocker). Der Fehler ist im Parent, nicht im Modul, und
tritt bereits vor dem Kompilieren auf.

---

## 15. Umsetzungsreihenfolge

| Schritt | Inhalt | Abhängig von |
|---|---|---|
| 1 | `pom.xml` (Test-Deps, Surefire-Pin) | — |
| 2 | Werttypen: `ExitReason`, `ActorPath`, `Behavior`, Exceptions | 1 |
| 3 | `internal`: `MessageKind`, `Envelope`, `Signals`, `Call`, `CurrentActor`, `NameRegistry` | 2 |
| 4 | `internal`: `MpscQueue`, `Wakeup` | 2 |
| 5 | Mailbox-Layer: `Mailbox`, `DefaultMailbox`, `LinkedMailbox`, `CoalescingMailbox`, `MailboxOverflow`, `Mailboxes` | 3,4 |
| 6 | `ActorRef`, `LocalActorRef` | 3,5 |
| 7 | `ActorContext`, `ActorCell`, `Actor`, `ActorSystem` | 6 |
| 8 | `Receive` (+ `Clause`) | 2,7 |
| 9 | `supervision/*` | 7 |
| 10 | `tick/*` | 7 |
| 11 | `session/*` | 7,10 |
| 12 | Tests Kernel | 8,9 |
| 13 | Tests Game-Layer | 10,11 |
| 14 | Beispiele + Benchmark | 12,13 |
| 15 | Verifikation | alles |

**Zwischenverifikation** nach jedem Schritt: Kompilieren. Bei Maven-Blocker
[9.3](#93-build-blocker):

```bash
# im Modulverzeichnis, ohne den Parent
mkdir -p target/classes target/test-classes
javac -d target/classes $(find src/main/java -name '*.java')
CP=target/classes:$(ls ~/.m2/repository/org/junit/jupiter/*/6.0.3/*.jar \
                     ~/.m2/repository/org/junit/platform/*/6.0.3/*.jar \
                     ~/.m2/repository/org/opentest4j/opentest4j/*/*.jar \
                     ~/.m2/repository/org/apiguardian/apiguardian-api/*/*.jar \
                     ~/.m2/repository/org/assertj/assertj-core/3.27.7/assertj-core-3.27.7.jar \
                     | grep -v sources | tr '\n' ':')
javac -cp "$CP" -d target/test-classes $(find src/test/java -name '*.java')
# Tests über den JUnit-Platform-Launcher starten (siehe Test-Runner)
java -cp "$CP:target/test-classes" …
```
