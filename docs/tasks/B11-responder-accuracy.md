# TASK B11 — Responder view: own-SOS filter, position accuracy, compass map

> Copy everything below the line into your agent. **B1–B10 are done.** This is a bug fix in the
> `:app` UI and location layers. It changes no protocol code.

---

## Context

**Repo:** `acm_elicit2026`, branch `fix/latency-and-responder-accuracy`. Product name is
**SafeHop**; the Kotlin package is `com.setu.mesh`. That split is intentional, do not "fix" it.

SafeHop is an offline mesh SOS relay over raw BLE broadcast. The app has two sections on a bottom
nav bar: **SOS** (the victim) and **Help others** (`MeshScreen`, the responder). Mesh Lab and Raw
radio diagnostics live behind a hidden long-press on the SOS screen's tier badge — leave all of
that alone.

Three defects reported on hardware, all in the responder half:

1. **My own SOS shows up in "Help others".** It should not — that section is other people.
2. **The relative-position map shows the wrong distance and direction.**
3. **The map does not rotate.** It should behave like a compass: up = where the phone is pointing.

Read first: `docs/design.md` (visual language), `docs/PRD.md` §4 (users).

## Hard constraints

- **No new dependencies.** No map SDK, no sensor library, no icon pack. Do not change
  `app/build.gradle.kts` or `gradle/libs.versions.toml`. `material-icons-extended` is **not**
  available — use text or hand-drawn `Canvas` shapes.
