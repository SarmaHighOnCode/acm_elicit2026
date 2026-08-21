# TASK A5 — Rich bundles (signed, ≤244 bytes)

> Copy everything below the line into your agent.
> **OPTIONAL. Do A1–A4 first. Do not start this while anything above is unfinished.**

---

## Context

**Repo:** https://github.com/SarmaHighOnCode/acm_elicit2026 (branch `main`)

SETU is an offline mesh SOS relay over BLE. Its base unit is a **24-byte beacon** that fits in a
legacy advertisement, so relaying is connectionless and cheap. Beacons are therefore **unsigned** —
a 64-byte Ed25519 signature cannot fit in 24 bytes — which is the main documented weakness in
`docs/THREAT-MODEL.md`.

Rich bundles are the answer: a larger payload carrying a free-text note, the hop chain, and a
signature, moved over a GATT connection or a BLE 5 extended advertisement, and sent **only by
nodes with enough battery to afford it**.

Read first: `docs/PROTOCOL.md` §8, `docs/THREAT-MODEL.md`.

**FROZEN — do not modify:** `core/.../link/Link.kt`, `core/.../engine/NodeHost.kt`.

**Hard constraints**
- AGP 9 has built-in Kotlin. No `org.jetbrains.kotlin.android`, no `kotlinOptions {}`.
- Do not change versions in `gradle/libs.versions.toml`.
- **Crypto:** use `java.security` / JDK-provided Ed25519 (JDK 15+, and we are on 17). **Do not add
  BouncyCastle** — a new dependency in `:core` is a bigger cost than the convenience is worth.
- Never call `System.currentTimeMillis()` inside a decision function.
- Do not claim the build passes without running it.

## Task

Implement the rich bundle format, its codec, and signature verification. **Protocol only** — the
Android GATT transport is a separate concern and is not on the critical path.

## Layout (`docs/PROTOCOL.md` §8)

```
beacon(24) + accuracyM(1) + altitude(2) + nameHash(4)
           + note(≤96, UTF-8, length-prefixed)
           + hopChain(count(1) + up to 8 × 3-byte node ids)
           + sigEd25519(64)
```
Maximum 215 bytes, inside the 244-byte budget.

The signature covers **everything before it**, with one exception: the beacon's `ttl`/`hops` byte
is excluded, because relays mutate it and every relay would otherwise invalidate the signature.
This mirrors how the beacon's own CRC is positioned.

## Files you may create

```
core/src/main/kotlin/com/setu/mesh/core/model/SosBundle.kt
core/src/main/kotlin/com/setu/mesh/core/codec/BundleCodec.kt
core/src/main/kotlin/com/setu/mesh/core/crypto/BundleSigner.kt
core/src/main/kotlin/com/setu/mesh/core/crypto/KeyStore.kt      // interface only, no persistence
core/src/test/kotlin/com/setu/mesh/core/codec/BundleCodecTest.kt
core/src/test/kotlin/com/setu/mesh/core/crypto/BundleSignerTest.kt
```

Plus, in `MeshNode`, handling for `LinkEvent.BundleReceived` — currently a no-op. Keep the change
**additive**; do not alter any existing signature.

## Requirements

1. `BundleCodec.encode/decode` round-trips every field. `decode` returns **null** on malformed
   input and never throws — same contract as `BeaconCodec`.
2. Note is UTF-8, length-prefixed, truncated at 96 **bytes** not 96 characters. Truncation must
   not split a multi-byte codepoint — this matters, the target users write in Indic scripts.
3. `hopChain` holds up to 8 node ids; a relay appends its own if there is room.
4. `BundleSigner.sign(bundle, privateKey)` and `verify(bundle, publicKey)`.
5. `decode` reports verification state explicitly:
   `Verified` · `Unsigned` · `SignatureInvalid` · `UnknownKey`.
   The UI depends on this distinction (see `docs/THREAT-MODEL.md`).
6. A bundle whose signature fails verification is **dropped, not relayed**. Unlike a beacon, we
   have room to check, so there is no excuse for forwarding it.

## Tests required

- round-trip every field, including an empty note and a full 96-byte note
- multi-byte UTF-8 (Devanagari) truncated at the byte limit without splitting a codepoint
- a tampered byte → `SignatureInvalid`
- mutating the `ttl`/`hops` byte → **still `Verified`** (this is the whole point of excluding it)
- a bundle with no signature → `Unsigned`
- fuzz `decode` with seeded random bytes — never throws
- encoded size ≤ 244 for maximal input

## Acceptance

```bash
./gradlew :core:test
```
Green, with `local.properties` renamed away as well.

## Definition of done

- [ ] `:core:test` green with and without `local.properties`, output pasted
- [ ] no new dependencies — JDK Ed25519 only, no BouncyCastle
- [ ] `decode` never throws, proven by a fuzz test
- [ ] TTL mutation does not invalidate a signature, proven by a test
- [ ] invalid-signature bundles are dropped, not relayed
- [ ] `Link.kt` and `NodeHost.kt` unchanged
- [ ] changes to `MeshNode` are additive only
