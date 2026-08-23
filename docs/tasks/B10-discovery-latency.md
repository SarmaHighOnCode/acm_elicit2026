# TASK B10 — Discovery latency: attentive mode

> Copy everything below the line into your agent. **B1–B9 are done and merged.** This is a bug
> fix, and unlike every other B task it **deliberately edits `:core`** — the defect is a
> scheduling decision and scheduling lives in `:core`. The frozen contract files stay untouched.

---

## Context

**Repo:** `acm_elicit2026`, branch `fix/latency-and-responder-accuracy`. Product name is
**SafeHop**; the Kotlin package is `com.setu.mesh`. That split is intentional, do not "fix" it.

SafeHop is an offline mesh SOS relay over raw BLE broadcast — no GATT, no pairing. A node
advertises a 24-byte beacon and relays what it hears with TTL−1.

**The bug, as reported on hardware:** tapping SOS on phone A takes **1–2 minutes** to show up on
phone B.

Read first: `docs/POWER.md` §1–3, `docs/PROTOCOL.md` §1–2.

## Root cause — already traced for you, do not re-derive it

The sending side is fine. `MeshNode.originateSos` puts the beacon in the outbox and the very next
loop iteration calls `link.setAdvertisedBeacons`, so it is on air within one
`beaconIntervalMillis` (500 ms–2 s). Confirm this, then leave it alone.

The **receiving** side is the whole problem. In `MeshNode.run()`:

```kotlin
if (plan.scanThisEpoch && plan.inRendezvousWindow && plan.scanWindowMillis > 0) {
    link.scanFor(plan.scanWindowMillis)
}
```

- `inRendezvousWindow` is true only in the **first 1 000 ms of each 60 000 ms wall-clock epoch**
  (`RendezvousScheduler.isInWindow`).
- `scanThisEpoch` additionally requires `scansInEpoch(epoch, tier)` — BRIDGE/RELAY every epoch,
  GOSSIP every 2nd, FLARE every 4th — **and** winning `ScannerElection.shouldScan`.

So the listener opens its ears once per epoch at best. Discovery latency is uniform on:

| Tier | Battery | Latency window |
|---|---|---|
| BRIDGE / RELAY | >30% or charging | 0–60 s (mean 30 s) |
| GOSSIP | 15–30% | 0–120 s |
| FLARE | 5–15% | 0–240 s |
| EMBER | <5% | never — deaf by design, correct |

"1–2 minutes" is exactly a GOSSIP-tier phone, or an unlucky BRIDGE draw.

**This is not a mistake.** Phase-locked low duty cycling is the core power argument and it must
survive this fix intact. What is missing is a way to spend energy on latency *when spending it is
both affordable and useful*.

## What you are building — attentive mode

A bounded, guarded, high-duty-cycle listening state. When a node is attentive it scans nearly
continuously, so discovery drops from ~30 s to roughly one beacon interval.

### The arithmetic you must respect

`BleScanner` enforces `MIN_SCAN_START_GAP_MILLIS = 6_000` because Android silently throttles an
app to **5 `startScan` calls per rolling 30 s** — over the limit there is no error, no callback
and no results. Android throttles *calls*, **not duration**. So attentive mode gets its duty
cycle from a **long window**, never from frequent starts:

```
ATTENTIVE_SCAN_WINDOW_MILLIS = 12_000
→ one startScan per ~12 s  =  2.5 per 30 s   (limit 5)  ✅
→ duty cycle ≈ 100% of the time the loop is not doing something else
```

Do **not** pick a short window and loop faster. A 3 s window would be ~10 starts per 30 s, the
throttle engages, and the app silently discovers nothing — which is worse than the bug you are
fixing and looks identical to working code.

### Where it goes

**`core/power/PowerGovernor.kt`** — `plan(...)` takes a new parameter `attentive: Boolean = false`.
When attentive is *honoured*, the returned `RadioPlan` has:

- `scanThisEpoch = true` — bypasses both `scansInEpoch` and `ScannerElection`
- `inRendezvousWindow = true` — bypasses the 1 s gate
- `scanWindowMillis = ATTENTIVE_SCAN_WINDOW_MILLIS`
- everything else (tier, beacon interval, `mayOpenConnections`) unchanged

**Attentive is refused, silently and without exception, when:**

- the node is in last gasp (`batteryPercent <= LAST_GASP_BATTERY_PERCENT && !charging`) — that
  early-return path in `plan()` stays exactly as it is, and
- `!charging && batteryPercent < ATTENTIVE_MIN_BATTERY_PERCENT` where
  `ATTENTIVE_MIN_BATTERY_PERCENT = 20`.

That guard is the entire reason this fix is defensible: a phone at 12% behaves exactly as it does
today, so every claim in `docs/POWER.md` about surviving on a nearly-dead handset still holds. A
charging phone is always eligible — it is gaining energy.

**`core/engine/MeshNode.kt`** — owns when attentive is on:

1. `fun setAttentive(active: Boolean)` — public, driven from `:app` by Activity lifecycle
   (foreground = attentive). Rationale to put in the KDoc: the screen being on already costs an
   order of magnitude more than the radio, and a user watching the app expects it to be live.
2. Auto for `ATTENTIVE_AFTER_SOS_MILLIS = 120_000` after `originateSos()` — the originator wants
   its RECEIPT back fast.
3. Auto for `ATTENTIVE_AFTER_SOS_MILLIS` after `onBeaconHeard` decodes a `MessageType.SOS` — the
   neighbourhood is live, so relay onward promptly instead of sitting on it for a minute.

