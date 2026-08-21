# Architecture

## Layers

```
┌──────────────────────────────────────────────────────────────┐
│  APPLICATION      SOS screen · Responder map · Mesh Lab      │   :app
├──────────────────────────────────────────────────────────────┤
│  ENGINE           MeshNode — origination, relay, snapshot    │
├──────────────────────────────────────────────────────────────┤
│  ROUTING          SeenSet · ForwardingPolicy · Outbox        │   :core
├──────────────────────────────────────────────────────────────┤
│  POWER GOVERNOR   PowerTier · Rendezvous · ScannerElection   │
├──────────────────────────────────────────────────────────────┤
│  LINK  (the seam) ─── opaque bytes, nothing above knows why  │
├───────────────────────────┬──────────────────────────────────┤
│  AndroidLink  (real BLE)  │  SimLink  (200 virtual nodes)    │  :app / :sim
└───────────────────────────┴──────────────────────────────────┘
```

## Modules

| Module | Plugin | Android? | Contents |
|--------|--------|----------|----------|
| `core/` | `kotlin-jvm` | **no** | protocol: model, codec, routing, power, engine, `Link` |
| `sim/` | `kotlin-jvm` + `application` | **no** | virtual world, mobility, battery model |
| `app/` | `com.android.application` | yes | BLE transport, foreground service, Compose UI |

`:app` depends on `:core` and `:sim`. `:sim` depends on `:core`. `:core` depends on nothing but
coroutines.

## The seam

[`core/link/Link.kt`](../core/src/main/kotlin/com/setu/mesh/core/link/Link.kt) and
[`core/engine/NodeHost.kt`](../core/src/main/kotlin/com/setu/mesh/core/engine/NodeHost.kt).

`Link` is the radio. `NodeHost` is the machine — clock, battery, GPS. Together they are the only
surface between protocol and platform.

Two rules keep it honest, and both are load-bearing:

1. **`Link` traffics in opaque byte arrays, never protocol types.** A Link that could parse a
   beacon would eventually be tempted to make a routing decision.
2. **Nothing in `:core` imports Android.** The `kotlin-jvm` plugin means the *compiler* enforces
   that, not a code review.

### Why this specific seam

**It makes the scale claim honest.** The simulator running 200 nodes executes the same `MeshNode`
class as the radio path — not a mock that happens to agree. When the demo shows a 200-node mesh
healing after node deaths, that is the shipping protocol, exercised.

**It unblocks parallel work.** `./gradlew :core:test` needs no device and no emulator. With the
seam frozen in hour one, protocol work and Android work never block each other:

| Builder | Owns | Needs a phone? |
|---------|------|----------------|
| **A** | `core/` + `sim/` | no |
| **B** | `app/` | yes |

**It is the fallback plan.** If raw BLE overruns the window, swapping `AndroidLink` for a Google
Nearby Connections implementation touches one class. `:core` is unaffected. See
[`adr/0002`](adr/0002-raw-ble-over-nearby-connections.md).

## Data flow — receiving a beacon

```
BLE scan callback
   → AndroidLink emits LinkEvent.BeaconHeard(payload, peer, rssi, at)
      → MeshNode.onBeaconHeard(payload, …)
         ├─ BeaconCodec.decode        → null on bad CRC / unknown version → dropped silently
         ├─ record neighbour battery  → feeds ScannerElection, free
         ├─ note clock sample         → feeds RendezvousScheduler
         ├─ note carrier for msgId    → feeds densityDamp, free
         ├─ RECEIPT / SAFE?           → Outbox.remove(referenced id)
         ├─ SeenSet.addIfNew          → already seen? stop
         ├─ beacon.relayed()          → TTL−1, hops+1; null if spent
         ├─ ForwardingPolicy.decide   → Relay(p) | Suppress(reason)
         └─ on Relay: Outbox.put
```

Three of those steps extract routing signal from a packet we were going to receive anyway.
Neighbour battery, clock, and carrier count all cost zero extra traffic.

## Data flow — the radio loop

```
loop:
   plan = PowerGovernor.plan(battery, charging, neighbours, now)
          ├─ battery ≤ 3%  → last-gasp burst
          ├─ PowerTier.forBattery
          ├─ RendezvousScheduler.scansInEpoch
          └─ ScannerElection.shouldScan
   Link.setAdvertisedBeacons(Outbox carousel slice)
   if plan.scanThisEpoch: Link.scanFor(window)
   ledger.bill…
   delay(plan.beaconIntervalMillis)
```

### The beacon carousel

Many Android devices report `isMultipleAdvertisementSupported() == false` and expose a single
advertising slot. When the outbox holds more messages than the radio has slots, `MeshNode`
rotates through them across successive intervals.

Ordering when airtime is scarce
([`Outbox.carouselOrder`](../core/src/main/kotlin/com/setu/mesh/core/routing/Outbox.kt)):

1. our own message first
2. then higher severity
3. then fewest known carriers — prefer messages nobody else is holding
4. then most recent

Eviction is the exact inverse: whatever is least worth broadcasting is the first thing dropped
when the buffer is full.

## Testability

`MeshNode`'s decision methods — `onBeaconHeard`, `planNow`, `beaconsToAdvertise` — are
synchronous and take an explicit `nowMillis`. No coroutine machinery is needed to test the
protocol, and the simulator can step time deterministically. `run()` is a thin loop over them.

Time is *always* a parameter, never `System.currentTimeMillis()` read inside a decision. That is
what makes rendezvous phase alignment testable at all.
