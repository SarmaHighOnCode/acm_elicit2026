# TASK B4 — `AndroidLink` (implements the frozen `Link` interface)

> Copy everything below the line into your agent. **Do B1, B2, B3 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over BLE. The protocol lives in `:core` as pure Kotlin with no
Android dependency; it reaches the radio only through a frozen interface called `Link`. The same
protocol code runs on a phone (this task) and in a 200-node simulator, which is what makes the
project's scale claims honest.

Read first: `docs/ARCHITECTURE.md` (the seam), `docs/PROTOCOL.md`.

**FROZEN — do not modify:** `core/src/main/kotlin/com/setu/mesh/core/link/Link.kt`

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

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- No new third-party dependencies.
- **`core/` and `sim/` are off limits.** If you believe `Link` needs changing, stop and report why
  — changing it blocks the other builder immediately.
- Do not claim it works without running it on real devices.

## Task

Compose `BleAdvertiser` (B2) and `BleScanner` (B3) into a single `Link` implementation.

This class is a **dumb pipe**. It moves opaque byte arrays between `MeshNode` and the radio.

**It must not:**
- parse a beacon, or import anything from `core.model` or `core.codec`
- decide what to advertise, or in what order
- deduplicate
- decide when to scan

All of those are routing decisions and they live in `:core`. A `Link` that can parse a beacon
will eventually make a routing decision, and the layering is gone. This is the most important
review criterion for this task.

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ble/AndroidLink.kt
```

## Implementation

```kotlin
class AndroidLink(
    context: Context,
    private val scope: CoroutineScope,
) : Link {

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val advertiser = BleAdvertiser(context)
    private val scanner = BleScanner(context)

    override val capabilities = LinkCapabilities(
        advertisingSlots = advertiser.advertisingSlots,
        supportsExtendedAdvertising = adapter.isLeExtendedAdvertisingSupported,
        maxBundleBytes = 244,
        canAdvertise = advertiser.canAdvertise,
    )

    override val events: Flow<LinkEvent> = /* merge scanner hits + window events + radio state */

    override suspend fun setAdvertisedBeacons(beacons: List<ByteArray>) { /* pass through */ }
    override suspend fun scanFor(windowMillis: Long) { /* delegate; emit ScanWindow open/close */ }
    override suspend fun sendBundle(peer: PeerHandle, payload: ByteArray): Boolean = false
    override suspend fun shutdown() { /* stop both radios */ }
}
```

**Capabilities must be read from the real adapter, not hardcoded.** `advertisingSlots` is 1 on
many budget devices and the protocol adapts to that — but only if you report it truthfully.

### Power tier → radio parameters

`MeshNode` gives you a `RadioPlan` with a `tier`. Map it to platform constants:

| Tier | `AdvertiseMode` | `ScanMode` | TX power |
|------|-----------------|------------|----------|
| BRIDGE | `ADVERTISE_MODE_LOW_LATENCY` | `SCAN_MODE_LOW_LATENCY` | `ADVERTISE_TX_POWER_HIGH` |
| RELAY | `ADVERTISE_MODE_BALANCED` | `SCAN_MODE_BALANCED` | `ADVERTISE_TX_POWER_MEDIUM` |
| GOSSIP | `ADVERTISE_MODE_BALANCED` | `SCAN_MODE_LOW_POWER` | `ADVERTISE_TX_POWER_MEDIUM` |
| FLARE | `ADVERTISE_MODE_LOW_POWER` | `SCAN_MODE_LOW_POWER` | `ADVERTISE_TX_POWER_MEDIUM` |
| EMBER | `ADVERTISE_MODE_LOW_POWER` | *never scans* | `ADVERTISE_TX_POWER_HIGH` |

EMBER uses **HIGH** transmit power deliberately: a nearly-dead phone is broadcasting rarely, so
each broadcast should carry as far as possible. It is spending its last energy on reach rather
than frequency.

Add a small mapper — `RadioPlan → (advertiseMode, scanMode, txPower)`. Reading `plan.tier` is
fine; that is configuration, not a routing decision.

### `sendBundle`

Return `false` unconditionally in v1. Rich bundles are a later task. **Do not implement GATT
here** — it is not on the critical path and it is a large time sink.

### Radio state

Register a `BroadcastReceiver` for `BluetoothAdapter.ACTION_STATE_CHANGED` and emit
`RadioUnavailable` when Bluetooth is turned off. Unregister in `shutdown()`.

## Wiring into the service

Update `SetuService` (from B1) to construct `AndroidLink`, an `AndroidNodeHost` (B5 — use a
temporary stub returning battery 100 / charging false / null position if B5 is not done), and a
`MeshNode`, then launch `meshNode.run()` in the service scope.

## Acceptance

```bash
./gradlew :app:assembleDebug
```

**This is integration gate G3 — the single most important checkpoint in the project.**

On **two physical devices**, both in **airplane mode with Bluetooth on**:
1. Both running SETU with the service started.
2. Device A originates an SOS.
3. **Device B logs a `BeaconHeard` whose 24 bytes match A's exactly.**

Nothing else in the project matters until this works.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] **G3 passed: beacon crosses two phones in airplane mode**, both hex dumps pasted
- [ ] `capabilities` reflects real adapter queries, not constants
- [ ] `grep -n "core.model\|core.codec" AndroidLink.kt` returns nothing
- [ ] no dedup, ordering, or scan-timing decisions in this class
- [ ] `Link.kt` unchanged — verify with `git diff --stat`
- [ ] Bluetooth off → `RadioUnavailable` emitted, no crash
