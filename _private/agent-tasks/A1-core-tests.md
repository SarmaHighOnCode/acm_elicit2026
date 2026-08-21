# TASK A1 — Unit tests for `:core`

> Copy everything below the line into your agent.

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay. When cell towers collapse, phones relay 24-byte emergency
beacons to each other over Bluetooth LE until one reaches a device with connectivity. Residual
battery is a first-class routing input, not just a constraint.

Read first: `docs/PROTOCOL.md`, `docs/POWER.md`.

Modules: `core/` (pure Kotlin/JVM protocol — **no Android imports ever**), `sim/` (simulator,
currently empty), `app/` (Android, currently does not build).

**FROZEN — do not modify:** `core/.../link/Link.kt`, `core/.../engine/NodeHost.kt`.

**Hard constraints**
- AGP 9 has built-in Kotlin. Do **not** apply `org.jetbrains.kotlin.android`; do **not** use
  `kotlinOptions {}`.
- Do not change versions in `gradle/libs.versions.toml`.
- Decision functions take an explicit `nowMillis`. Never call `System.currentTimeMillis()` inside
  one.
- No new third-party dependencies. **In particular, no mocking library** — the fakes needed here
  are ten lines each.
- Do not claim the build passes without running it. Paste real output.

## Task

`:core` has 15 source files and **zero tests**. Write the test suite.

This is the highest-value task in the project: `:core` is the half whose correctness is provable
with no hardware, and none of it is currently proven. It is also the work most likely to survive
if the Bluetooth work overruns.

The test dependencies are already wired in `core/build.gradle.kts` (`kotlin-test`,
`kotlin-test-junit5`, JUnit BOM, `useJUnitPlatform()`). Do not change that file.

## Files you may create

```
core/src/test/kotlin/com/setu/mesh/core/support/FakeLink.kt
core/src/test/kotlin/com/setu/mesh/core/support/FakeHost.kt
core/src/test/kotlin/com/setu/mesh/core/codec/Crc8Test.kt
core/src/test/kotlin/com/setu/mesh/core/codec/BeaconCodecTest.kt
core/src/test/kotlin/com/setu/mesh/core/routing/SeenSetTest.kt
core/src/test/kotlin/com/setu/mesh/core/routing/ForwardingPolicyTest.kt
core/src/test/kotlin/com/setu/mesh/core/routing/OutboxTest.kt
core/src/test/kotlin/com/setu/mesh/core/power/PowerTierTest.kt
core/src/test/kotlin/com/setu/mesh/core/power/RendezvousSchedulerTest.kt
core/src/test/kotlin/com/setu/mesh/core/power/ScannerElectionTest.kt
core/src/test/kotlin/com/setu/mesh/core/engine/MeshNodeTest.kt
```

**Files you must NOT touch:** everything else, including all of `core/src/main/`. If a test
cannot be written without changing production code, stop and report why instead of changing it.

## Test specifications

### `Crc8Test`
- `crc8("123456789") == 0xF4` — **this is verified, use it as the anchor vector**
- `crc8(empty) == 0x00`
- `crc8(byteArrayOf(0x00)) == 0x00`
- flipping any single bit of a 24-byte input changes the output

### `BeaconCodecTest`
- `encode(...).size == 24` — always, for every input
- round-trip **every field** independently
- boundary values: `ttl` 0 and 15 · `hops` 0 and 15 · `souls` 0 and 255 · `battery` 0, 100, 255 ·
  lat/lon at ±180° (`±1_800_000_000`) and at 0 · `epochMinute` 0 and `0xFFFFFF`
- every `MessageType` survives the round-trip
- every `SituationFlags` bit combination survives the round-trip
- corrupting any one of bytes 0..22 makes `decode` return **null** (CRC catches it)
- wrong length (23, 25, 0) returns null
- a payload with a version field != 1 returns null
- `decode` never throws, for any input including random noise — fuzz it with a seeded `Random`

### `SeenSetTest`
- `addIfNew(id, t)` returns true, then false for the same id
- after `expiryMillis` (10 min) the id is new again
- inserting > 1024 distinct ids evicts the least-recently-used
- `purgeExpired` drops only expired entries

### `ForwardingPolicyTest`
Assert the returned **probability**, not the coin flip, wherever the outcome is probabilistic.
Use a seeded `Random(42)` so results are reproducible.

