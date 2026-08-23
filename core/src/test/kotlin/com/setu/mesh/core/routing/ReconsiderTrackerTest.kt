package com.setu.mesh.core.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReconsiderTrackerTest {

    @Test
    fun `attempts is null for a key never recorded`() {
        val tracker = ReconsiderTracker()
        assertNull(tracker.attempts(42, nowMillis = 0L))
    }

    @Test
    fun `records and reports attempts, then clear removes it`() {
        val tracker = ReconsiderTracker()
        tracker.recordAttempt(1, attempts = 1, nowMillis = 0L)
        assertEquals(1, tracker.attempts(1, nowMillis = 0L))

        tracker.recordAttempt(1, attempts = 2, nowMillis = 100L)
        assertEquals(2, tracker.attempts(1, nowMillis = 100L))

        tracker.clear(1)
        assertNull(tracker.attempts(1, nowMillis = 100L))
    }

    @Test
    fun `bounded by capacity -- the oldest key is evicted once past it`() {
        val capacity = 4
        val tracker = ReconsiderTracker(capacity = capacity)
        for (key in 1..capacity) {
            tracker.recordAttempt(key, attempts = 1, nowMillis = 0L)
        }
        assertEquals(capacity, tracker.size)

        // One more key past capacity must evict the least-recently-touched entry (key 1, which
        // nothing has read or rewritten since).
        tracker.recordAttempt(capacity + 1, attempts = 1, nowMillis = 0L)
        assertEquals(capacity, tracker.size, "must stay bounded, not grow past capacity")
        assertNull(tracker.attempts(1, nowMillis = 0L), "the oldest untouched key must have been evicted")
        assertEquals(1, tracker.attempts(capacity + 1, nowMillis = 0L))
    }

    @Test
    fun `bounded by age -- an entry older than expiryMillis reads as gone`() {
        val expiryMillis = 10 * 60 * 1000L
        val tracker = ReconsiderTracker(expiryMillis = expiryMillis)
        tracker.recordAttempt(7, attempts = 1, nowMillis = 0L)

        assertEquals(1, tracker.attempts(7, nowMillis = expiryMillis))
        assertNull(tracker.attempts(7, nowMillis = expiryMillis + 1), "past expiry, the entry is gone")
    }

    @Test
    fun `purgeExpired removes stale entries without waiting for a read`() {
        val expiryMillis = 1_000L
        val tracker = ReconsiderTracker(expiryMillis = expiryMillis)
        tracker.recordAttempt(1, attempts = 1, nowMillis = 0L)
        tracker.recordAttempt(2, attempts = 1, nowMillis = 2_000L)

        tracker.purgeExpired(nowMillis = 2_000L)

        assertEquals(1, tracker.size, "only the fresh entry should remain")
        assertNull(tracker.attempts(1, nowMillis = 2_000L))
        assertEquals(1, tracker.attempts(2, nowMillis = 2_000L))
    }
}
