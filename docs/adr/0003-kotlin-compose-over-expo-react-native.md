# ADR 0003 — Native Kotlin + Compose, not Expo / React Native

**Status:** accepted · 2026-08-22

## Context

Expo's Modules API lets you write native Kotlin and call it from React Native, and BLE peripheral
mode is achievable that way — [`munim-bluetooth`](https://github.com/munimtechnologies/munim-bluetooth)
already implements central + peripheral + Android 8+ extended advertising for RN/Expo, so this is
a real option rather than a theoretical one.

The question was whether to build the app UI in React Native with the BLE work as a Kotlin native
module, or to stay entirely on native Kotlin with Jetpack Compose.

Both builders are new to Kotlin and Android. Neither has React Native experience.

## Decision

Native Kotlin with Jetpack Compose. No Expo, no React Native.

## Why

**Expo saves nothing on the critical path.** The gate that decides this project is G3 — a 24-byte
beacon crossing between two phones. Reaching it requires a BLE advertiser, a scanner, permissions,
and a foreground service. Every one of those is Kotlin under either option. Expo changes only the
UI layer, which is three screens.

**It adds surface rather than removing it.** With neither builder knowing React Native, Expo means
learning the Expo Modules API *in addition to* Kotlin and Android, plus a second toolchain to keep
alive under time pressure.

**Two concrete hazards:**

- `expo prebuild` regenerates the `android/` directory under Continuous Native Generation, which
  wipes manual `settings.gradle` edits — including the one that makes `:core` visible to the app.
  Surviving that requires a config plugin. The failure appears as "Gradle cannot find `:core`",
  typically at an unhelpful hour.
- Expo Go cannot load custom native modules, so a development build (EAS or local prebuild) is
  mandatory before the first beacon can be tested. That is setup time spent before any progress
  on the actual problem.

**The subtle risk was the deciding one.** Introducing JavaScript creates continuous pressure to
reimplement protocol logic in TypeScript. That would destroy the single property the architecture
rests on: the 200-node simulator running the *same* `MeshNode` class as the radio path. Lose that
and the scale demonstration becomes a separate mock that merely agrees with the real thing — which
is exactly the claim a technical judge will probe.

## Consequences

**Good**
- One toolchain, one language, one build.
- No bridge between the protocol and the UI; the service publishes a `StateFlow<NodeSnapshot>`
  and Compose renders it.
- `:core` stays the only implementation of the protocol, so the simulator's numbers mean something.

**Bad**
- Compose is new to both builders. Mitigated by keeping the UI to three simple screens and
  writing the app code complete and runnable rather than idiomatic-but-sparse.
- No hot reload for UI iteration; Compose previews are the substitute.

## Revisit if

The team gains a fluent React Native developer *and* G3 has already passed. At that point the UI
could be reconsidered independently, because the seam means the transport and protocol would not
have to change.
