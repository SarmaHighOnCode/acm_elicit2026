package com.setu.mesh.core.routing

import com.setu.mesh.core.model.MessageId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SeenSetTest {

    @Test
    fun `addIfNew true then false`() {
        val seen = SeenSet()
        val id = MessageId(1)
        val now = 1000L

        assertTrue(seen.addIfNew(id, now))
        assertFalse(seen.addIfNew(id, now + 1000L))
    }

    @Test
    fun `expiry after 10 min makes it new again`() {
        val expiryMillis = SeenSet.DEFAULT_EXPIRY_MILLIS
        val seen = SeenSet(expiryMillis = expiryMillis)
        val id = MessageId(1)
        val now = 1000L

        assertTrue(seen.addIfNew(id, now))
        
        // Before expiry, it is still not new
        assertFalse(seen.addIfNew(id, now + expiryMillis))
        
        // After expiry, it is new again
        assertTrue(seen.addIfNew(id, now + expiryMillis + 1))
    }

    @Test
    fun `capacity evicts LRU`() {
        val capacity = 5
        val seen = SeenSet(capacity = capacity)
        val now = 1000L

        for (i in 1..capacity) {
            assertTrue(seen.addIfNew(MessageId(i), now + i))
        }
        assertEquals(capacity, seen.size)

        // Add one more to evict the first one
        assertTrue(seen.addIfNew(MessageId(capacity + 1), now + capacity + 1))
        assertEquals(capacity, seen.size)

        // First one should be new again
        assertTrue(seen.addIfNew(MessageId(1), now + capacity + 2))
    }

    @Test
    fun `purgeExpired removes old entries`() {
        val expiryMillis = 100L
        val seen = SeenSet(expiryMillis = expiryMillis)
        
        seen.addIfNew(MessageId(1), 1000L)
        seen.addIfNew(MessageId(2), 1050L)
        
        assertEquals(2, seen.size)
        
        // At 1120L, Message 1 (added at 1000L) is older than 100L, so it should be purged.
        // Message 2 (added at 1050L) is 70L old, so it stays.
        seen.purgeExpired(1120L)
        
        assertEquals(1, seen.size)
        assertTrue(seen.addIfNew(MessageId(1), 1120L)) // 1 was purged, so it's new
        assertFalse(seen.addIfNew(MessageId(2), 1120L)) // 2 was not purged, so it's not new
    }
}
