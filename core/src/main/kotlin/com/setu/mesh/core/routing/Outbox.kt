package com.setu.mesh.core.routing

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon

/** One message this node is currently carrying on behalf of the mesh. */
class OutboxEntry(
    val beacon: SosBeacon,
    val addedAtMillis: Long,
    val isOwn: Boolean,
) {
    val encoded: ByteArray = BeaconCodec.encode(beacon)

    /** Distinct neighbours observed re-advertising this same message. Feeds density damping. */
    var neighboursHoldingCopy: Int = 0
        private set

    private val carriers = mutableSetOf<Int>()

    fun noteCarrier(nodeIdRaw: Int) {
        if (carriers.add(nodeIdRaw)) neighboursHoldingCopy = carriers.size
    }
}

/**
 * What this node is carrying and broadcasting.
 *
 * Capacity is small on purpose. A phone in a disaster is a courier with a bicycle, not a mail
 * server: it should hold the handful of messages most likely to still need moving, and let the
 * mesh's redundancy cover the rest. An unbounded buffer would trade someone's battery for
 * copies of messages that ten other phones already hold.
 *
 * Eviction order is the inverse of [carouselOrder]: whatever is least worth broadcasting is
 * also the first thing dropped when the buffer is full.
 */
class Outbox(private val capacity: Int = DEFAULT_CAPACITY) {

    // Keyed on (messageId, type), not messageId alone. A RECEIPT/SAFE deliberately carries the
    // *original* SOS's messageId (see MeshNode.markSafe / originateReceipt) so it can point back
    // at what it confirms -- but that means messageId alone can no longer identify one carried
    // beacon, since the SOS and its own RECEIPT collide on it. Folding the type into the key
    // lets both coexist, which is required for the RECEIPT to survive its own echo: without this
    // a node carrying a RECEIPT would delete its own copy the moment a neighbour echoed the same
    // RECEIPT back, because "remove the SOS this refers to" and "remove the RECEIPT itself" were
    // indistinguishable operations on the same key.
    private val entries = LinkedHashMap<Long, OutboxEntry>()

    val size: Int get() = entries.size

    fun contains(id: MessageId, type: MessageType): Boolean = entries.containsKey(key(id, type))

    fun get(id: MessageId, type: MessageType): OutboxEntry? = entries[key(id, type)]

    fun all(): List<OutboxEntry> = entries.values.toList()

    fun put(beacon: SosBeacon, nowMillis: Long, isOwn: Boolean): OutboxEntry {
        val k = key(beacon.messageId, beacon.type)
        val existing = entries[k]
        if (existing != null) return existing

        val entry = OutboxEntry(beacon, nowMillis, isOwn)
        entries[k] = entry
        if (entries.size > capacity) evictOne()
        return entry
    }

    /**
     * Stop carrying the beacon identified by ([id], [type]). Called when a RECEIPT proves the
     * matching SOS was delivered, when custody is transferred to a healthier node, or when the
     * sender marks themselves safe -- always naming the SOS entry explicitly so a RECEIPT/SAFE
     * beacon that merely *references* that id can never be mistaken for the entry it targets.
     *
     * Every call to this frees radio time, which is why delivery confirmation is treated as an
     * energy optimisation rather than a nicety.
     */
    fun remove(id: MessageId, type: MessageType): Boolean = entries.remove(key(id, type)) != null

    fun noteCarrier(id: MessageId, type: MessageType, carrierNodeIdRaw: Int) {
        entries[key(id, type)]?.noteCarrier(carrierNodeIdRaw)
    }

    /**
     * The beacons to broadcast, best first.
     *
     * When the radio exposes fewer advertising slots than we are carrying — which is the common
     * case, since many Android devices report `isMultipleAdvertisementSupported() == false` —
     * the engine round-robins this list over successive intervals. That rotation is the "beacon
     * carousel", and this ordering decides who gets airtime first when it is scarce.
     */
    fun carouselOrder(nowMillis: Long): List<OutboxEntry> = entries.values.sortedWith(
        compareByDescending<OutboxEntry> { it.isOwn }
            .thenByDescending { it.beacon.flags.severity.wire }
            .thenBy { it.neighboursHoldingCopy }
            .thenByDescending { it.addedAtMillis },
    )

    fun encodedCarousel(nowMillis: Long): List<ByteArray> =
        carouselOrder(nowMillis).map { it.encoded }

    /** Drop anything older than [maxAgeMillis] that this node did not originate. */
    fun purgeStale(nowMillis: Long, maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS) {
        entries.entries.removeAll { (_, e) ->
            !e.isOwn && nowMillis - e.addedAtMillis > maxAgeMillis
        }
    }

    private fun evictOne() {
        val victim = entries.values
            .filterNot { it.isOwn }
            .minWithOrNull(
                compareBy<OutboxEntry> { it.beacon.flags.severity.wire }
                    .thenByDescending { it.neighboursHoldingCopy }
                    .thenBy { it.addedAtMillis },
            ) ?: return
        entries.remove(key(victim.beacon.messageId, victim.beacon.type))
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
        const val DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L

        /** Convenience for tests and the simulator. */
        fun severityOf(entry: OutboxEntry): Severity = entry.beacon.flags.severity

        /**
         * Pack (messageId, type) into one Long key. [MessageId.raw] is a full 32-bit Int --
         * sign-extending it into the low 32 bits of the Long and shifting left by 3 never loses
         * information, and OR-ing in [MessageType.wire] (3 bits, 0-7) into the freed low bits
         * recovers a unique key per (id, type) pair with no collisions between distinct ids.
         */
        private fun key(id: MessageId, type: MessageType): Long =
            (id.raw.toLong() shl 3) or type.wire.toLong()
    }
}
