package com.setu.mesh.core.crypto

import com.setu.mesh.core.codec.BundleCodec
import com.setu.mesh.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.PublicKey

class BundleSignerTest {

    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val privateKey = keyPair.private
    private val publicKey = keyPair.public

    private val testKeyStore = object : KeyStore {
        override fun getPublicKey(originId: NodeId): PublicKey? {
            return if (originId.raw == 54321) publicKey else null
        }
    }

    private fun createTestBeacon(): SosBeacon {
        return SosBeacon(
            type = MessageType.SOS,
            ttl = 7,
            hops = 0,
            messageId = MessageId(12345),
            origin = NodeId(54321),
            position = GeoPoint((12.9716 * 1e7).toInt(), (77.5946 * 1e7).toInt()),
            epochMinute = 1000,
            flags = SituationFlags(severity = Severity.HIGH),
            souls = 3,
            originBattery = 85
        )
    }

    private fun createTestBundle(): SosBundle {
        return SosBundle(
            beacon = createTestBeacon(),
            accuracyM = 15,
            altitude = 900,
            nameHash = 0x12345678,
            note = "Test Note",
            hopChain = listOf(NodeId(1), NodeId(2))
        )
    }

    @Test
    fun `bundle with valid signature decodes as Verified`() {
        val bundle = createTestBundle()
        val signableBytes = BundleCodec.encodeSignablePayload(bundle)
        val signatureBytes = BundleSigner.sign(signableBytes, privateKey)
        
        val signedBundle = bundle.copy(signature = signatureBytes)
        val encoded = BundleCodec.encode(signedBundle)
        
        val decoded = BundleCodec.decode(encoded, testKeyStore)
        assertNotNull(decoded)
        assertEquals(BundleVerification.Verified, decoded?.verification)
    }

    @Test
    fun `bundle with no signature decodes as Unsigned`() {
        val bundle = createTestBundle()
        val encoded = BundleCodec.encode(bundle)
        
        val decoded = BundleCodec.decode(encoded, testKeyStore)
        assertNotNull(decoded)
        assertEquals(BundleVerification.Unsigned, decoded?.verification)
    }

    @Test
    fun `bundle with tampered byte decodes as SignatureInvalid`() {
        val bundle = createTestBundle()
        val signableBytes = BundleCodec.encodeSignablePayload(bundle)
        val signatureBytes = BundleSigner.sign(signableBytes, privateKey)
        
        val signedBundle = bundle.copy(signature = signatureBytes)
        val encoded = BundleCodec.encode(signedBundle)
        
        // Tamper with the note field
        encoded[50] = (encoded[50] + 1).toByte()
        
        val decoded = BundleCodec.decode(encoded, testKeyStore)
        assertNotNull(decoded)
        assertEquals(BundleVerification.SignatureInvalid, decoded?.verification)
    }

    @Test
    fun `mutating ttl hops preserves Verified state`() {
        val bundle = createTestBundle()
        val signableBytes = BundleCodec.encodeSignablePayload(bundle)
        val signatureBytes = BundleSigner.sign(signableBytes, privateKey)
        
        val signedBundle = bundle.copy(signature = signatureBytes)
        val encoded = BundleCodec.encode(signedBundle)
        
        // Mutate the ttl/hops byte (index 1 of the beacon)
        encoded[1] = 0x11 // ttl = 1, hops = 1
        
        // Recompute and update the CRC8 (byte index 23) because relays always update it
        encoded[23] = com.setu.mesh.core.codec.Crc8.compute(encoded, 0, 23).toByte()
        
        val decoded = BundleCodec.decode(encoded, testKeyStore)
        assertNotNull(decoded)
        assertEquals(BundleVerification.Verified, decoded?.verification)
    }

    @Test
    fun `bundle with unknown origin id decodes as UnknownKey`() {
        val bundle = createTestBundle().copy(
            beacon = createTestBeacon().copy(origin = NodeId(99999))
        )
        val signableBytes = BundleCodec.encodeSignablePayload(bundle)
        val signatureBytes = BundleSigner.sign(signableBytes, privateKey)
        
        val signedBundle = bundle.copy(signature = signatureBytes)
        val encoded = BundleCodec.encode(signedBundle)
        
        val decoded = BundleCodec.decode(encoded, testKeyStore)
        assertNotNull(decoded)
        assertEquals(BundleVerification.UnknownKey, decoded?.verification)
    }
}
