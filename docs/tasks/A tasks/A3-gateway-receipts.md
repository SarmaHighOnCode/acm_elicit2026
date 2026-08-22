# TASK A3 — Gateway role and the RECEIPT loop

> Copy everything below the line into your agent. **Do A1 and A2 first.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over Bluetooth LE. Phones relay 24-byte beacons peer-to-peer
when cell towers are down; only the *final* node in the chain needs connectivity. Residual
battery is a first-class routing input.

Read first: `docs/PROTOCOL.md` (especially §3 on referencing another message), `docs/POWER.md` §5.

Modules: `core/` (pure Kotlin/JVM, **no Android imports ever**), `sim/`, `app/`.

**FROZEN — do not modify:** `core/.../link/Link.kt`, `core/.../engine/NodeHost.kt`.

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change versions in `gradle/libs.versions.toml`.
- Decision functions take an explicit `nowMillis`; never `System.currentTimeMillis()` inside one.
- No new third-party dependencies.
- Do not claim the build passes without running it. Paste real output.

## Task

Close the delivery loop. When a node with connectivity accepts an SOS, a `RECEIPT` propagates
back through the mesh and every node carrying that message **drops it**.

This is not a nicety. It is one of the six battery mechanisms: each dropped message frees an
advertising slot in the beacon carousel and buffer in the outbox. **Confirming delivery is how
the mesh reclaims capacity.** The measurable drop in mesh-wide mAh/minute after a delivery is a
headline number for `docs/POWER.md`.

## Background: how referencing works

There is no room in 24 bytes for both a fresh message id and a reference to another one. So
`RECEIPT`, `SAFE` and `CUSTODY_ACK` put the **referenced** message's id in the `msgId` field, and
deduplication folds the type in:

```kotlin
dedupKey = msgId XOR (type × 0x9E3779B1)
```

`MeshNode.markSafe()` already does exactly this for `SAFE`. **Model `originateReceipt` on it.**

## Files you may create or modify

```
CREATE  core/src/main/kotlin/com/setu/mesh/core/engine/GatewayRole.kt
MODIFY  core/src/main/kotlin/com/setu/mesh/core/engine/MeshNode.kt   (additive only, see below)
CREATE  core/src/test/kotlin/com/setu/mesh/core/engine/GatewayRoleTest.kt
CREATE  sim/src/main/kotlin/com/setu/mesh/sim/GatewayScenario.kt
CREATE  sim/src/test/kotlin/com/setu/mesh/sim/ReceiptEnergyTest.kt
```

**Do NOT touch anything else.** In particular do not change `Link.kt` or `NodeHost.kt`.

### The one permitted change to `MeshNode`

Add exactly this method. It is **additive**, so the Android track is unaffected:

```kotlin
/**
 * Emit a RECEIPT for [forMessage] into the mesh. Carriers that hear it drop the original,
 * which is how delivery confirmation returns airtime and buffer to the network.
 */
fun originateReceipt(forMessage: MessageId, nowMillis: Long = host.nowMillis()): MessageId
```

Build it exactly like the existing `markSafe`: same beacon construction, `type = MessageType.RECEIPT`,
`messageId = forMessage`, `ttl = DEFAULT_TTL`, `hops = 0`, own position and battery, added to the
outbox with `isOwn = true` and registered in the seen-set under its folded dedup key.

Do **not** change any existing method signature. Do **not** change `NodeSnapshot`'s existing
fields — the receiving side already sets `ownSosDelivered` when a RECEIPT for our own message
arrives.

### `GatewayRole`

```kotlin
class GatewayRole(private val node: MeshNode) {
    var uplinkAvailable: Boolean = false
        private set

    fun onUplinkAvailable(available: Boolean)

    /** Called when the SOS has been handed to the outside world. Emits the RECEIPT. */
    fun acceptDelivery(messageId: MessageId, nowMillis: Long): MessageId?

    /** Messages this gateway has already receipted, so it does not re-emit. */
    val delivered: Set<MessageId>
}
```

`acceptDelivery` returns null and does nothing when `uplinkAvailable` is false or the message was
already delivered. Keep this class small — it is a role, not a subsystem. No I/O, no networking:
"handing to the outside world" is the caller's problem, because the transport differs between the
phone (real uplink) and the simulator (nothing).

## Tests required

`GatewayRoleTest`
- `acceptDelivery` with no uplink → null, no beacon emitted
- with uplink → a `RECEIPT` beacon appears in the node's advertised set
- the emitted receipt's `messageId` equals the **original** message's id
- calling `acceptDelivery` twice for the same id emits only once
- a node receiving that receipt removes the original from its outbox
- a node receiving a receipt for a message it never had does not crash

`ReceiptEnergyTest` (in `:sim`)
- run `dying-chain` or `flood` with one gateway
- assert: after delivery, **no** node's outbox still contains the delivered message
- assert: mesh-wide mAh per virtual minute is **lower** in the 5 minutes after delivery than in
  the 5 minutes before — this is the claim `docs/POWER.md` §5 makes, so it must be measured, not
  asserted rhetorically
- report the actual percentage drop in the test output

## Acceptance

```bash
./gradlew :core:test :sim:test
```

```bash
./gradlew :sim:run --args="--nodes 100 --scenario flood --minutes 20 --seed 11"
```
Output must show delivery ratio and the post-receipt energy drop.

## Definition of done

- [ ] both commands green, output pasted
- [ ] `originateReceipt` is the **only** addition to `MeshNode`; no existing signature changed
- [ ] `git diff --stat` shows no changes to `Link.kt` or `NodeHost.kt`
- [ ] the measured energy drop is reported as a real number
- [ ] no networking or I/O added to `:core`
