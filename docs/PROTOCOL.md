# SafeHop Protocol v1

Wire format and forwarding rules. Implemented in
[`core/src/main/kotlin/com/setu/mesh/core/`](../core/src/main/kotlin/com/setu/mesh/core/).

---

## 1. The size budget

Everything about this protocol is downstream of one number.

A BLE **legacy advertisement** carries 31 bytes of AD payload. Android emits a mandatory Flags
structure, and a Service Data structure with a 16-bit UUID costs four more:

```
  31   AD payload
 − 3   Flags          : length(1) + type(1) + data(1)
 − 4   Service Data   : length(1) + type(1) + UUID16(2)
 ────
 = 24  usable
```

If the whole message fits in 24 bytes, a relay is just *"broadcast this again with TTL−1"* — no
connection, no pairing, no GATT, no handshake. That is the cheapest possible relay, and it is why
SafeHop keeps working on a phone that cannot afford anything else.

**Design rule: if a field does not earn its byte, it does not ship.**

## 2. Beacon layout (24 bytes, big-endian)

```
 off size field      bits / encoding
 ─────────────────────────────────────────────────────────────────────
  0    1   verType    [7:5] version=1  [4:2] type  [1:0] posClass
                      posClass: 0 unknown/no fix/>100m · 1 ≤10m · 2 ≤30m · 3 ≤100m
  1    1   ttlHops    [7:4] ttl 0..15  [3:0] hops 0..15
  2    4   msgId      uint32, dedup key
  6    3   originId   uint24, node id
  9    4   lat        int32, degrees × 1e7
 13    4   lon        int32, degrees × 1e7
 17    3   epochMin   uint24, minutes since 2024-01-01T00:00:00Z
 20    1   flags      [7:6] severity  [5] medical  [4] trapped
                      [3] waterRising [2] vulnerable [1] mobility [0] hasBundle
 21    1   souls      uint8, people at this location
 22    1   battery    uint8, originator battery % (255 = unknown)
 23    1   crc8       CRC-8/ATM, poly 0x07, over bytes 0..22
```

Codec: [`BeaconCodec.kt`](../core/src/main/kotlin/com/setu/mesh/core/codec/BeaconCodec.kt).

### Field notes

**`lat`/`lon` as int32 × 1e7** — nominal ~1 cm resolution, which is absurd for a phone GPS and
costs nothing extra. int32 is simply the cheapest encoding covering ±180°. Real accuracy travels
in the rich bundle.

**`epochMin` as 24-bit minutes** — 16.7 M minutes ≈ 31 years of range from 2024 for three bytes.
Second-level precision is worthless in a disaster and would cost a byte we do not have. It
doubles as the mesh's shared coarse clock (§6).

**`battery`** — one byte, two optimisations: the altruism gradient (§5) and zero-message scanner
election (§7). Best value-per-byte in the protocol.

**`crc8`** — not a security control; an attacker recomputes it trivially. It exists to reject
frames garbled by a noisy 2.4 GHz band before they reach routing.

**`posClass`** — two bits riding for free in byte 0's previously-reserved space, and the cheapest
fix available for a real bug: `lat`/`lon` carry no quality signal at all, so a receiver cannot
tell a fix taken a second ago from one accepted minutes before a GPS outage and never refreshed.
Two bits cannot carry a metre figure, but they do not need to — a coarse bucket (≤10 m / ≤30 m /
≤100 m / worse-or-unknown) is enough for a responder to size an uncertainty circle instead of
trusting a point that might be tens of metres from where the sender actually is. `version` stays
`1`: an old build simply never sets these bits, which decodes as class 0 (unknown) on any
receiver — the honest answer, since that build never measured accuracy either — and a new build's
beacon decodes cleanly on an old one, because the old decode never looks at bits [1:0] in the
first place. Zero-byte cost, both directions compatible.

### Truncated identifiers are a deliberate trade

`originId` is 24-bit and `msgId` is 32-bit. Neither is globally unique.

With ~200 devices in one radio neighbourhood, `originId` collision probability is under 1.2×10⁻³.
The consequence of a collision is **one duplicate relay inside a 10-minute window**, never a lost
SOS, because deduplication keys on `msgId` rather than on the originator.

## 3. Message types (3 bits, all 8 allocated)

| Wire | Type | Meaning |
|-----:|------|---------|
| 0 | `SOS` | someone needs help — the only type a victim device originates |
| 1 | `RECEIPT` | reached a gateway; carriers may now drop the original |
| 2 | `DIGEST` | anti-entropy summary of what this node holds |
| 3 | `RESPONDER_CLAIM` | a team has taken this one, do not duplicate effort |
| 4 | `SAFE` | cancels a prior SOS, reclaims buffer and airtime |
| 5 | `ADVISORY` | authority-to-mesh broadcast, injected only at gateways |
| 6 | `CUSTODY_ACK` | receiver owns delivery; sender may delete its copy |
| 7 | `RESERVED` | future use |

