# ADR 0001 — Protocol core as a plain JVM module

**Status:** accepted · 2026-08-22

## Context

The protocol needs to run in two places: on a phone driving real BLE radios, and in a simulator
large enough to demonstrate behaviour at a few hundred nodes. Two builders need to work in
parallel, and only one of them can be holding a phone at any given moment.

The obvious approach is to write the protocol inside the Android app and, if a simulator is
needed later, mock it.

## Decision

The protocol lives in `:core`, a module using the `kotlin-jvm` plugin with **no Android
dependency of any kind**. `:sim` is likewise pure JVM. `:app` depends on both.

All platform contact happens through two interfaces: `Link` (the radio) and `NodeHost` (clock,
battery, GPS).

## Consequences

**Good**

- The 200-node simulator runs *the same `MeshNode` class* as the radio path. Scale claims in the
  demo are the shipping protocol being exercised, not a separate implementation that happens to
  agree.
- `./gradlew :core:test` runs on any laptop with no device and no emulator. Protocol correctness
  is verifiable while the Android build is broken, which during a hackathon is most of the time.
- Two builders are never blocked on one phone.
- "No Android in core" is enforced by the compiler, not by code review discipline.

**Bad**

- One more module boundary to cross when a protocol change needs a UI change.
- `Link` must traffic in opaque byte arrays, so the transport cannot do anything clever with
  message contents. This is intentional — a Link that could parse a beacon would eventually make
  a routing decision — but it does mean some optimisations are unavailable.

**Also**

- It is the fallback plan. If raw BLE overruns, swapping `AndroidLink` for a Nearby Connections
  implementation touches exactly one class.
