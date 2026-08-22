package com.setu.mesh.sim

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.power.ProtocolTuning
import java.io.File
import kotlin.random.Random

object Sweeps {

    private fun ensureSweepsDir(): File {
        val dir = File("_private/sweeps")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Helper to create a flood scenario where non-gateway nodes have a fixed starting battery.
     */
    private fun buildFloodWithBattery(
        nodeCount: Int,
        rangeMetres: Double,
        lossRate: Double,
        batteryPercent: Int,
        random: Random,
        tuning: ProtocolTuning = ProtocolTuning.DEFAULT,
    ): World {
        val world = Scenario.build("flood", nodeCount, rangeMetres, lossRate, random, tuning)
        world.nodes.filter { !it.isGateway }.forEach { node ->
            // Re-initialize battery remaining mAh to reflect specified start percent
            val cap = BatteryModel.DEFAULT_CAPACITY_MAH
            val field = BatteryModel::class.java.getDeclaredField("remainingMilliampHours")
            field.isAccessible = true
            field.setDouble(node.battery, cap * (batteryPercent / 100.0))
        }
        return world
    }

    /**
     * SWEEP 1: Delivery ratio vs. starting battery percentage (10% to 100% in steps of 10)
     */
    fun runSweep1(masterSeed: Long) {
        val sweepsDir = ensureSweepsDir()
        val csvFile = File(sweepsDir, "sweep1_battery_delivery.csv")
        val lines = mutableListOf<String>()
        lines.add("battery_percent,mean_delivery_ratio,min_delivery_ratio,max_delivery_ratio")

        println("=== Running Sweep 1: Delivery Ratio vs Starting Battery ===")
        println("Battery% | Mean Deliv% | Min Deliv% | Max Deliv%")
        println("---------+-------------+------------+-----------")

        for (b in 10..100 step 10) {
            val ratios = mutableListOf<Double>()
            for (seedIdx in 0 until 5) {
                val seed = masterSeed + b * 100 + seedIdx
                val world = buildFloodWithBattery(100, 80.0, 0.05, b, Random(seed))
                val ticks = 20 * (60_000 / World.TICK_MILLIS).toInt() // 20 virtual minutes
                for (t in 0 until ticks) {
                    world.tick()
                }
                val metrics = Metrics.collect(world.nodes)
                ratios.add(metrics.deliveryRatio)
            }
            val mean = ratios.average()
            val minVal = ratios.minOrNull() ?: 0.0
            val maxVal = ratios.maxOrNull() ?: 0.0

            println("%8d | %11.1f%% | %10.1f%% | %9.1f%%".format(b, mean * 100, minVal * 100, maxVal * 100))
            lines.add("$b,%.4f,%.4f,%.4f".format(mean, minVal, maxVal))
        }

        csvFile.writeText(lines.joinToString("\n") + "\n")
        println("Saved raw CSV to: ${csvFile.path}\n")
    }

    /**
     * SWEEP 2: Energy Gate load-bearing check (Gated vs Ungated)
     */
    fun runSweep2(masterSeed: Long) {
        val sweepsDir = ensureSweepsDir()
        val csvFile = File(sweepsDir, "sweep2_energy_gate.csv")
        val lines = mutableListOf<String>()
        lines.add("configuration,seed,mesh_lifetime_minutes,threshold_reached,delivery_ratio")

        println("=== Running Sweep 2: Energy Gate Load-Bearing Check ===")
        println("Config    | Seed | Lifetime (min) | Reached? | Delivery Ratio")
        println("----------+------+----------------+----------+---------------")

        // 20 nodes rather than 100, and 5% starting battery rather than 10%, specifically so
        // that reaching 50% mesh death is achievable inside a tractable virtual duration --
        // the earlier version ran only 30 virtual minutes at 10% starting battery, which is
        // nowhere near enough time for BatteryModel's ~10 mA idle draw (400 mAh / 10 mA = 40h
        // for a 10%-full 4000 mAh battery) plus radio cost to deplete anyone, so both arms
        // reported "100% survival" and the sweep never actually tested its own hypothesis.
        val nodeCount = 20
        val startBatteryPercent = 5
        val maxMinutes = 8 * 60 // 8 virtual hours
        val maxTicks = maxMinutes * (60_000 / World.TICK_MILLIS).toInt()

        for (gated in listOf(true, false)) {
            val tuning = ProtocolTuning(energyGateOverride = if (gated) null else 1.0)
            val configName = if (gated) "Gated" else "Ungated (Force 1.0)"

            for (seedIdx in 0 until 5) {
                val seed = masterSeed + (if (gated) 1000 else 2000) + seedIdx
                val world = buildFloodWithBattery(nodeCount, 80.0, 0.05, startBatteryPercent, Random(seed), tuning)
                var lifetimeMinutes = maxMinutes.toDouble()
                var thresholdReached = false

                for (t in 0 until maxTicks) {
                    world.tick()
                    val deadCount = world.nodes.count { it.battery.isDead }
                    if (!thresholdReached && deadCount >= world.nodes.size / 2) {
                        lifetimeMinutes = (t * World.TICK_MILLIS) / 60_000.0
                        thresholdReached = true
                    }
                }

                val metrics = Metrics.collect(world.nodes)
                println(
                    "%-9s | %4d | %14.1f | %8s | %14.1f%%".format(
                        configName, seed, lifetimeMinutes, if (thresholdReached) "yes" else "NO", metrics.deliveryRatio * 100,
                    ),
                )
                lines.add("$configName,$seed,%.2f,%b,%.4f".format(lifetimeMinutes, thresholdReached, metrics.deliveryRatio))
            }
        }

        csvFile.writeText(lines.joinToString("\n") + "\n")
        println("Saved raw CSV to: ${csvFile.path}\n")
    }

    /**
     * SWEEP 3: Phase-locked rendezvous (flood) vs Independent phase (unsynced)
     */
    fun runSweep3(masterSeed: Long) {
        val sweepsDir = ensureSweepsDir()
        val csvFile = File(sweepsDir, "sweep3_rendezvous.csv")
        val lines = mutableListOf<String>()
        lines.add("configuration,seed,delivery_ratio,mean_discovery_latency_sec")

        println("=== Running Sweep 3: Phase-Locked Rendezvous vs Unsynced ===")
        println("Configuration           | Seed | Delivery Ratio | Mean Discovery Latency (s)")
        println("------------------------+------+----------------+---------------------------")

        for (scenarioName in listOf("flood", "unsynced")) {
            val configLabel = if (scenarioName == "flood") "wall-clock phase-locked" else "independent random phase"

            for (seedIdx in 0 until 5) {
                val seed = masterSeed + (if (scenarioName == "flood") 3000 else 4000) + seedIdx
                val world = Scenario.build(scenarioName, 100, 80.0, 0.05, Random(seed))
                val ticksPerMin = (60_000 / World.TICK_MILLIS).toInt()
                val totalTicks = 20 * ticksPerMin

                // Track first time any non-originator node hears an SOS message
                val originationTick = mutableMapOf<Int, Long>()
                val discoveryTick = mutableMapOf<Int, Long>()

                for (t in 0 until totalTicks) {
                    // Check for newly originated SOS
                    for (node in world.nodes) {
                        val snapshots = node.meshNode.snapshot.value
                        val ownSos = snapshots.ownSos
                        if (ownSos != null && ownSos.messageId.raw !in originationTick) {
                            originationTick[ownSos.messageId.raw] = t.toLong()
                        }
                    }

                    world.tick()

                    // Check for discovered SOS in other nodes' outboxes/seen sets
                    for (node in world.nodes) {
                        if (node.isGateway) continue
                        val beacons = node.link.currentBeacons
                        for (b in beacons) {
                            val decoded = BeaconCodec.decode(b)
                            if (decoded != null && decoded.type == MessageType.SOS) {
                                val msgId = decoded.messageId.raw
                                if (msgId in originationTick && msgId !in discoveryTick) {
                                    discoveryTick[msgId] = t.toLong()
                                }
                            }
                        }
                    }
                }

                val latencies = discoveryTick.map { (msgId, discT) ->
                    val origT = originationTick[msgId] ?: discT
                    (discT - origT) * (World.TICK_MILLIS / 1000.0)
                }
                val meanLatency = if (latencies.isNotEmpty()) latencies.average() else 0.0
                val metrics = Metrics.collect(world.nodes)

                println("%-23s | %4d | %14.1f%% | %25.2f".format(configLabel, seed, metrics.deliveryRatio * 100, meanLatency))
                lines.add("$configLabel,$seed,%.4f,%.2f".format(metrics.deliveryRatio, meanLatency))
            }
        }

        csvFile.writeText(lines.joinToString("\n") + "\n")
        println("Saved raw CSV to: ${csvFile.path}\n")
    }

    /**
     * SWEEP 4: Scanner election rotation (10% battery banding vs raw percentage)
     */
    fun runSweep4(masterSeed: Long) {
        val sweepsDir = ensureSweepsDir()
        val csvFile = File(sweepsDir, "sweep4_scanner_election.csv")
        val lines = mutableListOf<String>()
        lines.add("configuration,seed,p95_mah,median_mah,mah_spread_p95_minus_median")

        println("=== Running Sweep 4: Scanner Election Rotation (Banding vs Raw) ===")
        println("Configuration | Seed | p95 mAh | Median mAh | Spread (p95 - median)")
        println("--------------+------+---------+------------+----------------------")

        for (banding in listOf(true, false)) {
            // bandSizePercent = 1 reproduces "no banding": every distinct battery percentage
            // becomes its own band, so the highest-battery node in a neighbourhood always wins
            // the tiebreak deterministically instead of rotating with peers in the same band.
            val tuning = ProtocolTuning(scannerBandSizePercent = if (banding) 10 else 1)
            val configLabel = if (banding) "With 10% Banding" else "Without Banding"

            for (seedIdx in 0 until 5) {
                val seed = masterSeed + (if (banding) 5000 else 6000) + seedIdx
                val world = Scenario.build("flood", 100, 80.0, 0.05, Random(seed), tuning)
                val totalTicks = 30 * (60_000 / World.TICK_MILLIS).toInt() // 30 virtual minutes

                for (t in 0 until totalTicks) {
                    world.tick()
                }

                val metrics = Metrics.collect(world.nodes)
                val spread = metrics.energyMahP95 - metrics.energyMahMedian

                println(configLabel.padEnd(20) + " | " + "%4d | %7.2f | %10.2f | %20.2f".format(seed, metrics.energyMahP95, metrics.energyMahMedian, spread))
                lines.add("$configLabel,$seed," + "%.4f,%.4f,%.4f".format(metrics.energyMahP95, metrics.energyMahMedian, spread))
            }
        }

        csvFile.writeText(lines.joinToString("\n") + "\n")
        println("Saved raw CSV to: ${csvFile.path}\n")
    }

    fun runAll(masterSeed: Long) {
        runSweep1(masterSeed)
        runSweep2(masterSeed)
        runSweep3(masterSeed)
        runSweep4(masterSeed)
    }
}
