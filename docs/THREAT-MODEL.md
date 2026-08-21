# Threat model

What SETU defends against, what it does not, and why. Written plainly because an emergency system
that overstates its guarantees is worse than one that admits its limits.

## Assumptions

- The adversary is within Bluetooth range (~100 m) — this is a *local* threat model.
- Devices are unpaired strangers. There is no PKI, no accounts, no prior trust.
- The network is degraded: no internet, no time server, partitioned neighbourhoods.
- Most participants are ordinary people who installed an app, not hardened nodes.

## What SETU defends against

| Threat | Defence |
|--------|---------|
| **Broadcast storm** (accidental or malicious flooding) | `SeenSet` LRU dedup, TTL ≤ 7 hops, `densityDamp` suppresses redundant copies |
| **Battery drain by traffic volume** | `energyGate` hard floor; below 5% a node carries only its own message |
| **Corrupted frames from a noisy band** | CRC-8 rejection before routing |
| **Unbounded memory growth on a long-lived relay** | Outbox capacity 32 with severity-aware eviction; seen-set bounded by count *and* age |
| **One phone being drained on everyone's behalf** | Scanner election rotates duty via epoch-mixed tiebreak and 10% battery banding |
| **Stale messages consuming airtime forever** | 6 h outbox expiry for non-own messages; `RECEIPT`/`SAFE` cause immediate drop |

## What SETU does NOT defend against

Stated directly. Each of these is real.

### Beacon spoofing — **unmitigated**

Beacons are unsigned. There is no room for a 64-byte Ed25519 signature in a 24-byte packet.

Anyone can broadcast a beacon claiming any `originId`, any position, any severity. Nothing in v1
detects this.

- **Partial mitigation today:** rate-limiting per `originId`; the UI marks beacons *unverified*
  and distinguishes them from signed rich bundles.
- **Why we shipped it anyway:** the alternative is requiring a connection for every message,
  which destroys the power thesis and therefore the mesh. A signed-only protocol that flattens
  every battery in an hour saves nobody.
- **Roadmap:** signed rich bundles as corroboration — an SOS whose beacon is later confirmed by a
  signed bundle is promoted in the responder UI.

### Location privacy — **by design, partially**

An SOS broadcasts your position in the clear to everyone in radio range. That is the point: you
want to be found. But it also means a hostile party in range learns where you are.

Not appropriate for adversarial contexts (conflict zones, protest). SETU is built for natural
disasters, where the population in range is overwhelmingly other victims and responders.

### Traffic analysis — **unmitigated**

`originId` is stable for the lifetime of an install, so a passive listener can track a device's
movement across the mesh. Rotating identifiers conflict with dedup and with the neighbour-energy
table. Not solved.

### Sybil attacks on scanner election — **unmitigated**

Scanner election trusts the battery byte in beacons. A malicious node can claim 100% battery
forever and be elected scanner every epoch — or claim 4% and never contribute while still
consuming.

Consequence is degraded efficiency, not lost messages: real nodes still relay. But a determined
adversary could suppress the neighbourhood's scanning duty.

### Denial of service by jamming — **out of scope**

2.4 GHz jamming defeats SETU completely. No software mitigation exists.

### Malicious `RECEIPT` injection — **partially mitigated**

A forged `RECEIPT` causes carriers to drop a message that never actually reached a gateway,
silently killing an SOS.

- **Mitigation today:** the originator's own copy is never dropped by a `RECEIPT` — only relays
  drop. The victim's phone keeps broadcasting until the *user* marks themselves safe.
- **Residual risk:** real, and the reason `RECEIPT` authentication is the top roadmap item.

## Design principle

> Where a security control and the energy thesis conflict, the energy thesis wins in v1 — and the
> limitation gets written down here rather than quietly omitted.

The reasoning: a mesh with no participating nodes has zero security properties *and* zero
availability. Availability is the precondition for everything else. But that trade must be
explicit, which is what this document is for.

## Roadmap, in priority order

1. `RECEIPT` authentication — highest severity, clearest fix
2. Signed rich bundles as corroboration for unsigned beacons
3. Rate-limit heuristics per `originId` with UI surfacing of anomalies
4. Rotating identifiers, if a scheme compatible with dedup can be found
