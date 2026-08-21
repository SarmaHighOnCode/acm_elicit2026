package com.setu.mesh.core.engine

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import com.setu.mesh.core.model.SosBeacon
import com.setu.mesh.core.support.FakeHost
import com.setu.mesh.core.support.FakeLink
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.setu.mesh.core.link.PeerHandle

class GatewayRoleTest {

    @Test
    fun `acceptDelivery with no uplink emits no beacon and returns null`() {
        val host = FakeHost()
        val node = MeshNode(NodeId(1), FakeLink(), host)
        val gateway = GatewayRole(node)

        // Gateway initially has uplinkAvailable = false
        val messageId = MessageId(123)
        val result = gateway.acceptDelivery(messageId, host.nowMillis())

        assertNull(result)
        val beacons = node.beaconsToAdvertise(10, host.nowMillis())
        assertTrue(beacons.isEmpty())
    }

    @Test
    fun `acceptDelivery with uplink emits a RECEIPT beacon`() {
        val host = FakeHost()
        val node = MeshNode(NodeId(1), FakeLink(), host)
        val gateway = GatewayRole(node)
        gateway.onUplinkAvailable(true)

        val messageId = MessageId(123)
        val result = gateway.acceptDelivery(messageId, host.nowMillis())

        assertEquals(messageId, result)

        val beacons = node.beaconsToAdvertise(10, host.nowMillis())
        assertEquals(1, beacons.size)

        val decoded = BeaconCodec.decode(beacons.first())
        assertNotNull(decoded)
        assertEquals(MessageType.RECEIPT, decoded!!.type)
        assertEquals(messageId, decoded.messageId)
    }

    @Test
    fun `calling acceptDelivery twice for the same id emits only once`() {
        val host = FakeHost()
        val node = MeshNode(NodeId(1), FakeLink(), host)
        val gateway = GatewayRole(node)
        gateway.onUplinkAvailable(true)

        val messageId = MessageId(123)
        val result1 = gateway.acceptDelivery(messageId, host.nowMillis())
        val result2 = gateway.acceptDelivery(messageId, host.nowMillis())

        assertNotNull(result1)
        assertNull(result2) // Second call returns null

        val beacons = node.beaconsToAdvertise(10, host.nowMillis())
        assertEquals(1, beacons.size)
    }

    @Test
    fun `a node receiving that receipt removes the original from its outbox`() {
        val host1 = FakeHost()
        val node1 = MeshNode(NodeId(1), FakeLink(), host1) // The carrier
        
        val host2 = FakeHost()
        val node2 = MeshNode(NodeId(2), FakeLink(), host2) // The gateway
        val gateway = GatewayRole(node2)
        gateway.onUplinkAvailable(true)

        // Carrier originates an SOS
        val messageId = node1.originateSos(SituationFlags(Severity.HIGH), 1, host1.nowMillis())

        // Ensure the SOS is in the carrier's outbox
        var beacons = node1.beaconsToAdvertise(10, host1.nowMillis())
        assertTrue(beacons.any { BeaconCodec.decode(it)?.type == MessageType.SOS })

        // Gateway accepts delivery and emits a receipt
        gateway.acceptDelivery(messageId, host2.nowMillis())
        val receiptBeacons = node2.beaconsToAdvertise(10, host2.nowMillis())
        val receiptEncoded = receiptBeacons.first { BeaconCodec.decode(it)?.type == MessageType.RECEIPT }

        // Carrier receives the receipt
        node1.onBeaconHeard(receiptEncoded, PeerHandle("gateway"), host1.nowMillis())

        // The carrier's outbox should no longer contain the SOS
        beacons = node1.beaconsToAdvertise(10, host1.nowMillis())
        assertFalse(beacons.any { BeaconCodec.decode(it)?.type == MessageType.SOS })
    }

    @Test
    fun `a node receiving a receipt for a message it never had does not crash`() {
        val host = FakeHost()
        val node = MeshNode(NodeId(1), FakeLink(), host)

        // Node receives a receipt for an unknown message
        val receipt = SosBeacon(
            type = MessageType.RECEIPT,
            ttl = 7,
            hops = 0,
            messageId = MessageId(999),
            origin = NodeId(2),
            position = GeoPoint.of(0.0, 0.0),
            epochMinute = 0,
            flags = SituationFlags(),
            souls = 0,
            originBattery = 100
        )
        val encoded = BeaconCodec.encode(receipt)

        assertDoesNotThrow {
            node.onBeaconHeard(encoded, PeerHandle("B"), host.nowMillis())
        }
    }
}
