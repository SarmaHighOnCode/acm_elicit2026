package com.setu.mesh.core.model

/**
 * SETU wire primitives.
 *
 * Everything in this file is deliberately small. The design constraint that drives the whole
 * protocol is that a complete, routable SOS must fit inside the 24 usable bytes of a BLE
 * *legacy* advertisement, because connectionless advertising is the only radio mode cheap
 * enough to run on a phone at 4% battery.
 *
 * 31 bytes of AD payload
 *   - 3 bytes  AD structure: Flags          (len + type + data)
 *   - 4 bytes  AD structure: Service Data   (len + type + 16-bit UUID)
 *   = 24 bytes usable                       <-- SosBeacon.SIZE
 */

/** Protocol version carried in the first 3 bits of every packet. */
const val SETU_VERSION: Int = 1

/**
 * SETU time origin: 2024-01-01T00:00:00Z.
 * Timestamps travel as *minutes* since this epoch in 24 bits, which buys ~31 years of range
 * for 3 bytes. Second-level precision is worthless in a disaster and costs us a byte.
 */
const val SETU_EPOCH_MILLIS: Long = 1_704_067_200_000L

/** Milliseconds in one minute, hoisted so the codec reads cleanly. */
const val MILLIS_PER_MINUTE: Long = 60_000L

/** Default hop budget for a freshly created SOS. Matches the practical ceiling of BLE floods. */
const val DEFAULT_TTL: Int = 7

/** Hard ceiling imposed by the 4-bit TTL field. */
const val MAX_TTL: Int = 15

/** Sentinel used when a node cannot read its own battery level. */
const val BATTERY_UNKNOWN: Int = 255

/**
 * A node identity truncated to 24 bits for the wire.
 *
 * 24 bits is not globally unique and we do not pretend otherwise: it is a *local* disambiguator.
 * With ~200 devices in one radio neighbourhood the birthday collision probability is under
 * 1.2e-3, and a collision degrades to a duplicate relay rather than a lost message because
 * dedup is keyed on [MessageId], not on the originator alone.
 */
@JvmInline
value class NodeId(val raw: Int) {
    init {
        require(raw in 0..0xFFFFFF) { "NodeId must fit in 24 bits, got $raw" }
    }

    /** Short human-readable form used in logs and in the responder UI, e.g. "N-3F9A12". */
    fun short(): String = "N-" + raw.toString(16).uppercase().padStart(6, '0')

    companion object {
        val UNKNOWN = NodeId(0)

        /** Derives a stable 24-bit id from a device's persistent installation UUID. */
        fun fromSeed(seed: String): NodeId {
            var h = -0x7ee3623b // FNV-1a 32-bit offset basis, sign-extended
            for (c in seed) {
                h = h xor c.code
                h *= 16777619
            }
            return NodeId(h and 0xFFFFFF)
        }
    }
}

/**
 * Deduplication key. 32 bits derived from (origin, sequence, epochMinute).
 *
 * Truncation is safe here for the same reason as [NodeId]: the consequence of a collision is a
 * dropped duplicate relay within one 10-minute window, not a lost SOS.
 */
@JvmInline
value class MessageId(val raw: Int) {
    fun short(): String = "M-" + raw.toUInt().toString(16).uppercase().padStart(8, '0')

    companion object {
        fun of(origin: NodeId, sequence: Int, epochMinute: Int): MessageId {
            var h = -0x7ee3623b
            for (v in intArrayOf(origin.raw, sequence, epochMinute)) {
                var x = v
                repeat(4) {
                    h = h xor (x and 0xFF)
                    h *= 16777619
                    x = x ushr 8
                }
            }
            return MessageId(h)
        }
    }
}

/** Packet kind. Three bits, all eight values allocated. */
enum class MessageType(val wire: Int) {
    /** A person needs help. The only type a victim device originates. */
    SOS(0),

    /** "Your SOS reached a gateway." Propagates back and lets nodes free the original. */
    RECEIPT(1),

