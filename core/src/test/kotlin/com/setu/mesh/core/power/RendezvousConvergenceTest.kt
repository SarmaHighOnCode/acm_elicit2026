package com.setu.mesh.core.power

import com.setu.mesh.core.model.SETU_EPOCH_MILLIS
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Regression guard for a hardware-only failure the simulator cannot reproduce.
 *
 * `MeshNode.run()` wakes on a grid whose phase is set by whenever the node started. The
 * rendezvous window is 1s wide inside a 60s epoch. If the grid never lands inside the window,
 * the node never scans -- permanently, while reporting itself healthy. A FLARE node (2s
 * interval) starting 1.2s into an epoch is a concrete instance: wakes at 1.2s, 3.2s, 5.2s ...
 * all of which miss [0s, 1s).
 *
 * `World.tick()` steps a fixed 250ms and therefore always samples the window, so `:sim` is
 * structurally blind to this. These tests model the wake grid directly instead.
 */
class RendezvousConvergenceTest {

    /**
     * Mirrors `MeshNode.nextSleepMillis`. Kept as a local copy on purpose: this test is about the
     * *scheduling arithmetic*, and duplicating it here means the test still fails loudly if
     * someone simplifies the production version back to a flat interval.
     */
    private fun nextSleep(governor: PowerGovernor, plan: RadioPlan, now: Long): Long {
        val base = plan.beaconIntervalMillis
        val sleep = if (plan.tier.scans && !plan.inRendezvousWindow) {
            minOf(base, governor.millisUntilNextWindow(now, plan.tier))
        } else {
            base
        }
        return sleep.coerceAtLeast(200L)
    }

    @Test
    fun `every starting phase eventually lands inside a rendezvous window`() {
        // Sweep starting phases across a full epoch, at a resolution fine enough to catch the
        // pathological offsets (the original bug reproduced at 1200ms, 1500ms, 1999ms).
        for (startPhase in 0L until 60_000L step 97L) {
            val governor = PowerGovernor()
            val selfId = com.setu.mesh.core.model.NodeId(1)
            var now = SETU_EPOCH_MILLIS + startPhase
            var landedInWindow = false

            // Ten epochs of wall time is far more than enough if the scheduler converges at all.
            val deadline = now + 10 * 60_000L
            while (now < deadline) {
                val plan = governor.plan(selfId, batteryPercent = 10, charging = false, neighbours = emptyList(), nowMillis = now)
                if (plan.scanThisEpoch && plan.inRendezvousWindow) {
                    landedInWindow = true
                    break
                }
                now += nextSleep(governor, plan, now)
            }

            assertTrue(
                landedInWindow,
                "A node starting at phase ${startPhase}ms never landed in a rendezvous window " +
                    "within 10 epochs -- it would be permanently deaf on hardware.",
            )
        }
    }

    @Test
    fun `a flat beacon-interval sleep does miss the window, proving this test has teeth`() {
        // The pre-fix behaviour, asserted explicitly so the regression guard above cannot be
        // mistaken for a test that would pass against any implementation.
        val governor = PowerGovernor()
        val selfId = com.setu.mesh.core.model.NodeId(1)
        var now = SETU_EPOCH_MILLIS + 1_200L // the phase that reproduced the original bug
        var landedInWindow = false

        val deadline = now + 10 * 60_000L
        while (now < deadline) {
            val plan = governor.plan(selfId, batteryPercent = 10, charging = false, neighbours = emptyList(), nowMillis = now)
            if (plan.scanThisEpoch && plan.inRendezvousWindow) {
                landedInWindow = true
                break
            }
            now += plan.beaconIntervalMillis.coerceAtLeast(200L) // flat sleep: the bug
        }

        assertTrue(
            !landedInWindow,
            "Expected the flat-interval sleep to miss the window from phase 1200ms. If this now " +
                "passes, the tier timings changed and the convergence test above may be vacuous.",
        )
    }
}
