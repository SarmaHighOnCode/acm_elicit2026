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
  0    1   verType    [7:5] version=1  [4:2] type  [1:0] reserved
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
`ALTRUISM_GRADIENT`, `DENSITY_DAMPED`, `PROBABILISTIC`, `TTL_EXHAUSTED`), so the Mesh Lab can
show *why* a node stayed quiet.

## 6. Deduplication

[`SeenSet.kt`](../core/src/main/kotlin/com/setu/mesh/core/routing/SeenSet.kt) — LRU, 1024
entries, 10-minute expiry.

Bounded by **both** count and age: count so a long-lived relay's memory cannot grow without
limit, age so that a genuinely repeated SOS from the same person hours later is treated as new
information rather than silently swallowed.

Without dedup, a controlled flood is just a broadcast storm — three nodes in mutual range relay
the same beacon to each other until their batteries are flat. Dedup is not an optimisation; it is
what stops the protocol destroying the network it runs on.

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