Model this as one `attentiveUntilMillis: Long` plus one `foregroundAttentive: Boolean`, folded
into a single private `isAttentive(nowMillis)` that `planNow` and `refreshSnapshot` both call.
Do not scatter the condition.

**`nextSleepMillis` is the trap that will silently undo this whole task.** It currently sleeps
`min(beaconInterval, millisUntilNextWindow(...))` whenever the tier scans and the node is outside
the window. While attentive, the node must **not** park until the next rendezvous window — sleep
`beaconIntervalMillis` (floored at `MIN_LOOP_DELAY_MILLIS`) instead. Get this wrong and the plan
says "scan now" while the loop is asleep for 50 s, and the fix does nothing on hardware while
looking correct in review.

**`NodeSnapshot`** — you may add `val attentive: Boolean = false`. This is the one field addition
authorised for this task; do not add others.

### App plumbing

**`app/service/SetuService.kt`** — add a companion function alongside the existing
`setBatteryOverride`, same shape, same null-safety:

```kotlin
/** No-op when the mesh is not running. */
fun setAttentive(active: Boolean) {
    runningInstance?.meshNode?.setAttentive(active)
}
```

**`app/MainActivity.kt`** — call it from the plain Activity callbacks:

```kotlin
override fun onStart() { super.onStart(); SetuService.setAttentive(true) }
override fun onStop()  { super.onStop();  SetuService.setAttentive(false) }
```

Use the Activity overrides, **not** a Compose `LifecycleEventObserver`. There is no
`lifecycle-runtime-compose` in the version catalog and you may not add one; `LocalLifecycleOwner`
has moved packages between Compose versions and is not worth the risk here.

## Hard constraints

- **Do not modify** `core/link/Link.kt` or `core/engine/NodeHost.kt`. They are the frozen contract
  and changing either blocks the other track. This fix does not need them — verify that claim
  rather than assuming it.
- **No new dependencies.** Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- AGP 9 has built-in Kotlin: no `org.jetbrains.kotlin.android` plugin, no `kotlinOptions {}` block.
  An agent trained on AGP 8 will try to add both. Do not.
- **Wall clock, never uptime.** `nowMillis()` is `System.currentTimeMillis()`. The rendezvous
  schedule is derived from absolute time — that is how phones meet without exchanging scheduling
  messages, and `elapsedRealtime()` breaks it silently.
- Do not touch `sim/`. Do not touch anything under `app/ui/` except nothing at all — this task has
  no UI work beyond the two Activity overrides above.
- Do not weaken `BleScanner.MIN_SCAN_START_GAP_MILLIS`. It is load-bearing.
- Do not shorten `DEFAULT_EPOCH_MILLIS`. The 60 s epoch is sized against the same Android throttle
  and shortening it is the wrong fix — it would raise the floor cost for *every* node at *every*
  battery level, which is precisely what attentive mode exists to avoid.

## Tests

Extend the existing suites; do not create new files unless there is genuinely no home for a case.

`core/src/test/kotlin/com/setu/mesh/core/power/PowerGovernorTest.kt`:
- attentive above the battery floor ⇒ `scanThisEpoch`, `inRendezvousWindow`, and a
  `scanWindowMillis` of `ATTENTIVE_SCAN_WINDOW_MILLIS`, even at a wall-clock instant that is
  outside the rendezvous window and in an epoch the tier would normally skip
- attentive at 12% and not charging ⇒ plan is byte-for-byte what it would be with
  `attentive = false`
- attentive at 12% **and charging** ⇒ honoured
- attentive in last gasp ⇒ refused, `lastGasp` still true, `inRendezvousWindow` still false

`core/src/test/kotlin/com/setu/mesh/core/engine/MeshNodeTest.kt`:
- `originateSos` leaves the node attentive; it is no longer attentive
  `ATTENTIVE_AFTER_SOS_MILLIS + 1` later
- hearing an SOS beacon makes the node attentive; hearing a RECEIPT or SAFE does not
- `setAttentive(true)` holds regardless of elapsed time until `setAttentive(false)`

All existing tests must stay green **unmodified**. If an existing test fails, your change is
wrong — do not edit the test to match it.

## Docs

`docs/POWER.md` is the single source of truth for the power story and it currently describes the
duty cycle without attentive mode, which will be wrong once you are done. Add a subsection to §2
covering: what triggers attentive mode, the 12 s window / 5-per-30 s arithmetic above, and the
20% battery guard. Update the §1 tier table's surrounding prose if it now reads as absolute.
**Do not create a new doc for this** — the workspace rule is update-over-create, and a second
document describing scan scheduling is exactly the fragmentation that rule exists to prevent.

## Acceptance

```
./gradlew :core:test :app:assembleDebug
```

must pass. Then confirm by reading your own diff:

- [ ] `Link.kt` and `NodeHost.kt` unchanged.
- [ ] No change to `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- [ ] `ATTENTIVE_SCAN_WINDOW_MILLIS >= 2 * BleScanner.MIN_SCAN_START_GAP_MILLIS`.
- [ ] `nextSleepMillis` does not park until the next rendezvous window while attentive.
- [ ] Attentive is refused below 20% on battery, and in last gasp, with tests proving both.
- [ ] `MeshNode.run()`'s scan condition still consults `plan`, not a second copy of the logic.
- [ ] `docs/POWER.md` §2 describes attentive mode; no new doc file was created.
- [ ] Nothing under `sim/` or `app/ui/` changed.

Do not commit. Leave the work in the working tree and report what changed, including the measured
`startScan` rate your window size implies.
