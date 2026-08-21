# SETU — Build Plan

Task-by-task execution plan, written to be handed directly to coding agents.

**Two builders:**

| Track | Owner | Scope | Needs a phone? |
|-------|-------|-------|----------------|
| **A** | *you* | protocol, relay/routing, simulator, gateway behaviour, tests | no |
| **B** | *teammate* | Android app: BLE transport, foreground service, Compose UI | yes |
| **C** | *optional* | real gateway ingest service | no |

Tracks A and B touch **disjoint directories** and meet only at a frozen interface. Neither can
block the other. Do not let anyone edit outside their track's file list without agreeing first.

---

## 0. Verified current state

Checked on 2026-08-22, not assumed:

| Module | State |
|--------|-------|
| `:core` | **compiles and jars.** 15 source files. **Zero tests.** |
| `:sim` | build file only — `compileKotlin` reports `NO-SOURCE` |
| `:app` | **fails**: `:app:processDebugMainManifest` — `app/src/main/AndroidManifest.xml` does not exist. AGP config is otherwise healthy (26 tasks ran first). |

Toolchain, all verified present locally: AGP 9.3.0 · Gradle 9.5.0 · JDK 17.0.12 · Kotlin 2.3.21 ·
platform `android-37.0` · build-tools `36.0.0` · Compose BOM 2026.08.00.

`local.properties` is gitignored and contains `sdk.dir=C:/Users/Jaideep/AppData/Local/Android/Sdk`.
**Every machine needs its own.** A fresh clone will fail on `:app` until it exists.

### AGP 9 gotcha that will bite an agent

AGP 9 has **built-in Kotlin support**. Do **not** apply `org.jetbrains.kotlin.android`, and
`kotlinOptions {}` no longer exists. Use:

```kotlin
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }
```

An agent trained on AGP 8 will try to add the Kotlin Android plugin. Reject that change.

---

## 1. The frozen contract

**These two interfaces are frozen. Changing either blocks both builders simultaneously.** If a
change is genuinely required, both people agree first, in writing.

### `Link` — the radio

`core/src/main/kotlin/com/setu/mesh/core/link/Link.kt`

```kotlin
interface Link {
    val capabilities: LinkCapabilities
    val events: Flow<LinkEvent>
    suspend fun setAdvertisedBeacons(beacons: List<ByteArray>)  // each exactly 24 bytes; empty = stop
    suspend fun scanFor(windowMillis: Long)                     // bounded window, then stop
    suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean
    suspend fun shutdown()
}

data class LinkCapabilities(
    val advertisingSlots: Int = 1,
    val supportsExtendedAdvertising: Boolean = false,
    val maxBundleBytes: Int = 244,
    val canAdvertise: Boolean = true,
)

sealed interface LinkEvent {
    data class BeaconHeard(val payload: ByteArray, val peer: PeerHandle, val rssiDbm: Int, val atMillis: Long) : LinkEvent
    data class BundleReceived(val payload: ByteArray, val peer: PeerHandle, val atMillis: Long) : LinkEvent
    data class ScanWindow(val open: Boolean, val atMillis: Long) : LinkEvent
    data class RadioUnavailable(val reason: String, val atMillis: Long) : LinkEvent
}

@JvmInline value class PeerHandle(val address: String)
```

**Rule: `Link` traffics in opaque bytes, never protocol types.** A Link that can parse a beacon
will eventually make a routing decision, and the layering is gone.

`scanFor` is a *bounded window* rather than start/stop calls specifically because Android
throttles an app to **5 `startScan` calls per 30 seconds**.

### `NodeHost` — the machine

`core/src/main/kotlin/com/setu/mesh/core/engine/NodeHost.kt`

```kotlin
interface NodeHost {
    fun nowMillis(): Long          // wall clock, NOT uptime — rendezvous phase depends on this
    fun batteryPercent(): Int      // 0..100, or 255 (BATTERY_UNKNOWN)
    fun isCharging(): Boolean
    fun position(): GeoPoint?
    fun hasTrustedClock(): Boolean // GPS or recent NTP
}
```