- AGP 9 has built-in Kotlin: no `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- `core/` and `sim/` are **off limits**. In particular `GeoPoint` does not gain an accuracy field
  and `NodeHost.position()` keeps returning `GeoPoint?` — it is part of the frozen contract in
  `core/engine/NodeHost.kt`.
- Do not touch `ble/`, and touch `service/` only where Part 2 says.
- **No invented numbers.** Every distance, bearing and accuracy figure must come from a real fix
  or not be shown at all. A plausible-looking placeholder reads as a measurement and is worse
  than an honest blank.
- No emoji in UI. No pure black. Corner radii 14 dp / 28 dp per the `rounded` tokens.
- Neumorphic soft shadows are for secondary surfaces only. Severity colours, the SOS button and
  the SIMULATED banner stay flat and saturated — `Theme.kt` already states the rule ("safety
  colours must hold in any mode") and it holds here.

---

## Part 1 — Own SOS must not appear in "Help others"

**Files:** `app/ui/MeshViewModel.kt`, `app/ui/MeshScreen.kt`

`SetuService.carriedMessages()` returns the whole outbox, which includes this node's own SOS
(`Outbox.put(..., isOwn = true)`). `sortForResponder` then *deliberately pins own messages to the
top*:

```kotlin
compareByDescending<SosBeacon> { it.origin.raw == selfOriginRaw }
```

That was a B7 decision and it is now wrong. Fix:

- Exclude beacons whose `origin.raw` equals this node's id from the responder list.
- **Guard the unknown case:** `MeshScreen` currently computes `selfOriginRaw = snapshot?.id?.raw ?: -1`.
  When the id is unknown, filter **nothing** — never let a null id silently empty the screen. A
  `NodeId` raw is always in `0..0xFFFFFF`, so `-1` is already a safe sentinel; keep that property
  explicit rather than accidental.
- Delete the now-dead pinning comparator and the `selfOriginRaw` parameter from
  `sortForResponder`, and update its KDoc. Sort order stays: CRITICAL first, then newest within a
  severity. That ordering is a safety decision, not a display preference — say so in the KDoc as
  it already does.
- Decide *where* the filter lives and do it once. Filtering in `MeshViewModel.refresh()` needs the
  self id in the view model; filtering in `MeshScreen` has it already. Either is fine — pick one
  and do not do it in both.

**Do not "helpfully" re-add own-SOS status here.** The SOS screen already owns it: `StatusLadder`,
`ownSosMaxHops` and `ownSosDelivered` on `NodeSnapshot`. Duplicating it in Help others is the bug.

The empty state ("No SOS received. SafeHop is listening.") stays as it is, and will now correctly
be what a lone phone with its own SOS out sees on that tab.

---

## Part 2 — Position accuracy

**Files:** `app/service/AndroidNodeHost.kt`, `app/service/SetuService.kt`

### Root causes, already traced — do not re-derive them

`AndroidNodeHost` is why distances and bearings are wrong:

```kotlin
private const val LOCATION_UPDATE_MIN_TIME_MILLIS = 60_000L
private const val LOCATION_UPDATE_MIN_DISTANCE_METRES = 50.0f
```

1. **The fix effectively never updates** — at most once a minute, and only after moving 50 m. Two
   phones on a table share whatever fix each happened to have at startup.
2. **Only one provider is subscribed.** `requestLocationUpdates` picks GPS *or* NETWORK, never
   both. Indoors, GPS is enabled but yields nothing, so the node sits forever on a stale seed.
3. **The `getLastKnownLocation` seed has no age or accuracy check.** It can be hours old and from
   a different part of the city. This alone produces "wrong distance and direction" with no other
   bug present.

### Fixes

- Subscribe to **both** `GPS_PROVIDER` and `NETWORK_PROVIDER` when each is enabled, each with its
  own listener, and keep the better of the two. Use the standard "is this fix better" comparison:
  significantly newer (> 30 s), or better `accuracy`, or same provider and not significantly
  worse. Write it as one small private function with a comment explaining the rule — do not
  inline three conditions at the call site.
- Interval `1_000` ms, min distance `0f`. Justify it in a comment: GPS is not this app's battery
  story (the radio is), and during an emergency the position *is* the payload.
- Reject a `getLastKnownLocation` seed older than `MAX_SEED_FIX_AGE_MILLIS = 5 * 60_000`. Log when
  you drop one — a silently discarded seed is confusing during bring-up.
- Keep accuracy and timestamp alongside the point. `GeoPoint` is `:core` and must not grow a
  field, so introduce an **app-layer** type:

  ```kotlin
  data class SelfFix(
      val point: GeoPoint,
      val accuracyMetres: Float,
      val atMillis: Long,
      val provider: String,
  )
  ```

  `AndroidNodeHost.position()` keeps its exact current signature and behaviour (frozen contract);
  add `fun lastFix(): SelfFix?` beside it.
- `SetuService`: add `fun selfFix(): SelfFix?` to the companion, in the same shape as the existing
  `selfPosition()`, and reimplement `selfPosition()` on top of it so there is one source of truth.

### Show the fix quality — this is not optional

Two phones 5 m apart, each with a ±10 m fix, **cannot** produce a meaningful bearing. That is
physics, not a bug, and the UI must not pretend otherwise:

- The map gets a footer line: `Your fix: ±8 m · 3 s ago · GPS`. Real values only; when there is
  no fix, say so instead of showing a number.
- When `accuracyMetres > 30f` **or** the fix is older than 60 s, mark the map as degraded — a
  short muted caption such as `Position accuracy is poor; bearings are approximate.` Do not just
  grey pixels; say the words.
- The SOS screen gets a compact equivalent line so a victim knows whether their SOS carried a
  position at all.
- If `position()` was null when the user tapped SOS, the SOS screen must say **"Sent without
  location — still searching for GPS"**, prominently.

### Explicit non-goal: do not re-originate an SOS to correct its position

It is tempting, and it is wrong. The seen-set is keyed on `MessageId` folded with the message
type, so an "updated" beacon reusing the id is suppressed by every peer that already saw it —
the correction never propagates. Minting a fresh id instead puts **two live SOS from one person**
into a mesh whose whole design goal is to conserve airtime. Fixing the fix-acquisition path above
is the correct fix. Say this in a code comment where someone would otherwise try it.

---

## Part 3 — Compass rotation and a map that reads correctly

**Files:** `app/ui/components/RelativeMap.kt`, new `app/ui/components/Heading.kt`,
`app/ui/components/SosCard.kt`

The projection math in `relativeOffsetMetres` is **already correct** — equirectangular with a
per-latitude longitude correction, accurate to well under a metre at BLE range. Do not rewrite it.
What is missing is heading, scale, and honesty about precision.

### 3a. Heading source — new `Heading.kt`

```kotlin
/** True-north heading in degrees, or null when this device cannot provide one. */
@Composable
fun rememberTrueHeadingDegrees(self: GeoPoint?): Float?
```

- `SensorManager`, preferring `TYPE_ROTATION_VECTOR`, falling back to
  `TYPE_GEOMAGNETIC_ROTATION_VECTOR`, then `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` via
  `getRotationMatrix`. If none of those exist, return `null` — the map then stays north-up and
  labels itself `North up`. A phone without a magnetometer is common and must not crash or lie.
- `getRotationMatrixFromVector` → `remapCoordinateSystem` for the current display rotation →
  `getOrientation` → azimuth in radians → degrees.
- **Magnetic to true north:** the rotation vector's reference is *magnetic* north, but every
  bearing you compute from lat/lon is relative to *true* north. Add
  `GeomagneticField(lat, lon, 0f, System.currentTimeMillis()).declination`, which needs `self`;
  when `self` is null, skip the correction and note it. Declination reaches double-digit degrees
  in parts of the world — skipping it is a real error, not a rounding detail.
- **Smooth circularly.** A plain EMA over degrees jumps the long way round at the 359°→0° wrap and
  makes the map spin. Convert to a unit vector, EMA the components (α ≈ 0.15), `atan2` back.
- Register in a `DisposableEffect` at `SENSOR_DELAY_UI` and unregister on dispose.

### 3b. `RelativeMap`

- **The plot area must be square.** It is currently `fillMaxWidth().height(220.dp)`; rotating a
  non-square canvas clips the corners. Use a centred square of `min(width, height)`.
- Rotate plotted content by `-heading` about the centre so up = where the phone points. Distance
  rings and the self dot are rotation-invariant; the compass rose is not — draw an `N` tick that
  rotates, so the user can always find north.
- Draw a heading cone at the centre for the device's facing.
- **The tap hit-test must use the identical transform as the draw.** It currently duplicates
  `toScreenPoint` inside `detectTapGestures`. Once rotation exists, the duplicate will drift and
  taps will select the wrong report — a bug that is invisible in review and obvious on a phone.
  Factor **one** `private fun plotPoint(offsetMetres, maxRangeMetres, centre, scale, headingDegrees): Offset`
  and call it from both paths. Do not copy the rotation into two places.
- **Auto-range instead of the fixed 120 m.** `DEFAULT_MAX_RANGE_METRES = 120.0` makes a cluster of
  reports 10 m away land on top of the centre dot, which is a large part of why the map "shows the
  wrong distance". Pick the range from the furthest plotted beacon, snapped up to the nearest of
  25 / 50 / 100 / 250 / 500 / 1000 m, floor 25 m. Keep the existing clamp-to-edge behaviour for
  anything still beyond range — a report from far away is worth showing as "that direction, far",
  not silently dropping.
- Ring labels stay upright and readable; do not let them rotate with the map.

### 3c. Cardinal bearing on the cards

Add `bearingDegrees(self, other)` and a 16-point (or 8-point) `compassPoint(degrees)` helper
beside `relativeOffsetMetres`, and show `NE · 43 m` on `SosCard` — but **only** when both
positions are known and the self fix is not degraded per Part 2. Reports with
`position == GeoPoint.UNKNOWN` already land in the "Position unknown" section; leave that split
alone. A 220 dp map cannot be read to the metre, so the text is what a responder actually acts on.

---

## Acceptance

```
./gradlew :core:test :app:assembleDebug
```

must pass. Then confirm by reading your own diff:

- [ ] No file under `core/` or `sim/` changed; `GeoPoint` has no new field.
- [ ] `NodeHost.position()` signature and semantics unchanged.
- [ ] No change to `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- [ ] Own-origin beacons are filtered from Help others in exactly one place, and a null self id
      filters nothing.
- [ ] `sortForResponder` no longer takes `selfOriginRaw`, and all callers are updated.
- [ ] Both GPS and NETWORK are subscribed when available; stale seeds are rejected with a log.
- [ ] Accuracy and fix age are rendered from real values, with an explicit degraded state.
- [ ] No SOS is re-originated to correct a position, and a comment says why.
- [ ] Draw and hit-test share one `plotPoint` function — rotation is not duplicated.
- [ ] Heading falls back to north-up, labelled, when no suitable sensor exists.
- [ ] Magnetic declination is applied when a self fix is available.
- [ ] The SIMULATED banner in Mesh Lab is untouched.

Do not commit. Leave the work in the working tree and report what changed, and state plainly what
positional accuracy a user should actually expect from two phones a few metres apart.
