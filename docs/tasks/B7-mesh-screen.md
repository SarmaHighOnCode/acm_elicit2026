# TASK B7 — Mesh / responder screen

> Copy everything below the line into your agent. **Do B1–B6 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SafeHop is an offline mesh SOS relay over BLE. This screen is the **responder** view: what a rescue
worker or a bystander-with-signal sees. It lists the SOS messages this device has received or is
carrying.

Read first: `docs/PRD.md` §4, `docs/THREAT-MODEL.md` (**important — see the verification section
below**).

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- **No new third-party dependencies.** In particular **no map SDK** — no Google Maps, no
  MapLibre, no tile downloads. A map that needs the internet is absurd in an offline-mesh app,
  and it would fail on stage. Draw positions on a Compose `Canvas` with relative coordinates.
- `core/` and `sim/` are off limits.

## Data available

Each carried message is an `OutboxEntry`:
```kotlin
class OutboxEntry(
    val beacon: SosBeacon,
    val addedAtMillis: Long,
    val isOwn: Boolean,
) {
    val neighboursHoldingCopy: Int
}
```

```kotlin
data class SosBeacon(
    val type: MessageType, val ttl: Int, val hops: Int,
    val messageId: MessageId, val origin: NodeId,
    val position: GeoPoint,          // GeoPoint.UNKNOWN if the sender had no fix
    val epochMinute: Int,
    val flags: SituationFlags,       // severity + trapped/medical/water/vulnerable/mobility
    val souls: Int,
    val originBattery: Int,
) {
    val isCritical: Boolean
    fun ageMinutes(nowMillis: Long): Int
}
```

You will need a way to read the outbox. If `MeshNode` does not expose it, **report that rather
than adding it yourself** — Track A owns `:core` and an uncoordinated change there causes a merge
conflict at the worst possible moment.

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ui/MeshScreen.kt
app/src/main/kotlin/com/setu/mesh/app/ui/MeshViewModel.kt
app/src/main/kotlin/com/setu/mesh/app/ui/components/SosCard.kt
app/src/main/kotlin/com/setu/mesh/app/ui/components/RelativeMap.kt
```

## Requirements

### 1. Triage-sorted list — sort order is a safety decision
1. `CRITICAL` first, then HIGH, MODERATE, LOW
2. within a severity, **newest first**
3. own messages pinned to the top

Each card shows: severity, souls, situation icons (trapped / medical / water rising), age in
minutes, hop count, distance and bearing if both positions are known, and originator battery.

### 2. Verification state — do not skip this

Beacons are **unsigned**. There is no room for a 64-byte signature in 24 bytes, so anyone in
range can broadcast a beacon claiming any identity, position or severity. This is a real,
documented limitation.

Every card must be visibly marked **"Unverified"**. When signed rich bundles exist (a later
task), those get a distinct "Verified" state.

Do not present unverified reports as confirmed facts. Overstating certainty in an emergency tool
is worse than admitting the limit, and a judge who reads the threat model will check for exactly
this.

### 3. Relative position view
A Compose `Canvas`. Own device at centre, SOS positions plotted relative to it, scaled to fit,
with a distance ring and a scale label. Colour by severity.

If `position == GeoPoint.UNKNOWN`, show the card in a separate **"Position unknown"** group —
never plot it at the origin.

### 4. Empty state
> *No SOS received. SafeHop is listening.*

Show tier and neighbour count so the user can tell the difference between "nothing is happening"
and "the app is broken". That distinction matters a great deal in a disaster.

### 5. Detail sheet
Tap a card: full beacon detail, hop count, TTL remaining, message id, how many neighbours are
known to be carrying it, and the raw 24 bytes as hex. The hex is genuinely useful — it is the
fastest way to debug a protocol problem during integration, and it demos well.

## Explicitly do not build

- online map tiles of any kind
- a chat or reply feature
- clustering, heatmaps, or route planning
- responder claim/assignment (that is a later protocol feature, `RESPONDER_CLAIM`)

## Acceptance

```bash
./gradlew :app:assembleDebug
```

On two physical devices: A sends SOS at each severity in turn; B shows them correctly sorted,
each marked Unverified, plotted on the canvas when position is known and grouped separately when
not.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] sort order verified with at least one message of each severity
- [ ] every card marked **Unverified**
- [ ] unknown positions grouped separately, never plotted at origin
- [ ] empty state shows tier and neighbour count
- [ ] hex dump matches the sender's logged bytes
- [ ] no map SDK, no network calls, no new dependencies
- [ ] `core/`, `sim/`, and all `build.gradle.kts` untouched