### `MeshNode` — the engine both tracks drive

`core/src/main/kotlin/com/setu/mesh/core/engine/MeshNode.kt`

```kotlin
class MeshNode(
    val id: NodeId,
    private val link: Link,
    private val host: NodeHost,
    private val governor: PowerGovernor = PowerGovernor(),
    private val random: Random = Random.Default,
) {
    val snapshot: StateFlow<NodeSnapshot>
    val ledger: EnergyLedger

    fun originateSos(flags: SituationFlags, souls: Int, nowMillis: Long = host.nowMillis()): MessageId
    fun markSafe(nowMillis: Long = host.nowMillis())
    fun onBeaconHeard(payload: ByteArray, from: PeerHandle, nowMillis: Long): RelayDecision
    fun planNow(nowMillis: Long = host.nowMillis()): RadioPlan
    fun beaconsToAdvertise(slots: Int, nowMillis: Long): List<ByteArray>
    suspend fun run()   // drives the link; cancel the scope to stop
}
```

Decision methods take an explicit `nowMillis` so they are testable with no coroutine machinery
and steppable deterministically by the simulator. **Never read `System.currentTimeMillis()`
inside a decision** — that is what makes rendezvous alignment testable at all.

---

## 2. Track A — yours

### A1 · Unit tests for `:core` — **do this first**

`:core` has zero tests today. It is the half of the project whose correctness is provable without
hardware, and right now none of it is proven. This is also the task that most protects you if the
radio work overruns.

**Files:** `core/src/test/kotlin/com/setu/mesh/core/`

| File | Must cover |
|------|------------|
| `Crc8Test.kt` | known vectors — CRC-8/ATM of `"123456789"` is `0xF4`; empty input is `0x00`; single-bit flip changes output |
| `BeaconCodecTest.kt` | round-trip **every field**; encoded length is exactly 24; boundary values (`ttl` 0 and 15, `souls` 0 and 255, `battery` 0/100/255, lat/lon at ±180°, `epochMin` at 0 and 0xFFFFFF); corrupt any byte → `decode` returns null; wrong length → null; wrong version → null |
| `SeenSetTest.kt` | `addIfNew` true then false; expiry after 10 min makes it new again; capacity 1024 evicts LRU; `purgeExpired` |
| `ForwardingPolicyTest.kt` | truth table below |
| `OutboxTest.kt` | carousel ordering (own → severity → fewest carriers → newest); eviction is the inverse; `remove` on RECEIPT; `purgeStale` spares own messages |
| `PowerTierTest.kt` | `forBattery` boundaries at 60/30/15/5; charging always → BRIDGE |
| `RendezvousSchedulerTest.kt` | **two nodes with different tiers land in the same window** (the whole point); `scansInEpoch` phase alignment; `millisUntilNextWindow` never negative; drift correction converges and is damped |
| `ScannerElectionTest.kt` | determinism — same inputs on two "nodes" give the same answer; quota is `ceil(√n)`; rotation across epochs; empty neighbours → true |
| `MeshNodeTest.kt` | own SOS always relays at 1% battery; duplicate beacon suppressed; TTL decrements and stops at 0; RECEIPT removes from outbox; `beaconsToAdvertise` rotates when slots < outbox size |

**`ForwardingPolicyTest` truth table** — assert with a seeded `Random(42)` and, where the result
is probabilistic, assert the returned `probability` rather than the coin flip:

| self batt | charging | origin batt | severity | k | expect |
|---|---|---|---|---|---|
| 100 | false | 50 | LOW | 0 | Relay, p = 0.35 |
| 50 | false | 50 | CRITICAL | 0 | Relay, p = 1.0 |
| 4 | false | 50 | HIGH | 0 | Suppress(ENERGY_GATE) |
| 4 | false | 50 | CRITICAL | 0 | Relay, p ≈ 0.15 |
| 20 | false | 90 | MODERATE | 0 | Suppress or heavily damped — ALTRUISM_GRADIENT |
| 90 | false | 20 | MODERATE | 0 | Relay, p = 0.6 |
| 100 | false | 50 | HIGH | 12 | damped, p ≈ 0.85 × 3/12 |
| any | any | any | any | any | `isOwnMessage = true` → **always** Relay(1.0) |

