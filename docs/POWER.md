# POWER.md — the battery answer

> **Challenge question:** How does the relay chain keep working when every device in it is
> running on a nearly-dead battery?

This document is the full answer. Six mechanisms, each implemented in
[`core/power/`](../core/src/main/kotlin/com/setu/mesh/core/power/) and
[`core/routing/`](../core/src/main/kotlin/com/setu/mesh/core/routing/), plus an honest account of
what has actually been measured.

---

## 0. The asymmetry everything rests on

The two BLE radio modes do not cost remotely the same.

| Operation | Reference figure |
|-----------|------------------|
| Continuous **scanning** | ~40 mW (nRF51822, active continuous scan) |
| **Advertising** | ~600 µW |

That is roughly **two orders of magnitude**. Phone-class figures differ from silicon-class
figures, but the *ratio* is the design input, and it is stable: listening is expensive, shouting
is cheap.

Two consequences drive the whole protocol:

1. **The base case must be broadcast, not connect.** SETU relays by re-advertising a 24-byte
   beacon (see [`PROTOCOL.md`](PROTOCOL.md) §1). No connection, no pairing, no GATT.
2. **When battery falls, cut listening before shouting.** Being *findable* matters more than
   being *sociable*. A dying phone should be selfish.

---

## 1. The power ladder

[`PowerTier.kt`](../core/src/main/kotlin/com/setu/mesh/core/power/PowerTier.kt)

| Tier | Battery | Beacon interval | Scan window | Scans every | Connections |
|------|---------|-----------------|-------------|-------------|-------------|
| **BRIDGE** | >60% or charging | 500 ms | 8 s | epoch | yes |
| **RELAY** | 30–60% | 1 s | 3 s | epoch | yes |
| **GOSSIP** | 15–30% | 1 s | 1.5 s | 2 epochs | no |
| **FLARE** | 5–15% | 2 s | 1 s | 4 epochs | no |
| **EMBER** | <5% | 10 s | — | **never** | no |

Note what does *not* happen: the ladder never stops advertising. EMBER is deliberately **deaf but
still shouting** — it cannot hear anyone, and it is still findable by anyone who is listening.

A **charging** device jumps straight to BRIDGE whatever its level. It is gaining energy, so
spending it on the neighbourhood costs its owner nothing.

---

## 2. Phase-locked rendezvous — the failure mode nobody demos

Here is what quietly kills naive low-power meshes.

Two nodes each listening 5% of the time, with independent phase, overlap **0.25%** of the time.
They will essentially never hear each other. The network is dead, and both phones report that
everything is fine.

SETU derives every wake window from **absolute wall-clock time**, not from each node's own
uptime:

```
epoch  = floor(unixMillis / 60_000)
window = [epoch × 60_000, +1_000 ms)
```

Every node in the region wakes inside the *same one-second window* — without ever having
exchanged a scheduling message. Low tiers skip whole epochs but stay phase-aligned
(`epoch % n == 0`), so a FLARE node listening once every four minutes lands exactly on a window a
BRIDGE node is listening through.

[`RendezvousScheduler.kt`](../core/src/main/kotlin/com/setu/mesh/core/power/RendezvousScheduler.kt)

**Clock degradation** is graceful: GPS time → last NTP sync → consensus drift from the `epochMin`
byte every beacon already carries, damped to ¼ of the observed error per observation.

### It also dodges a hard Android limit

Android throttles an app to **5 `startScan` calls per 30 seconds**. Implementations that
duty-cycle by rapidly stopping and starting scans hit this, get silently throttled, and stop
discovering anything — with no error. One scan per 60-second epoch is comfortably inside the
budget. The epoch design is *why* this constraint is not a problem, not a patch applied after
hitting it.

---

## 3. Zero-message scanner election

Scanning is the expensive half, so in any neighbourhood we want **few listeners and many
shouters**.

The obvious way to arrange that is to negotiate roles. But negotiation traffic costs exactly the
energy we are trying to save, and it fails precisely when the network is degraded.

SETU already puts each node's battery level in every beacon. That one byte is enough:

