package com.setu.mesh.core.codec

import com.setu.mesh.core.crypto.BundleSigner
import com.setu.mesh.core.crypto.KeyStore
import com.setu.mesh.core.model.BundleVerification
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.SosBundle
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object BundleCodec {

    private const val MAX_NOTE_BYTES = 96
    private const val MAX_HOP_COUNT = 8

    fun encode(bundle: SosBundle): ByteArray {
        val signableBytes = encodeSignablePayload(bundle)
        if (bundle.signature == null) {
            return signableBytes
        }
        val buffer = ByteBuffer.allocate(signableBytes.size + 64)
        buffer.put(signableBytes)
        buffer.put(bundle.signature)
        return buffer.array()
    }

    /**
     * Encodes the bundle *without* the signature field, which is exactly the byte array
     * that the signature is calculated over.
     */
    fun encodeSignablePayload(bundle: SosBundle): ByteArray {
        val beaconBytes = BeaconCodec.encode(bundle.beacon)
        
        // Truncate note safely (UTF-8)
        val noteBytes = truncateUtf8(bundle.note, MAX_NOTE_BYTES)
        
        val hopCount = bundle.hopChain.size.coerceAtMost(MAX_HOP_COUNT)
        
        val bufferSize = 24 + 1 + 2 + 4 + 1 + noteBytes.size + 1 + (hopCount * 3)
        val buffer = ByteBuffer.allocate(bufferSize)
        
        buffer.put(beaconBytes)
        buffer.put(bundle.accuracyM.toByte())
        buffer.putShort(bundle.altitude.toShort())
        buffer.putInt(bundle.nameHash)
        
        buffer.put(noteBytes.size.toByte())
        buffer.put(noteBytes)
        
        buffer.put(hopCount.toByte())
        for (i in 0 until hopCount) {
            val id = bundle.hopChain[i].raw
            buffer.put((id shr 16).toByte())
            buffer.put((id shr 8).toByte())
            buffer.put(id.toByte())
        }
        
        return buffer.array()
    }

    fun decode(bytes: ByteArray, keyStore: KeyStore): SosBundle? {
        try {
            val buffer = ByteBuffer.wrap(bytes)
            
            // Beacon is 24 bytes
            val beaconBytes = ByteArray(24)
            buffer.get(beaconBytes)
            val beacon = BeaconCodec.decode(beaconBytes) ?: return null
            
            val accuracyM = buffer.get().toInt() and 0xFF
            val altitude = buffer.short.toInt()
            val nameHash = buffer.int
            
            val noteLength = buffer.get().toInt() and 0xFF
            if (noteLength > MAX_NOTE_BYTES) return null
            val noteBytes = ByteArray(noteLength)
            buffer.get(noteBytes)
            val note = String(noteBytes, StandardCharsets.UTF_8)
            
            val hopCount = buffer.get().toInt() and 0xFF
            if (hopCount > MAX_HOP_COUNT) return null
            val hopChain = mutableListOf<NodeId>()
            for (i in 0 until hopCount) {
                val b1 = buffer.get().toInt() and 0xFF
                val b2 = buffer.get().toInt() and 0xFF
                val b3 = buffer.get().toInt() and 0xFF
                val rawId = (b1 shl 16) or (b2 shl 8) or b3
                hopChain.add(NodeId(rawId))
            }
            
            var signature: ByteArray? = null
            var verification = BundleVerification.Unsigned
            
            if (buffer.remaining() >= 64) {
                signature = ByteArray(64)
                buffer.get(signature)
                
                val publicKey = keyStore.getPublicKey(beacon.origin)
                if (publicKey == null) {
                    verification = BundleVerification.UnknownKey
                } else {
                    // Extract signable payload from the original byte array
                    val signableLength = bytes.size - buffer.remaining() - 64
                    val signableBytes = bytes.copyOfRange(0, signableLength)
                    
                    if (BundleSigner.verify(signableBytes, signature, publicKey)) {
                        verification = BundleVerification.Verified
                    } else {
                        verification = BundleVerification.SignatureInvalid
                    }
                }
            }
            
            return SosBundle(
                beacon = beacon,
                accuracyM = accuracyM,
                altitude = altitude,
                nameHash = nameHash,
                note = note,
                hopChain = hopChain,
                signature = signature,
                verification = verification
            )
        } catch (e: Exception) {
            return null // Underflow or other corruption
        }
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        
        var length = maxBytes
        // If the first excluded byte is a continuation byte, the character was split.
        if ((bytes[length].toInt() and 0xC0) == 0x80) {
            // Drop included continuation bytes
            while (length > 0 && (bytes[length - 1].toInt() and 0xC0) == 0x80) {
                length--
            }
            // Drop the leading byte of the split character
            if (length > 0) {
                length--
            }
        }
        return bytes.copyOf(length)
    }
}