**Acceptance:**
```bash
./gradlew :core:test
```
Must pass on a machine with **no Android SDK and no device**. Verify by temporarily renaming
`local.properties` — `:core:test` must still pass.

**Gotcha:** `MeshNode` needs a fake `Link` and fake `NodeHost`. Write `FakeLink` and `FakeHost`
in `core/src/test/kotlin/.../support/` — a `MutableSharedFlow<LinkEvent>` and mutable
battery/clock fields. Do **not** add a mocking library; these are ten lines each.

---

### A2 · `:sim` — the virtual mesh

**Files:** `sim/src/main/kotlin/com/setu/mesh/sim/`

| File | Responsibility |
|------|----------------|
| `VirtualClock.kt` | monotonic virtual time; `advance(millis)`; every node reads it via its `NodeHost` |
| `SimHost.kt` | `NodeHost` backed by `VirtualClock` + `BatteryModel` + a fixed `GeoPoint` |
| `BatteryModel.kt` | starts at a given %, drains from the node's `EnergyLedger` deltas plus a baseline idle draw; clamps at 0; `isDead` |
| `World.kt` | holds N `SimNode`s (id, position, `SimLink`, `MeshNode`, `BatteryModel`); computes who is in range; delivers beacons |
| `SimLink.kt` | `Link` impl. `setAdvertisedBeacons` stores the current set; `scanFor` asks `World` for beacons from in-range neighbours and emits `BeaconHeard`, applying loss |
| `Mobility.kt` | `Static` and `RandomWalk`. Keep it simple — mobility is not the story |
| `Scenario.kt` | named setups: `flood` (clustered, 1 gateway, mixed batteries), `drain` (all start <15%), `partition` (two clusters, one bridge node), `dying-chain` (a line where relays die in sequence) |
| `Metrics.kt` | delivery ratio, median/max hops, mAh per node, tier histogram over time, messages carried per node |
| `Main.kt` | CLI: `--nodes N --scenario S --minutes M --seed X --json` |

**Hard requirements:**

1. **Deterministic.** Same `--seed` ⇒ byte-identical output. Every `Random` is seeded from the
   CLI seed; nothing calls `Random.Default` or `System.currentTimeMillis()`.
2. **Drives real `MeshNode` instances.** Do not reimplement routing in the simulator. If you find
   yourself writing forwarding logic in `:sim`, stop — that is the one thing that makes the scale
   claim dishonest.
3. **Step, don't sleep.** Advance `VirtualClock` in fixed ticks (250 ms) and call the nodes'
   decision methods directly. Do **not** use `MeshNode.run()` with real `delay()` — 200 nodes at
   wall-clock speed is unusable.

**Radio model:** in-range if euclidean distance ≤ `rangeMetres` (default 80). Delivery
probability falls off with distance: `p = 1 - (d/range)^2`, plus a flat `--loss` rate. Good
enough; do not build a propagation model.

**Acceptance:**
```bash
./gradlew :sim:run --args="--nodes 200 --scenario flood --minutes 30 --seed 7"
```
Prints a metrics summary. Running twice with the same seed gives identical output.

---

### A3 · Gateway behaviour + `RECEIPT` loop

The PS says connectivity is only needed at the final gateway node. This closes the loop and is
one of the six battery mechanisms — receipts make carriers *drop* messages, reclaiming airtime.

**Files:** `core/src/main/kotlin/com/setu/mesh/core/engine/GatewayRole.kt`

```kotlin
class GatewayRole(private val node: MeshNode) {
    fun onUplinkAvailable(available: Boolean)
    fun acceptDelivery(messageId: MessageId, nowMillis: Long)  // emits RECEIPT into the mesh
}
```

