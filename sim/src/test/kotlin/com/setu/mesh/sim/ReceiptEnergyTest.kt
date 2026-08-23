package com.setu.mesh.sim

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReceiptEnergyTest {

    @Test
    fun `delivery confirmation reclaims energy and clears outboxes`() {
        // 20 nodes, not 2: a pair cannot demonstrate a "mesh-wide" energy effect, and the
        // previous version's 3.02% measured drop was noise at that scale.
        //
        // "flood" rather than "dying-chain": dying-chain lays nodes out in a straight line at
        // ~70% of radio range apart, so a message needs one hop per node to cross it. With the
        // default TTL of 7 hops, a message from the far end of a 20-node chain physically
        // cannot reach a gateway at the near end -- that isn't an energy-model question, it's a
        // hop-budget one, and it silently made the original 2-node version of this test easy
        // (a 1-hop chain) while hiding that a longer chain wouldn't work here at all.
        // "flood" clusters nodes within mutual radio range instead, so multi-hop distance stays
        // small regardless of node count, which is what this test actually needs to isolate.
        val world = Scenario.build("flood", 20, 80.0, 0.05, Random(11))
        val gateway = world.gatewayNode()

        val energyAtTick = mutableListOf<Double>()
        var deliveryTick = -1
        var clearedTick = -1

        val ticksPerMinute = (60_000 / World.TICK_MILLIS).toInt() // 240
        // Phase-locked rendezvous means a node only listens ~1s per 60s epoch (see
        // docs/POWER.md §2), so discovery is far slower than it was before that mechanism was
        // actually wired into World.tick() -- 30 virtual minutes was tuned against the
        // unwired, always-scanning behaviour and is no longer enough. 90 minutes gives multiple
        // rendezvous epochs' worth of margin for a message to cross a small cluster and for the
        // RECEIPT to propagate back out to every carrier.
        val testDurationTicks = ticksPerMinute * 90

        // Initially disable gateway uplink so SOS propagates and burns energy for 5 minutes
        val gatewayRole = checkNotNull(gateway.gatewayRole)
        gatewayRole.onUplinkAvailable(false)

        for (t in 0 until testDurationTicks) {
            if (t == ticksPerMinute * 5) {
                // Enable gateway uplink at 5 minutes
                gatewayRole.onUplinkAvailable(true)
            }
            energyAtTick.add(world.nodes.sumOf { it.meshNode.ledger.totalMilliampHours })
            world.tick()
            if (deliveryTick == -1 && gatewayRole.delivered.isNotEmpty()) {
                deliveryTick = t
            }
            // The gateway accepting delivery is only the *start* of the RECEIPT's own trip back
            // out across the mesh -- it still has to cross the same phase-locked, once-a-minute
            // rendezvous windows the SOS did. Energy does not actually start dropping until every
            // carrier has heard it and cleared the SOS, so track the tick that happens at,
            // instead of assuming it coincides with first delivery.
            if (deliveryTick != -1 && clearedTick == -1) {
                val now = world.nodes.first().clock.nowMillis()
                val stillCarrying = world.nodes.any { node ->
                    !node.battery.isDead && node.meshNode.carriedMessages(now).any { it.type == MessageType.SOS }
                }
                if (!stillCarrying) clearedTick = t
            }
        }

        assertTrue(deliveryTick != -1, "Message was never delivered to the gateway")

        val deliveredMessageId = gatewayRole.delivered.first()

        // 1. Assert no node's outbox still contains the delivered message at the end
        val outboxesWithSos = world.nodes.filter { !it.battery.isDead }.count { node ->
            val beacons = node.meshNode.beaconsToAdvertise(100, world.nodes.first().clock.nowMillis())
            beacons.any { encoded ->
                val decoded = BeaconCodec.decode(encoded)
                decoded != null && decoded.type == MessageType.SOS && decoded.messageId == deliveredMessageId
            }
        }
        assertTrue(outboxesWithSos == 0, "Expected all alive nodes to drop the SOS, but $outboxesWithSos nodes still had it")
        assertTrue(clearedTick != -1, "SOS was delivered but never fully cleared from the mesh before the test ended")

        // 2. Measure energy drop around full mesh-wide clearance, not around first delivery.
        //
        // "flood" seeds 3 concurrent SOS messages (see Scenario.originateSosMessages), and this
        // mesh only grants ~1s of scan time per node per 60s rendezvous epoch (see docs/POWER.md
        // §2). Emptying every carrier's outbox of every SOS -- which is what actually lets
        // attentive mode (ATTENTIVE_AFTER_SOS_MILLIS, MeshNode.kt) lapse mesh-wide and beacon
        // intervals lengthen -- takes many such epochs after the gateway's first accept, not the
        // few seconds a window centred on deliveryTick would assume. Centring on clearedTick
        // instead measures the "before" window while the mesh is still actively clearing (still
        // representative of pre-reclaim load) and the "after" window once every node has both
        // dropped the SOS and had its own last-heard-SOS attentive window expire.
        val windowTicks = ticksPerMinute * 5 // 5 minutes

        val startBefore = maxOf(0, clearedTick - windowTicks)
        val endBefore = clearedTick
        val energyBefore = energyAtTick[endBefore] - energyAtTick[startBefore]
        val minutesBefore = (endBefore - startBefore).toDouble() / ticksPerMinute
        val rateBefore = energyBefore / minutesBefore

        val startAfter = clearedTick
        val endAfter = minOf(clearedTick + windowTicks, testDurationTicks - 1)
        val energyAfter = energyAtTick[endAfter] - energyAtTick[startAfter]
        val minutesAfter = (endAfter - startAfter).toDouble() / ticksPerMinute
        val rateAfter = energyAfter / minutesAfter

        println("Energy rate before clearance: $rateBefore mAh/min")
        println("Energy rate after clearance:  $rateAfter mAh/min")

        val dropPercent = ((rateBefore - rateAfter) / rateBefore) * 100
        println("Actual energy drop: ${String.format("%.2f", dropPercent)}%")

        // A magnitude threshold, not just direction: the earlier 2-node version of this test
        // asserted only `rateAfter < rateBefore`, which passes on a 0.001% drop that is pure
        // noise. 20% is comfortably below the measured ~38% at seed 11 (leaving headroom for
        // seed-to-seed variance) while still being large enough that a regression which quietly
        // stopped RECEIPT propagation, or reintroduced the altruism-gradient bug that used to
        // suppress RECEIPT relay, would fail this rather than slip through as a technically-true
        // but meaningless direction check.
        assertTrue(
            dropPercent > 20.0,
            "Expected a meaningful mesh-wide energy drop after clearance (>20%%), got %.2f%%".format(dropPercent),
        )
    }
}
