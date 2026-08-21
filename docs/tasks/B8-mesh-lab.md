# TASK B8 — Mesh Lab (in-app simulator view)

> Copy everything below the line into your agent.
> **Do B1–B7 and A2 first. This is the FIRST thing to cut if time runs short.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over BLE. With only two or three phones on stage, a live demo
cannot show multi-hop behaviour at scale. Mesh Lab runs the `:sim` module **in-app** and renders
a few hundred virtual nodes, so a judge can watch the mesh heal — and break it themselves.

The claim this screen supports is only credible because `:sim` drives the *same* `MeshNode` class
as the real radio path. It is not a mock or an animation.

Read first: `docs/ARCHITECTURE.md`, `docs/DEMO.md` (steps 4–5), and `:sim` from task A2.

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change `app/build.gradle.kts` (`:sim` is already a dependency) or
  `gradle/libs.versions.toml`.
- No new third-party dependencies. No charting library — draw with Compose `Canvas`.
- `core/` and `sim/` source are off limits; **consume** `:sim`, do not modify it. If you need a
  new hook, report it — Track A owns that module.
- **Never label simulated output as live.** See below.

## The honesty requirement

This screen shows **simulated** data. The demo script explicitly instructs the presenter to say
so out loud.

The UI must make it impossible to confuse with the real mesh:
- a persistent **"SIMULATED"** banner, always visible, not dismissable
- visually distinct from the live Mesh screen (different accent colour, different header)
- never share a component with the live view in a way that makes them look alike

A judge who thinks you passed off a simulation as hardware will discount everything else you
showed them. This banner is worth more than any feature on this screen.

## Files you may create

```
app/src/main/kotlin/com/setu/mesh/app/ui/lab/MeshLabScreen.kt
app/src/main/kotlin/com/setu/mesh/app/ui/lab/MeshLabViewModel.kt
app/src/main/kotlin/com/setu/mesh/app/ui/lab/NodeGraphCanvas.kt
```

## Requirements

### 1. Run the simulation off the main thread
Step `:sim` in `Dispatchers.Default`, publish snapshots to the UI at ~10 fps via a `StateFlow`.
**Never step the simulation on the main thread** — 200 nodes will freeze the UI.

Start with 100 nodes on a phone, not 200. Measure, then raise it if the frame time allows.

### 2. Node graph — `NodeGraphCanvas`
Compose `Canvas`:
- one dot per node, positioned by its simulated coordinates
- **fill colour = power tier** (BRIDGE/RELAY green, GOSSIP amber, FLARE orange, EMBER red, dead grey)
- thin lines between in-range pairs
- a brief highlight on a node when it relays a message
- pinch to zoom, drag to pan

Keep it to one `Canvas` with a single draw pass. Do not create a composable per node — 200
composables will not hold frame rate.

### 3. Live metrics strip
Delivery ratio · nodes alive · mean battery · tier histogram. Update at the snapshot rate, not
per simulation tick.

### 4. Let the judge break it — the point of the screen
- **tap a node → kill it.** Watch the mesh reroute.
- **drain slider** — drop every node's battery by N% at once, so the tier ladder visibly cascades
- **scenario picker** — `flood`, `drain`, `partition`, `dying-chain`, `unsynced`
- pause / resume / reset

`unsynced` is the most valuable one to demo: it randomises rendezvous phase instead of deriving
it from wall-clock time, and delivery visibly collapses. That is the A/B proving phase-locked
rendezvous is load-bearing rather than decorative.

### 5. Reset must be reliable
The judge will break it thoroughly. Reset must return to a clean, deterministic state every time
— reconstruct the `World` from the seed rather than trying to undo changes. A Mesh Lab that
wedges mid-demo is worse than no Mesh Lab.

## Explicitly do not build

- time-travel scrubbing or replay
- editing node positions by hand
- exporting data from the phone
- a second rendering path for the live mesh

## Acceptance

```bash
./gradlew :app:assembleDebug
```

On a physical device:
1. Launch Mesh Lab with 100 nodes; confirm the UI stays responsive (no dropped-frame stutter
   while panning).
2. Kill 10 nodes by tapping; confirm delivery ratio recovers.
3. Drain to 10%; confirm the tier histogram cascades toward FLARE/EMBER.
4. Switch to `unsynced`; confirm delivery ratio visibly drops.
5. Reset; confirm a clean state.
6. Confirm the SIMULATED banner is visible in every state, including full-screen zoom.

## Definition of done

- [ ] `:app:assembleDebug` green, output pasted
- [ ] 100 nodes run without UI stutter on the demo handset
- [ ] simulation steps off the main thread
- [ ] tap-to-kill, drain slider, scenario picker, and reset all work
- [ ] `unsynced` shows a visibly lower delivery ratio than `flood`
- [ ] **SIMULATED banner always visible and not dismissable**
- [ ] `core/` and `sim/` source untouched