- Every node sees the same neighbourhood table, built from beacons it overheard for free.
- Every node applies the same deterministic ranking.
- Every node independently reaches the same answer.
- The top `ceil(√n)` elect themselves scanners for the next epoch.

**Zero coordination messages.**

[`ScannerElection.kt`](../core/src/main/kotlin/com/setu/mesh/core/power/ScannerElection.kt)

Two details that matter:

- **Battery is bucketed into 10% bands.** Without banding, the single best-charged phone would
  scan every epoch forever and be drained on everyone else's behalf. Banding lets everyone within
  10% of each other take turns.
- **The tiebreak is mixed with the epoch number**, so the duty rotates deterministically and
  identically on every node.

`√n` rather than a fixed fraction because coverage redundancy should grow far more slowly than
density — in a crowded shelter that means a handful of listeners for a hundred phones.

Under partition, each partition simply elects its own scanners. Nothing needs to notice.

---

## 4. Effort flows toward whoever is worse off

Standard DTN energy-aware routing (EA-Epidemic and relatives) forwards copies toward
**higher**-energy nodes. SETU inverts that into a rule that is simultaneously energy-optimal and
ethically defensible:

> **Relay preferentially for people worse off than you.**

If the originator's battery — carried in the beacon — is *higher* than yours and they are not
critical, damp hard. They can afford to keep broadcasting for themselves; you may not be able to.

```
altruismGradient = 1.0   if selfBattery ≥ originBattery
                 = 0.6   if within 15 points
                 = 0.25  otherwise
                 = 1.0   always, if severity is CRITICAL
```

And underneath it, the hard floor:

```
energyGate = 1.0   at ≥40%
           = 0.6   at ≥15%
           = 0.25  at ≥5%
           = 0.0   below 5%   — except 0.15 for CRITICAL
```

**A node always relays its own SOS.** No gate applies, at any battery level. A phone at 1% still
shouts for its owner; it just stops volunteering to carry for strangers.

Below 5%, a node carries nothing but its own message — with one exception. A CRITICAL beacon
still gets a 0.15 chance, because a wrong suppression there costs a life and a wrong relay costs
milliamps.

---

## 5. Delivery confirmation *is* an energy optimisation

When a gateway accepts an SOS, a `RECEIPT` propagates back and every node holding that message
**drops it**. Same for `SAFE` when someone marks themselves out of danger.

This is not politeness. Each dropped message frees advertising slots in the beacon carousel and
buffer in the outbox, which is airtime and battery returned to messages that still need moving.
In a mesh, *confirming delivery is how you reclaim capacity.*

---

## 6. Last-gasp flush

Below **3%**, conservation is pointless — the phone is going to die either way.

SETU stops conserving and spends the remainder burst-advertising its entire outbox at 250 ms
intervals, so a healthier neighbour picks up custody before the lights go out.

[`PowerGovernor.kt`](../core/src/main/kotlin/com/setu/mesh/core/power/PowerGovernor.kt)

> **Your phone dying is not your SOS dying.**

---

## 7. The energy ledger

[`EnergyLedger.kt`](../core/src/main/kotlin/com/setu/mesh/core/power/EnergyLedger.kt)

Every radio operation is billed in mAh so the app can say, in plain language:

> *SETU used 1.8% of your battery in 3 hours and carried 47 messages for 12 people.*

This is a **product feature, not instrumentation**. An app that quietly drains a phone gets
uninstalled before the disaster, and a mesh with no participating nodes relays nothing. The
relay-user's trust is the network's availability. The same cost model drives the simulator's
battery curves.

---

## 8. What is measured and what is not

**Nothing in this section is measured yet.** The constants in `RadioCostModel` are
order-of-magnitude estimates:

| Constant | Default | Basis |
|----------|--------:|-------|
| `advertiseMilliampsAt1Hz` | 1.2 mA | estimate |
| `scanMilliamps` | 18.0 mA | estimate |
| `connectionMilliamps` | 12.0 mA | estimate |

### Measurement plan

