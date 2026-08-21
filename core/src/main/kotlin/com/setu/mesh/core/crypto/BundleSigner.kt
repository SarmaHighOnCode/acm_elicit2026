package com.setu.mesh.core.crypto

import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature

object BundleSigner {
    
    fun sign(payload: ByteArray, privateKey: PrivateKey): ByteArray {
        val signablePayload = extractSignablePayload(payload)
        val signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(signablePayload)
        return signature.sign()
    }
    
    fun verify(payload: ByteArray, signatureBytes: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val signablePayload = extractSignablePayload(payload)
            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(publicKey)
            signature.update(signablePayload)
            signature.verify(signatureBytes)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Extracts the portion of the encoded bundle that is covered by the signature.
     * The signature covers everything EXCEPT the `ttl/hops` byte at index 1 of the beacon.
     * To achieve this without knowing the exact structure length here, we just zero out byte 1.
     * The payload is expected to be the encoded bytes *up to* the signature itself.
     */
    fun extractSignablePayload(payload: ByteArray): ByteArray {
        val signable = payload.copyOf()
        if (signable.size > 1) {
            signable[1] = 0 // Zero out the ttl/hops byte
        }
        if (signable.size > 23) {
            signable[23] = 0 // Zero out the crc8 byte because it changes when ttl/hops changes
        }
        return signable
    }
}
