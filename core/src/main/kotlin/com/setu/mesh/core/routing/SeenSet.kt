package com.setu.mesh.core.routing

import com.setu.mesh.core.model.MessageId

/**
 * Bounded, expiring record of message ids already handled.
 *
 * Without this, a controlled flood is just a broadcast storm: three nodes in mutual range will
 * relay the same beacon to each other until their batteries are flat. Dedup is therefore not an
 * optimisation, it is the thing that stops the protocol from destroying the network it runs on.
 *
 * Bounded by both count and age: count so memory cannot grow without limit on a long-lived
 * relay, and age so that a genuinely repeated SOS from the same person hours later is treated
 * as new information rather than silently swallowed.
 */
class SeenSet(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
) {
    private val entries = object : LinkedHashMap<Int, Long>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean =
            size > capacity
    }

    val size: Int get() = entries.size

    fun contains(id: MessageId, nowMillis: Long): Boolean {
        val at = entries[id.raw] ?: return false
        if (nowMillis - at > expiryMillis) {
            entries.remove(id.raw)
            return false
        }
        return true
    }

    /**
     * Records [id] and reports whether it was new. The engine's hot path is a single call to
     * this: `if (!seen.addIfNew(id, now)) return` .
     */
    fun addIfNew(id: MessageId, nowMillis: Long): Boolean {
        if (contains(id, nowMillis)) {
            entries[id.raw] = entries[id.raw] ?: nowMillis
            return false
        }
        entries[id.raw] = nowMillis
        return true
    }

    fun purgeExpired(nowMillis: Long) {
        entries.entries.removeAll { nowMillis - it.value > expiryMillis }
    }

    companion object {
        const val DEFAULT_CAPACITY = 1024
        const val DEFAULT_EXPIRY_MILLIS = 10 * 60 * 1000L
    }
}