`MeshNode` needs a small addition: a way to inject an originated non-SOS beacon. Add

```kotlin
fun originateReceipt(forMessage: MessageId, nowMillis: Long): MessageId
```

modelled exactly on the existing `markSafe`, which already builds a `SAFE` beacon that reuses the
referenced `msgId`. **This is the only permitted change to `MeshNode`'s public API in Track A** —
it is additive, so Track B is unaffected.

**Acceptance:** a `:sim` scenario where one node is a gateway; assert that after delivery, every
other node's outbox no longer contains the message, and that total mesh mAh/minute drops
measurably afterwards. That drop is the headline number for `POWER.md`.

---

### A4 · Simulator-backed numbers for `POWER.md`

`docs/POWER.md` §8 currently says **nothing is measured** and lists `RadioCostModel` constants as
estimates. Fill in what the simulator can honestly provide, kept clearly separate from hardware
measurements (which are Track B's M1–M6).

Produce:
- delivery ratio vs. mean starting battery (sweep 10 %–100 %)
- mesh lifetime with and without the energy gate
- mesh lifetime with and without phase-locked rendezvous — **this is the money chart**; run the
  same scenario with independent random phase and show delivery collapse
- scanner-election rotation: per-node mAh spread, with and without banding

**Label every one of these "simulated".** They are model outputs, not measurements.

---

### A5 · Rich bundle codec *(only after A1–A4)*

`core/codec/BundleCodec.kt` + `core/model/SosBundle.kt`, per `docs/PROTOCOL.md` §8. Ed25519
signing. Do not start this until the simulator is producing numbers.

---

## 3. Track B — your teammate

### B1 · Manifest, permissions, foreground service — **unblocks everything**

`:app` does not build at all until the manifest exists.

**Files:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/{strings,themes}.xml`

```xml
<!-- Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<!-- API <= 30 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<!-- GPS for the SOS position -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

Service declared with `android:foregroundServiceType="connectedDevice"`.

`neverForLocation` on `BLUETOOTH_SCAN` is deliberate: it avoids needing location permission
*for scanning*. GPS is still requested separately for the SOS position.

**Acceptance:** `./gradlew :app:assembleDebug` succeeds and the APK installs.

---

### B2 · `BleAdvertiser`

**File:** `app/src/main/kotlin/com/setu/mesh/app/ble/BleAdvertiser.kt`

Service Data UUID — 16-bit, to keep the header at 4 bytes:

```kotlin
val SETU_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("00005E70-0000-1000-8000-00805F9B34FB")
```

The `0000XXXX-0000-1000-8000-00805F9B34FB` form is the Bluetooth base UUID; Android encodes it
on air as a 16-bit UUID, which is what the 24-byte budget assumes. **A random 128-bit UUID costs
16 bytes and the beacon will not fit.**

```kotlin
AdvertiseSettings.Builder()
    .setAdvertiseMode(ADVERTISE_MODE_LOW_LATENCY)   // governor overrides per tier
    .setTxPowerLevel(ADVERTISE_TX_POWER_MEDIUM)
    .setConnectable(false)                          // beacons are connectionless
    .build()

AdvertiseData.Builder()
    .setIncludeDeviceName(false)      // MANDATORY — the device name alone can blow the budget
    .setIncludeTxPowerLevel(false)    // MANDATORY
    .addServiceData(SETU_SERVICE_UUID, beaconBytes)  // exactly 24
    .build()
```

Implements the **beacon carousel**: given `List<ByteArray>` and `advertisingSlots` slots, start
that many `AdvertisingSet`s; if more beacons than slots, rotate on each interval.

**Gotchas:**
- Check `BluetoothAdapter.isMultipleAdvertisementSupported()` at startup and report the true slot
  count in `LinkCapabilities.advertisingSlots`. Many devices report `false` → 1 slot.
- `ADVERTISE_FAILED_DATA_TOO_LARGE` means the budget is blown. Log the byte count and check the
  two `setInclude*` flags first.
- Always `stopAdvertising` before starting a new set, or callbacks leak.

---

### B3 · `BleScanner`

**File:** `app/src/main/kotlin/com/setu/mesh/app/ble/BleScanner.kt`

```kotlin
ScanFilter.Builder().setServiceData(SETU_SERVICE_UUID, byteArrayOf(), byteArrayOf()).build()
ScanSettings.Builder()
    .setScanMode(SCAN_MODE_LOW_LATENCY)   // governor picks per tier
    .setCallbackType(CALLBACK_TYPE_ALL_MATCHES)
    .setReportDelay(0)
    .build()
```

`scanFor(windowMillis)` starts a scan, waits, stops, emits `ScanWindow(open=…)` around it and a
`BeaconHeard` per result. Extract the payload with
`result.scanRecord?.getServiceData(SETU_SERVICE_UUID)` and **drop anything not exactly 24 bytes**.

**The gotcha that will cost you hours:** Android silently throttles an app to **5 `startScan`
calls per 30 seconds**. Exceeding it returns no error and no results. Enforce a minimum 6-second
spacing between scan starts and log every start with a timestamp. The 60-second rendezvous epoch
already respects this — do not "optimise" it to a faster cycle.

---

### B4 · `AndroidLink` — implements `Link`

**File:** `app/src/main/kotlin/com/setu/mesh/app/ble/AndroidLink.kt`

Composes `BleAdvertiser` + `BleScanner` into the frozen interface. Populates
`LinkCapabilities` from real adapter queries:

```kotlin
LinkCapabilities(
    advertisingSlots = if (adapter.isMultipleAdvertisementSupported) 4 else 1,
    supportsExtendedAdvertising = adapter.isLeExtendedAdvertisingSupported,
    maxBundleBytes = 244,
    canAdvertise = adapter.bluetoothLeAdvertiser != null,
)
```

`sendBundle` may return `false` unconditionally in v1 — rich bundles are Track A / A5. Do not
block on GATT.

---

### B5 · `AndroidNodeHost` + `SetuService`

**Files:** `app/src/main/kotlin/com/setu/mesh/app/service/{AndroidNodeHost,SetuService}.kt`

`AndroidNodeHost`:
- `nowMillis()` → `System.currentTimeMillis()` (**wall clock, not `elapsedRealtime`**)
- `batteryPercent()` → `BatteryManager.BATTERY_PROPERTY_CAPACITY`
- `isCharging()` → `BatteryManager.isCharging`
- `position()` → last known fix, cached; null until first fix
- `hasTrustedClock()` → `true` if GPS time or NTP within the last hour

Add a **debug battery override** (`setOverride(percent: Int?)`) — the demo depends on forcing 4%
without draining a real phone. Guard it with `BuildConfig.DEBUG`.

`SetuService`: `LifecycleService`, `startForeground` with `connectedDevice` type, owns the
`MeshNode` and its coroutine scope, notification shows tier + carrying count.

---

### B6 · SOS screen

**File:** `app/src/main/kotlin/com/setu/mesh/app/ui/SosScreen.kt`

Requirements: **three taps maximum**, one-handed, legible in the dark.

1. Full-width SOS button, at least 30 % of screen height, bottom half of the screen.
2. Triage: severity, souls (−/+), and toggles for trapped / medical / water rising.
3. Status ladder with the real hop chain from `NodeSnapshot`:
   `created → carried by N → M hops out → reached rescuers ✓`
4. Tier badge (BRIDGE/RELAY/GOSSIP/FLARE/EMBER) — the demo points at this.
5. Energy line in plain words: *"SETU used 1.8 % of your battery in 3 h and carried 47 messages."*

Palette: near-black background with amber/red accents. On OLED that is genuinely lower power,
which ties the UI to the thesis — say so in the pitch.

---

### B7 · Mesh screen · B8 · Mesh Lab

`MeshScreen.kt` — received SOS list sorted by severity, with **unverified beacon** marked
distinctly from signed bundles (see `docs/THREAT-MODEL.md`).

`MeshLabScreen.kt` — renders `:sim` in-app on a Compose `Canvas`; nodes coloured by tier, edges
for live links, tap to kill a node. **Depends on A2. Cut this first if time runs short.**

---

## 4. Track C — optional gateway service

**Do not start this until Track A is complete, and never put it on the demo critical path.**

The demo works with zero servers: the gateway phone displays received SOS directly. A backend
adds a network dependency to a live demo, which is exactly the failure mode the architecture was
shaped to avoid.

If you want it anyway: a small Kotlin/ktor or Node/Fastify service with `POST /api/sos`,
`GET /api/sos`, SQLite storage, and a single responder page. Runs on `localhost` at the demo,
never on the internet. Keep it in `gateway/` and out of `settings.gradle.kts` so it cannot break
the APK build.

---

## 5. Integration checkpoints

| # | Gate | Owner | Why it matters |
|---|------|-------|----------------|
| **G1** | `:app:assembleDebug` succeeds, APK installs | B | nothing else in Track B can proceed |
| **G2** | `./gradlew :core:test` green with `local.properties` renamed away | A | proves the protocol needs no hardware |
| **G3** | **first 24-byte beacon crosses two phones in airplane mode** | B | **the make-or-break gate — the whole premise** |
| **G4** | `:sim` runs 200 nodes deterministically | A | the scale claim |
| **G5** | 4 % battery override → EMBER → beacon still received | both | the challenge-question demo |
| **G6** | Relay killed mid-flight, message still arrives | both | resilience demo |

**G3 is the one that decides this project.** If it has not happened by the two-thirds mark of
your window, stop all UI and simulator work and put both people on it. If it cannot be made to
work at all, swap `AndroidLink` for a Google Nearby Connections implementation — that touches
exactly one class and leaves `:core` untouched. See `docs/adr/0002`.

---

## 6. Handing a task to an agent

Copy this template. The context block is what stops an agent inventing an architecture.

```
Repo: https://github.com/SarmaHighOnCode/acm_elicit2026
Read first, in order: docs/BUILD-PLAN.md §1 (frozen contract), docs/PROTOCOL.md, docs/POWER.md

Task: <ID and title from this document>
Files you may create or modify: <exact list from the task>
Files you must NOT touch: everything else, especially
  core/src/main/kotlin/com/setu/mesh/core/link/Link.kt
  core/src/main/kotlin/com/setu/mesh/core/engine/NodeHost.kt

Constraints:
- :core must not import anything Android. Enforced by the kotlin-jvm plugin.
- AGP 9 has built-in Kotlin. Do NOT add org.jetbrains.kotlin.android. Do NOT use kotlinOptions {}.
- Do not change versions in gradle/libs.versions.toml.
- Decision methods take an explicit nowMillis. Never call System.currentTimeMillis() inside one.
- No new third-party dependencies without asking.

Acceptance: <the exact gradle command from the task>
Report the command output. Do not claim it passes without running it.
```

### Rules for reviewing agent output

1. **Run the acceptance command yourself.** Agents claim green builds that are not green.
2. **Reject any diff that touches the frozen interfaces** unless it was agreed first.
3. **Reject any new dependency** that arrived without being asked for.
4. **Reject routing logic that appears in `:sim` or `:app`.** It belongs in `:core`, and this is
   the single most likely way the architecture quietly rots.
5. **Reject invented numbers.** If a doc gains a battery figure, it must trace to `POWER.md` §8
   or a labelled simulator run.

---

## 7. Ordering

**Track A:** A1 → A2 → A3 → A4 → *(A5 only if time remains)*
**Track B:** B1 → B2 → B3 → B4 → B5 → **G3** → B6 → B7 → B8

A1 first because it is cheap, it is the thing that makes every later change safe, and it is the
only work that is guaranteed to survive if the radio never cooperates.

B1 first because `:app` does not compile at all until the manifest exists.
