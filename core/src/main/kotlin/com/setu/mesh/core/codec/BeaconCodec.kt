package com.setu.mesh.core.codec

import com.setu.mesh.core.model.BATTERY_UNKNOWN
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.MessageId
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.SETU_VERSION
import com.setu.mesh.core.model.SituationFlags
import com.setu.mesh.core.model.SosBeacon

/**
 * Wire codec for the 24-byte beacon. Big-endian throughout.
 *
 * ```
 *  off size field       notes
 *   0    1  verType     3b version | 3b type | 2b posClass (0 unknown, 1 ≤10m, 2 ≤30m, 3 ≤100m)
 *   1    1  ttlHops     4b ttl     | 4b hops
 *   2    4  msgId       dedup key
 *   6    3  originId    24-bit node id
 *   9    4  lat         int32, degrees * 1e7
 *  13    4  lon         int32, degrees * 1e7
 *  17    3  epochMin    minutes since 2024-01-01Z
 *  20    1  flags       2b severity | 6 situation bits
 *  21    1  souls
 *  22    1  battery     originator battery %
 *  23    1  crc8        over bytes 0..22
 * ```
 *
 * [decode] returns null rather than throwing for every malformed input. Bad frames are the
 * normal case on a crowded band, and a relay must drop them silently, not crash a rescue app.
 */
object BeaconCodec {

    fun encode(beacon: SosBeacon): ByteArray {
        val out = ByteArray(SosBeacon.SIZE)

        val posClass = beacon.positionAccuracyClass.coerceIn(0, 3)
        out[0] = (((SETU_VERSION and 0b111) shl 5) or ((beacon.type.wire and 0b111) shl 2) or posClass).toByte()
        out[1] = (((beacon.ttl.coerceIn(0, 15)) shl 4) or beacon.hops.coerceIn(0, 15)).toByte()

        putInt32(out, 2, beacon.messageId.raw)
        putUInt24(out, 6, beacon.origin.raw)
        putInt32(out, 9, beacon.position.latitudeE7)
        putInt32(out, 13, beacon.position.longitudeE7)
        putUInt24(out, 17, beacon.epochMinute)

        out[20] = beacon.flags.toWire().toByte()
        out[21] = beacon.souls.coerceIn(0, 255).toByte()
        out[22] = beacon.originBattery.coerceIn(0, BATTERY_UNKNOWN).toByte()
        out[23] = Crc8.compute(out, 0, 23).toByte()

        return out
    }

    /** Returns null for wrong length, failed CRC, or an unknown protocol version. */
    fun decode(bytes: ByteArray): SosBeacon? {
        if (bytes.size != SosBeacon.SIZE) return null
        if (Crc8.compute(bytes, 0, 23) != (bytes[23].toInt() and 0xFF)) return null

        val verType = bytes[0].toInt() and 0xFF
        val version = (verType ushr 5) and 0b111
        // Forward compatibility is a version bump away, but a v1 node must not guess at a
        // layout it does not know. Relaying unknown versions blind is a v2 concern.
        if (version != SETU_VERSION) return null

        val ttlHops = bytes[1].toInt() and 0xFF

        return SosBeacon(
            type = MessageType.fromWire((verType ushr 2) and 0b111),
            ttl = (ttlHops ushr 4) and 0x0F,
            hops = ttlHops and 0x0F,
            messageId = MessageId(getInt32(bytes, 2)),
            origin = NodeId(getUInt24(bytes, 6)),
            position = GeoPoint(getInt32(bytes, 9), getInt32(bytes, 13)),
            epochMinute = getUInt24(bytes, 17),
            flags = SituationFlags.fromWire(bytes[20].toInt() and 0xFF),
            souls = bytes[21].toInt() and 0xFF,
            originBattery = bytes[22].toInt() and 0xFF,
            // Bits [1:0] of byte 0. Backward compatible in both directions: a pre-this-change
            // build never sets these bits, so its beacons decode here as class 0 (unknown), which
            // is the honest answer since those builds never measured accuracy either. A beacon
            // from a new build decodes fine on an old build too -- the old decode only ever reads
            // (verType ushr 5) for version and (verType ushr 2) and 0b111 for type, so bits [1:0]
            // are simply never looked at there. Version stays 1 either way; nothing about the
            // layout above byte 0 moved.
            positionAccuracyClass = verType and 0b11,
        )
    }

    private fun putInt32(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value ushr 24).toByte()
        dst[at + 1] = (value ushr 16).toByte()
        dst[at + 2] = (value ushr 8).toByte()
        dst[at + 3] = value.toByte()
    }

    private fun getInt32(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 24) or
            ((src[at + 1].toInt() and 0xFF) shl 16) or
            ((src[at + 2].toInt() and 0xFF) shl 8) or
            (src[at + 3].toInt() and 0xFF)

    private fun putUInt24(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value ushr 16).toByte()
        dst[at + 1] = (value ushr 8).toByte()
        dst[at + 2] = value.toByte()
    }

    private fun getUInt24(src: ByteArray, at: Int): Int =
        ((src[at].toInt() and 0xFF) shl 16) or
            ((src[at + 1].toInt() and 0xFF) shl 8) or
            (src[at + 2].toInt() and 0xFF)
}