    /** Anti-entropy summary of what this node is holding. */
    DIGEST(2),

    /** A responder has taken ownership of an SOS, so other teams do not duplicate effort. */
    RESPONDER_CLAIM(3),

    /** "I am safe" — cancels a prior SOS and reclaims buffer/energy across the mesh. */
    SAFE(4),

    /** Authority-to-mesh broadcast, e.g. "shelter open at X". Injected only at gateways. */
    ADVISORY(5),

    /** Custody accepted: the receiver now owns delivery, the sender may delete its copy. */
    CUSTODY_ACK(6),

    /** Reserved for a future version. Unknown types are relayed blind but never parsed. */
    RESERVED(7);

    companion object {
        private val BY_WIRE = entries.associateBy(MessageType::wire)
        fun fromWire(wire: Int): MessageType = BY_WIRE[wire] ?: RESERVED
    }
}

/**
 * Triage severity. Two bits, and it is the single most important input to the forwarding
 * policy: a CRITICAL bundle is relayed by nodes that would refuse to relay anything else.
 */
enum class Severity(val wire: Int) {
    /** Informational: safe, reporting position only. */
    LOW(0),

    /** Needs assistance but not time-critical. */
    MODERATE(1),

    /** Injury, trapped, or stranded with a deteriorating situation. */
    HIGH(2),

    /** Life-threatening within hours: water rising, severe bleeding, no air. */
    CRITICAL(3);

    companion object {
        fun fromWire(wire: Int): Severity = entries[wire and 0b11]
    }
}

/**
 * Situation bits packed into a single wire byte alongside [Severity].
 *
 * These are deliberately coarse. A panicking person taps three large buttons; anything more
 * granular would not be filled in truthfully and would cost bytes we do not have.
 */
data class SituationFlags(
    val severity: Severity = Severity.MODERATE,
    val medicalNeed: Boolean = false,
    val trapped: Boolean = false,
    val waterRising: Boolean = false,
    val vulnerableOccupant: Boolean = false,
    val mobilityImpaired: Boolean = false,
    val hasRichBundle: Boolean = false,
) {
    fun toWire(): Int {
        var b = (severity.wire and 0b11) shl 6
        if (medicalNeed) b = b or (1 shl 5)
        if (trapped) b = b or (1 shl 4)
        if (waterRising) b = b or (1 shl 3)
        if (vulnerableOccupant) b = b or (1 shl 2)
        if (mobilityImpaired) b = b or (1 shl 1)
        if (hasRichBundle) b = b or 1
        return b and 0xFF
    }

    companion object {
        fun fromWire(b: Int): SituationFlags = SituationFlags(
            severity = Severity.fromWire((b ushr 6) and 0b11),
            medicalNeed = (b and (1 shl 5)) != 0,
            trapped = (b and (1 shl 4)) != 0,
            waterRising = (b and (1 shl 3)) != 0,
            vulnerableOccupant = (b and (1 shl 2)) != 0,
            mobilityImpaired = (b and (1 shl 1)) != 0,
            hasRichBundle = (b and 1) != 0,
        )
    }
}

/**
 * A position at ~1cm nominal resolution (degrees * 1e7 in an int32).
 *
 * The precision is absurd for a phone GPS fix and that is fine: we are not paying for it. The
 * int32 encoding is the cheapest representation that covers the full coordinate range, and
 * real accuracy is reported separately in the rich bundle.
 */
data class GeoPoint(val latitudeE7: Int, val longitudeE7: Int) {
    val latitude: Double get() = latitudeE7 / 1e7
    val longitude: Double get() = longitudeE7 / 1e7

    companion object {
        val UNKNOWN = GeoPoint(Int.MIN_VALUE, Int.MIN_VALUE)

        fun of(latitude: Double, longitude: Double) = GeoPoint(
            latitudeE7 = Math.round(latitude * 1e7).toInt(),
            longitudeE7 = Math.round(longitude * 1e7).toInt(),
        )
    }
}
