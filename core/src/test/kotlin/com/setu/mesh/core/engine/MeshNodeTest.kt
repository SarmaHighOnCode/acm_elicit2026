package com.setu.mesh.core.engine

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.model.*
import com.setu.mesh.core.power.PowerGovernor
import com.setu.mesh.core.power.PowerTier
import com.setu.mesh.core.routing.RelayDecision
import com.setu.mesh.core.routing.SuppressReason
import com.setu.mesh.core.support.FakeHost
import com.setu.mesh.core.support.FakeLink
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class MeshNodeTest {

    private fun testBeacon(id: Int, type: MessageType = MessageType.SOS, ttl: Int = 7, severity: Severity = Severity.HIGH): SosBeacon = SosBeacon(
        type = type,
        ttl = ttl,
        hops = 0,
        messageId = MessageId(id),
        origin = NodeId(100),
        position = GeoPoint.of(0.0, 0.0),
        epochMinute = 0,
        flags = SituationFlags(severity = severity),
        souls = 1,
        originBattery = 100
    )

    @Test
    fun `own SOS always relays at 1 percent battery`() {
        val link = FakeLink()
        val host = FakeHost(battery = 1)
        val node = MeshNode(NodeId(1), link, host)

        // The act of originating places it in the outbox
        val messageId = node.originateSos(SituationFlags(), 1)
        
        // Let's ask for beacons to advertise. It should include our own SOS.
        val beacons = node.beaconsToAdvertise(1, host.nowMillis())
        assertEquals(1, beacons.size)
        
        val decoded = BeaconCodec.decode(beacons[0])
        assertNotNull(decoded)
        assertEquals(messageId, decoded!!.messageId)
    }

    @Test
    fun `resending an SOS replaces the previous one instead of accumulating`() {
        val node = MeshNode(NodeId(1), FakeLink(), FakeHost(battery = 90), random = Random(0))

        val first = node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)
        assertEquals(1, node.snapshot.value.carrying)

        // Every triage toggle on the SOS screen calls through to here. Before this was fixed,
        // each one left another own-copy behind: own entries are exempt from both purgeStale
        // and evictOne, so the carried count climbed forever and the outbox grew past capacity.
        val second = node.originateSos(SituationFlags(severity = Severity.CRITICAL), souls = 4)
        val third = node.originateSos(SituationFlags(severity = Severity.CRITICAL), souls = 5)

        assertNotEquals(first, second)
        assertNotEquals(second, third)
        assertEquals(1, node.snapshot.value.carrying)

        val own = node.snapshot.value.ownSos
        assertNotNull(own)
        assertEquals(5, own!!.souls)
        assertEquals(Severity.CRITICAL, own.flags.severity)
        assertEquals(listOf(third), node.carriedMessages().map { it.messageId })
    }

    @Test
    fun `many resends never grow the outbox past one own message`() {
        val node = MeshNode(NodeId(1), FakeLink(), FakeHost(battery = 90), random = Random(0))

        repeat(50) { i -> node.originateSos(SituationFlags(severity = Severity.HIGH), souls = i + 1) }

        assertEquals(1, node.snapshot.value.carrying)
        assertEquals(50, node.snapshot.value.ownSos?.souls)
    }

    @Test
    fun `originateSos carries the host's position accuracy class`() {
        val host = FakeHost(battery = 90, accuracyClass = 2)
        val node = MeshNode(NodeId(1), FakeLink(), host)

        node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)

        val own = node.snapshot.value.ownSos
        assertNotNull(own)
        assertEquals(2, own!!.positionAccuracyClass)
        assertEquals(30.0, own.senderAccuracyMetres)

        // A later resend must reflect the host's *current* class, not whatever it was at the
        // first call -- originateSos reads it fresh every time, exactly like host.position().
        host.accuracyClass = 0
        node.originateSos(SituationFlags(severity = Severity.CRITICAL), souls = 2)
        assertEquals(0, node.snapshot.value.ownSos?.positionAccuracyClass)
    }

    // RC4 note: this test used to assert that a re-heard duplicate was suppressed with reason
    // PROBABILISTIC, on the theory that SeenSet.addIfNew was the entire dedup gate and every
    // duplicate hearing fell through to the same catch-all. That was the bug this task exists to
    // fix: PROBABILISTIC now means "the policy ran again and lost the roll again", not "this is a
    // repeat". A message the policy has already relayed has nothing left to decide, and the
    // engine says so with SuppressReason.DUPLICATE instead. The first hearing has to be
    // deterministic for that to be testable, and charging alone does not achieve it: charging
    // pins energyGate to 1.0, but probability is severityWeight * gate * gradient * density, and
    // a HIGH beacon carries severityWeight 0.85 -- so this relayed only ~85% of runs and failed
    // the rest. CRITICAL is what actually pins every factor: weight 1.0, and it bypasses the
    // altruism gradient outright. Charging stays for the energy gate.
    @Test
    fun `duplicate beacon is suppressed as DUPLICATE once this node has a terminal answer for it`() {
        val link = FakeLink()
        val host = FakeHost(charging = true)
        val node = MeshNode(NodeId(1), link, host)

        val beacon = testBeacon(123, severity = Severity.CRITICAL)
        val encoded = BeaconCodec.encode(beacon)

        val decision1 = node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis())
        assertTrue(decision1 is RelayDecision.Relay, "CRITICAL while charging pins probability to 1.0, so this must relay")

        val decision2 = node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis())
        assertTrue(decision2 is RelayDecision.Suppress)
        assertEquals(SuppressReason.DUPLICATE, (decision2 as RelayDecision.Suppress).reason)
    }

    @Test
    fun `TTL decrements and stops at 0`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        // Beacon with TTL 2 should be relayed as TTL 1
        val beacon = testBeacon(123, ttl = 2)
        val encoded = BeaconCodec.encode(beacon)

        val decision = node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis())
        assertTrue(decision is RelayDecision.Relay || (decision is RelayDecision.Suppress && decision.reason == SuppressReason.PROBABILISTIC))
        
        // Let's get the advertised beacons. It should have TTL 1
        if (decision is RelayDecision.Relay) {
            val beacons = node.beaconsToAdvertise(10, host.nowMillis())
            val decoded = beacons.mapNotNull { BeaconCodec.decode(it) }.find { it.messageId.raw == 123 }
            assertNotNull(decoded)
            assertEquals(1, decoded!!.ttl)
            assertEquals(1, decoded.hops)
        }
        
        // Beacon with TTL 1 -> will decrement to 0, which ForwardingPolicy suppresses immediately
        val beaconOne = testBeacon(124, ttl = 1)
        val encodedOne = BeaconCodec.encode(beaconOne)
        val decisionOne = node.onBeaconHeard(encodedOne, PeerHandle("A"), host.nowMillis())
        assertTrue(decisionOne is RelayDecision.Suppress)
        assertEquals(SuppressReason.TTL_EXHAUSTED, (decisionOne as RelayDecision.Suppress).reason)
    }

    // ---------------------------------------------------------------- reconsideration (RC4/S3)

    /** MODERATE, self battery 20 vs. origin battery 90, k=0 -- p = 0.6*0.6*0.25*1.0 = 0.09,
     *  the same truth-table row as ForwardingPolicyTest's "case 5". Low enough that a scripted
     *  0.99 draw reliably suppresses it and a scripted 0.01 draw reliably relays it. */
    private fun lowProbabilityBeacon(id: Int, ttl: Int = 7): SosBeacon = SosBeacon(
        type = MessageType.SOS,
        ttl = ttl,
        hops = 0,
        messageId = MessageId(id),
        origin = NodeId(100),
        position = GeoPoint.of(0.0, 0.0),
        epochMinute = 0,
        flags = SituationFlags(severity = Severity.MODERATE),
        souls = 1,
        originBattery = 90,
    )

    @Test
    fun `a PROBABILISTIC suppression gets a fresh roll when the same beacon is heard again`() {
        val host = FakeHost(battery = 20)
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.99, 0.99, 0.01)))
        val encoded = BeaconCodec.encode(lowProbabilityBeacon(700))
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(first is RelayDecision.Suppress && first.reason == SuppressReason.PROBABILISTIC)

        // Before this task, SeenSet.addIfNew ran before the policy and this second hearing would
        // never have reached ForwardingPolicy.decide at all -- the message's life would already
        // be over. It gets a genuinely fresh roll instead.
        val second = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(second is RelayDecision.Suppress && second.reason == SuppressReason.PROBABILISTIC)

        val third = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(third is RelayDecision.Relay, "third scripted draw (0.01) is below p=0.09 and must relay")

        // Newest first: the relay that just happened was attempt 3, and the two suppressions
        // before it were attempts 2 and 1, in that order.
        val history = node.recentForwardingDecisions()
        assertEquals(3, history[0].attempt)
        assertEquals(2, history[1].attempt)
        assertEquals(1, history[2].attempt)
    }

    @Test
    fun `attempts cap at MAX_RECONSIDER_ATTEMPTS and then stop`() {
        val host = FakeHost(battery = 20)
        // Only 3 draws scripted: if a 4th hearing tried to run the policy again, ScriptedRandom
        // would throw, failing the test loudly instead of silently over-counting.
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.99, 0.99, 0.99)))
        val encoded = BeaconCodec.encode(lowProbabilityBeacon(701))
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        val second = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        val third = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        for (d in listOf(first, second, third)) {
            assertTrue(d is RelayDecision.Suppress && d.reason == SuppressReason.PROBABILISTIC)
        }

        val fourth = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(fourth is RelayDecision.Suppress)
        assertEquals(SuppressReason.DUPLICATE, (fourth as RelayDecision.Suppress).reason)
    }

    @Test
    fun `a message already relayed is never reconsidered`() {
        val host = FakeHost(battery = 90)
        // Severity HIGH, self 90 vs. origin 50, k=0 -> p = 0.85*1.0*1.0*1.0 = 0.85; a 0.01 draw
        // relays it outright on the first hearing. Only one draw is scripted -- if the second
        // hearing tried to reconsider, ScriptedRandom would throw.
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.01)))
        val beacon = SosBeacon(
            type = MessageType.SOS,
            ttl = 7,
            hops = 0,
            messageId = MessageId(702),
            origin = NodeId(100),
            position = GeoPoint.of(0.0, 0.0),
            epochMinute = 0,
            flags = SituationFlags(severity = Severity.HIGH),
            souls = 1,
            originBattery = 50,
        )
        val encoded = BeaconCodec.encode(beacon)
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(first is RelayDecision.Relay)

        val second = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(second is RelayDecision.Suppress)
        assertEquals(SuppressReason.DUPLICATE, (second as RelayDecision.Suppress).reason)
    }

    @Test
    fun `ENERGY_GATE is terminal -- a repeat hearing is DUPLICATE, not a fresh gate check`() {
        val host = FakeHost(battery = 2) // below the 5% floor, not CRITICAL -> gate is 0.0
        // No draws scripted at all: the energy gate suppresses before the policy ever rolls the
        // dice, on both hearings -- if a reconsideration slipped through and reached the roll,
        // ScriptedRandom would throw.
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(emptyList()))
        val encoded = BeaconCodec.encode(lowProbabilityBeacon(703))
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(first is RelayDecision.Suppress && first.reason == SuppressReason.ENERGY_GATE)

        val second = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(second is RelayDecision.Suppress)
        assertEquals(SuppressReason.DUPLICATE, (second as RelayDecision.Suppress).reason)
    }

    @Test
    fun `TTL_EXHAUSTED is terminal -- it never turns into a relay no matter how many times it is heard`() {
        val host = FakeHost(battery = 90)
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(emptyList()))
        // ttl = 0 on arrival: relayed() returns null immediately, before the policy runs at all.
        val encoded = BeaconCodec.encode(lowProbabilityBeacon(704, ttl = 0))
        val now = host.nowMillis()

        repeat(3) {
            val decision = node.onBeaconHeard(encoded, PeerHandle("A"), now)
            assertTrue(decision is RelayDecision.Suppress)
            assertEquals(SuppressReason.TTL_EXHAUSTED, (decision as RelayDecision.Suppress).reason)
        }
    }

    @Test
    fun `RSSI is applied exactly once per hearing, not double-counted by the reconsideration restructure`() {
        val host = FakeHost(battery = 90)
        val node = MeshNode(NodeId(1), FakeLink(), host, random = Random(0))
        // hops = 0: noteDirectSignal only ever looks at a beacon's originator, never a relay hop.
        val beacon = testBeacon(800)
        val encoded = BeaconCodec.encode(beacon)

        node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis(), rssiDbm = -60)
        assertEquals(-60, node.directSignalDbm(beacon.origin.raw))

        // If the restructure accidentally ran noteDirectSignal twice in one onBeaconHeard call,
        // this single additional hearing would already show a doubly-smoothed value instead of
        // one EMA step.
        node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis(), rssiDbm = -40)
        val expectedAlpha = 0.3 // mirrors MeshNode's private RSSI_EMA_ALPHA
        val expected = (-60.0 + expectedAlpha * (-40.0 - -60.0)).roundToInt()
        assertEquals(expected, node.directSignalDbm(beacon.origin.raw))
    }

    @Test
    fun `recentForwardingDecisions is bounded at 32 and ordered newest first`() {
        val host = FakeHost(battery = 90)
        val node = MeshNode(NodeId(1), FakeLink(), host)

        // A malformed frame (wrong size) never reaches the codec's CRC/version checks, so this
        // exercises the ring buffer with no dependency on dedup, TTL, or the policy at all --
        // just a cheap way to generate 40 distinct, ordered records.
        for (i in 1..40) {
            node.onBeaconHeard(ByteArray(1), PeerHandle("A"), nowMillis = i.toLong())
        }

        val history = node.recentForwardingDecisions()
        assertEquals(32, history.size)
        assertEquals(40L, history.first().atMillis, "newest record must be first")
        assertEquals(9L, history.last().atMillis, "oldest 8 of 40 must have been evicted")
        for (i in 0 until history.size - 1) {
            assertTrue(history[i].atMillis > history[i + 1].atMillis, "must be strictly newest-first")
        }
    }

    /**
     * A [Random] that returns a scripted sequence of `nextDouble()` draws, one per call, so a
     * test can pin exactly which side of `ForwardingPolicy`'s probabilistic roll a decision lands
     * on without hunting for a real seed that happens to produce the right sequence. Throws if
     * asked for more draws than scripted, so a reconsideration slipping through when the test
     * expects it not to fails loudly instead of silently drawing from an exhausted script.
     */
    private class ScriptedRandom(private val draws: List<Double>) : Random() {
        private var index = 0
        override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)
        override fun nextDouble(): Double {
            check(index < draws.size) { "ScriptedRandom asked for draw #$index but only ${draws.size} were scripted" }
            return draws[index++]
        }
    }

    @Test
    fun `RECEIPT removes from outbox`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        // Originate our own message
        val myMsgId = node.originateSos(SituationFlags(), 1)
        
        // Ensure it is in the outbox
        val initialBeacons = node.beaconsToAdvertise(10, host.nowMillis())
        assertTrue(initialBeacons.any { BeaconCodec.decode(it)?.messageId == myMsgId })

        // Receive RECEIPT for our own message
        val receipt = testBeacon(myMsgId.raw, MessageType.RECEIPT)
        val encodedReceipt = BeaconCodec.encode(receipt)
        node.onBeaconHeard(encodedReceipt, PeerHandle("B"), host.nowMillis())
        
        // Our message should be removed from the outbox
        val currentBeacons = node.beaconsToAdvertise(10, host.nowMillis())
        assertFalse(currentBeacons.any { BeaconCodec.decode(it)?.messageId == myMsgId && BeaconCodec.decode(it)?.type == MessageType.SOS })
        
        // And snapshot should reflect delivery
        assertTrue(node.snapshot.value.ownSosDelivered)
    }

    @Test
    fun `markSafe removes the original and emits a SAFE beacon`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        val myMsgId = node.originateSos(SituationFlags(severity = Severity.HIGH), 2)

        val beforeBeacons = node.beaconsToAdvertise(10, host.nowMillis()).mapNotNull { BeaconCodec.decode(it) }
        assertTrue(beforeBeacons.any { it.messageId == myMsgId && it.type == MessageType.SOS })

        node.markSafe(host.nowMillis())

        val afterBeacons = node.beaconsToAdvertise(10, host.nowMillis()).mapNotNull { BeaconCodec.decode(it) }

        // The original SOS is gone from the outbox...
        assertFalse(afterBeacons.any { it.messageId == myMsgId && it.type == MessageType.SOS })
        // ...replaced by a SAFE beacon that still references the same message id, per the
        // referencing scheme in docs/PROTOCOL.md §3 (no room for both a fresh id and a
        // reference in 24 bytes, so SAFE reuses the original's msgId).
        assertTrue(afterBeacons.any { it.messageId == myMsgId && it.type == MessageType.SAFE })
    }

    @Test
    fun `beaconsToAdvertise rotates when slots less than outbox size`() {
        val link = FakeLink()
        // Charging at 100% pins the forwarding policy to a probability of exactly 1.0 (energy
        // gate, altruism gradient and density damping all at their maximum), so the two relays
        // below are deterministic. This test is about the carousel, not about probabilistic
        // forwarding.
        val host = FakeHost(battery = 100, charging = true)
        val node = MeshNode(NodeId(1), link, host, random = Random(1)) // seed 1

        // One own SOS plus two messages carried for other people. This used to be three
        // originateSos calls, which only ever produced three entries because a resend leaked
        // its superseded copy into the outbox -- the bug covered by
        // `resending an SOS replaces the previous one instead of accumulating`. A genuine
        // three-entry outbox is one own message and two being relayed.
        node.originateSos(SituationFlags(severity = Severity.LOW), 1)
        listOf(201, 202).forEach { id ->
            val heard = SosBeacon(
                type = MessageType.SOS,
                ttl = 7,
                hops = 0,
                messageId = MessageId(id),
                origin = NodeId(id),
                position = GeoPoint.of(0.0, 0.0),
                epochMinute = 0,
                flags = SituationFlags(severity = Severity.CRITICAL),
                souls = 1,
                originBattery = 20,
            )
            val decision = node.onBeaconHeard(BeaconCodec.encode(heard), PeerHandle("p$id"), host.nowMillis())
            assertTrue(decision is RelayDecision.Relay, "relay must be deterministic here, got $decision")
        }
        assertEquals(3, node.snapshot.value.carrying)

        val slots = 2
        
        val slice1 = node.beaconsToAdvertise(slots, host.nowMillis())
        assertEquals(2, slice1.size)
        val d1 = BeaconCodec.decode(slice1[0])!!
        val d2 = BeaconCodec.decode(slice1[1])!!
        
        val slice2 = node.beaconsToAdvertise(slots, host.nowMillis())
        assertEquals(2, slice2.size)
        val d3 = BeaconCodec.decode(slice2[0])!!
        val d4 = BeaconCodec.decode(slice2[1])!!
        
        val set1 = setOf(d1.messageId, d2.messageId)
        val set2 = setOf(d3.messageId, d4.messageId)
        
        assertNotEquals(set1, set2)
        val allSeen = set1 + set2
        assertEquals(3, allSeen.size)
    }

    // ---------------------------------------------------------------- attentive mode (B10)

    @Test
    fun `originateSos leaves the node attentive until ATTENTIVE_AFTER_SOS_MILLIS later`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        val now = host.nowMillis()
        node.originateSos(SituationFlags(), 1, now)

        // Mirrors MeshNode's private ATTENTIVE_AFTER_SOS_MILLIS; there is no public handle to
        // the constant itself, so the boundary is asserted through planNow()'s output instead.
        val attentiveAfterSosMillis = 120_000L

        assertEquals(
            PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS,
            node.planNow(now).scanWindowMillis,
            "expected attentive scan window immediately after originating an SOS",
        )
        assertNotEquals(
            PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS,
            node.planNow(now + attentiveAfterSosMillis + 1).scanWindowMillis,
            "expected attentive mode to have lapsed just past ATTENTIVE_AFTER_SOS_MILLIS",
        )
    }

    @Test
    fun `hearing an SOS beacon makes the node attentive, RECEIPT and SAFE do not`() {
        val link = FakeLink()
        val host = FakeHost()
        val now = host.nowMillis()

        val sosNode = MeshNode(NodeId(1), link, host)
        sosNode.onBeaconHeard(BeaconCodec.encode(testBeacon(500, MessageType.SOS)), PeerHandle("A"), now)
        assertEquals(
            PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS,
            sosNode.planNow(now).scanWindowMillis,
            "expected attentive scan window after hearing a neighbour's SOS",
        )

        val receiptNode = MeshNode(NodeId(2), link, host)
        receiptNode.onBeaconHeard(BeaconCodec.encode(testBeacon(501, MessageType.RECEIPT)), PeerHandle("A"), now)
        assertEquals(
            PowerTier.BRIDGE.scanWindowMillis,
            receiptNode.planNow(now).scanWindowMillis,
            "a RECEIPT should not trigger attentive mode",
        )

        val safeNode = MeshNode(NodeId(3), link, host)
        safeNode.onBeaconHeard(BeaconCodec.encode(testBeacon(502, MessageType.SAFE)), PeerHandle("A"), now)
        assertEquals(
            PowerTier.BRIDGE.scanWindowMillis,
            safeNode.planNow(now).scanWindowMillis,
            "a SAFE should not trigger attentive mode",
        )
    }

    @Test
    fun `setAttentive(true) holds regardless of elapsed time until setAttentive(false)`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        node.setAttentive(true)
        val now = host.nowMillis()

        assertEquals(PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS, node.planNow(now).scanWindowMillis)
        // Far past any SOS-triggered window -- setAttentive(true) never expires on its own.
        assertEquals(
            PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS,
            node.planNow(now + 10 * 60_000L).scanWindowMillis,
        )

        node.setAttentive(false)
        assertNotEquals(
            PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS,
            node.planNow(now + 10 * 60_000L).scanWindowMillis,
        )
    }

    // ---------------------------------------------------------------- own SAFE bounded life

    /** Mirrors MeshNode's private OWN_SAFE_BROADCAST_MILLIS -- SeenSet.DEFAULT_EXPIRY_MILLIS,
     *  ten minutes. There is no public handle to the constant itself, same situation as
     *  ATTENTIVE_AFTER_SOS_MILLIS above. */
    private val ownSafeBroadcastMillis = 600_000L

    @Test
    fun `carrying count returns to its prior value across safe-SOS cycles and never grows`() {
        val node = MeshNode(NodeId(1), FakeLink(), FakeHost(battery = 90))

        node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)
        assertEquals(1, node.snapshot.value.carrying)

        // Before this fix: markSafe's SAFE reuses the SOS's own outbox key (SAFE carries the
        // original's messageId, docs/PROTOCOL.md §3), so markSafe alone never grew the count --
        // but ownMessageId was set to null, so the *next* originateSos's own-supersede check
        // (`ownMessageId?.let { outbox.remove(it) }`) could never reach the outstanding SAFE. Its
        // isOwn = true entry is exempt from both Outbox.purgeStale and Outbox.evictOne, so it
        // sat there forever while a second own entry (the new SOS) piled up alongside it.
        repeat(5) {
            node.markSafe()
            assertEquals(1, node.snapshot.value.carrying, "a SAFE replaces the SOS under the same key, not adds to it")

            node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)
            assertEquals(1, node.snapshot.value.carrying, "carrying must not grow across safe -> SOS cycles")
        }
    }

    @Test
    fun `own SAFE is dropped from the outbox once past OWN_SAFE_BROADCAST_MILLIS`() {
        val host = FakeHost(battery = 90)
        val node = MeshNode(NodeId(1), FakeLink(), host)
        val now = host.nowMillis()

        node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1, now)
        node.markSafe(now)
        assertEquals(1, node.snapshot.value.carrying)
        assertTrue(node.carriedMessages(now).any { it.type == MessageType.SAFE })

        // Not yet expired just short of the boundary.
        assertEquals(1, node.beaconsToAdvertise(10, now + ownSafeBroadcastMillis - 1).size)

        // Past the bounded broadcast life the SAFE has done its whole job -- continuing to
        // advertise it is pure airtime waste, and worse, Outbox.carouselOrder sorts isOwn first
        // so it would keep outranking a live SOS. Before this fix there was no bound at all: an
        // isOwn entry is exempt from both Outbox.purgeStale and Outbox.evictOne, so nothing ever
        // reclaimed it.
        val after = now + ownSafeBroadcastMillis + 1
        assertTrue(node.beaconsToAdvertise(10, after).isEmpty(), "own SAFE must be reclaimed past its bounded broadcast life")
        assertEquals(0, node.carriedMessages(after).size)
    }

    @Test
    fun `a new SOS supersedes an outstanding own SAFE`() {
        val node = MeshNode(NodeId(1), FakeLink(), FakeHost(battery = 90))

        node.originateSos(SituationFlags(severity = Severity.HIGH), souls = 1)
        node.markSafe()
        assertTrue(node.carriedMessages().any { it.type == MessageType.SAFE })

        // The person is calling for help again: the stale "I am safe" must not keep occupying
        // the outbox (or carousel priority) ahead of the new SOS.
        val second = node.originateSos(SituationFlags(severity = Severity.CRITICAL), souls = 2)
        assertEquals(1, node.snapshot.value.carrying)
        val carried = node.carriedMessages()
        assertEquals(listOf(second), carried.map { it.messageId })
        assertTrue(carried.none { it.type == MessageType.SAFE })
    }

    // ---------------------------------------------------------------- cancelled-SOS resurrection

    @Test
    fun `a cancelled SOS is not resurrected via the reconsideration path`() {
        val host = FakeHost(battery = 20)
        // MODERATE, self 20 vs origin 90, k=0 -> p = 0.09, same construction as the
        // reconsideration tests above. Draw 1 loses the SOS's first roll (PROBABILISTIC,
        // tracked by ReconsiderTracker); draw 2 relays the cancelling SAFE; draw 3 is what a
        // buggy re-run of the policy against the still-outstanding reconsideration entry would
        // consume, and would relay (0.01 < 0.09) -- demonstrating the resurrection this fix
        // closes rather than merely avoiding it by accident.
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.99, 0.01, 0.01)))
        val beacon = lowProbabilityBeacon(900)
        val encoded = BeaconCodec.encode(beacon)
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(first is RelayDecision.Suppress && first.reason == SuppressReason.PROBABILISTIC)

        // Cancelled: a SAFE referencing the same message id arrives.
        val safe = testBeacon(900, MessageType.SAFE)
        val safeDecision = node.onBeaconHeard(BeaconCodec.encode(safe), PeerHandle("B"), now)
        assertTrue(safeDecision is RelayDecision.Relay, "the cancelling SAFE itself must still relay")

        // Heard again inside the reconsideration window. Before this fix, ReconsiderTracker's
        // attempts entry for this dedup key was untouched by the SAFE, so the policy ran again.
        val second = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(second is RelayDecision.Suppress, "a cancelled SOS must never relay again")
        assertEquals(SuppressReason.CANCELLED, (second as RelayDecision.Suppress).reason)
        assertFalse(node.carriedMessages(now).any { it.messageId == beacon.messageId && it.type == MessageType.SOS })
    }

    @Test
    fun `a cancelled SOS is not resurrected as a fresh hearing once SeenSet has forgotten it`() {
        val host = FakeHost(charging = true)
        // CRITICAL while charging pins probability to exactly 1.0 (see the DUPLICATE test above
        // for why HIGH is not enough) -- three scripted draws cover the SOS's own relay, the
        // cancelling SAFE's relay, and what a buggy fresh policy run after SeenSet's ten-minute
        // window would consume and relay.
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.5, 0.5, 0.5)))
        val beacon = testBeacon(901, severity = Severity.CRITICAL)
        val encoded = BeaconCodec.encode(beacon)
        val now = host.nowMillis()

        val first = node.onBeaconHeard(encoded, PeerHandle("A"), now)
        assertTrue(first is RelayDecision.Relay)

        val safe = testBeacon(901, MessageType.SAFE)
        node.onBeaconHeard(BeaconCodec.encode(safe), PeerHandle("B"), now)
        assertFalse(node.carriedMessages(now).any { it.messageId == beacon.messageId && it.type == MessageType.SOS })

        // Past SeenSet's ten-minute window, a re-heard SOS(901) is a genuine *first* hearing
        // again -- a different route into resurrection than the reconsideration test above.
        // CancelledSet's much longer expiry (Outbox.DEFAULT_MAX_AGE_MILLIS, six hours) is what
        // has to catch this one.
        val muchLater = now + 11 * 60_000L
        val second = node.onBeaconHeard(encoded, PeerHandle("A"), muchLater)
        assertTrue(second is RelayDecision.Suppress)
        assertEquals(SuppressReason.CANCELLED, (second as RelayDecision.Suppress).reason)
        assertFalse(node.carriedMessages(muchLater).any { it.messageId == beacon.messageId && it.type == MessageType.SOS })
    }

    @Test
    fun `relaying a SAFE or RECEIPT is unaffected by CancelledSet`() {
        val host = FakeHost(charging = true)
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.0, 0.0)))
        val now = host.nowMillis()

        val safe = testBeacon(910, MessageType.SAFE)
        val safeDecision = node.onBeaconHeard(BeaconCodec.encode(safe), PeerHandle("A"), now)
        assertTrue(safeDecision is RelayDecision.Relay, "a SAFE must still relay normally")

        val receipt = testBeacon(911, MessageType.RECEIPT)
        val receiptDecision = node.onBeaconHeard(BeaconCodec.encode(receipt), PeerHandle("A"), now)
        assertTrue(receiptDecision is RelayDecision.Relay, "a RECEIPT must still relay normally")
    }

    @Test
    fun `a different message id from the same origin is unaffected by a prior cancellation`() {
        val host = FakeHost(charging = true)
        val node = MeshNode(NodeId(1), FakeLink(), host, random = ScriptedRandom(listOf(0.0, 0.0, 0.0)))
        val now = host.nowMillis()

        val firstBeacon = testBeacon(920, severity = Severity.CRITICAL)
        node.onBeaconHeard(BeaconCodec.encode(firstBeacon), PeerHandle("A"), now)
        val safe = testBeacon(920, MessageType.SAFE)
        node.onBeaconHeard(BeaconCodec.encode(safe), PeerHandle("B"), now)
        assertFalse(node.carriedMessages(now).any { it.messageId == firstBeacon.messageId && it.type == MessageType.SOS })

        // Same origin, but a genuinely different message id -- e.g. a brand new SOS the same
        // person raised later. MessageId.of mixes in a fresh sequence number, so this must relay
        // exactly as if 920 had never been cancelled.
        val secondBeacon = testBeacon(921, severity = Severity.CRITICAL)
        val decision = node.onBeaconHeard(BeaconCodec.encode(secondBeacon), PeerHandle("A"), now)
        assertTrue(decision is RelayDecision.Relay, "a different message id must not be suppressed by a prior cancellation")
        assertTrue(node.carriedMessages(now).any { it.messageId == secondBeacon.messageId })
    }
}
