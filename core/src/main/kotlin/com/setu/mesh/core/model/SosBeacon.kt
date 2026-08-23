package com.setu.mesh.core.model

/**
 * The unit of propagation in SETU: a complete, routable emergency message in **24 bytes**.
 *
 * The size is not an aesthetic choice. A BLE legacy advertisement carries 31 bytes of AD
 * payload; the mandatory Flags structure costs 3 and a 16-bit Service Data header costs 4,
 * leaving exactly 24. Fitting inside that budget is what lets a node relay by *re-advertising*
 * instead of opening a connection — and connectionless broadcast is the only radio mode cheap
 * enough to keep running at 4% battery.
 *
 * Field layout is documented in `docs/PROTOCOL.md` and implemented in
 * [com.setu.mesh.core.codec.BeaconCodec].
 */
data class SosBeacon(
    val type: MessageType,
    /** Remaining hop budget, 0..15. A relay decrements this before re-advertising. */
    val ttl: Int,
    /** Hops already travelled, 0..15. Grows as TTL shrinks; used for provenance in the UI. */
    val hops: Int,
    val messageId: MessageId,
    val origin: NodeId,
    val position: GeoPoint,
    /** Minutes since [SETU_EPOCH_MILLIS]. Doubles as the mesh's shared coarse clock. */
    val epochMinute: Int,
    val flags: SituationFlags,
    /** People at the location, 0..255. */
    val souls: Int,
    /**
     * Battery of the node that *originated* this message, 0..100.
     *
     * Carrying it costs one byte and buys the two cheapest optimisations in the protocol:
     * the energy-gradient forwarding rule, and a scanner election that needs no negotiation
     * traffic at all because every node can already see its neighbours' energy.
     */
    val originBattery: Int,
    /**
     * Accuracy class of [position], 0..3, packed into the two reserved bits of wire byte 0.
     * See docs/PROTOCOL.md §2 for the class boundaries. Defaults to 0 (unknown) so every
     * construction site that predates this field -- test fixtures, `:sim`, and the RECEIPT/SAFE
     * originators in [com.setu.mesh.core.engine.MeshNode] that were written before it existed --
     * keeps compiling without having to fabricate a quality figure it does not have.
     */
    val positionAccuracyClass: Int = 0,
) {
    val isCritical: Boolean get() = flags.severity == Severity.CRITICAL

    /**
     * What [positionAccuracyClass] actually claims about [position], in metres, or `null` when
     * the sender reported no fix at all or one worse than 100 m. This is the field the responder
     * side reads to size an uncertainty circle around a reported position instead of trusting a
     * point that may have been frozen minutes before it was sent -- see docs/PROTOCOL.md §2.
     */
    val senderAccuracyMetres: Double?
        get() = when (positionAccuracyClass) {
            1 -> 10.0
            2 -> 30.0
            3 -> 100.0
            else -> null
        }

    /** Age in whole minutes against a wall-clock instant. */
    fun ageMinutes(nowMillis: Long): Int {
        val nowMin = ((nowMillis - SETU_EPOCH_MILLIS) / MILLIS_PER_MINUTE).toInt()
        return (nowMin - epochMinute).coerceAtLeast(0)
    }

    /** The same message one hop further along, or null if its budget is spent. */
    fun relayed(): SosBeacon? {
        if (ttl <= 0 || hops >= 15) return null
        return copy(ttl = ttl - 1, hops = hops + 1)
    }

    companion object {
        /** Usable bytes in a BLE legacy advertisement after Flags and Service Data headers. */
        const val SIZE: Int = 24
    }
}
