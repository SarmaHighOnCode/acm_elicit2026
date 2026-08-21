package com.setu.mesh.core.power

import com.setu.mesh.core.model.NodeId
import kotlin.math.ceil
import kotlin.math.sqrt

/** What a node knows about a neighbour, learned purely from beacons it overheard. */
data class NeighbourEnergy(
    val id: NodeId,
    val batteryPercent: Int,
    val lastHeardMillis: Long,
)

/**
 * Decides who pays for scanning — **without sending a single message**.
 *
 * Scanning is the expensive half of the radio, so in any neighbourhood we want few listeners
 * and many shouters. The usual way to arrange that is to negotiate roles, but negotiation
 * traffic costs exactly the energy we are trying to save, and it fails precisely when the
 * network is degraded.
 *
 * SETU already puts each node's battery level in every beacon it broadcasts. That one byte is
 * enough: every node independently sees the same neighbourhood table, applies the same
 * deterministic ranking, and arrives at the same answer. Zero coordination messages, and it
 * degrades correctly under partition because each partition simply elects its own scanners.
 *
 * Quota is `ceil(sqrt(n))` of `n` known nodes. Square root, not a fixed fraction, because
 * coverage redundancy needs to grow much more slowly than density — in a crowded shelter that
 * means a handful of listeners for a hundred phones.
 */
object ScannerElection {

    fun scannerQuota(knownNodeCount: Int): Int =
        ceil(sqrt(knownNodeCount.coerceAtLeast(1).toDouble())).toInt().coerceAtLeast(1)

    /**
     * @param epoch current rendezvous epoch; mixed into the tiebreak so the duty rotates and no
     *   single phone is drained on behalf of everyone else.
     * @param neighbours nodes heard recently; the caller is responsible for expiring stale ones.
     */
    fun shouldScan(
        selfId: NodeId,
        selfBattery: Int,
        selfCharging: Boolean,
        neighbours: List<NeighbourEnergy>,
        epoch: Long,
    ): Boolean {
        // Alone, or the only one left: there is nobody to delegate listening to.
        if (neighbours.isEmpty()) return true

        val ranked = buildList {
            add(rankOf(selfId, selfBattery, selfCharging, epoch))
            neighbours.forEach { add(rankOf(it.id, it.batteryPercent, charging = false, epoch = epoch)) }
        }.sortedWith(compareByDescending<Rank> { it.band }.thenByDescending { it.tiebreak })

        val quota = scannerQuota(ranked.size)
        return ranked.take(quota).any { it.id == selfId }
    }

    @Volatile
    var disableBanding: Boolean = false

    private fun rankOf(id: NodeId, battery: Int, charging: Boolean, epoch: Long): Rank = Rank(
        id = id,
        // Coarse 10% bands rather than the raw percentage: without banding, the single
        // best-charged phone would scan every epoch forever. Banding lets everyone within
        // 10% of each other take turns via the epoch-mixed tiebreak.
        band = if (charging) BAND_CHARGING else if (disableBanding) battery else battery / 10,
        tiebreak = rotate(id.raw, epoch),
    )

    /** Deterministic, identical on every node, and different every epoch. */
    private fun rotate(nodeIdRaw: Int, epoch: Long): Int {
        var h = nodeIdRaw.toLong() * 0x9E3779B1L xor (epoch * 0xBF58476DL)
        h = h xor (h ushr 29)
        h *= 0x94D049BBL
        h = h xor (h ushr 32)
        return (h and 0x7FFFFFFF).toInt()
    }

    private data class Rank(val id: NodeId, val band: Int, val tiebreak: Int)

    /** Above any real battery band, so charging devices always outrank battery-powered ones. */
    private const val BAND_CHARGING = 100
}
