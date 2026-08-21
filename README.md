# SETU

**Offline mesh SOS relay for signal-dead zones.**
Hacks 11.0 — Problem Statement #15, Disaster Management.

*Setu* (सेतु) is Sanskrit for **bridge**. Every phone running SETU is one span of it.

---

## The problem

> During severe floods, cyclones, or earthquakes, mobile cell towers frequently collapse, leaving
> stranded victims unable to send emergency SOS requests even when rescue teams are nearby.

When the tower dies, so does every app on the phone. But the phone still has two radios that
never needed a tower: Bluetooth Low Energy and Wi-Fi Direct. In a flooded apartment block there
are dozens of phones within a hundred metres of each other, and at least one of them is usually
close enough to something with a working uplink.

The devices are already there. Nothing is using them.

## The thesis

Most attempts at this build a chat app and put a red button on it. That misreads the problem.

> **A disaster mesh is not a bandwidth problem. It is an energy-allocation problem.**
> Every byte forwarded costs joules that a stranded person may need in order to stay reachable.
> **SETU routes battery, not packets.**

Everything below falls out of that one sentence — including the answer to the challenge question,
which is not a feature bolted on at the end but the reason the protocol is shaped the way it is.

---

## The core idea: multi-hop SOS that never opens a connection

A complete, routable SOS is **24 bytes** — exactly the usable payload of a BLE *legacy*
advertisement:

```
  31 bytes   AD payload in a legacy advertisement
 −  3 bytes  Flags AD structure          (length + type + data)
 −  4 bytes  Service Data AD structure   (length + type + 16-bit UUID)
 ─────────
 = 24 bytes  usable  ← the entire SETU beacon
```

Relaying is *re-advertising*. A node hears a beacon, decrements its TTL, and puts it back on the
air. No connection. No pairing. No handshake. No GATT. The sender never learns the relay exists.

This matters because connectionless broadcast is the cheapest thing a phone radio can do.
Published measurements on BLE silicon put continuous scanning near **40 mW** against roughly
**600 µW** for advertising — about two orders of magnitude apart. A protocol whose base case is
*broadcast* rather than *connect* is the difference between a mesh that dies with the first flat
battery and one that keeps working for hours.

Connections still exist, for rich bundles (a free-text note, the hop chain, anti-entropy
digests). They are opened only by nodes that can currently afford them.

### What fits in 24 bytes

| Off | Size | Field | Notes |
|----:|-----:|-------|-------|
| 0 | 1 | `verType` | 3b version, 3b message type, 2b reserved |
| 1 | 1 | `ttlHops` | 4b TTL (0–15), 4b hops travelled |
| 2 | 4 | `msgId` | dedup key |
| 6 | 3 | `originId` | 24-bit node id |
| 9 | 4 | `lat` | int32, degrees × 1e7 |
| 13 | 4 | `lon` | int32, degrees × 1e7 |
| 17 | 3 | `epochMin` | minutes since 2024-01-01Z — also the mesh's shared clock |
| 20 | 1 | `flags` | 2b severity + medical / trapped / water-rising / vulnerable / mobility |
| 21 | 1 | `souls` | how many people are at this location |
| 22 | 1 | `battery` | **originator's battery %** — see below |
| 23 | 1 | `crc8` | CRC-8/ATM over bytes 0–22 |

That `battery` byte is the cheapest thing in the protocol and it pays for two separate
optimisations. See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the full wire format.

---

## The challenge question

> **How does the relay chain keep working when every device in it is running on a nearly-dead
> battery?**

Six mechanisms. Each one is code in [`core/`](core/), not a slide.

### 1. Drop listening before you drop shouting

Scanning costs ~100× what advertising costs, so the power ladder does not turn one dial down
uniformly — it removes *scanning* first and protects *advertising* to the very end.

| Tier | Battery | Behaviour |
|------|---------|-----------|
| **BRIDGE** | >60% or charging | continuous scan, GATT server up, carries the neighbourhood |
| **RELAY** | 30–60% | scan ~30% duty, connects on demand |
| **GOSSIP** | 15–30% | beacons at 1 Hz, listens 10% inside sync windows, stops paying for connections |
| **FLARE** | 5–15% | advertise only, ears open one second per minute |
| **EMBER** | <5% | beacon every 10 s, **never scans** |

