# TASK B3 — BLE scanner

> Copy everything below the line into your agent. **Do B1 and B2 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay. Emergency messages are 24 bytes and ride inside BLE legacy
advertisement service data, so relaying means hearing a beacon and re-advertising it. Scanning is
the expensive half of the radio — roughly 100× the cost of advertising — which is why SETU scans
in short, scheduled windows rather than continuously.

Read first: `docs/PROTOCOL.md` §1–2, `docs/POWER.md` §1–2.

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- No new third-party dependencies.
- `core/` and `sim/` are off limits.
- Do not claim it works without running it on a real device. Emulators cannot do BLE.

## THE GOTCHA THAT WILL COST YOU HOURS

**Android silently throttles an app to 5 `startScan` calls per 30 seconds.**

Exceeding it returns **no error, fires no callback, and yields no results**. Your code looks
correct, the logs look clean, and nothing is ever discovered.

Consequences for this task:
- enforce a **minimum 6-second gap** between scan starts, in code, not by convention
- log every `startScan` with a timestamp and the gap since the previous one
- if a caller asks for a scan too soon, **queue or drop it and log loudly** — never just call
  through
- SETU's 60-second rendezvous epoch issues at most one scan per epoch, comfortably inside the
  budget. **Do not "optimise" it to a faster cycle.** The epoch length exists because of this
  limit.

## Task

Implement scanning as a **bounded window**: start, listen for N milliseconds, stop. Not
start/stop calls, because of the throttle above.

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ble/BleScanner.kt
```

## Configuration

```kotlin
val filter = ScanFilter.Builder()
    .setServiceData(SetuUuids.SETU_SERVICE_UUID, byteArrayOf(), byteArrayOf())
    .build()

val settings = ScanSettings.Builder()
    .setScanMode(scanMode)                       // caller supplies; governor picks per tier
    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
    .setReportDelay(0)                           // 0 = immediate; non-zero batches, which
    .build()                                     //     breaks the bounded-window model
```

The empty mask on `setServiceData` matches *any* payload carrying our UUID — which is what we
want. A non-empty mask filters on payload content and will silently drop real beacons.

## Required API

```kotlin
class BleScanner(context: Context) {
    /** Emitted for each beacon heard, plus window open/close events. */
    val results: Flow<ScanHit>

    /** Start a scan, listen for [windowMillis], stop. Suspends for the duration. */
    suspend fun scanFor(windowMillis: Long, scanMode: Int)

    fun stop()
}

data class ScanHit(val payload: ByteArray, val address: String, val rssiDbm: Int, val atMillis: Long)
```

Extract the payload with:
```kotlin
result.scanRecord?.getServiceData(SetuUuids.SETU_SERVICE_UUID)
```

**Drop anything that is not exactly 24 bytes.** Do not pad, do not truncate — a wrong-length
payload is either a different app on the same UUID or a corrupt frame, and `:core` will reject it
anyway via CRC. Count and log drops; a high drop rate is a real signal during integration.

## Error handling

`ScanCallback.onScanFailed` codes — map each to a readable log line:

| Code | Meaning |
|---|---|
| `SCAN_FAILED_ALREADY_STARTED` | you did not stop the previous scan |
| `SCAN_FAILED_APPLICATION_REGISTRATION_FAILED` | usually a missing permission |
| `SCAN_FAILED_INTERNAL_ERROR` | stack wedged; needs a Bluetooth restart |
| `SCAN_FAILED_FEATURE_UNSUPPORTED` | device cannot scan |
| `SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES` | too many filters/scans |
| `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` | **the 5-per-30s throttle** — log this one very loudly |

Expose the most recent failure so the UI can surface it.

## Duplicate reports

BLE scanning reports the *same* advertiser repeatedly while it is in range. That is expected and
correct — deduplication happens in `:core` via `SeenSet`, keyed on message id.

**Do not deduplicate here.** Two beacons with identical payloads from different addresses are
meaningful (two nodes carrying the same message, which feeds density damping). Pass everything up.

## Acceptance

```bash
./gradlew :app:assembleDebug
```

On two physical devices: device A advertising from B2, device B scanning. Confirm B receives
A's exact 24 bytes.

Throttle test: call `scanFor` ten times in twenty seconds and confirm your rate limiter blocks
the excess and logs it, rather than hitting `SCAN_FAILED_SCANNING_TOO_FREQUENTLY`.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] device B receives device A's beacon, bytes match the logged hex exactly
- [ ] rate limiter enforces ≥6 s between scan starts, proven by the throttle test
- [ ] every `startScan` logged with timestamp and gap
- [ ] non-24-byte payloads dropped and counted
- [ ] no deduplication in this class
- [ ] `core/`, `sim/`, and all `build.gradle.kts` untouched
