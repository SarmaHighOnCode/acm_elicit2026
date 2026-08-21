package com.setu.mesh.core.power

import com.setu.mesh.core.model.MILLIS_PER_MINUTE
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

class RendezvousSchedulerTest {

    @Test
    fun `two nodes with different tiers land in the same window`() {
        val scheduler = RendezvousScheduler()
        
        // BRIDGE scans every epoch. FLARE scans every 4 epochs.
        // Let's check epoch 4 (which is a multiple of both 1 and 4).
        val epoch: Long = 4
        val nowMillis = SETU_EPOCH_MILLIS + (epoch * RendezvousScheduler.DEFAULT_EPOCH_MILLIS) + 500L
        
        assertTrue(scheduler.isInWindow(nowMillis))
        assertTrue(scheduler.scansInEpoch(epoch, PowerTier.BRIDGE))
        assertTrue(scheduler.scansInEpoch(epoch, PowerTier.FLARE))
        
        // At epoch 5, BRIDGE scans, FLARE does not.
        val epoch5: Long = 5
        assertTrue(scheduler.scansInEpoch(epoch5, PowerTier.BRIDGE))
        assertFalse(scheduler.scansInEpoch(epoch5, PowerTier.FLARE))
    }

    @Test
    fun `scansInEpoch phase alignment`() {
        val scheduler = RendezvousScheduler()
        
        assertTrue(scheduler.scansInEpoch(0, PowerTier.BRIDGE))
        assertTrue(scheduler.scansInEpoch(1, PowerTier.BRIDGE))
        
        assertTrue(scheduler.scansInEpoch(0, PowerTier.GOSSIP)) // every 2
        assertFalse(scheduler.scansInEpoch(1, PowerTier.GOSSIP))
        assertTrue(scheduler.scansInEpoch(2, PowerTier.GOSSIP))
        
        assertTrue(scheduler.scansInEpoch(0, PowerTier.FLARE)) // every 4
        assertFalse(scheduler.scansInEpoch(1, PowerTier.FLARE))
        assertFalse(scheduler.scansInEpoch(2, PowerTier.FLARE))
        assertFalse(scheduler.scansInEpoch(3, PowerTier.FLARE))
        assertTrue(scheduler.scansInEpoch(4, PowerTier.FLARE))
    }

    @Test
    fun `EMBER never scans`() {
        val scheduler = RendezvousScheduler()

        assertFalse(scheduler.scansInEpoch(0, PowerTier.EMBER))
        assertFalse(scheduler.scansInEpoch(1, PowerTier.EMBER))
        assertFalse(scheduler.scansInEpoch(1000, PowerTier.EMBER))

        for (offset in 0..120000L step 1000L) {
            val nowMillis = SETU_EPOCH_MILLIS + offset
            assertEquals(
                Long.MAX_VALUE,
                scheduler.millisUntilNextWindow(nowMillis, PowerTier.EMBER),
                "EMBER must never report a next scan window",
            )
        }
    }

    @Test
    fun `millisUntilNextWindow never negative`() {
        val scheduler = RendezvousScheduler()
        
        for (offset in 0..120000L step 1000L) {
            val nowMillis = SETU_EPOCH_MILLIS + offset
            val waitBridge = scheduler.millisUntilNextWindow(nowMillis, PowerTier.BRIDGE)
            assertTrue(waitBridge >= 0, "Wait time should not be negative, got $waitBridge")
            
            val waitFlare = scheduler.millisUntilNextWindow(nowMillis, PowerTier.FLARE)
            assertTrue(waitFlare >= 0, "Wait time should not be negative, got $waitFlare")
        }
    }

    @Test
    fun `drift correction converges and is damped`() {
        val scheduler = RendezvousScheduler()
        
        val localTime = SETU_EPOCH_MILLIS + (10 * MILLIS_PER_MINUTE)
        
        // Peer is 4 minutes ahead
        val peerEpochMinute = 14 
        
        scheduler.applyPeerObservation(peerEpochMinute, localTime, trustedLocalClock = false)
        
        // Drift is damped to 1/4 of the error. 
        // Error is +4 minutes = 240,000 ms.
        // Correction should be 60,000 ms.
        assertEquals(60_000L, scheduler.driftCorrectionMillis)
        
        // A trusted clock ignores the peer
        val schedulerTrusted = RendezvousScheduler()
        schedulerTrusted.applyPeerObservation(peerEpochMinute, localTime, trustedLocalClock = true)
        assertEquals(0L, schedulerTrusted.driftCorrectionMillis)
    }

    @Test
    fun `drift correction converges toward the peer over repeated observations`() {
        // A single applyPeerObservation call only proves the first quarter-step. The actual
        // claim in docs/POWER.md is that repeated observations pull a wrong clock in gradually
        // rather than snapping it -- that only shows up across a sequence of calls.
        val scheduler = RendezvousScheduler()
        val localTime = SETU_EPOCH_MILLIS + (10 * MILLIS_PER_MINUTE)
        val peerEpochMinute = 14 // consistently 4 minutes ahead of local time
        val trueOffsetMillis = 4 * MILLIS_PER_MINUTE

        val corrections = mutableListOf<Long>()
        repeat(20) {
            scheduler.applyPeerObservation(peerEpochMinute, localTime, trustedLocalClock = false)
            corrections.add(scheduler.driftCorrectionMillis)
        }

        // Damped: the very first step must not jump straight to the full error.
        assertTrue(
            corrections.first() < trueOffsetMillis,
            "first correction should be a damped fraction of the error, not a full jump: $corrections",
        )

        // Monotonic and never overshoots the true offset -- it should approach the peer's
        // clock, not oscillate around it or run past it.
        for (i in 1 until corrections.size) {
            assertTrue(
                corrections[i] >= corrections[i - 1],
                "correction should move monotonically toward the peer, not backwards: $corrections",
            )
            assertTrue(
                corrections[i] <= trueOffsetMillis,
                "correction should never overshoot the true 4-minute offset: $corrections",
            )
        }

        // It reaches a stable fixed point -- once local time matches the peer's reported
        // minute, applyPeerObservation becomes a no-op (see the deltaMinutes == 0 early return).
        assertEquals(trueOffsetMillis, corrections.last())
        scheduler.applyPeerObservation(peerEpochMinute, localTime, trustedLocalClock = false)
        assertEquals(trueOffsetMillis, scheduler.driftCorrectionMillis, "correction should hold once converged")
    }
}
