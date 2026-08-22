# TASK B6 — SOS screen (Compose)

> Copy everything below the line into your agent. **Do B1–B5 first, and pass gate G3.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SafeHop is an offline mesh SOS relay over BLE. This screen is what a stranded person uses. Design
for the actual situation: **panicking, one-handed, possibly in darkness, possibly in water, phone
at 4%.** Every interaction assumption you would normally make is wrong here.

Read first: `docs/PRD.md` §4 (users), `docs/POWER.md`.

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.
- No new third-party dependencies. Material 3 only, no icon packs, no image loaders.
- `core/` and `sim/` are off limits.
- Do not invent battery figures. Show only what `NodeSnapshot`/`EnergyLedger` actually reports.

## Data available

```kotlin
data class NodeSnapshot(
    val id: NodeId,
    val tier: PowerTier,               // BRIDGE | RELAY | GOSSIP | FLARE | EMBER
    val batteryPercent: Int,
    val carrying: Int,                 // messages held for other people
    val neighbourCount: Int,
    val advertising: Boolean,
    val scanning: Boolean,
    val lastGasp: Boolean,
    val ownSos: SosBeacon?,
    val ownSosMaxHops: Int,            // furthest hop count seen for our own SOS
    val ownSosDelivered: Boolean,      // a RECEIPT came back
    val energyMilliampHours: Double,
    val beaconsRelayed: Long,
)
```

Actions: `MeshNode.originateSos(flags: SituationFlags, souls: Int)` and `MeshNode.markSafe()`.

```kotlin
data class SituationFlags(
    val severity: Severity = Severity.MODERATE,   // LOW | MODERATE | HIGH | CRITICAL
    val medicalNeed: Boolean = false,
    val trapped: Boolean = false,
    val waterRising: Boolean = false,
    val vulnerableOccupant: Boolean = false,
    val mobilityImpaired: Boolean = false,
    val hasRichBundle: Boolean = false,
)
```

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ui/SosScreen.kt
app/src/main/kotlin/com/setu/mesh/app/ui/SosViewModel.kt
app/src/main/kotlin/com/setu/mesh/app/ui/components/TierBadge.kt
app/src/main/kotlin/com/setu/mesh/app/ui/components/StatusLadder.kt
```

## Requirements

### 1. Three taps maximum to a sent SOS
- **SOS button: at least 30% of screen height**, full width, in the **bottom half** where a thumb
  reaches. Not a small button in the middle, and not at the top.
- Tapping it sends immediately with defaults. Triage refines afterwards — **never block sending
  on a form.** A person under water does not fill in a form.
- Long-press to cancel within 3 seconds guards against pocket taps, with a visible countdown.

### 2. Triage, after sending
Severity selector, souls counter (− / +, default 1), and three toggles: **trapped**, **medical**,
**water rising**. Large targets, minimum 56dp. Every change re-originates with updated flags.

### 3. Status ladder — the emotional core
```
✓ SOS created
✓ Carried by 3 phones
✓ 5 hops out
⏳ Waiting for a rescuer to confirm
```
Drive it from `carrying`, `ownSosMaxHops`, `ownSosDelivered`. When `ownSosDelivered` becomes
true, make it unmistakable — this is the moment a frightened person learns they were heard.

### 4. Tier badge
Show `tier` prominently — the demo points at it. Colour it: BRIDGE/RELAY green, GOSSIP amber,
FLARE orange, EMBER red. When `lastGasp` is true, show a distinct state.

Add one plain-language line explaining the current tier, e.g. for EMBER:
> *Battery critical. Still broadcasting, no longer listening, to stay findable for longer.*

### 5. Energy honesty
```
SafeHop used 1.8% of your battery in 3h and carried 47 messages for 12 people.
```
From `energyMilliampHours` and `beaconsRelayed`. **If the numbers are not available yet, show
nothing** — do not display a placeholder that looks like a real measurement.

### 6. Legible in the dark, and cheap
- background `#0A0A0B`, near-black — on OLED this genuinely draws less power, which ties the UI
  to the project thesis
- amber/red accents, high contrast, body text ≥18sp, status text ≥24sp
- no animations that run continuously; a pulsing SOS button costs frames and battery. One
  transition on state change is enough.

### 7. Accessibility
Content descriptions on every control, and the SOS button must be reachable by TalkBack in one
swipe from the top.

## Explicitly do not build

- a chat interface — SafeHop is a transport for emergencies, not a messenger
- a map on this screen (that is B7)
- onboarding, tutorials, or a splash screen
- settings beyond the debug battery override

## Acceptance

```bash
./gradlew :app:assembleDebug
```

On a physical device:
1. Send an SOS in three taps or fewer, timed from a locked screen.
2. Confirm it appears on a second device.
3. Verify the ladder advances as hops accumulate.
4. Force battery override to 4% and confirm the badge shows EMBER with its explanation.
5. Look at it in a dark room and check it is readable without adjusting brightness.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] SOS sendable in ≤3 taps, one-handed, verified on a real device
- [ ] status ladder advances from real snapshot data
- [ ] tier badge shows all five states with plain-language explanations
- [ ] no invented numbers anywhere in the UI
- [ ] no continuously-running animations
- [ ] `core/`, `sim/`, and all `build.gradle.kts` untouched
