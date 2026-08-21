package com.setu.mesh.core.codec

import com.setu.mesh.core.crypto.KeyStore
import com.setu.mesh.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.PublicKey

class BundleCodecTest {

    private val dummyKeyStore = object : KeyStore {
        override fun getPublicKey(originId: NodeId): PublicKey? = null
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

    private fun createTestBundle(note: String = "Test Note", hopChain: List<NodeId> = emptyList()): SosBundle {
        return SosBundle(
            beacon = createTestBeacon(),
            accuracyM = 15,
            altitude = 900,
            nameHash = 0x12345678,
            note = note,
            hopChain = hopChain
        )
    }

    @Test
    fun `round-trip every field`() {
        val bundle = createTestBundle(
            note = "Hello world",
            hopChain = listOf(NodeId(1), NodeId(2), NodeId(3))
        )
        val encoded = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(encoded, dummyKeyStore)
        
        assertNotNull(decoded)
        assertEquals(bundle, decoded)
    }

    @Test
    fun `empty note round-trips correctly`() {
        val bundle = createTestBundle(note = "")
        val encoded = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(encoded, dummyKeyStore)
        
        assertNotNull(decoded)
        assertEquals("", decoded?.note)
        assertEquals(bundle, decoded)
    }

    @Test
    fun `full 96-byte note round-trips correctly`() {
        val note = "a".repeat(96)
        val bundle = createTestBundle(note = note)
        val encoded = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(encoded, dummyKeyStore)
        
        assertNotNull(decoded)
        assertEquals(note, decoded?.note)
    }

    @Test
    fun `multi-byte UTF-8 Devanagari truncated at byte limit without splitting a codepoint`() {
        // "नमस्ते" (Namaste) in Devanagari. Let's create a long string.
        // न (3 bytes) म (3 bytes) स (3 bytes) ् (3 bytes) त (3 bytes) े (3 bytes) -> each is 3 bytes (mostly)
        val longNote = "नमस्ते ".repeat(20) // ~300 bytes
        
        val bundle = createTestBundle(note = longNote)
        val encoded = BundleCodec.encode(bundle)
        val decoded = BundleCodec.decode(encoded, dummyKeyStore)
        
        assertNotNull(decoded)
        // Ensure the note is well-formed UTF-8 and exactly fits or is less than 96 bytes
        val decodedNoteBytes = decoded!!.note.toByteArray(Charsets.UTF_8)
        assertTrue(decodedNoteBytes.size <= 96)
        
        // Ensure no replacement characters (which happens if a codepoint is split)
        assertFalse(decoded.note.contains("\uFFFD"))
        
        // Ensure the string ends on a valid character boundary
        // If it was split, the last character would be malformed.
        // We can check this by encoding it back.
        assertEquals(decoded.note, String(decodedNoteBytes, Charsets.UTF_8))
    }

    @Test
    fun `decode never throws on random fuzz`() {
        val random = java.util.Random(42)
        for (i in 0..1000) {
            val bytes = ByteArray(random.nextInt(250))
            random.nextBytes(bytes)
            assertDoesNotThrow {
                BundleCodec.decode(bytes, dummyKeyStore)
            }
        }
    }

    @Test
    fun `encoded size is under 244 for maximal input`() {
        val bundle = createTestBundle(
            note = "a".repeat(96),
            hopChain = List(8) { NodeId(it) }
        ).copy(signature = ByteArray(64))
        
        val encoded = BundleCodec.encode(bundle)
        assertTrue(encoded.size <= 244, "Encoded size is ${encoded.size}")
    }
}