### Referencing another message with no room for two ids

`RECEIPT`, `SAFE` and `CUSTODY_ACK` need to point at an existing message. There is no room for
both a fresh id and a reference, so **`msgId` carries the referenced message's id**, and the
deduplication key folds the type in:

```kotlin
dedupKey = msgId XOR (type × 0x9E3779B1)
```

A `RECEIPT` for message *M* is therefore deduplicated independently of *M* itself while still
pointing at it. Zero extra bytes.

## 4. TTL and hop accounting

- Fresh SOS: `ttl = 7`, `hops = 0`.
- A relay emits `ttl−1, hops+1`. At `ttl == 0` the message stops propagating but is still
  carried and displayed locally.
- Both fields are 4 bits, so 15 is a hard ceiling.
- `hops` is provenance: it is what the victim's UI shows as *"carried by 3 phones, 7 hops out."*

TTL 7 matches what BLE controlled floods sustain in practice before duplicate suppression
dominates, and matches bitchat's choice on the same radio.

## 5. Forwarding decision

[`ForwardingPolicy.kt`](../core/src/main/kotlin/com/setu/mesh/core/routing/ForwardingPolicy.kt).

**Hard rule first: a node always relays its own SOS.** No gate applies at any battery level. A
phone at 1% still shouts for its owner; it just stops volunteering to carry for others.

For everything else, three multiplicative gates:

```
P(relay) = w_severity × energyGate(selfBattery) × altruismGradient × densityDamp(k)
```

| Gate | Rule |
|------|------|
| `w_severity` | CRITICAL 1.0 · HIGH 0.85 · MODERATE 0.6 · LOW 0.35 |
| `energyGate` | ≥40% → 1.0 · ≥15% → 0.6 · ≥5% → 0.25 · below 5% → 0.0 (CRITICAL: 0.15) |
| `altruismGradient` | self ≥ origin → 1.0 · within 15 pts → 0.6 · else → 0.25. CRITICAL bypasses. |
| `densityDamp` | `min(1, 3 / k)` where *k* = distinct neighbours already re-advertising this id |

**The altruism gradient inverts the textbook rule.** Standard DTN routes copies toward
higher-energy nodes. SafeHop relays preferentially *for people worse off than you*: if the
originator has more battery than you and is not critical, they can afford to keep shouting and
you may not be able to. Energy-optimal and ethically right at the same time.

`k` is observed for free — a node counts distinct peers it has heard re-advertising the same
`msgId`. No extra traffic.

Every decision returns a `RelayDecision` carrying its reason (`ENERGY_GATE`,
`ALTRUISM_GRADIENT`, `DENSITY_DAMPED`, `PROBABILISTIC`, `TTL_EXHAUSTED`), so the Mesh Lab and a
field tester's diagnostics screen can both show *why* a node stayed quiet.

Two more reasons exist on the wire engine's side of `MeshNode.onBeaconHeard`, not inside
`ForwardingPolicy.decide` itself: `MALFORMED` (bad CRC or an unknown version — the frame never
reached the policy at all) and `DUPLICATE` (a repeat hearing of a message this node already has a
terminal answer for). Both used to be invisible in exactly the same way a lost `PROBABILISTIC`
roll was — nothing happened, and there was no way to tell any of them apart. A field test that
found phone b never getting a relay phone a heard cleanly is what surfaced that gap: see §6 for
the fix on the `PROBABILISTIC` side, which is the one that actually changes protocol behaviour
rather than just labelling it.

## 6. Deduplication

[`SeenSet.kt`](../core/src/main/kotlin/com/setu/mesh/core/routing/SeenSet.kt) — LRU, 1024
entries, 10-minute expiry.

Bounded by **both** count and age: count so a long-lived relay's memory cannot grow without
limit, age so that a genuinely repeated SOS from the same person hours later is treated as new
information rather than silently swallowed.

Without dedup, a controlled flood is just a broadcast storm — three nodes in mutual range relay
the same beacon to each other until their batteries are flat. Dedup is not an optimisation; it is
what stops the protocol destroying the network it runs on.

### A lost dice roll used to be permanent, and that was a bug, not dedup working as intended

