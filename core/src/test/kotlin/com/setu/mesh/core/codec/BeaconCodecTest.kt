package com.setu.mesh.core.codec

import com.setu.mesh.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BeaconCodecTest {

    @Test
    fun `round-trip every field`() {
        val original = SosBeacon(
            type = MessageType.SOS,
            ttl = 5,
            hops = 2,
            messageId = MessageId(0x12345678),
            origin = NodeId(0xABCDEF),
            position = GeoPoint.of(37.7749, -122.4194),
            epochMinute = 123456,
            flags = SituationFlags(
                severity = Severity.HIGH,
                medicalNeed = true,
                trapped = false,
                waterRising = true,
                vulnerableOccupant = false,
                mobilityImpaired = true,
                hasRichBundle = false
            ),
            souls = 4,
            originBattery = 42
        )

        val encoded = BeaconCodec.encode(original)
        assertEquals(SosBeacon.SIZE, encoded.size)

        val decoded = BeaconCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `boundary values`() {
        val original = SosBeacon(
            type = MessageType.RECEIPT,
            ttl = 15, // max TTL
            hops = 15, // max hops
            messageId = MessageId(-1),
            origin = NodeId(0xFFFFFF),
            position = GeoPoint.of(180.0, -180.0), // boundary lat/lon
            epochMinute = 0xFFFFFF, // max epoch
            flags = SituationFlags(severity = Severity.CRITICAL, medicalNeed = true, trapped = true, waterRising = true, vulnerableOccupant = true, mobilityImpaired = true, hasRichBundle = true),
            souls = 255, // max souls
            originBattery = BATTERY_UNKNOWN // boundary battery
        )

        val encoded = BeaconCodec.encode(original)
        val decoded = BeaconCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(original, decoded)
        
        val zeroBounds = SosBeacon(
            type = MessageType.SOS,
            ttl = 0,
            hops = 0,
            messageId = MessageId(0),
            origin = NodeId(0),
            position = GeoPoint.of(0.0, 0.0),
            epochMinute = 0,
            flags = SituationFlags(),
            souls = 0,
            originBattery = 0
        )
        val encodedZero = BeaconCodec.encode(zeroBounds)
        val decodedZero = BeaconCodec.decode(encodedZero)
        assertEquals(zeroBounds, decodedZero)
    }

    @Test
    fun `corrupt any byte returns null`() {
        val beacon = SosBeacon(
            type = MessageType.SOS,
            ttl = 7,
            hops = 0,
            messageId = MessageId(1),
            origin = NodeId(1),
            position = GeoPoint.of(10.0, 20.0),
            epochMinute = 100,
            flags = SituationFlags(),
            souls = 1,
            originBattery = 100
        )
        val encoded = BeaconCodec.encode(beacon)

        for (i in encoded.indices) {
            val corrupted = encoded.copyOf()
            corrupted[i] = (corrupted[i].toInt() xor 0xFF).toByte()
            assertNull(BeaconCodec.decode(corrupted), "Should return null when byte $i is corrupted")
        }
    }

    @Test
    fun `wrong length returns null`() {
        assertNull(BeaconCodec.decode(ByteArray(23)))
        assertNull(BeaconCodec.decode(ByteArray(25)))
        assertNull(BeaconCodec.decode(ByteArray(0)))
    }

    @Test
    fun `every message type survives the round-trip`() {
        for (type in MessageType.entries) {
            val beacon = SosBeacon(
                type = type,
                ttl = 7,
                hops = 0,
                messageId = MessageId(1),
                origin = NodeId(1),
                position = GeoPoint.of(10.0, 20.0),
                epochMinute = 100,
                flags = SituationFlags(),
                souls = 1,
                originBattery = 100
            )
            val decoded = BeaconCodec.decode(BeaconCodec.encode(beacon))
            assertEquals(type, decoded?.type, "MessageType.$type did not round-trip")
        }
    }

    @Test
    fun `every situation flags bit combination survives the round-trip`() {
        // 6 independent boolean bits * 4 severity values = 256 combinations. Exhaustive,
        // not sampled, because a single dropped bit in the packed byte is exactly the kind
        // of bug that only shows up on one specific combination.
        for (severity in Severity.entries) {
            for (bits in 0 until 64) {
                val flags = SituationFlags(
                    severity = severity,
                    medicalNeed = (bits and 0b100000) != 0,
                    trapped = (bits and 0b010000) != 0,
                    waterRising = (bits and 0b001000) != 0,
                    vulnerableOccupant = (bits and 0b000100) != 0,
                    mobilityImpaired = (bits and 0b000010) != 0,
                    hasRichBundle = (bits and 0b000001) != 0,
                )
                val beacon = SosBeacon(
                    type = MessageType.SOS,
                    ttl = 7,
                    hops = 0,
                    messageId = MessageId(1),
                    origin = NodeId(1),
                    position = GeoPoint.of(0.0, 0.0),
                    epochMinute = 0,
                    flags = flags,
                    souls = 1,
                    originBattery = 100
                )
                val decoded = BeaconCodec.decode(BeaconCodec.encode(beacon))
                assertEquals(flags, decoded?.flags, "flags=$flags (severity=$severity, bits=$bits) did not round-trip")
            }
        }
    }

    @Test
    fun `decode never throws on random noise`() {
        // decode() parses bytes arriving over the air from unauthenticated strangers. It must
        // never throw, only ever return null, regardless of what garbage is on the wire.
        val random = kotlin.random.Random(20260822)
        repeat(20_000) {
            val length = random.nextInt(0, 64)
            val garbage = ByteArray(length) { random.nextInt(256).toByte() }
            assertDoesNotThrow({
                BeaconCodec.decode(garbage)
            }, "decode threw on: ${garbage.joinToString(",")}")
        }
    }

    @Test
    fun `wrong version returns null`() {
        val beacon = SosBeacon(
            type = MessageType.SOS,
            ttl = 7,
            hops = 0,
            messageId = MessageId(1),
            origin = NodeId(1),
            position = GeoPoint.of(10.0, 20.0),
            epochMinute = 100,
            flags = SituationFlags(),
            souls = 1,
            originBattery = 100
        )
        val encoded = BeaconCodec.encode(beacon)
        
        // Mess with the version bits (top 3 bits of byte 0)
        // Current version is 1. Let's change it to 2.
        val verType = encoded[0].toInt() and 0xFF
        val typeAndRes = verType and 0x1F
        val newVerType = (2 shl 5) or typeAndRes
        encoded[0] = newVerType.toByte()
        
        // Fix CRC since we changed a byte
        encoded[23] = Crc8.compute(encoded, 0, 23).toByte()

        assertNull(BeaconCodec.decode(encoded))
    }
}
