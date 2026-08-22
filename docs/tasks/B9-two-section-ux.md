# TASK B9 — Two-section UX rebuild (SOS + Help others)

> Copy everything below the line into your agent. **B1–B8 are done and merged.** This task
> restructures the app shell and rebuilds the SOS screen's layout. It changes no protocol code.

---

## Context

**Repo:** `acm_elicit2026`, branch `feature/app-skeleton`. App name is **SafeHop**; the Kotlin
package and internal module names are `setu`/`com.setu.mesh` — that split is intentional, do not
"fix" it.

SafeHop is an offline mesh SOS relay over BLE. Today the app ships **four** top-level tabs —
`SOS`, `Mesh`, `Mesh Lab`, `Diagnostics` — in a `PrimaryTabRow` in `MainActivity.kt`. Those four
tabs serve four completely different audiences (panicking victim / responder / demo judge /
developer) at equal visual weight, which is why the app reads as incoherent. A person drowning is
one tap from a button labelled "Advertise 1 beacon".

**You are collapsing this to two sections and rebuilding the SOS screen around a central circular
button.**

Read first: `docs/design.md` (visual language), `docs/PRD.md` §4 (users), `docs/POWER.md`.

## Hard constraints

- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- **Do not change `app/build.gradle.kts` or `gradle/libs.versions.toml`.**
- **No new dependencies.** In particular there is **no `navigation-compose`** in the version
  catalog — navigation stays hand-rolled with a state enum plus Material 3 `NavigationBar`.
  No icon packs either: `material-icons-extended` is *not* a dependency, so use text labels or
  hand-drawn `Canvas`/vector shapes for nav icons.
- `core/` and `sim/` are **off limits**. This is a UI-layer task only.
- Do not invent battery, energy, or delivery figures. Render only what `NodeSnapshot` /
  `EnergyLedger` actually report — a placeholder number looks like a real measurement.
- Do not touch `service/`, `ble/`, or `data/`. If you think you need a new field on
  `NodeSnapshot`, stop and report instead of adding one.

---

## Part 1 — Two-section shell

**File:** `app/src/main/kotlin/com/setu/mesh/app/MainActivity.kt`

Replace the four-tab `PrimaryTabRow` with a **Material 3 `NavigationBar` at the bottom**, two
destinations:

| Destination | Label | Screen |
|---|---|---|
| 0 | `SOS` | `SosScreen()` |
| 1 | `Help others` | `MeshScreen()` |

- Bottom placement is deliberate: one-handed thumb reach for someone in a panic. Do not move it
  back to the top.
- Selected index must survive rotation — keep `rememberSaveable`.
- `PermissionGate` gating stays exactly as it is.
- Each `NavigationBarItem` needs a `contentDescription`. Touch targets ≥ 48dp.

### Mesh Lab and Diagnostics do not disappear

Both leave the nav but **stay in the app**, reachable through one hidden entry:

- **Long-press the `TierBadge`** at the top of the SOS screen (add a `Modifier` parameter to
  `TierBadge` and attach `combinedClickable` at the call site; do not bake the gesture into the
  component itself).
- That opens a **Developer** screen — a full-screen overlay or replacement composable with a
  visible back affordance — containing Mesh Lab and Raw radio diagnostics as two internal tabs.
- No visual hint that the long-press exists. No toast, no badge, no "hold for dev tools" text.

Why they survive: Mesh Lab is the 100-node judge demo, and Diagnostics is the fastest path to
debugging `AndroidLink`/`BleAdvertiser`/`BleScanner` if **gate G3 (a beacon crossing two physical
phones) fails on hardware** — G3 is still unverified.

**Move the inline `DiagnosticsScreen`** and its `MeshControls` / `AdvertiserTestControls` /
`ScannerTestControls` composables **out of `MainActivity.kt`** into
`app/src/main/kotlin/com/setu/mesh/app/ui/dev/DiagnosticsScreen.kt`, unchanged in behaviour.
`MainActivity.kt` should end up as the shell and nothing else.

`MeshLabScreen`, `MeshLabViewModel`, and `NodeGraphCanvas` keep their current package and
behaviour. **The yellow `SIMULATED — not live radio data` banner is non-negotiable and stays
exactly as loud as it is.** Presenting a simulated hop as a live one is the single fastest way to
lose a technical judge's trust.

---

## Part 2 — SOS screen rebuild

**File:** `app/src/main/kotlin/com/setu/mesh/app/ui/SosScreen.kt`

### Target layout

```
┌────────────────────────────┐
│  [ TierBadge ]             │   fixed — long-press = dev entry
│                            │
│          ╭──────╮          │
│         │  SOS   │         │   fixed, never scrolls off
│          ╰──────╯          │   circular, red, centred
│                            │
│   ── status ladder ──      │   only when an SOS is outstanding
│ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ │
│  Situation   [L][M][H][C]  │   scrolls
│  People here      −  1  +  │
│  Trapped              [✓]  │
│  Medical need         [ ]  │
│  Water rising         [ ]  │
├────────────────────────────┤
│    ◉ SOS   │  Help others  │
└────────────────────────────┘
```

1. **Top:** `TierBadge` (unchanged data, plus the long-press modifier).
2. **Centre, fixed:** the circular SOS button. It must **never scroll out of view** at any screen
   size — put it in a non-scrolling region, with only the triage block below it scrolling.
3. **Below, scrolling:** `StatusLadder` when `sosActive`, then the triage controls
   (Situation / People here / Trapped / Medical need / Water rising), then `EnergySummary`.

