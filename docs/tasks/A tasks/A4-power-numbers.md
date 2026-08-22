# TASK A4 — Simulator-backed numbers for POWER.md

> Copy everything below the line into your agent. **Do A1, A2, A3 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over Bluetooth LE. Its core claim — and the hackathon problem
statement's challenge question — is that the relay chain keeps working when every device is nearly
dead. `docs/POWER.md` describes six mechanisms that make that true.

Right now **every number in `docs/POWER.md` §8 is marked unmeasured**, and the `RadioCostModel`
constants are labelled order-of-magnitude estimates. That honesty is deliberate and must be
preserved. Your job is to add what the simulator can legitimately provide, kept clearly separate
from hardware measurements.

Read first: `docs/POWER.md` in full.

**FROZEN — do not modify:** `core/.../link/Link.kt`, `core/.../engine/NodeHost.kt`.

**Hard constraints**
- No new third-party dependencies. No plotting library — emit CSV and describe the charts.
- Do not change versions in `gradle/libs.versions.toml`.
- **Never present a simulated number as a measurement.** Every figure you add must be labelled
  `simulated`. A judge with a multimeter will ask, and an unlabelled model output is the fastest
  way to lose the argument.
- Do not claim the build passes without running it. Paste real output.

## Task

Produce four sweeps, write the results into `docs/POWER.md`, and make them reproducible.

### Sweep 1 — delivery ratio vs. starting battery
Sweep mean starting battery from 10% to 100% in steps of 10, `flood` scenario, 5 seeds each,
report mean and spread of delivery ratio.

### Sweep 2 — the energy gate is load-bearing
Same scenario, run with the energy gate active vs. forced to 1.0 (always relay). Report:
- mesh lifetime (virtual minutes until 50% of nodes are dead)
- delivery ratio over the whole run

Expected shape: ungated delivers slightly better early, then collapses as the mesh dies. If it
does **not** show that, report the actual result rather than massaging it — a negative result is
still a finding, and quietly tuning constants until the chart looks right is how this becomes
dishonest.

### Sweep 3 — phase-locked rendezvous is load-bearing *(the money chart)*
`flood` vs. `unsynced` (rendezvous phase randomised per node instead of derived from wall-clock).
Report delivery ratio and mean discovery latency for both.

This is the A/B for the single most-quoted claim in the pitch: *two nodes each listening 5% of
the time with independent phase overlap 0.25% of the time.* Verify that the simulator actually
reproduces the collapse.

### Sweep 4 — scanner election rotation
`flood`, with and without the 10% battery banding in `ScannerElection`. Report per-node mAh
spread (p95 minus median). Banding should visibly narrow it.

To run "without banding" you may add a **test-only** flag; do not change the production default.

## Files you may create or modify

```
CREATE  sim/src/main/kotlin/com/setu/mesh/sim/Sweeps.kt        // --sweep <n> mode in Main
MODIFY  sim/src/main/kotlin/com/setu/mesh/sim/Main.kt          // add --sweep
CREATE  _private/sweeps/*.csv                                   // raw output, gitignored
MODIFY  docs/POWER.md                                           // results section only
```

`_private/` is gitignored — put raw CSV there, not in the repo.

**Do NOT touch** `core/src/main/`, except that if a sweep genuinely cannot be run without a
seam, report what you need rather than adding it yourself.

## How to write the results into POWER.md

Add a new section **§9 Simulated results**, placed *after* the existing §8 measurement plan so
that measured and simulated stay visually separate. Use this shape:

```markdown
## 9. Simulated results

> Every figure in this section is **simulated**, produced by `:sim` against the cost model in
> `RadioCostModel`. These are model outputs, not measurements. Hardware measurements are in §8
> and are still pending.
>
> Reproduce with: `./gradlew :sim:run --args="--sweep 3 --seed 7"`

### 9.1 Phase-locked rendezvous vs. independent phase (simulated)
| Configuration | Delivery ratio | Mean discovery latency |
|---|---|---|
| wall-clock phase-locked | … | … |
| independent random phase | … | … |
```

Do **not** edit §8. Do **not** change the `RadioCostModel` constants — if a sweep suggests they
are wrong, say so in the text and leave them alone.

## Acceptance

```bash
./gradlew :sim:run --args="--sweep 1 --seed 7"
./gradlew :sim:run --args="--sweep 2 --seed 7"
./gradlew :sim:run --args="--sweep 3 --seed 7"
./gradlew :sim:run --args="--sweep 4 --seed 7"
```

Each must be reproducible: re-running with the same seed gives identical numbers.

## Definition of done

- [ ] all four sweeps run, output pasted
- [ ] `docs/POWER.md` §9 added with real numbers from those runs
- [ ] every figure in §9 is labelled **simulated**
- [ ] §8 unchanged; `RadioCostModel` constants unchanged
- [ ] reproduction command included next to each table
- [ ] if a sweep contradicted the design claim, that is stated plainly rather than hidden