An EMBER node is deliberately *deaf*. It is still shouting, and it is still findable. A dying
phone should be selfish, and SETU encodes exactly when that switch flips.

### 2. Phase-locked rendezvous — why low duty cycles don't silently kill the mesh

This is the failure mode nobody demos. Two nodes each listening 5% of the time, with independent
phase, overlap **0.25%** of the time. They will essentially never hear each other, the network is
dead, and both phones cheerfully report that everything is fine.

SETU derives every wake window from **absolute wall-clock time**, not from each node's uptime:

```
epoch  = floor(unixMillis / 60_000)
window = [epoch × 60_000, +1_000ms)
```

Every node in the region therefore wakes inside the *same one-second window* without ever having
exchanged a scheduling message. Low tiers skip whole epochs but stay phase-aligned
(`epoch % n == 0`), so an EMBER node waking once every four minutes still lands exactly on a
window a BRIDGE node is listening through.

Clock quality degrades gracefully: GPS time → last NTP sync → consensus drift correction from the
`epochMin` field that every beacon already carries.

*Bonus:* one scan per 60 s epoch also keeps SETU inside Android's hard limit of **5 `startScan`
calls per 30 seconds** — a throttle that silently breaks naive duty-cycling implementations.

### 3. Zero-message scanner election

In any neighbourhood we want few listeners and many shouters. The obvious way to arrange that is
to negotiate roles — but negotiation traffic costs exactly the energy we are trying to save, and
it fails precisely when the network is degraded.

Every beacon already carries the sender's battery level. That one byte is enough: every node sees
the same neighbourhood table, applies the same deterministic ranking, and independently reaches
the same answer. The top `ceil(√n)` elect themselves scanners for the next epoch.

**Zero coordination messages.** Battery is bucketed into coarse 10% bands and the tiebreak is
mixed with the epoch number, so the duty *rotates* instead of draining the one best-charged
phone. Under partition, each partition simply elects its own scanners.

### 4. Effort flows toward whoever is worse off

The DTN literature says route copies toward higher-energy nodes. SETU inverts it into something
that is both energy-optimal and ethically right: **relay preferentially for people worse off than
you.** If the originator has more battery than you do and is not critical, damp hard — they can
afford to keep shouting for themselves; you may not be able to.

A node **always** relays its own SOS. No gate applies, at any battery level.

### 5. Delivery confirmation *is* an energy optimisation

When a gateway takes an SOS, a `RECEIPT` propagates back through the mesh and every node holding
that message **drops it**. Same for `SAFE`. Confirmation is not a nicety here — it is how the
mesh reclaims airtime and buffer from messages that no longer need moving.

### 6. Last-gasp flush

Below 3% battery, conservation is pointless — the phone is going to die either way. SETU stops
conserving and spends the remainder burst-advertising everything it is carrying at 250 ms
intervals, so a healthier neighbour picks up custody before the lights go out.

> **Your phone dying is not your SOS dying.**

### Backed by an energy ledger, not an assertion

Every radio operation is billed in estimated mAh, so the app can state plainly: *"SETU used 1.8%
of your battery in 3 hours and carried 47 messages for 12 people."* An app that quietly drains a
phone gets uninstalled before the disaster, and a mesh with no nodes relays nothing — so
transparency is a survival feature.

