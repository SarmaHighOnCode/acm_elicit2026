package com.setu.mesh.core.model

enum class BundleVerification {
    Verified,
    Unsigned,
    SignatureInvalid,
    UnknownKey
}

data class SosBundle(
    val beacon: SosBeacon,
    val accuracyM: Int,
    val altitude: Int,
    val nameHash: Int,
    val note: String,
    val hopChain: List<NodeId>,
    val signature: ByteArray? = null,
    val verification: BundleVerification = BundleVerification.Unsigned
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SosBundle

        if (beacon != other.beacon) return false
        if (accuracyM != other.accuracyM) return false
        if (altitude != other.altitude) return false
        if (nameHash != other.nameHash) return false
        if (note != other.note) return false
        if (hopChain != other.hopChain) return false
        if (signature != null) {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false
        if (verification != other.verification) return false

        return true
    }

    override fun hashCode(): Int {
        var result = beacon.hashCode()
        result = 31 * result + accuracyM
        result = 31 * result + altitude
        result = 31 * result + nameHash
        result = 31 * result + note.hashCode()
        result = 31 * result + hopChain.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + verification.hashCode()
        return result
    }
}
