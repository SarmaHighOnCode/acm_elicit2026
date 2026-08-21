package com.setu.mesh.sim

import com.setu.mesh.core.engine.MeshNode
import com.setu.mesh.core.link.LinkCapabilities
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SituationFlags
import com.setu.mesh.core.power.PowerGovernor
import kotlin.random.Random

/**
 * Named setups that demonstrate different protocol properties.
 *
 * Every scenario returns a [World] ready to run. Scenario functions take a seeded [Random]
 * so results are deterministic.
 */
object Scenario {

    val NAMES = listOf("flood", "drain", "partition", "dying-chain", "unsynced")

    fun build(
        name: String,
        nodeCount: Int,
        rangeMetres: Double,
        lossRate: Double,
        random: Random,
    ): World = when (name) {
        "flood" -> flood(nodeCount, rangeMetres, lossRate, random)
        "drain" -> drain(nodeCount, rangeMetres, lossRate, random)
        "partition" -> partition(nodeCount, rangeMetres, lossRate, random)
        "dying-chain" -> dyingChain(nodeCount, rangeMetres, lossRate, random)
        "unsynced" -> unsynced(nodeCount, rangeMetres, lossRate, random)
        else -> error("Unknown scenario: $name. Available: ${NAMES.joinToString()}")
    }

    /**
     * **flood**: N nodes in 4 clusters within range of each other, 1 gateway.
     * Batteries 10–100%. Demonstrates baseline delivery and the power ladder.
     */
    private fun flood(n: Int, range: Double, loss: Double, masterRandom: Random): World {
        val clock = VirtualClock()
        val nodes = mutableListOf<SimNode>()

        // 4 cluster centres arranged in a grid within range of each other
        val clusterCentres = listOf(
            GeoPoint.of(28.6100, 77.2100),
            GeoPoint.of(28.6106, 77.2100),   // ~66m north
            GeoPoint.of(28.6100, 77.2106),   // ~66m east
            GeoPoint.of(28.6106, 77.2106),   // ~66m NE
        )

        for (i in 0 until n) {
            val nodeRandom = Random(masterRandom.nextLong())
            val cluster = clusterCentres[i % clusterCentres.size]
            val pos = GeoPoint(
                cluster.latitudeE7 + nodeRandom.nextInt(-2700, 2700),
                cluster.longitudeE7 + nodeRandom.nextInt(-2700, 2700),
            )
            val battery = if (i == 0) 100 else nodeRandom.nextInt(10, 101)
            nodes += makeNode(i, clock, pos, battery, trustedClock = true, nodeRandom, i == 0)
        }

        originateSosMessages(nodes, n, clock.nowMillis(), masterRandom)
        return World(nodes, clock, range, loss, Random(masterRandom.nextLong()))
    }

    /**
     * **drain**: All nodes start below 15%. Demonstrates the energy gate and tier behaviour
     * when the entire mesh is starving.
     */
    private fun drain(n: Int, range: Double, loss: Double, masterRandom: Random): World {
        val clock = VirtualClock()
        val nodes = mutableListOf<SimNode>()

        val centre = GeoPoint.of(28.6100, 77.2100)
        for (i in 0 until n) {
            val nodeRandom = Random(masterRandom.nextLong())
            val pos = GeoPoint(
                centre.latitudeE7 + nodeRandom.nextInt(-3600, 3600),
                centre.longitudeE7 + nodeRandom.nextInt(-3600, 3600),
            )
            val battery = nodeRandom.nextInt(3, 16) // 3–15%
            nodes += makeNode(i, clock, pos, battery, true, nodeRandom, i == 0)
        }

        val now = clock.nowMillis()
        for (i in 1..(3.coerceAtMost(n - 1))) {
            nodes[i].meshNode.originateSos(
                SituationFlags(severity = Severity.CRITICAL),
                souls = 1,
                nowMillis = now,
            )
        }

        return World(nodes, clock, range, loss, Random(masterRandom.nextLong()))
    }

    /**
     * **partition**: Two clusters connected by a single low-battery bridge node.
     * Demonstrates partition tolerance and per-partition scanner election.
     */
    private fun partition(n: Int, range: Double, loss: Double, masterRandom: Random): World {
        val clock = VirtualClock()
        val nodes = mutableListOf<SimNode>()

        val half = n / 2
        val clusterA = GeoPoint.of(28.6100, 77.2100)
        val clusterB = GeoPoint.of(28.6114, 77.2100) // ~155m apart

        // Bridge node positioned between the two clusters
        val bridgePos = GeoPoint.of(28.6107, 77.2100) // ~77m from each
        val bridgeRandom = Random(masterRandom.nextLong())
        nodes += makeNode(0, clock, bridgePos, 20, true, bridgeRandom, isGateway = false)

        // Cluster A (gateway is node 1)
        for (i in 1..half) {
            val nodeRandom = Random(masterRandom.nextLong())
            val pos = GeoPoint(
                clusterA.latitudeE7 + nodeRandom.nextInt(-1800, 1800),
                clusterA.longitudeE7 + nodeRandom.nextInt(-1800, 1800),
            )
            nodes += makeNode(i, clock, pos, nodeRandom.nextInt(30, 101), true, nodeRandom, i == 1)
        }

        // Cluster B
        for (i in (half + 1) until n) {
            val nodeRandom = Random(masterRandom.nextLong())
            val pos = GeoPoint(
                clusterB.latitudeE7 + nodeRandom.nextInt(-1800, 1800),
                clusterB.longitudeE7 + nodeRandom.nextInt(-1800, 1800),
            )
            nodes += makeNode(i, clock, pos, nodeRandom.nextInt(30, 101), true, nodeRandom, false)
        }

        // SOS from cluster B that must cross the bridge
        val now = clock.nowMillis()
        if (half + 1 < n) {
            nodes[half + 1].meshNode.originateSos(
                SituationFlags(severity = Severity.CRITICAL),
                souls = 2,
                nowMillis = now,
            )
        }

        return World(nodes, clock, range, loss, Random(masterRandom.nextLong()))
    }

