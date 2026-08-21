package com.setu.mesh.sim

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DeterminismTest {

    @Test
    fun `same seed produces identical metrics`() {
        val metrics1 = runSim(nodes = 50, scenario = "flood", minutes = 5, seed = 42)
        val metrics2 = runSim(nodes = 50, scenario = "flood", minutes = 5, seed = 42)

        assertEquals(metrics1.toJsonString(), metrics2.toJsonString())
    }

    @Test
    fun `different seeds produce different metrics`() {
        val metrics1 = runSim(nodes = 50, scenario = "flood", minutes = 5, seed = 42)
        val metrics2 = runSim(nodes = 50, scenario = "flood", minutes = 5, seed = 99)

        assertNotEquals(metrics1.toJsonString(), metrics2.toJsonString())
    }

    @Test
    fun `determinism across all scenarios`() {
        for (scenario in Scenario.NAMES) {
            val n = if (scenario == "dying-chain") 15 else 30
            val m1 = runSim(nodes = n, scenario = scenario, minutes = 3, seed = 7)
            val m2 = runSim(nodes = n, scenario = scenario, minutes = 3, seed = 7)
            assertEquals(m1.toJsonString(), m2.toJsonString(), "Non-deterministic result for scenario '$scenario'")
        }
    }

    private fun runSim(nodes: Int, scenario: String, minutes: Int, seed: Long): Metrics {
        val masterRandom = Random(seed)
        val world = Scenario.build(scenario, nodes, 80.0, 0.05, masterRandom)
        val totalTicks = (minutes * 60_000L) / World.TICK_MILLIS
        for (t in 0 until totalTicks) {
            world.tick()
        }
        return Metrics.collect(world.nodes)
    }
}
