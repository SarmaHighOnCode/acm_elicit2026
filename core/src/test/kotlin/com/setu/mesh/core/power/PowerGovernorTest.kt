package com.setu.mesh.core.power

import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `attentive above the battery floor forces a scan outside the window and in a skipped epoch`() {
        val governor = PowerGovernor()
        val self = NodeId(1)

        // SETU_EPOCH_MILLIS lands exactly on an epoch boundary (epoch 28,401,120, even). One
        // epoch later is odd, which GOSSIP (15-30% battery) normally sits out entirely --
        // epochsBetweenScans = 2 -- and 30s into that epoch is well outside the 1s rendezvous
        // window. Confirm the tier really would skip this epoch, then confirm attentive
        // overrides both gates at once.
        val skippedEpochOutsideWindow = SETU_EPOCH_MILLIS + RendezvousScheduler.DEFAULT_EPOCH_MILLIS + 30_000L

        val notAttentive = governor.plan(
            self, batteryPercent = 20, charging = false, neighbours = emptyList(),
            nowMillis = skippedEpochOutsideWindow, attentive = false,
        )
        assertFalse(notAttentive.scanThisEpoch, "GOSSIP should sit out this epoch without attentive mode")

        val plan = governor.plan(
            self, batteryPercent = 20, charging = false, neighbours = emptyList(),
            nowMillis = skippedEpochOutsideWindow, attentive = true,
        )
        assertTrue(plan.scanThisEpoch)
        assertTrue(plan.inRendezvousWindow)
        assertEquals(PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS, plan.scanWindowMillis)
    }

    @Test
    fun `attentive at 12 percent and not charging is refused -- plan is unchanged`() {
        val governor = PowerGovernor()
        val self = NodeId(1)
        val now = SETU_EPOCH_MILLIS + 30_000L

        val withoutAttentive = governor.plan(self, batteryPercent = 12, charging = false, neighbours = emptyList(), nowMillis = now, attentive = false)
        val withAttentive = governor.plan(self, batteryPercent = 12, charging = false, neighbours = emptyList(), nowMillis = now, attentive = true)

        assertEquals(withoutAttentive, withAttentive)
    }

    @Test
    fun `attentive at 12 percent while charging is honoured`() {
        val governor = PowerGovernor()
        val self = NodeId(1)
        val now = SETU_EPOCH_MILLIS + 30_000L

        val plan = governor.plan(self, batteryPercent = 12, charging = true, neighbours = emptyList(), nowMillis = now, attentive = true)

        assertTrue(plan.scanThisEpoch)
        assertTrue(plan.inRendezvousWindow)
        assertEquals(PowerGovernor.ATTENTIVE_SCAN_WINDOW_MILLIS, plan.scanWindowMillis)
    }

    @Test
    fun `attentive in last gasp is refused`() {
        val governor = PowerGovernor()
        val self = NodeId(1)
        val insideWindow = SETU_EPOCH_MILLIS + 500L

        val plan = governor.plan(self, batteryPercent = 1, charging = false, neighbours = emptyList(), nowMillis = insideWindow, attentive = true)

        assertTrue(plan.lastGasp)
        assertFalse(plan.inRendezvousWindow)
    }
}
