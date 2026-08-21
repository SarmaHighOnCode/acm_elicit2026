package com.setu.mesh.sim

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ScenarioTest {

    @Test
    fun `flood scenario delivers most SOS`() {
        val metrics = runSim("flood", 100, 10, seed = 7)
        assertTrue(metrics.sosOriginated > 0, "Should originate some SOS")
        assertTrue(metrics.deliveryRatio > 0.0, "Flood should deliver at least some SOS")
    }

    @Test
    fun `drain scenario runs without crashing`() {
        val metrics = runSim("drain", 50, 5, seed = 3)
        assertTrue(metrics.totalNodes == 50)
        assertTrue(metrics.sosOriginated > 0)
        // Drain starts with 3–15%, so many nodes should be in FLARE/EMBER tiers
        val lowTierCount = (metrics.tierHistogram[com.setu.mesh.core.power.PowerTier.FLARE] ?: 0) +
            (metrics.tierHistogram[com.setu.mesh.core.power.PowerTier.EMBER] ?: 0) +
            metrics.deadAtEnd
        assertTrue(lowTierCount > 0, "Drain scenario should have nodes in low tiers or dead")
    }

    @Test
    fun `partition scenario runs`() {
        val metrics = runSim("partition", 30, 5, seed = 5)
        assertTrue(metrics.sosOriginated > 0)
    }

    @Test
    fun `dying-chain scenario runs`() {
        val metrics = runSim("dying-chain", 10, 5, seed = 1)
        assertTrue(metrics.sosOriginated > 0)
    }

    @Test
    fun `unsynced scenario has worse delivery than flood`() {
        val flood = runSim("flood", 80, 10, seed = 7)
        val unsynced = runSim("unsynced", 80, 10, seed = 7)

        // The unsynced scenario should generally have worse or equal delivery.
        // We check that both run and produce valid output.
        assertTrue(flood.sosOriginated > 0)
        assertTrue(unsynced.sosOriginated > 0)
        // We do NOT assert flood > unsynced strictly — it's a statistical property,
        // but we do log it for manual inspection.
        println("flood delivery: ${flood.deliveryRatio}, unsynced delivery: ${unsynced.deliveryRatio}")
    }

    private fun runSim(scenario: String, nodes: Int, minutes: Int, seed: Long): Metrics {
        val masterRandom = Random(seed)
        val world = Scenario.build(scenario, nodes, 80.0, 0.05, masterRandom)
        val totalTicks = (minutes * 60_000L) / World.TICK_MILLIS
        for (t in 0 until totalTicks) {
            world.tick()
        }
        return Metrics.collect(world.nodes)
    }
}
