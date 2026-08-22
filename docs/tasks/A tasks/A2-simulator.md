# TASK A2 — The `:sim` virtual mesh

> Copy everything below the line into your agent. **Do A1 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay. Phones relay 24-byte emergency beacons over Bluetooth LE when
cell towers are down. Residual battery is a first-class routing input.

Read first: `docs/PROTOCOL.md`, `docs/POWER.md`, `docs/ARCHITECTURE.md`.

Modules: `core/` (pure Kotlin/JVM protocol, **no Android imports ever**), `sim/` (this task —
currently has a `build.gradle.kts` and no sources), `app/` (Android).

**FROZEN — do not modify:** `core/.../link/Link.kt`, `core/.../engine/NodeHost.kt`.

```kotlin
interface Link {
    val capabilities: LinkCapabilities
    val events: Flow<LinkEvent>
    suspend fun setAdvertisedBeacons(beacons: List<ByteArray>)  // each exactly 24 bytes
    suspend fun scanFor(windowMillis: Long)
    suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean
    suspend fun shutdown()
}
interface NodeHost {
    fun nowMillis(): Long; fun batteryPercent(): Int; fun isCharging(): Boolean
    fun position(): GeoPoint?; fun hasTrustedClock(): Boolean
}
class MeshNode(id: NodeId, link: Link, host: NodeHost,
               governor: PowerGovernor = PowerGovernor(), random: Random = Random.Default) {
    val snapshot: StateFlow<NodeSnapshot>
    val ledger: EnergyLedger
    fun originateSos(flags: SituationFlags, souls: Int, nowMillis: Long): MessageId
    fun onBeaconHeard(payload: ByteArray, from: PeerHandle, nowMillis: Long): RelayDecision
    fun planNow(nowMillis: Long): RadioPlan
    fun beaconsToAdvertise(slots: Int, nowMillis: Long): List<ByteArray>
    suspend fun run()
}
```

**Hard constraints**
- AGP 9 has built-in Kotlin. Do **not** apply `org.jetbrains.kotlin.android`; no `kotlinOptions {}`.
- Do not change versions in `gradle/libs.versions.toml`.
- Never call `System.currentTimeMillis()` — the simulator runs on virtual time only.
- No new third-party dependencies (no CLI parsing library — hand-roll the arg parsing).
- Do not claim the build passes without running it. Paste real output.

## Task

Build a deterministic simulator that runs a few hundred virtual SETU nodes so the protocol's
behaviour at scale can be demonstrated without 200 phones.

### The rule that matters most

**The simulator must drive real `MeshNode` instances.** Do **not** reimplement forwarding,
dedup, tier selection or scanner election inside `:sim`. If you find yourself writing routing
logic here, stop — the entire credibility of the scale claim rests on the simulator and the phone
running the same `MeshNode` class. `:sim` supplies only a `Link` and a `NodeHost`.

## Files you may create

```
sim/src/main/kotlin/com/setu/mesh/sim/VirtualClock.kt
sim/src/main/kotlin/com/setu/mesh/sim/BatteryModel.kt
sim/src/main/kotlin/com/setu/mesh/sim/SimHost.kt
sim/src/main/kotlin/com/setu/mesh/sim/SimLink.kt
sim/src/main/kotlin/com/setu/mesh/sim/World.kt
sim/src/main/kotlin/com/setu/mesh/sim/Mobility.kt
sim/src/main/kotlin/com/setu/mesh/sim/Scenario.kt
sim/src/main/kotlin/com/setu/mesh/sim/Metrics.kt
sim/src/main/kotlin/com/setu/mesh/sim/Main.kt
sim/src/test/kotlin/com/setu/mesh/sim/DeterminismTest.kt
sim/src/test/kotlin/com/setu/mesh/sim/ScenarioTest.kt
```

**Do NOT touch** `core/src/main/` or `app/`. `sim/build.gradle.kts` is already correct
(`application` plugin, `mainClass = "com.setu.mesh.sim.MainKt"`) — do not change it.

## Component specifications

### `VirtualClock`
Monotonic virtual time. `nowMillis(): Long`, `advance(millis: Long)`. Starts at a fixed, realistic
wall-clock value — **not zero** — because the rendezvous scheduler derives epoch phase from
absolute time. Use `1_755_000_000_000L`.

### `BatteryModel`
- constructed with a starting percentage and a capacity in mAh (default 4000)
- `drain(milliampHours: Double)` reduces the level; clamps at 0
- `percent: Int`, `isDead: Boolean`
- also applies a baseline idle draw per tick so nodes die even when quiet

