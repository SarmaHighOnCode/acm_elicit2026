package com.setu.mesh.core.routing

import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.MessageType

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

/**
 * Bounded, expiring record of how many times a [SuppressReason.PROBABILISTIC] suppression has
 * been reconsidered, keyed by the same dedup key [SeenSet] uses.
 *
 * Deliberately the same shape as [SeenSet] — an LRU cap plus an age cap — rather than a second
 * bounding scheme: the reason a dedup key needs bounding at all (memory cannot grow without
 * limit; a key nobody has mentioned in ten minutes is not worth remembering) applies here
 * identically. The capacity is far smaller than [SeenSet]'s 1024, because only messages that
 * actually lost their probabilistic roll ever land here — everything relayed outright, or
 * terminally suppressed by [SuppressReason.TTL_EXHAUSTED] or [SuppressReason.ENERGY_GATE], never
 * gets an entry.
 *
 * See `MeshNode.onBeaconHeard` for how this closes RC4's second defect: without it, one lost
 * dice roll ended a message's life for the full 10-minute [SeenSet] window, no matter how many
 * more times it was heard.
 */
class ReconsiderTracker(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val expiryMillis: Long = SeenSet.DEFAULT_EXPIRY_MILLIS,
) {
    private val entries = object : LinkedHashMap<Int, Entry>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>): Boolean =
            size > capacity
    }

    val size: Int get() = entries.size

    /**
     * Attempts already spent reconsidering [key], or null if it is not being tracked — never
     * suppressed probabilistically, already resolved (relayed, or its budget spent), or expired.
     */
    fun attempts(key: Int, nowMillis: Long): Int? {
        val entry = entries[key] ?: return null
        if (nowMillis - entry.atMillis > expiryMillis) {
            entries.remove(key)
            return null
        }
        return entry.attempts
    }

    /** Records that [key] has now been attempted [attempts] times, as of [nowMillis]. */
    fun recordAttempt(key: Int, attempts: Int, nowMillis: Long) {
        entries[key] = Entry(attempts, nowMillis)
    }

    /** Stops tracking [key] — it has a terminal answer now, one way or the other. */
    fun clear(key: Int) {
        entries.remove(key)
    }

    fun purgeExpired(nowMillis: Long) {
        entries.entries.removeAll { nowMillis - it.value.atMillis > expiryMillis }
    }

    private data class Entry(val attempts: Int, val atMillis: Long)

    companion object {
        /**
         * Smaller than [SeenSet.DEFAULT_CAPACITY] on purpose — see the class doc. 64 lost rolls
         * outstanding at once is already a very busy neighbourhood.
         */
        const val DEFAULT_CAPACITY = 64
    }
}

/**
 * Bounded, expiring record of message ids whose SOS is already cancelled — a
 * [MessageType.RECEIPT] or [MessageType.SAFE] referencing that id was heard, or this node itself
 * originated the SAFE (`MeshNode.markSafe`).
 *
 * This used to be implicit, and that was the bug. Before reconsideration existed, `SeenSet` was
 * the entire forwarding gate and `onBeaconHeard` returned early on any already-seen id — so a
 * cancelled SOS re-heard inside `SeenSet`'s window was suppressed as a side effect of ordinary
 * dedup, never because anything actually remembered it had been cancelled. Reconsideration had
 * to remove that early return — a lost [SuppressReason.PROBABILISTIC] roll genuinely needs
 * revisiting — and doing so deleted the accidental protection along with it: a carrier still
 * advertising a cancelled `SOS(M)` inside the reconsideration window could turn that dead message
 * back into a fresh `Relay` and put it right back in the outbox, RECEIPT/SAFE notwithstanding.
 * This set replaces the accident with an explicit fact.
 *
 * **Keyed on the raw, un-folded [MessageId]** — deliberately not the type-folded dedup key
 * `SeenSet`/`ReconsiderTracker` use. A cancellation always names the *original* SOS, and a
 * `SAFE`/`RECEIPT` carries that exact id verbatim in `beacon.messageId` (docs/PROTOCOL.md §3) —
 * but its own *dedup* key folds in its own type, so `dedupKey(SAFE)` and `dedupKey(SOS)` for the
 * same referenced message differ. Keying this on the dedup key instead would silently miss every
 * cancellation it exists to catch.
 *
 * **Expiry is six hours ([Outbox.DEFAULT_MAX_AGE_MILLIS]), not `SeenSet`'s ten minutes**, and
 * that gap is the fix, not an implementation detail. Bounding this to `SeenSet`'s window would
 * only move the bug: the moment `SeenSet` forgets `M`, a re-heard `SOS(M)` looks like a brand new
 * first hearing again — the identical phantom, resurrected by a slightly longer route through
 * the *other* early return this class replaces. The principled bound is "how long could any node
 * anywhere still be carrying `M`", and that is exactly what `Outbox.DEFAULT_MAX_AGE_MILLIS`
 * means — past it, no honest carrier has `M` left to re-advertise, cancelled or not, so there is
 * nothing left to defend against. Remembering a cancelled id for hours rather than minutes is
 * safe precisely because a genuinely new SOS from the same person always mints a **fresh**
 * [MessageId] (`MessageId.of` mixes in a new `sequence`) — this can delay a stale echo of a
 * closed call for help, never suppress a real new one.
 *
 * Same bounded shape as [SeenSet] and [ReconsiderTracker] — an LRU cap plus an age cap — rather
 * than a third bounding scheme, for the identical reason both of those give: memory cannot grow
 * without limit, and an id nobody has mentioned in [DEFAULT_EXPIRY_MILLIS] is not worth
 * remembering.
 */
class CancelledSet(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
) {
    private val entries = object : LinkedHashMap<Int, Long>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Long>): Boolean =
            size > capacity
    }

    val size: Int get() = entries.size

    /** True if [id]'s SOS was cancelled and that record has not yet expired. */
    fun contains(id: MessageId, nowMillis: Long): Boolean {
        val at = entries[id.raw] ?: return false
        if (nowMillis - at > expiryMillis) {
            entries.remove(id.raw)
            return false
        }
        return true
    }

    /** Records that [id]'s SOS is cancelled as of [nowMillis]. */
    fun record(id: MessageId, nowMillis: Long) {
        entries[id.raw] = nowMillis
    }

    fun purgeExpired(nowMillis: Long) {
        entries.entries.removeAll { nowMillis - it.value > expiryMillis }
    }

    companion object {
        /**
         * Smaller than [SeenSet.DEFAULT_CAPACITY] despite a much longer [DEFAULT_EXPIRY_MILLIS]:
         * an entry lands here only once per cancelled SOS — a single event per message lifecycle
         * — not on every hearing of every message type the way `SeenSet` fills up. 512
         * comfortably covers a disaster-scale mesh's worth of distinct cancellations outstanding
         * across a six-hour window without needing `SeenSet`'s full 1024, which is sized for a
         * ten-minute window of *every* hearing.
         */
        const val DEFAULT_CAPACITY = 512

        /** See the class doc for why this is [Outbox.DEFAULT_MAX_AGE_MILLIS] and not [SeenSet.DEFAULT_EXPIRY_MILLIS]. */
        const val DEFAULT_EXPIRY_MILLIS = Outbox.DEFAULT_MAX_AGE_MILLIS
    }
}
