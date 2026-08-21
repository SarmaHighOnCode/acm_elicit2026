# ADR 0002 — Raw BLE GATT + advertising, not Nearby Connections

**Status:** accepted · 2026-08-22

## Context

Two candidate transports for phone-to-phone communication with no infrastructure:

**Google Nearby Connections** (`P2P_CLUSTER`) handles discovery, connection and encryption over
BLE / Bluetooth Classic / Wi-Fi Direct in roughly a hundred lines. It does *not* provide
multi-hop routing, so SETU's routing layer would still be ours.

**Raw BLE** means writing a GATT server and client, packing advertisement bytes by hand,
negotiating MTU, and handling per-vendor Bluetooth quirks.

Both builders are new to Kotlin and Android, and the window is under 24 hours.

## Decision

Raw BLE GATT + advertising.

## Why

Nearby Connections is a **black box for power control**. It decides when to advertise, when to
scan, and which radio to use. SETU's entire thesis is that residual battery should drive those
decisions — the power ladder, phase-locked rendezvous, and scanner election all require direct
control of duty cycle. Handing that to a library removes the differentiator and leaves a
competent but ordinary mesh chat app.

It also removes the 24-byte beacon. Connectionless relay via legacy advertisement is what makes
EMBER tier possible at all; Nearby always connects.

## Consequences

**Good**

- Real duty-cycle control, so the answer to the challenge question is implemented rather than
  described.
- The 24-byte beacon is real on hardware, not simulated.
- No Google Play Services dependency.

**Bad — and this is the schedule risk of the whole project**

- Considerably more code, in the area where neither builder has experience.
- Android BLE is genuinely hostile: `isMultipleAdvertisementSupported()` is false on many
  devices, background scanning needs a foreground service, and there is a hard throttle of **5
  `startScan` calls per 30 seconds** that fails silently.

## Mitigation

A hard gate: **the first beacon must cross between two phones by H10.** If it has not, drop the
Mesh Lab screen and ship SOS + relay only. UI work does not begin before the radio works.

If the gate is missed entirely, swapping `AndroidLink` for a Nearby Connections implementation
touches one class and leaves `:core` untouched — see [ADR 0001](0001-shared-protocol-core-as-plain-jvm-module.md).
The seam exists precisely so this decision is reversible.

## Note on the throttle

The 5-scans-per-30-seconds limit is *why* the rendezvous epoch is 60 seconds with at most one
scan per epoch. The constraint shaped the design rather than being patched around after being
discovered.