Each tick, read the node's `EnergyLedger` total, take the delta since last tick, and drain that.
The ledger is already billed by `MeshNode`; do not invent a second cost model.

### `SimHost : NodeHost`
Backed by `VirtualClock`, a `BatteryModel`, and a fixed or mobile `GeoPoint`.
`hasTrustedClock()` returns a configurable value — default `true`, but scenarios should be able
to set it `false` to exercise drift correction.

### `SimLink : Link`
- `setAdvertisedBeacons(list)` stores the current advertised set for this node
- `scanFor(windowMillis)` asks `World` for beacons currently advertised by in-range neighbours,
  applies loss, and emits a `BeaconHeard` for each survivor
- `sendBundle` returns `false` (rich bundles are out of scope here)
- `capabilities` configurable per node so single-slot devices can be simulated — default
  `advertisingSlots = 1`, which is the common real-world case

### `World`
Holds `SimNode(id, position, link, meshNode, battery)`. Computes in-range pairs.

**Radio model** — keep it simple, do not build a propagation model:
- in range if euclidean distance ≤ `rangeMetres` (default 80)
- delivery probability `p = 1 - (d / range)^2`, then multiplied by `(1 - lossRate)`

**Stepping** — this is critical for usability:
```
for each tick (250 ms of virtual time):
    for each alive node:
        plan = node.planNow(clock.now)
        node.beaconsToAdvertise(slots, clock.now) -> link.setAdvertisedBeacons
        if plan.scanThisEpoch: deliver in-range beacons into that node
        battery.drain(ledger delta)
    clock.advance(250)
```
**Do NOT use `MeshNode.run()`** — it uses real `delay()`, and 200 nodes at wall-clock speed is
unusable. Call the decision methods directly.

### `Mobility`
`Static` and `RandomWalk(speedMetresPerSecond)`. Nothing more; mobility is not the story.

### `Scenario`
Named setups, selected by `--scenario`:

| Name | Setup | Demonstrates |
|------|-------|--------------|
| `flood` | 200 nodes in 4 clusters, 1 gateway, batteries 10–100% | baseline delivery |
| `drain` | all nodes start below 15% | the energy gate keeping the mesh alive |
| `partition` | two clusters + one bridge node | partition tolerance, per-partition scanner election |
| `dying-chain` | a line of relays that die in sequence | messages migrating off dying nodes |
| `unsynced` | rendezvous phase randomised per node instead of wall-clock derived | **the control case — delivery should collapse** |

`unsynced` is the most valuable scenario: it is the A/B that proves phase-locked rendezvous is
load-bearing rather than decorative.

### `Metrics`
- delivery ratio (SOS that reached the gateway / SOS originated)
- median and max hop count at delivery
- mAh consumed per node: mean, median, p95, max
- tier histogram sampled over time
- messages carried per node
- count of nodes dead at end

`--json` emits machine-readable output; default is a human-readable table.

### `Main`
```
--nodes N        default 200
--scenario S     default flood
--minutes M      default 30   (virtual minutes)
--seed X         default 1
--range R        default 80   (metres)
--loss L         default 0.05
--json
```
Hand-roll the parsing. No CLI library.

## Determinism — non-negotiable

Same `--seed` ⇒ **byte-identical output**. That means:
- every `Random` is derived from the CLI seed; nothing uses `Random.Default`
- each node gets its own seeded `Random`, passed into its `MeshNode` constructor
- no iteration over `HashMap`/`HashSet` where order affects results — use `LinkedHashMap` or sort
- no wall-clock reads anywhere

## Acceptance

```bash
./gradlew :sim:run --args="--nodes 200 --scenario flood --minutes 30 --seed 7"
```

```bash
./gradlew :sim:test
```

Determinism check — the two outputs must be identical:
```bash
./gradlew -q :sim:run --args="--nodes 50 --scenario flood --minutes 10 --seed 3 --json" > /tmp/a.json
./gradlew -q :sim:run --args="--nodes 50 --scenario flood --minutes 10 --seed 3 --json" > /tmp/b.json
diff /tmp/a.json /tmp/b.json && echo DETERMINISTIC
```

## Definition of done

- [ ] all three commands above run, output pasted
- [ ] `diff` prints nothing and `DETERMINISTIC` appears
- [ ] 200 nodes × 30 virtual minutes completes in under 30 seconds of real time
- [ ] `grep -rn "currentTimeMillis\|Random.Default" sim/src/main` returns nothing
- [ ] **no forwarding, dedup, tier or election logic exists anywhere in `sim/`** — it all comes
      from `MeshNode`
- [ ] `flood` shows delivery ratio > 0.9; `unsynced` shows a markedly lower one
- [ ] zero files under `core/src/main/` changed