### The SOS button

- **Circular.** Diameter from `BoxWithConstraints`: roughly `min(maxWidth, maxHeight) * 0.55f`,
  clamped to `160.dp..260.dp`, so it holds on a small phone and in landscape.
- **Red, flat, high contrast.** Use `colorScheme.secondary` (`#EF5350`) / `onSecondary`. See the
  neumorphism note below — this specific button gets **no** soft shadow treatment.
- Label `SOS` when idle. When `sosActive`, the button becomes the resend affordance and its label
  changes accordingly (`SOS SENT` with a smaller `Tap to resend` line, or equivalent).
- **Tapping sends immediately.** No confirmation dialog, no form first. Triage refines afterwards
  and must never block sending.
- **Pull "I am safe now" out of the button.** Today it is a nested `clickable` `Text` *inside* the
  SOS button — that is an accessibility bug and it will not fit inside a circle. Make it a
  separate, clearly secondary control (outlined, calm colour, ≥48dp) rendered below the circle
  only when `sosActive`.
- Keep `contentDescription = "Send emergency SOS"` and give the safe control its own description.

### Invariants you must not break

These are load-bearing and each one is a bug that was already fixed once:

- **`sosActive` is derived, never mirrored:** it is exactly `snapshot?.ownSos != null`. Do not
  introduce a UI boolean tracking "did I send" — on rotation or service restart it desyncs and the
  screen claims "not sent" while the mesh is still broadcasting.
- **Triage state stays `rememberSaveable`** so a rotation does not silently reset someone's
  "trapped / water rising" answers.
- **Editing triage while active re-sends** (`resendIfActive()`), so the mesh carries the corrected
  situation rather than a stale one. Preserve this on every control.
- `EnergySummary` renders only when energy `> 0.0`.

---

## Part 3 — Visual language

`docs/design.md` specifies neumorphism. Apply it **the way that document itself prescribes**:

> "Nobody ships a full neumorphic interface anymore — that experiment failed. But the technique
> survived as an accent. It works best in controlled doses."

Concretely:

- **Neumorphic soft-shadow treatment → secondary surfaces only:** triage cards, the counter
  buttons, toggle rows, the People-here stepper, section containers. Two shadow layers
  (light top-left, dark bottom-right), 14dp radius, 150ms press animation.
- **Emergency elements → flat and saturated, never neumorphic:** the SOS button, severity
  selection state, the `StatusLadder`, the tier badge, the SIMULATED banner. Neumorphism encodes
  state as *subtle shadow difference*, which is exactly the wrong choice for a screen read at 4%
  battery, one-handed, possibly in darkness or water. `Theme.kt` already states this rule —
  "Emergency accents (amber/red/green) unchanged — safety colours must hold in any mode" — and it
  holds here.
- Reuse the existing tokens in `ui/theme/Theme.kt`. If you add neumorphic shadow helpers, put them
  in a new `ui/theme/Neumorphic.kt` as reusable `Modifier` extensions, and make them read
  `LocalIsDarkTheme` — soft shadows need different light/dark treatment (dark mode's `#0A0A0B`
  OLED background cannot carry a lighter-than-surface highlight the same way).
- Corner radius 14dp / 28dp per the `rounded` tokens. No emoji in new UI. No pure black.

The responder section's user-facing name in the nav is **"Help others"**. Section headers inside
`MeshScreen` ("Position known" / "Position unknown") stay as they are.

---

## Files you may create or modify

```
app/src/main/kotlin/com/setu/mesh/app/MainActivity.kt              rewrite shell
app/src/main/kotlin/com/setu/mesh/app/ui/SosScreen.kt              rebuild layout
app/src/main/kotlin/com/setu/mesh/app/ui/components/TierBadge.kt   add Modifier param
app/src/main/kotlin/com/setu/mesh/app/ui/theme/Theme.kt            tokens only, no colour changes
app/src/main/kotlin/com/setu/mesh/app/ui/theme/Neumorphic.kt       NEW  shadow modifiers
app/src/main/kotlin/com/setu/mesh/app/ui/dev/DiagnosticsScreen.kt  NEW  moved from MainActivity
app/src/main/kotlin/com/setu/mesh/app/ui/dev/DeveloperScreen.kt    NEW  hidden Lab+Diag host
```

`MeshScreen.kt`, `MeshViewModel.kt`, `RelativeMap.kt`, `SosCard.kt`, `StatusLadder.kt`,
`MeshLabScreen.kt`, `MeshLabViewModel.kt`, `NodeGraphCanvas.kt` — touch only if the shell change
forces it.

## Acceptance

```
./gradlew :app:assembleDebug
```

must pass. Then confirm by reading your own diff:

- [ ] Exactly two items in the bottom `NavigationBar`; no `PrimaryTabRow` remains.
- [ ] `MainActivity.kt` contains no test-control composables.
- [ ] Long-pressing the tier badge reaches both Mesh Lab and Diagnostics.
- [ ] The SIMULATED banner is untouched.
- [ ] The SOS button is circular, red, centred, and cannot scroll out of view.
- [ ] "I am safe now" is not nested inside the SOS button.
- [ ] `sosActive` is still `snapshot?.ownSos != null` with no mirrored boolean anywhere.
- [ ] Every triage control still calls `resendIfActive()`.
- [ ] All triage state is still `rememberSaveable`.
- [ ] No new entry in `libs.versions.toml` or `app/build.gradle.kts`.
- [ ] No file under `core/` or `sim/` changed.

Do not commit. Leave the work in the working tree and report what changed.
