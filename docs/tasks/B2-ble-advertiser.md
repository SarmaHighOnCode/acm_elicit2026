# TASK B2 — BLE advertiser (the 24-byte beacon on air)

> Copy everything below the line into your agent. **Do B1 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay. A complete emergency message is encoded in **24 bytes** —
exactly the usable payload of a BLE *legacy* advertisement — so that relaying is just
"re-advertise this with TTL−1". No connection, no pairing, no GATT. Connectionless broadcast is
roughly 100× cheaper than scanning, which is what lets the mesh keep working on nearly-dead
phones.

Read first: `docs/PROTOCOL.md` §1–2, `docs/POWER.md` §0–1.

**The byte budget — this is why 24:**
```
  31   AD payload in a legacy advertisement
 − 3   Flags AD structure         (len + type + data)
 − 4   Service Data AD structure  (len + type + 16-bit UUID)
 ────
 = 24  usable
```

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- No new third-party dependencies. Use the platform BLE API directly.
- `core/` and `sim/` are off limits.
- Do not claim it works without running it on a real device. An emulator cannot do BLE.

## Task

Implement the advertiser: broadcast 24-byte beacons, with a carousel for when there are more
messages to advertise than the radio has slots.

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ble/SetuUuids.kt
app/src/main/kotlin/com/setu/mesh/app/ble/BleAdvertiser.kt
```

## The service UUID — get this right or nothing fits

```kotlin
// 16-bit UUID 0x5E70 expressed in the Bluetooth base UUID form. Android encodes this on air
// as a 2-byte UUID, which is what the 24-byte budget assumes.
val SETU_SERVICE_UUID: ParcelUuid =
    ParcelUuid.fromString("00005E70-0000-1000-8000-00805F9B34FB")
```

**A randomly generated 128-bit UUID costs 16 bytes on air and the beacon will not fit.** It must
be in the `0000XXXX-0000-1000-8000-00805F9B34FB` base form.

## Advertising configuration

```kotlin
AdvertiseSettings.Builder()
    .setAdvertiseMode(mode)              // caller supplies; governor picks per power tier
    .setTxPowerLevel(txPower)            // caller supplies
    .setConnectable(false)               // beacons are connectionless
    .setTimeout(0)
    .build()

AdvertiseData.Builder()
    .setIncludeDeviceName(false)         // MANDATORY — a device name alone can blow the budget
    .setIncludeTxPowerLevel(false)       // MANDATORY
    .addServiceData(SETU_SERVICE_UUID, beacon)   // beacon.size must be exactly 24
    .build()
```

Both `setInclude*` calls are mandatory. Forgetting either produces
`ADVERTISE_FAILED_DATA_TOO_LARGE`, and it is the single most common way this fails.

## Required API

```kotlin
class BleAdvertiser(context: Context) {
    /** Real slot count. Many devices report false and expose exactly one. */
    val advertisingSlots: Int
    val canAdvertise: Boolean          // bluetoothLeAdvertiser != null
    val supportsExtendedAdvertising: Boolean   // adapter.isLeExtendedAdvertisingSupported

    /** Replace the advertised set. Empty list stops advertising. Each entry must be 24 bytes. */
    fun setBeacons(beacons: List<ByteArray>, mode: Int, txPower: Int)

    fun stop()
}
```

- `advertisingSlots` = `if (adapter.isMultipleAdvertisementSupported) 4 else 1`
- reject any beacon whose size is not exactly 24 — log loudly and skip it, do not truncate
- always stop existing advertisers before starting new ones, or callbacks leak and the radio
  eventually refuses to start

## The beacon carousel

When `beacons.size > advertisingSlots` — the common case, since most budget devices expose one
slot — rotate. Advertise the first `slots` entries, and every `rotationIntervalMillis` (default
`1000`) shift the window forward by `slots` so every message eventually gets airtime.

`MeshNode.beaconsToAdvertise(slots, now)` already does the ordering and rotation on the protocol
side. Your job is only to put the bytes on the radio; **do not implement priority ordering here** —
that is routing logic and it belongs in `:core`.

## Error handling

Implement `AdvertiseCallback.onStartFailure` and map every code to a readable log line:

| Code | Meaning | Action |
|---|---|---|
| `ADVERTISE_FAILED_DATA_TOO_LARGE` | budget blown | log the byte count; check the two `setInclude*` flags |
| `ADVERTISE_FAILED_TOO_MANY_ADVERTISERS` | slots exhausted | reduce to 1 slot and retry |
| `ADVERTISE_FAILED_ALREADY_STARTED` | did not stop first | stop, then retry |
| `ADVERTISE_FAILED_INTERNAL_ERROR` | stack wedged | log; surface to the UI |
| `ADVERTISE_FAILED_FEATURE_UNSUPPORTED` | device cannot advertise | set `canAdvertise = false` |

Expose the most recent failure so the UI can show it. A silent advertiser is indistinguishable
from an empty room, and you will waste hours on that during integration.

## Verifying it actually transmits

Do not trust "no error" as proof. Confirm with a second device:

- **nRF Connect** (Nordic, free on Play Store) → Scanner → filter on `0x5E70` → confirm the
  service data is present and shows **exactly 24 bytes**
- log the hex of every beacon you hand to the radio and compare it byte-for-byte with what nRF
  Connect shows

## Acceptance

```bash
./gradlew :app:assembleDebug
```

Then on a physical device: start advertising a known 24-byte test pattern and confirm in nRF
Connect on a **second** device that the bytes match exactly.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] nRF Connect on a second device shows the service data, exactly 24 bytes, matching the logged hex
- [ ] `advertisingSlots` reports the device's real capability
- [ ] carousel rotates when given more beacons than slots — verified in logs
- [ ] every `onStartFailure` code produces a readable log line
- [ ] no priority/ordering logic in this class
- [ ] `core/`, `sim/`, and all `build.gradle.kts` untouched