| self batt | charging | origin batt | severity | k | expected |
|---|---|---|---|---|---|
| 100 | false | 50 | LOW | 0 | `Relay`, p = 0.35 |
| 50 | false | 50 | CRITICAL | 0 | `Relay`, p = 1.0 |
| 4 | false | 50 | HIGH | 0 | `Suppress(ENERGY_GATE)` |
| 4 | false | 50 | CRITICAL | 0 | `Relay`, p ≈ 0.15 |
| 20 | false | 90 | MODERATE | 0 | damped via `ALTRUISM_GRADIENT` |
| 90 | false | 20 | MODERATE | 0 | `Relay`, p = 0.6 |
| 100 | false | 50 | HIGH | 12 | p ≈ 0.85 × (3/12) |
| anything | any | any | any | any | `isOwnMessage = true` → **always** `Relay(1.0)` |

Also: `ttl == 0` → `Suppress(TTL_EXHAUSTED)` regardless of everything else.

### `OutboxTest`
- carousel order: own message first, then higher severity, then fewest known carriers, then newest
- eviction at capacity removes the inverse — lowest severity, most carriers, oldest — and
  **never evicts an own message**
- `remove(id)` works (this is the RECEIPT path)
- `purgeStale` drops old non-own messages and **spares own messages**
- `noteCarrier` increments `neighboursHoldingCopy` only for distinct node ids

### `PowerTierTest`
- `forBattery` boundaries: 100→BRIDGE, 61→BRIDGE, 60→BRIDGE, 59→RELAY, 30→RELAY, 29→GOSSIP,
  15→GOSSIP, 14→FLARE, 5→FLARE, 4→EMBER, 0→EMBER
- `charging = true` returns BRIDGE at **every** battery level including 0
- `EMBER.scans == false`; every other tier `scans == true`

### `RendezvousSchedulerTest`
**The most important test in the suite** — it proves the mechanism that stops low duty cycles
silently killing the mesh.

- two schedulers with the *same* wall-clock but *different* tiers both report `isInWindow(t) ==
  true` for some shared `t` — i.e. a FLARE node and a BRIDGE node meet
- `scansInEpoch` is true for BRIDGE every epoch, for GOSSIP every 2nd, for FLARE every 4th, and
  is **phase-aligned on the absolute epoch index** (not a per-node counter)
- `millisUntilNextWindow` is never negative and never larger than
  `epochsBetweenScans × 60_000`
- `EMBER` returns `Long.MAX_VALUE` (never scans)
- drift correction: a scheduler with an untrusted clock offset by +10 minutes converges toward 0
  over repeated `applyPeerObservation` calls, and moves by **less than** the full error each time
  (damping)
- `applyPeerObservation` is a no-op when `trustedLocalClock = true`

### `ScannerElectionTest`
- **determinism**: two separately-constructed neighbour tables with identical contents produce
  identical answers — this is what makes the zero-message election work at all
- quota is `ceil(sqrt(n))` where n includes self
- empty neighbour list → `shouldScan == true` (alone, nobody to delegate to)
- a charging node always outranks battery-powered nodes
- **rotation**: across 20 consecutive epochs with all batteries equal, more than one distinct node
  gets elected
- **banding**: nodes within the same 10% band take turns; a node 30 points higher wins consistently

### `MeshNodeTest`
Needs `FakeLink` and `FakeHost` in `support/`:
- `FakeLink` — `MutableSharedFlow<LinkEvent>` for `events`; records `setAdvertisedBeacons` calls
  and `scanFor` windows; configurable `LinkCapabilities`
- `FakeHost` — mutable `battery`, `charging`, `now`, `position`, `trustedClock` fields

Tests:
- `originateSos` puts the beacon in the outbox and it appears in `beaconsToAdvertise`
- **an own SOS is advertised even at 1% battery** (the protocol floor)
- the same beacon delivered twice → the second is suppressed
- a relayed beacon comes back with `ttl - 1` and `hops + 1`
- a beacon arriving at `ttl == 0` is not re-advertised
- a `RECEIPT` referencing a carried message removes it from the outbox
- `markSafe` removes the original and emits a `SAFE` beacon
- `beaconsToAdvertise(slots = 1, ...)` **rotates** across successive calls when the outbox holds 3
  messages — every message eventually gets airtime

## Acceptance

```bash
./gradlew :core:test
```

Must be green. Then prove it needs no Android toolchain:

```bash
mv local.properties local.properties.bak && ./gradlew :core:test ; mv local.properties.bak local.properties
```

Still green. Paste both outputs.

## Definition of done

- [ ] `./gradlew :core:test` passes, output pasted
- [ ] passes with `local.properties` absent
- [ ] zero files under `core/src/main/` changed
- [ ] no new dependencies in `core/build.gradle.kts`
- [ ] no mocking library
- [ ] every table row above has a corresponding assertion
