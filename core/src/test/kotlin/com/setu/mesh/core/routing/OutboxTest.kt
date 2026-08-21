package com.setu.mesh.core.routing

import com.setu.mesh.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OutboxTest {

    private fun testBeacon(id: Int, severity: Severity): SosBeacon = SosBeacon(
        type = MessageType.SOS,
        ttl = 7,
        hops = 0,
        messageId = MessageId(id),
        origin = NodeId(1),
        position = GeoPoint.of(0.0, 0.0),
        epochMinute = 0,
        flags = SituationFlags(severity = severity),
        souls = 1,
        originBattery = 100
    )

    @Test
    fun `carousel ordering and eviction`() {
        // Capacity is 3 for testing
        val outbox = Outbox(capacity = 3)

        // own -> severity -> fewest carriers -> newest
        val b1 = testBeacon(1, Severity.LOW)
        val b2 = testBeacon(2, Severity.MODERATE)
        val b3 = testBeacon(3, Severity.CRITICAL)

        outbox.put(b1, 1000L, isOwn = false)
        outbox.put(b2, 1100L, isOwn = false)
        outbox.put(b3, 1200L, isOwn = true) // own message

        var order = outbox.carouselOrder(1300L)
        assertEquals(3, order.size)
        // 1st: own (b3)
        assertEquals(MessageId(3), order[0].beacon.messageId)
        // 2nd: severity MODERATE > LOW (b2)
        assertEquals(MessageId(2), order[1].beacon.messageId)
        // 3rd: severity LOW (b1)
        assertEquals(MessageId(1), order[2].beacon.messageId)

        // Add 4th to trigger eviction
        val b4 = testBeacon(4, Severity.HIGH)
        outbox.put(b4, 1300L, isOwn = false)

        // Eviction should drop the lowest severity non-own message (b1 - LOW)
        assertNull(outbox.get(MessageId(1)))
        assertEquals(3, outbox.size)

        order = outbox.carouselOrder(1400L)
        // order should be b3 (own) -> b4 (HIGH) -> b2 (MODERATE)
        assertEquals(MessageId(3), order[0].beacon.messageId)
        assertEquals(MessageId(4), order[1].beacon.messageId)
        assertEquals(MessageId(2), order[2].beacon.messageId)
        
        // Let's test fewest carriers tiebreaker. b4 and b5 both HIGH.
        val b5 = testBeacon(5, Severity.HIGH)
        outbox.remove(MessageId(2)) // make room
        outbox.put(b5, 1500L, isOwn = false)
        
        // Now outbox has b3(own), b4(HIGH), b5(HIGH). 
        // We add carrier for b4 so b5 has fewer.
        outbox.noteCarrier(MessageId(4), 99)
        
        order = outbox.carouselOrder(1600L)
        // b3 (own) -> b5 (HIGH, 0 carriers) -> b4 (HIGH, 1 carrier)
        assertEquals(MessageId(3), order[0].beacon.messageId)
        assertEquals(MessageId(5), order[1].beacon.messageId)
        assertEquals(MessageId(4), order[2].beacon.messageId)
    }

    @Test
    fun `remove on RECEIPT`() {
        val outbox = Outbox()
        outbox.put(testBeacon(1, Severity.LOW), 1000L, isOwn = false)
        assertTrue(outbox.contains(MessageId(1)))
        
        assertTrue(outbox.remove(MessageId(1)))
        assertFalse(outbox.contains(MessageId(1)))
    }

    @Test
    fun `purgeStale spares own messages`() {
        val outbox = Outbox()
        outbox.put(testBeacon(1, Severity.LOW), 1000L, isOwn = false)
        outbox.put(testBeacon(2, Severity.LOW), 1000L, isOwn = true)
        
        val maxAge = Outbox.DEFAULT_MAX_AGE_MILLIS
        outbox.purgeStale(1000L + maxAge + 1)
        
        assertFalse(outbox.contains(MessageId(1))) // purged
        assertTrue(outbox.contains(MessageId(2))) // spared because it's own
    }
}
