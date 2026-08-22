# Demo script

Five minutes. The order matters: **prove the premise before showing anything pretty.**

## Before you start

- [ ] Both phones charged, APK installed, permissions already granted
- [ ] Bluetooth **on**, airplane mode **on** — verify visibly on both screens
- [ ] Battery override enabled in debug settings
- [ ] Mesh Lab preloaded with the 200-node scenario
- [ ] Screen brightness up; the UI is dark-on-black and dies under stage lights

## 0:00 — The setup (30 s)

> "Cell towers fall down in exactly the disasters where you most need them. When that happens
> every communication app on your phone stops working at the same moment. But the phone still
> has two radios that never needed a tower."

Hold up both phones. **Show airplane mode on both screens.**

> "These two phones cannot reach the internet, each other's carrier, or anything else. Watch."

## 0:30 — The premise (60 s)

1. Phone A: tap **SOS**, three taps of triage — *2 people, trapped, water rising*.
2. Phone B: the SOS appears.

> "No pairing. No connection. No handshake. Phone B doesn't know phone A exists. That whole
> message is 24 bytes riding inside a Bluetooth advertisement — the same kind of packet a fitness
> tracker uses to say hello."

**This is the single most important moment.** If it works, everything after is upside.

## 1:30 — The challenge question (2 min)

> "The interesting question isn't whether it works. It's what happens when everyone's battery is
> nearly gone."

Set phone A's battery override to **4%**.

Show the tier badge drop to **EMBER**.

> "Phone A just went deaf. It has stopped scanning entirely — scanning costs about a hundred
> times what broadcasting costs. It is still shouting, still findable, and it will keep doing
> that for hours."

Tap SOS again on A. It still arrives on B.

> "A dying phone gets selfish. It always relays its own SOS at any battery level, but it stops
> volunteering to carry for strangers. That switch is in the protocol, not in a settings screen."

Then the part most people miss:

> "Here's the failure nobody demos. Two phones each listening 5% of the time, at random,
> overlap 0.25% of the time. Your mesh is dead and both phones say they're fine. So SafeHop derives
> its wake windows from wall-clock time — every phone in the area wakes in the same one-second
> window, and they never send a single message to agree on that."

## 3:30 — Break it (60 s)

With a third phone if available: A → B → C, then **kill B mid-flight**. Show the message still
arriving.

With two phones: do this in Mesh Lab instead, and **say which it is**.

> "Simulated, to be clear — we have two phones on stage."

Never present a simulated hop as a live one. A technical judge will ask, and the answer decides
whether they believe anything else you said.

## 4:30 — Scale (30 s)

Open **Mesh Lab**. 200 nodes.

> "Same protocol code as the phones — literally the same Kotlin module, not a mock. The
> simulator and the radio share `MeshNode`."

Hand the phone to a judge.

> "Kill whichever nodes you like."

## Closing line

> "Most people build a chat app and put a red button on it. We think a disaster mesh isn't a
> bandwidth problem — it's an energy-allocation problem. Every byte you forward costs battery
> that a stranded person may need to stay reachable. So SafeHop routes battery, not packets."

---

## If asked

**"Is it secure?"**
> Beacons are unsigned — 64 bytes of signature don't fit in 24. Rich bundles carry Ed25519;
> beacons don't, and the UI marks them unverified. Mitigation today is rate-limiting per
> originator ID. It's a real limitation and it's in our threat model.

**"How much battery does it use?"**
> Point at `POWER.md`. Quote **only** measured numbers. If M1–M6 aren't done: *"we have an
> instrumented ledger and a measurement plan; we haven't finished the soak tests, so I'm not
> going to quote you a number we haven't verified."* That answer earns more credit than a
> confident guess.

**"Why not Wi-Fi Direct / LoRa / Nearby Connections?"**
> Nearby Connections is a black box for power control — we'd lose duty-cycle control, which is
> the whole thesis. Wi-Fi Direct is a documented next tier for bulk transfer. LoRa needs hardware
> nobody has in a flood. See `adr/0002`.

**"What if nobody else has the app?"**
> Then it's a torch and a whistle, and we say so. The mesh needs density — which is exactly why
> the energy ledger matters: an app that drains phones gets uninstalled before the disaster, and
> then there is no mesh at all.

## Do not

- Do not start the demo with Mesh Lab. Simulation before hardware reads as "the hardware doesn't
  work."
- Do not claim measured battery figures that are not in `POWER.md` §8.
- Do not let the phones auto-lock. Disable screen timeout beforehand.