| # | Test | Method | Result |
|---|------|--------|--------|
| M1 | Idle drain baseline | 2 h, app not running, screen off, airplane mode | *pending* |
| M2 | EMBER tier | 2 h beacon-only at 10 s, screen off | *pending* |
| M3 | FLARE tier | 2 h, 2 s beacon + 1 s scan per epoch | *pending* |
| M4 | RELAY tier | 2 h, 1 s beacon + 3 s scan per epoch | *pending* |
| M5 | BRIDGE tier | 2 h continuous scan | *pending* |
| M6 | Ledger accuracy | compare ledger mAh against measured Δ battery | *pending* |

Method: `dumpsys batterystats`, screen off, airplane mode with Bluetooth on, same handset for all
runs, battery capacity read from the device spec sheet.

> **Rule for the pitch:** quote M1–M6 or quote nothing. A smaller verified number beats a larger
> invented one, and a judge who owns a multimeter will ask.

---

## References

- Kang & Chung, *Energy-aware routing in intermittently connected DTNs*, 2017
- *EA-Epidemic: An Energy Aware Epidemic-Based Routing Protocol for DTNs*, J. Communications 12(6), 2017
- *Optimal Energy-Aware Epidemic Routing in DTNs*, ACM MobiHoc 2012 / IEEE TAC
- Spyropoulos et al., *Spray and Wait*, 2005
- *Connection-less BLE Performance Evaluation on Smartphones*, Procedia CS, 2019
- [bitchat WHITEPAPER](https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md) — TTL, dedup and duty-cycling on the same radio
- [Android BLE advertising](https://source.android.com/docs/core/connect/bluetooth/ble_advertising)

---

## 9. Simulated results

*All numbers in this section are from deterministic multi-run simulation runs (`:sim`, seed = 7, 5 runs per data point). Real hardware figures will replace them when M1–M6 run.*

### 9.1 Delivery ratio vs. starting battery

Swept mean starting battery across 100 nodes in the `flood` scenario over 20 virtual minutes.

| Starting Battery % | Mean Delivery Ratio | Spread [Min, Max] |
|-------------------|---------------------|-------------------|
| 10% | 32.0% | [20.0%, 50.0%] |
| 20% | 54.0% | [40.0%, 70.0%] |
| 30% | 52.0% | [40.0%, 60.0%] |
| 40% | 44.0% | [30.0%, 50.0%] |
| 50% | 64.0% | [40.0%, 80.0%] |
| 60% | 72.0% | [50.0%, 100.0%] |
| 70% | 80.0% | [60.0%, 100.0%] |
| 80% | 80.0% | [50.0%, 100.0%] |
| 90% | 100.0% | [100.0%, 100.0%] |
| 100% | 100.0% | [100.0%, 100.0%] |

### 9.2 Energy gate impact

Comparison of 100-node mesh performance when the energy gate is active vs. forced to 1.0 (always relay).

| Configuration | Mean Delivery Ratio | 30-min Mesh Survival |
|---------------|---------------------|----------------------|
| **Gated** | 34.0% | 100% |
| **Ungated (Force 1.0)** | 40.0% | 100% |

### 9.3 Phase-locked rendezvous impact

Comparison of delivery ratio and discovery time under wall-clock phase locking vs. independent random phases (`unsynced`).

| Configuration | Mean Delivery Ratio |
|---------------|---------------------|
| **Wall-clock phase-locked** | 72.0% |
| **Independent random phase** | 70.0% |

### 9.4 Scanner election rotation

Comparison of energy distribution across nodes over 30 virtual minutes with vs. without 10% battery banding in `ScannerElection`.

| Configuration | Mean p95 mAh | Mean Median mAh | Mean mAh Spread (p95 - Median) |
|---------------|--------------|-----------------|--------------------------------|
| **With 10% Banding** | 9.51 mAh | 1.15 mAh | **8.36 mAh** |
| **Without Banding** | 9.90 mAh | 0.73 mAh | **9.17 mAh** |

*Banding visibly narrows the energy spread between nodes (8.36 mAh vs 9.17 mAh) by rotating scanner election duty among similarly-charged peers.*

