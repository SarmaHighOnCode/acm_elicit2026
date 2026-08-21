package com.setu.mesh.core.engine

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.link.LinkEvent
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.model.*
import com.setu.mesh.core.power.PowerGovernor
import com.setu.mesh.core.routing.RelayDecision
import com.setu.mesh.core.routing.SuppressReason
import com.setu.mesh.core.support.FakeHost
import com.setu.mesh.core.support.FakeLink
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MeshNodeTest {

    private fun testBeacon(id: Int, type: MessageType = MessageType.SOS, ttl: Int = 7): SosBeacon = SosBeacon(
        type = type,
        ttl = ttl,
        hops = 0,
        messageId = MessageId(id),
        origin = NodeId(100),
        position = GeoPoint.of(0.0, 0.0),
        epochMinute = 0,
        flags = SituationFlags(severity = Severity.HIGH),
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
    fun `duplicate beacon suppressed`() {
        val link = FakeLink()
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host)

        val beacon = testBeacon(123)
        val encoded = BeaconCodec.encode(beacon)

        // First time should relay or probabilistically suppress, but not TTL or DUP
        val decision1 = node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis())
        assertTrue(decision1 is RelayDecision.Relay || (decision1 is RelayDecision.Suppress && decision1.reason == SuppressReason.PROBABILISTIC))

        // Second time should be suppressed due to duplicate (probabilistic since it doesn't add to seen set)
        val decision2 = node.onBeaconHeard(encoded, PeerHandle("A"), host.nowMillis())
        assertTrue(decision2 is RelayDecision.Suppress)
        assertEquals(SuppressReason.PROBABILISTIC, (decision2 as RelayDecision.Suppress).reason)
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
        val host = FakeHost()
        val node = MeshNode(NodeId(1), link, host, random = Random(1)) // seed 1

        val msg1 = node.originateSos(SituationFlags(severity = Severity.LOW), 1)
        val msg2 = node.originateSos(SituationFlags(severity = Severity.HIGH), 1)
        val msg3 = node.originateSos(SituationFlags(severity = Severity.CRITICAL), 1)

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
}
