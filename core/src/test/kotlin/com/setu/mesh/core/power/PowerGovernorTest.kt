package com.setu.mesh.core.power

import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PowerGovernorTest {

    @Test
    fun `plan reports inRendezvousWindow true inside the window and false outside it`() {
        val governor = PowerGovernor()
        val self = NodeId(1)

        // Inside the first second of an epoch: window is open.
        val insideWindow = SETU_EPOCH_MILLIS + 500L
        val planInside = governor.plan(self, batteryPercent = 100, charging = false, neighbours = emptyList(), nowMillis = insideWindow)
        assertTrue(planInside.inRendezvousWindow, "expected inRendezvousWindow at +500ms into the epoch")

        // Well past the window (epoch is 60s, window is 1s): window is closed.
        val outsideWindow = SETU_EPOCH_MILLIS + 30_000L
        val planOutside = governor.plan(self, batteryPercent = 100, charging = false, neighbours = emptyList(), nowMillis = outsideWindow)
        assertFalse(planOutside.inRendezvousWindow, "expected window closed at +30s into the epoch")
    }

    @Test
    fun `last-gasp plan never reports inRendezvousWindow`() {
        // Below LAST_GASP_BATTERY_PERCENT the node bursts continuously rather than waiting for
        // a rendezvous window -- inRendezvousWindow must be false on that path regardless of
        // the actual wall-clock position, since the node isn't scanning at all in last-gasp.
        val governor = PowerGovernor()
        val self = NodeId(1)

        val insideWindow = SETU_EPOCH_MILLIS + 500L
        val plan = governor.plan(self, batteryPercent = 1, charging = false, neighbours = emptyList(), nowMillis = insideWindow)

        assertTrue(plan.lastGasp)
        assertFalse(plan.inRendezvousWindow)
    }
}