    /**
     * **dying-chain**: A line of relays that die in sequence.
     */
    private fun dyingChain(n: Int, range: Double, loss: Double, masterRandom: Random): World {
        val clock = VirtualClock()
        val nodes = mutableListOf<SimNode>()

        val count = n.coerceAtMost(20)
        val spacing = (range * 0.7 * 90).toInt() // ~70% of range in GeoPoint units

        for (i in 0 until count) {
            val nodeRandom = Random(masterRandom.nextLong())
            val pos = GeoPoint(
                286_100_000 + i * spacing,
                772_100_000,
            )
            val battery = if (i == 0) 100 else (100 - i * (90 / count)).coerceAtLeast(3)
            nodes += makeNode(i, clock, pos, battery, true, nodeRandom, i == 0)
        }

        val now = clock.nowMillis()
        nodes[count - 1].meshNode.originateSos(
            SituationFlags(severity = Severity.HIGH),
            souls = 1,
            nowMillis = now,
        )

        return World(nodes, clock, range, loss, Random(masterRandom.nextLong()))
    }

    /**
     * **unsynced**: The control case. Phase-locked rendezvous is destroyed by giving
     * each node a different clock offset. Delivery should collapse compared to flood.
     */
    private fun unsynced(n: Int, range: Double, loss: Double, masterRandom: Random): World {
        val nodes = mutableListOf<SimNode>()
        val perNodeClocks = mutableListOf<VirtualClock>()

        val clusterCentres = listOf(
            GeoPoint.of(28.6100, 77.2100),
            GeoPoint.of(28.6106, 77.2100),
            GeoPoint.of(28.6100, 77.2106),
            GeoPoint.of(28.6106, 77.2106),
        )

        for (i in 0 until n) {
            val nodeRandom = Random(masterRandom.nextLong())
            val cluster = clusterCentres[i % clusterCentres.size]
            val pos = GeoPoint(
                cluster.latitudeE7 + nodeRandom.nextInt(-2700, 2700),
                cluster.longitudeE7 + nodeRandom.nextInt(-2700, 2700),
            )
            val battery = if (i == 0) 100 else nodeRandom.nextInt(10, 101)

            // Random clock offset of up to 60 seconds to destroy phase alignment
            val clockOffset = nodeRandom.nextLong(0, 60_000)
            val clock = VirtualClock(VirtualClock.DEFAULT_START_MILLIS + clockOffset)
            perNodeClocks += clock

            val batteryModel = BatteryModel(battery)
            val link = SimLink()
            val host = SimHost(clock, batteryModel, Mobility.Static(pos), trustedClock = false, random = nodeRandom)
            val nodeId = NodeId(i and 0xFFFFFF)
            val meshNode = MeshNode(nodeId, link, host, PowerGovernor(), nodeRandom)
            nodes += SimNode(nodeId, host, link, meshNode, batteryModel, clock, i == 0)
        }

        originateSosMessages(nodes, n, nodes.first().clock.nowMillis(), masterRandom)
        return World(nodes, perNodeClocks, range, loss, Random(masterRandom.nextLong()))
    }

    // ---- helpers ----

    private fun makeNode(
        index: Int,
        clock: VirtualClock,
        position: GeoPoint,
        batteryPercent: Int,
        trustedClock: Boolean,
        random: Random,
        isGateway: Boolean,
    ): SimNode {
        val battery = BatteryModel(batteryPercent)
        val link = SimLink()
        val host = SimHost(clock, battery, Mobility.Static(position), trustedClock = trustedClock, random = random)
        val nodeId = NodeId(index and 0xFFFFFF)
        val meshNode = MeshNode(nodeId, link, host, PowerGovernor(), random)
        return SimNode(nodeId, host, link, meshNode, battery, clock, isGateway)
    }

    private fun originateSosMessages(nodes: List<SimNode>, n: Int, now: Long, random: Random) {
        val sosCount = (n / 10).coerceAtLeast(3).coerceAtMost(20).coerceAtMost(n - 1)
        for (i in 1..sosCount) {
            val severity = when (i % 4) {
                0 -> Severity.CRITICAL
                1 -> Severity.HIGH
                2 -> Severity.MODERATE
                else -> Severity.LOW
            }
            nodes[i].meshNode.originateSos(
                SituationFlags(severity = severity),
                souls = random.nextInt(1, 6),
                nowMillis = now,
            )
        }
    }
}