For a MODERATE beacon whose originator has more battery than the receiver, §5's product of gates
can land as low as `0.6 × 1.0 × 0.25 × 1.0 ≈ 0.09` — roughly one relay attempt in eleven. Losing
that roll is expected; the old bug was what losing it *cost*. `SeenSet.addIfNew` ran before
`ForwardingPolicy.decide` and was the entire forwarding gate: the instant a message was marked
seen, it was marked seen for good, so a `Suppress(PROBABILISTIC)` outcome ended that message's
life on this node for the rest of the 10-minute window — no matter how many more times a neighbour
re-advertised it in the meantime. A three-phone field test caught this directly: phone c sent an
SOS, phone a heard it and lost its roll, and phone b never got the relay, even though the radio
link was fine (b could hear a's *own* SOS from the same spot).

The fix keeps dedup's actual job intact — a genuine duplicate's *side effects* (the free clock
sample, carrier count, and RSSI reading `onBeaconHeard` extracts from every hearing regardless of
outcome) are still applied exactly once per hearing, never per roll — but separates "seen" from
"decided". A beacon suppressed for `PROBABILISTIC` is kept in a second, smaller bounded table (64
entries, same 10-minute expiry as `SeenSet` — the same reason to bound it applies at a smaller
scale, since only messages that actually lost their roll ever land here) recording how many times
it has been reconsidered. Hearing it again while under `MAX_RECONSIDER_ATTEMPTS = 3` re-runs
`ForwardingPolicy.decide` against the **current** context rather than the one from whenever it was
first heard.

**What this costs, and why it is bounded rather than free.** Each reconsideration is one more call
to `ForwardingPolicy.decide` — no extra radio traffic by itself, since it only runs when a beacon
is heard anyway — but it can turn a suppression into a relay that would not otherwise have
happened, which *is* an extra beacon on air. Two things keep that cost from compounding:

- **`densityDamp` shrinks the probability exactly as the reason to retry goes away.** Every extra
  hearing that makes reconsideration possible is, by construction, evidence that `k` (distinct
  carriers already re-advertising this id) is rising. A higher `k` pushes `densityDamp` down for
  the *very same retry* that rising `k` enabled. A message that keeps getting reconsidered is
  therefore a message whose relay probability is falling each time — the retry is self-limiting,
  not multiplicative, and cannot turn one lost roll into a storm of relays.
- **`MAX_RECONSIDER_ATTEMPTS = 3` caps the airtime any single lost roll can cost**, independent of
  how many more times the same message happens to be heard inside its 10-minute window. Three
  tries is enough to catch the common case — a couple of unlucky rolls in a thin neighbourhood,
  the exact shape of the field-test failure — without a message that keeps losing becoming a
  standing source of re-evaluation for the rest of its lifetime in `SeenSet`.

In a dense mesh where the same beacon is heard from many neighbours in quick succession, the
practical cost is small: `k` climbs fast, so `densityDamp` usually drives the probability toward
zero well before the third attempt, and most reconsiderations after the first one or two either
relay (because they were always going to, eventually) or stop being retried at all once `k` alone
would suppress them outright.

**`TTL_EXHAUSTED` and `ENERGY_GATE` stay terminal — deliberately, not as an oversight.** Neither
is reconsidered, ever. `TTL_EXHAUSTED` reflects the hop budget of the specific path this hearing
travelled; a shorter path might still bring a relayable copy later, but that arrives as a fresh
hearing with its own `ttl`, not as a retry of this one. `ENERGY_GATE` is the load-bearing one: it
is what lets a phone below the relay floor keep behaving exactly as `docs/POWER.md`'s low-battery
survival story describes. Reconsidering it would mean a phone at 3% re-evaluating whether to spend
its last milliamps on someone else's message every time that message crossed its radio again —
precisely the outcome the gate exists to prevent. `PROBABILISTIC` is safe to retry because losing
it is not a statement about whether this node *can* relay, only about whether this particular roll
said so; `ENERGY_GATE` and `TTL_EXHAUSTED` are statements that would not change on a retry, so
retrying them would only cost a CPU cycle to reach the same answer, plus, for the energy gate, the
risk of implementing that re-check sloppily enough to spend battery a phone that low cannot spare.

## 7. Shared clock

`epochMin` in every beacon doubles as a coarse time source. Priority:

1. GPS time
2. last NTP sync before connectivity was lost
3. **consensus drift** — nudge the local clock toward the median of observed `epochMin` values,
   damped to ¼ of the observed error per observation so a node with a wildly wrong clock is
   pulled in over several beacons rather than yanking the neighbourhood's phase around

This matters because the rendezvous schedule (see [`POWER.md`](POWER.md) §2) is derived from
absolute wall-clock time. Clock agreement *is* the synchronisation mechanism.

## 8. Rich bundles (specified, not yet implemented)

Beyond 24 bytes, over a GATT connection or a BLE 5 extended advertisement (≤244 bytes):

```
beacon(24) + accuracyM(1) + altitude(2) + nameHash(4)
           + note(≤96 UTF-8) + hopChain(≤8 × 3) + sigEd25519(64)
```

Opened only by nodes at RELAY tier or above.

**Beacons are unsigned** — 64 bytes of signature cannot fit in 24. Rich bundles are signed;
beacons are not. This is a real limitation, and the UI must show *unverified beacon* distinctly
from *signed bundle*. Mitigation is rate-limiting per `originId`. See
[`THREAT-MODEL.md`](THREAT-MODEL.md).

## 9. Version handling

`version` occupies the top 3 bits of byte 0. A v1 node **rejects** any other version rather than
guessing at a layout it does not know. Blind relay of unknown versions is a v2 concern; getting
it wrong in v1 would mean forwarding garbage at the cost of real battery.
