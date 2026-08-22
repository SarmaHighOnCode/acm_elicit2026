# SafeHop — Product Requirements

**Problem Statement #15 · Disaster Management · Hacks 11.0**
Status: active build · Owner: 2 builders · Window: <24 h wall clock

---

## 1. Problem

When floods, cyclones or earthquakes bring down cell towers, every communication app on a phone
stops working at once. Stranded people cannot reach rescuers even when a rescue team is two
streets away, and rescuers cannot see where anyone is.

The hardware to fix this is already in everyone's pocket. Bluetooth Low Energy and Wi-Fi Direct
never needed a tower. In a flooded apartment block there are dozens of phones within radio range
of each other, and usually at least one within reach of something that still has an uplink.

**Nothing currently uses them.**

## 2. What we are building

A device-to-device emergency relay. An SOS created on a phone with no signal hops peer-to-peer
across nearby phones until it reaches a node that has connectivity, which becomes the gateway.
Everything between the victim and the gateway is infrastructure-free.

**In one line:** *a bridge made of strangers' phones.*

## 3. Why this is hard, and what most attempts get wrong

The problem statement's own challenge question names the real difficulty:

> How does the relay chain keep working when every device in it is running on a nearly-dead
> battery?

The naive build is a Bluetooth chat app with an SOS button. It demos beautifully on two freshly
charged phones and fails in the field for three reasons:

1. **Flooding flattens batteries.** Classic epidemic relay delivers well right up to the moment
   it drains every phone in the region, at which point delivery goes to zero.
2. **Naive duty cycling silently kills the mesh.** Two nodes listening 5% of the time with
   independent phase overlap 0.25% of the time. The network is dead and every phone reports that
   it is fine.
3. **Connection-oriented designs are too expensive to run when it matters.** Scanning and GATT
   cost roughly 100× what advertising costs. A protocol whose base case is *connect* cannot run
   at 4%.

SafeHop's answer is to make **residual battery a first-class protocol input** rather than a
constraint to be respected. Battery level is carried in every packet and feeds routing,
scheduling, and role election. See [`POWER.md`](POWER.md).

## 4. Users

| User | Situation | Needs |
|------|-----------|-------|
| **Victim** | Stranded, panicking, phone at 4%, possibly one-handed, possibly in the dark or in water | Send an SOS in under 5 seconds. Know whether it got out. Not have the app kill the battery. |
| **Relay** (bystander) | Has signal or doesn't, walking past, uninvolved | Contribute without thinking about it. Not be silently drained. Honest accounting of what it cost them. |
| **Responder** | Rescue worker with a device, on the ground | See who needs help, where, how badly. Not duplicate another team's work. |

The relay user is the one most builds forget, and they are the ones the whole network depends on.
If SafeHop is not trustworthy about battery, it gets uninstalled before the disaster and there is no
mesh.

## 5. Requirements

### Must have (demo-blocking)

- **R1** — An SOS created on an offline phone reaches a second offline phone over BLE, with both
  in airplane mode. *This is the only requirement that proves the premise.*
- **R2** — SOS creation in ≤3 taps, usable one-handed, legible in darkness.
- **R3** — Triage capture: severity, number of people, and at least trapped / medical / water.
- **R4** — Position attached from GPS when available.
- **R5** — Relay behaviour changes measurably with battery level, and the change is visible.
- **R6** — Deduplication prevents broadcast storms; the same message is never relayed twice by
  one node inside the dedup window.
- **R7** — Victim sees status: created → carried by N → reached a gateway.
- **R8** — App survives screen-off via a foreground service.

### Should have

- **R9** — Responder view listing received SOS sorted by triage severity.
- **R10** — Mesh Lab: in-app simulator running the same protocol code at ~200 nodes, with an
  energy view and the ability to kill nodes.
- **R11** — Energy ledger surfaced in the UI in plain language.
- **R12** — `RECEIPT` / `SAFE` propagation causes carriers to drop the message.

### Won't have (this build)

Web console · cloud backend · iOS · Wi-Fi Direct tier · beacon-level encryption · accounts ·
real map tiles · voice · photos.

Each was cut to keep the deliverable at **one APK with zero servers**. Every server is a live-demo
failure mode, and the problem statement explicitly only requires connectivity at the final
gateway node.

## 6. Success criteria

| # | Criterion | How it is proven |
|---|-----------|------------------|
| S1 | Message crosses two phones, both in airplane mode | Live, on stage |
| S2 | Multi-hop with TTL decrement | 3 phones live if available, otherwise simulator — **labelled as which** |
| S3 | Low-battery node still originates and is still heard | Battery override to 4%, node drops to EMBER, beacon still received |
| S4 | Mesh survives a relay dying mid-flight | Kill phone B during propagation, message still arrives |
| S5 | Battery cost is stated and defensible | Measured drain per tier in `POWER.md`, compared against the ledger |
| S6 | Protocol claims are testable without hardware | `./gradlew :core:test` green on any laptop |

**S5 is the one that wins the challenge question.** A measured number beats an impressive
architecture diagram.

## 7. Scope boundaries

SafeHop is a **transport for emergencies**, not a messenger. Explicitly *not* in scope:

- General chat. The moment it carries arbitrary conversation, airtime and battery go to chatter
  instead of to SOS traffic, and the energy argument collapses.
- Medical guidance. Triage flags describe a situation; they do not advise.
- Anything that requires the victim to have signed up in advance.

## 8. Key risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Raw BLE plumbing overruns the window | No live demo at all | Hard gate: **first beacon must cross two phones by H10.** If missed, drop Mesh Lab and ship SOS + relay only. UI work does not start before the radio works. |
| Only 2 phones available | Cannot show a true multi-hop chain | Simulator covers multi-hop; label simulated hops as simulated, never as live |
| Both builders new to Kotlin/Android | Slow debugging | `app/` code written complete and runnable rather than idiomatic-but-sparse; Compose kept simple |
| Android device BLE quirks | Advertising or scanning silently fails on one handset | Capability detection at startup; single-slot carousel fallback; test on both handsets early |
| Over-claiming in the pitch | Loses credibility with a technical judge instantly | Status table in README; measured vs estimated separated in `POWER.md` |

## 9. Open questions

- Do we get a third phone? Materially strengthens S2.
- Which handsets, and do both report `isMultipleAdvertisementSupported() == true`?
- Battery capacity of the demo handsets, needed to convert the ledger's mAh into a real percentage.
