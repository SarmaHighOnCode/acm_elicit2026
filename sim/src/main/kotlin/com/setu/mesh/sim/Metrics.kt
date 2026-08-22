package com.setu.mesh.sim

import com.setu.mesh.core.engine.NodeSnapshot
import com.setu.mesh.core.power.PowerTier

/**
 * End-of-run metrics extracted from the simulated nodes.
 *
 * No routing logic here — everything is read from [NodeSnapshot] and [MeshNode.ledger].
 */
data class Metrics(
    val totalNodes: Int,
    val sosOriginated: Int,
    val sosDelivered: Int,
    val deliveryRatio: Double,
    val medianHops: Double,
    val maxHops: Int,
    val energyMahMean: Double,
    val energyMahMedian: Double,
    val energyMahP95: Double,
    val energyMahMax: Double,
    val messagesCarriedMean: Double,
    val tierHistogram: Map<PowerTier, Int>,
    val deadAtEnd: Int,
) {
    fun toHumanString(): String = buildString {
        appendLine("╔══════════════════════════════════════════╗")
        appendLine("║         SETU Mesh Simulation Results     ║")
        appendLine("╠══════════════════════════════════════════╣")
        appendLine("║  Nodes:          %-5d                   ║".format(totalNodes))
        appendLine("║  SOS originated: %-5d                   ║".format(sosOriginated))
        appendLine("║  SOS delivered:  %-5d                   ║".format(sosDelivered))
        appendLine("║  Delivery ratio: %-6.1f%%                 ║".format(deliveryRatio * 100))
        appendLine("╠══════════════════════════════════════════╣")
        appendLine("║  Hops — median:  %-5.1f  max: %-3d          ║".format(medianHops, maxHops))
        appendLine("╠══════════════════════════════════════════╣")
        appendLine("║  Energy (mAh) — mean:   %-8.2f          ║".format(energyMahMean))
        appendLine("║                  median: %-8.2f          ║".format(energyMahMedian))
        appendLine("║                  p95:    %-8.2f          ║".format(energyMahP95))
        appendLine("║                  max:    %-8.2f          ║".format(energyMahMax))
        appendLine("║  Messages carried/node:  %-6.1f           ║".format(messagesCarriedMean))
        appendLine("╠══════════════════════════════════════════╣")
        appendLine("║  Tier distribution at end:               ║")
        for (tier in PowerTier.entries) {
            val count = tierHistogram[tier] ?: 0
            appendLine("║    %-8s %4d                          ║".format(tier.name, count))
        }
        appendLine("║  Dead at end:    %-5d                   ║".format(deadAtEnd))
        appendLine("╚══════════════════════════════════════════╝")
    }

    fun toJsonString(): String = buildString {
        appendLine("{")
        appendLine("""  "totalNodes": $totalNodes,""")
        appendLine("""  "sosOriginated": $sosOriginated,""")
        appendLine("""  "sosDelivered": $sosDelivered,""")
        appendLine("""  "deliveryRatio": $deliveryRatio,""")
        appendLine("""  "medianHops": $medianHops,""")
        appendLine("""  "maxHops": $maxHops,""")
        appendLine("""  "energy": {""")
        appendLine("""    "meanMah": $energyMahMean,""")
        appendLine("""    "medianMah": $energyMahMedian,""")
        appendLine("""    "p95Mah": $energyMahP95,""")
        appendLine("""    "maxMah": $energyMahMax""")
        appendLine("""  },""")
        appendLine("""  "messagesCarriedMean": $messagesCarriedMean,""")
        appendLine("""  "tierHistogram": {""")
        val tierEntries = PowerTier.entries.map { """"${it.name}": ${tierHistogram[it] ?: 0}""" }
        appendLine("    " + tierEntries.joinToString(", "))
        appendLine("""  },""")
        appendLine("""  "deadAtEnd": $deadAtEnd""")
        appendLine("}")
    }

    companion object {
        fun collect(nodes: List<SimNode>): Metrics {
            val snapshots = nodes.map { it.meshNode.snapshot.value }

            val sosOriginated = snapshots.count { it.ownSos != null || it.ownSosDelivered }
            val sosDelivered = snapshots.count { it.ownSosDelivered }

            val hopCounts = snapshots
                .filter { it.ownSos != null && it.ownSosMaxHops > 0 }
                .map { it.ownSosMaxHops }
                .sorted()

            val medianHops = if (hopCounts.isNotEmpty()) {
                if (hopCounts.size % 2 == 0) {
                    (hopCounts[hopCounts.size / 2 - 1] + hopCounts[hopCounts.size / 2]) / 2.0
                } else {
                    hopCounts[hopCounts.size / 2].toDouble()
                }
            } else 0.0

            val energies = nodes.map { it.meshNode.ledger.totalMilliampHours }.sorted()
            val energyMean = if (energies.isNotEmpty()) energies.sum() / energies.size else 0.0
            val energyMedian = median(energies)
            val energyP95 = percentile(energies, 95)
            val energyMax = energies.lastOrNull() ?: 0.0

            val carried = snapshots.map { it.beaconsRelayed.toDouble() }
            val carriedMean = if (carried.isNotEmpty()) carried.sum() / carried.size else 0.0

            val tierHist = snapshots.groupingBy { it.tier }.eachCount()
            val dead = nodes.count { it.battery.isDead }

            return Metrics(
                totalNodes = nodes.size,
                sosOriginated = sosOriginated,
                sosDelivered = sosDelivered,
                deliveryRatio = if (sosOriginated > 0) sosDelivered.toDouble() / sosOriginated else 0.0,
                medianHops = medianHops,
                maxHops = hopCounts.maxOrNull() ?: 0,
                energyMahMean = energyMean,
                energyMahMedian = energyMedian,
                energyMahP95 = energyP95,
                energyMahMax = energyMax,
                messagesCarriedMean = carriedMean,
                tierHistogram = tierHist,
                deadAtEnd = dead,
            )
        }

        private fun median(sorted: List<Double>): Double {
            if (sorted.isEmpty()) return 0.0
            return if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            } else {
                sorted[sorted.size / 2]
            }
        }

        private fun percentile(sorted: List<Double>, pct: Int): Double {
            if (sorted.isEmpty()) return 0.0
            val idx = ((pct / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }
    }
}
