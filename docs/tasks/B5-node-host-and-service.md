# TASK B5 — `AndroidNodeHost` and the live foreground service

> Copy everything below the line into your agent. **Do B1–B4 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over BLE. The protocol (`:core`) is pure Kotlin and reaches the
platform through two frozen interfaces: `Link` (the radio, done in B4) and `NodeHost` (the
machine — clock, battery, GPS). **Battery is a first-class protocol input**: nearly every routing
and scheduling decision is a function of it.

Read first: `docs/POWER.md`, `docs/ARCHITECTURE.md`.

**FROZEN — do not modify:** `core/src/main/kotlin/com/setu/mesh/core/engine/NodeHost.kt`

```kotlin
interface NodeHost {
    fun nowMillis(): Long          // wall clock, NOT uptime
    fun batteryPercent(): Int      // 0..100, or 255 = BATTERY_UNKNOWN
    fun isCharging(): Boolean
    fun position(): GeoPoint?      // null until first fix
    fun hasTrustedClock(): Boolean
}
```

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- No new third-party dependencies. **Use the platform `LocationManager`, not Play Services
  location** — it is one less dependency and works without Google services.
- `core/` and `sim/` are off limits.
- Do not claim it works without running it on a real device.

## Task

Implement `NodeHost` for Android and wire the whole node together inside the foreground service.

## Files you may create or modify

```
CREATE  app/src/main/kotlin/com/setu/mesh/app/service/AndroidNodeHost.kt
MODIFY  app/src/main/kotlin/com/setu/mesh/app/service/SetuService.kt
CREATE  app/src/main/kotlin/com/setu/mesh/app/data/NodeIdentity.kt
```

## `AndroidNodeHost`

### `nowMillis()`
```kotlin
override fun nowMillis(): Long = System.currentTimeMillis()
```
**Wall clock, not `SystemClock.elapsedRealtime()`.** The rendezvous scheduler derives its wake
window from absolute time — that is the entire mechanism by which phones meet without exchanging
scheduling messages. Uptime would break it silently: every phone would have its own phase, and
discovery would collapse without any visible error.

### `batteryPercent()`
```kotlin
batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
```
Cache it and refresh from `ACTION_BATTERY_CHANGED` rather than polling — this is read on every
protocol tick. Return `255` (`BATTERY_UNKNOWN`) if the read fails.

### `isCharging()`
`BatteryManager.isCharging` on API 23+.

### `position()`
`LocationManager.getLastKnownLocation` across GPS and network providers, whichever is fresher.
Convert to `GeoPoint.of(lat, lon)`. Return **null** until a first fix exists — the protocol
handles that; do not substitute `(0, 0)`, which is a real place in the Atlantic and would put a
false pin on the responder map.

Request updates at a low rate (60 s / 50 m). GPS is not the power problem here, but there is no
reason to burn it either.

### `hasTrustedClock()`
`true` if `System.currentTimeMillis()` came from GPS or a network time sync. A reasonable
approximation: `Settings.Global.AUTO_TIME` is enabled. When this returns `false`, the protocol
falls back to consensus drift correction using timestamps carried in beacons.

### Debug battery override — the demo depends on this

```kotlin
/** Debug-only. Forces batteryPercent() to return a fixed value. Null restores the real reading. */
fun setBatteryOverride(percent: Int?)
```

Guard with `if (BuildConfig.DEBUG)`. The demo forces a phone to 4% so it drops to EMBER tier
on stage — draining a real phone to 4% before a demo is not an option.

## `NodeIdentity`

A stable 24-bit node id, persisted so it survives restarts:

```kotlin
object NodeIdentity {
    fun get(context: Context): NodeId   // NodeId.fromSeed(persistedUuid)
}
```

Generate a random UUID on first run, store it in `SharedPreferences`, derive the `NodeId` with the
existing `NodeId.fromSeed(seed: String)`. **Do not use `ANDROID_ID` or any hardware identifier** —
it is a privacy problem and it is not needed.

## `SetuService` wiring

```kotlin
val host = AndroidNodeHost(this)
val link = AndroidLink(this, serviceScope)
val node = MeshNode(NodeIdentity.get(this), link, host)
serviceScope.launch { node.run() }
```

Expose `node.snapshot` (a `StateFlow<NodeSnapshot>`) to the UI via a binder or a singleton holder.

Update the notification live from the snapshot:
> **SETU · RELAY** — carrying 3 · 5 nearby

Keep the update rate low (every few seconds, not every tick) — notification churn is itself a
battery cost, which would be an embarrassing thing to get wrong in this particular app.

## Lifecycle

- `START_STICKY`
- restart on `BOOT_COMPLETED` if the user had it running (needs a receiver — optional, note it if
  you skip it)
- cancel the scope and call `link.shutdown()` in `onDestroy`
- **Android 15+**: a foreground service cannot always be started from the background. Start it
  from a user action (a button), not automatically at launch.

## Acceptance

```bash
./gradlew :app:assembleDebug
```

On a physical device:
1. Start the service; confirm the notification shows a real tier and counts.
2. Watch the tier change as battery drops, or force it with `setBatteryOverride`.
3. **Override to 4% → tier must become EMBER and scanning must stop entirely** (verify in logs:
   no `startScan` calls).
4. Background the app for 10 minutes; confirm the service survives and beacons still go out.
5. Toggle Bluetooth off and on; confirm recovery without a crash.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] notification shows live tier and carried count
- [ ] battery override to 4% → EMBER, zero `startScan` calls afterwards (log evidence pasted)
- [ ] `nowMillis()` uses `System.currentTimeMillis()`, **not** `elapsedRealtime`
- [ ] `position()` returns null before first fix, never `(0,0)`
- [ ] node id is stable across app restarts, derived from a generated UUID, not a hardware id
- [ ] service survives 10 minutes backgrounded
- [ ] `NodeHost.kt` unchanged — verify with `git diff --stat`