The default cost constants in [`EnergyLedger.kt`](core/src/main/kotlin/com/setu/mesh/core/power/EnergyLedger.kt)
are **order-of-magnitude estimates, not measurements**. See
[`docs/POWER.md`](docs/POWER.md) for what has actually been measured on the demo handsets and
what has not. We would rather show a smaller verified number than a bigger invented one.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  APPLICATION      SOS screen · Responder map · Mesh Lab      │   :app
├──────────────────────────────────────────────────────────────┤
│  ENGINE           MeshNode — origination, relay, snapshot    │
├──────────────────────────────────────────────────────────────┤
│  ROUTING          SeenSet · ForwardingPolicy · Outbox        │   :core
├──────────────────────────────────────────────────────────────┤
│  POWER GOVERNOR   PowerTier · Rendezvous · ScannerElection   │   ← the differentiator
├──────────────────────────────────────────────────────────────┤
│  LINK  (the seam) ─── opaque bytes, nothing above knows why  │
├───────────────────────────┬──────────────────────────────────┤
│  AndroidLink  (real BLE)  │  SimLink  (200 virtual nodes)    │   :app / :sim
└───────────────────────────┴──────────────────────────────────┘
```

Three Gradle modules, **one APK, zero servers, no cloud, no accounts**:

| Module | Language | Depends on Android? | What it is |
|--------|----------|---------------------|------------|
| `core/` | Kotlin/JVM | **no** | the protocol: codec, routing, power governor |
| `sim/` | Kotlin/JVM | **no** | virtual mesh + battery model, drives `core` |
| `app/` | Kotlin/Android | yes | BLE transport, foreground service, Compose UI |

### Why `core` is a plain JVM module

Two reasons, both load-bearing:

1. **The scale claim is honest.** The simulator that runs 200 nodes executes *the same
   `MeshNode` class* as the radio path. It is not a separate mock that happens to agree.
2. **Two people are not blocked on one phone.** `./gradlew :core:test` needs no device and no
   emulator. The `Link` interface is frozen early; after that the protocol work and the Android
   work proceed independently.

The `kotlin-jvm` plugin means "no Android in `core`" is enforced by the *compiler*, not by a code
review.

---

## Status

This is an active hackathon build. Honest state:

| Area | State |
|------|-------|
| 24-byte beacon codec + CRC-8 | implemented, compiling |
| Seen-set dedup, outbox, carousel ordering | implemented |
| Energy-aware forwarding policy | implemented |
| Power tiers, rendezvous, scanner election, ledger | implemented |
| `MeshNode` engine + `Link` seam | implemented, frozen |
| Unit tests | in progress |
| `SimLink` + virtual world | in progress |
| Android BLE transport (advertiser / scanner / GATT) | in progress |
| Compose UI | in progress |
| Measured battery numbers | **not yet — nothing quoted as measured** |

### Not built, on purpose

Web console, cloud backend, iOS, a Wi-Fi Direct tier, encryption beyond bundle signatures,
accounts, real map tiles. Each was cut to keep one APK finishable and demoable. See
[`docs/PRD.md`](docs/PRD.md) for the reasoning.

### Known limitation, stated plainly

Beacons are **unsigned** — there is no room for a 64-byte signature in 24 bytes. Rich bundles
carry Ed25519; beacons do not. The UI must therefore distinguish an *unverified beacon* from a
*signed bundle*, and rate-limit per originator. See [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md).

---

## Build

Requires JDK 17, Android SDK platform 37, build-tools 36.0.0.

```bash
./gradlew :core:test
```

```bash
./gradlew :app:assembleDebug
```

Versions are pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and were resolved
against Google Maven on 2026-08-22: AGP 9.3.0 · Gradle 9.5.0 · Kotlin 2.3.21 · compileSdk 37 ·
minSdk 26 · Compose BOM 2026.08.00. Do not bump them during the build window.

`minSdk 26` is forced by `BluetoothAdapter.isLeExtendedAdvertisingSupported()`.

## Docs

| Document | What it covers |
|----------|----------------|
| [`docs/PRD.md`](docs/PRD.md) | product requirements, scope, non-goals, success criteria |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | module boundaries, the seam, data flow |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | wire format, message types, dedup, TTL |
| [`docs/POWER.md`](docs/POWER.md) | the battery answer in full, with measurement plan |
| [`docs/DEMO.md`](docs/DEMO.md) | the five-minute demo script |
| [`docs/THREAT-MODEL.md`](docs/THREAT-MODEL.md) | what SETU does and does not defend against |
| [`docs/tasks/`](docs/tasks/) | Android build tasks, B1–B8, with acceptance criteria |
| [`docs/adr/`](docs/adr/) | architecture decision records |

## Prior art

SETU is not the first BLE mesh messenger and does not claim to be. [Bridgefy], [Briar] and
[bitchat] all relay messages over Bluetooth; bitchat in particular validates the dual-role
GATT + controlled-flood + TTL-7 approach on Android. [Meshtastic] does the same over LoRa.

What is different here is the **objective function**. Those projects optimise for message
delivery and treat battery as a constraint to be respected. SETU treats residual battery as the
scarce resource being *allocated* — it is an input to routing, to scheduling, to role election,
and to what the app tells you about itself.

[Bridgefy]: https://bridgefy.me
[Briar]: https://briarproject.org
[bitchat]: https://github.com/permissionlesstech/bitchat
[Meshtastic]: https://meshtastic.org

## Licence

MIT — see [LICENSE](LICENSE).
