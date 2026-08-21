# Track B — the Android app

Task files for the app build. Each `B*.md` is **self-contained**: copy everything below the
horizontal rule into a coding agent and it has the full context, the interfaces, the constraints,
and an acceptance command.

Start with **B1**. `:app` does not compile at all right now.

---

## What you are building

One APK. No servers, no cloud, no accounts.

```
core/   Kotlin/JVM   protocol: codec, routing, power governor    ← done, compiles. NOT yours.
sim/    Kotlin/JVM   virtual mesh, drives the same MeshNode      ← in progress. NOT yours.
app/    Android      BLE transport + Compose UI                  ← YOURS
```

The app is a **foreground service** owning one `MeshNode`. That node reaches the radio only
through `Link`. `AndroidLink` wraps a BLE **advertiser** (broadcasts a 24-byte beacon as service
data) and a BLE **scanner** (listens in scheduled windows).

**Relaying = hearing a beacon and re-advertising it with TTL−1.** No connections, no pairing, no
GATT. That is deliberate: connectionless broadcast costs roughly 1/100th of scanning, which is
what lets the mesh survive on nearly-dead phones.

Three Compose screens — **SOS**, **Mesh** (responder list), **Mesh Lab** (in-app simulator). The
service publishes a `StateFlow<NodeSnapshot>`; the screens render it.

Read `../PROTOCOL.md` and `../POWER.md` before B2.

## Order

| # | Task | Produces |
|---|------|----------|
| B1 | manifest + service | `:app` builds and installs |
| B2 | BLE advertiser | 24 bytes on air |
| B3 | BLE scanner | bounded-window listening |
| B4 | `AndroidLink` | **gate G3** |
| B5 | `AndroidNodeHost` + live service | a running node |
| B6 | SOS screen | the victim UI |
| B7 | Mesh screen | the responder UI |
| B8 | Mesh Lab | scale demo — **cut this first if short on time** |

### Gate G3 decides the project

**A 24-byte beacon crosses two phones in airplane mode.** It sits at the end of B4.

Nothing else matters until this works. Do **not** start UI work (B6+) before it passes. If G3
has not passed by two-thirds of the build window, everyone stops and works on it.

If it turns out to be impossible on the available handsets, swapping `AndroidLink` for a Google
Nearby Connections implementation touches exactly one class and leaves `:core` untouched. That is
what the interface below is for. See [`../adr/0002`](../adr/0002-raw-ble-over-nearby-connections.md).

## The frozen contract

**Do not modify these two files.** Changing either blocks the other builder immediately. If you
believe a change is required, say so before making it.

```
core/src/main/kotlin/com/setu/mesh/core/link/Link.kt
core/src/main/kotlin/com/setu/mesh/core/engine/NodeHost.kt
```

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

interface NodeHost {
    fun nowMillis(): Long          // wall clock, NOT uptime
    fun batteryPercent(): Int      // 0..100, or 255 = unknown
    fun isCharging(): Boolean
    fun position(): GeoPoint?      // null until first fix
    fun hasTrustedClock(): Boolean
}
```

`Link` moves **opaque bytes**. It must never parse a beacon, deduplicate, choose what to
advertise, or decide when to scan — those are routing decisions and they live in `:core`.

## Rules

1. **`:app` owns only `app/`.** Do not edit `core/` or `sim/`.
2. **No routing logic in `app/`.** If you are writing dedup, priority ordering, or TTL handling,
   it belongs in `:core` — flag it instead.
3. **AGP 9 has built-in Kotlin.** Do **not** apply `org.jetbrains.kotlin.android`. Do **not** use
   `kotlinOptions {}` — it no longer exists. An agent trained on AGP 8 will try to add both;
   reject that diff.
4. **Do not change** `app/build.gradle.kts` or `gradle/libs.versions.toml`.
5. **No new third-party dependencies** without agreeing first. No map SDK, no mocking library,
   no charting library.
6. **Wall clock, not uptime.** `nowMillis()` must be `System.currentTimeMillis()`. The rendezvous
   scheduler derives its wake window from absolute time — that is how phones meet without
   exchanging scheduling messages. `elapsedRealtime()` breaks it silently.
7. **No invented numbers in the UI.** Battery figures come from the real `EnergyLedger` or they
   are not shown.

## Two gotchas that will otherwise cost you a night

**Android throttles you to 5 `startScan` calls per 30 seconds.** Over the limit there is *no
error, no callback, and no results* — your code looks correct and discovers nothing. The
60-second rendezvous epoch exists partly because of this. Do not "optimise" it faster. Details
in B3.

**The service UUID must be 16-bit**, in the Bluetooth base form
`00005E70-0000-1000-8000-00805F9B34FB`. A randomly generated 128-bit UUID costs 16 bytes on air
and the 24-byte beacon will not fit. Details in B2.

## Setup

Needs JDK 17, Android Studio with SDK platform 37 and build-tools 36.0.0.

Create `local.properties` in the repo root (it is gitignored, every machine needs its own):
```properties
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```

Then:
```bash
./gradlew :app:assembleDebug
```

**An emulator cannot do BLE.** All of B2 onward requires physical devices — two minimum.
[nRF Connect](https://play.google.com/store/apps/details?id=no.nordicsemi.android.mcp) on a
second phone is the fastest way to confirm your beacons are actually on air.

## Before you accept an agent's work

1. **Run the acceptance command yourself.** Agents claim green builds that are not green.
2. Check `git diff --stat` — reject anything touching `Link.kt`, `NodeHost.kt`, or `core/`.
3. Reject dependencies nobody asked for.
4. Reject routing logic that appeared in `app/`.
