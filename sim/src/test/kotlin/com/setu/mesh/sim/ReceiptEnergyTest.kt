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

        val ticksPerMinute = (60_000 / World.TICK_MILLIS).toInt() // 240
        // Phase-locked rendezvous means a node only listens ~1s per 60s epoch (see
        // docs/POWER.md §2), so discovery is far slower than it was before that mechanism was
        // actually wired into World.tick() -- 30 virtual minutes was tuned against the
        // unwired, always-scanning behaviour and is no longer enough. 90 minutes gives multiple
        // rendezvous epochs' worth of margin for a message to cross a small cluster.
        val testDurationTicks = ticksPerMinute * 300

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
        }

        assertTrue(deliveryTick != -1, "Message was never delivered to the gateway")

        val deliveredMessageId = gatewayRole.delivered.first()

        // 1. Assert no node's outbox still contains the delivered message at the end
        println("DEBUG deliveryTick=$deliveryTick of $testDurationTicks")
        val offenders = world.nodes.filter { !it.battery.isDead }.filter { node ->
            val beacons = node.meshNode.beaconsToAdvertise(100, world.nodes.first().clock.nowMillis())
            beacons.any { encoded ->
                val decoded = BeaconCodec.decode(encoded)
                decoded != null && decoded.type == MessageType.SOS && decoded.messageId == deliveredMessageId
            }
        }
        for (o in offenders) {
            println("DEBUG offender node=${o.id} tier=${o.meshNode.snapshot.value.tier} battery=${o.battery.percent} isGateway=${o.isGateway}")
        }
        val outboxesWithSos = offenders.size
        assertTrue(outboxesWithSos == 0, "Expected all alive nodes to drop the SOS, but $outboxesWithSos nodes still had it")

        // 2. Measure energy drop
        val windowTicks = ticksPerMinute * 5 // 5 minutes

        val startBefore = maxOf(0, deliveryTick - windowTicks)
        val endBefore = deliveryTick
        val energyBefore = energyAtTick[endBefore] - energyAtTick[startBefore]
        val minutesBefore = (endBefore - startBefore).toDouble() / ticksPerMinute
        val rateBefore = energyBefore / minutesBefore

        val startAfter = deliveryTick
        val endAfter = minOf(deliveryTick + windowTicks, testDurationTicks - 1)
        val energyAfter = energyAtTick[endAfter] - energyAtTick[startAfter]
        val minutesAfter = (endAfter - startAfter).toDouble() / ticksPerMinute
        val rateAfter = energyAfter / minutesAfter

        println("Energy rate before delivery: $rateBefore mAh/min")
        println("Energy rate after delivery:  $rateAfter mAh/min")

        assertTrue(rateAfter < rateBefore, "Expected energy rate to drop after delivery")

        val dropPercent = ((rateBefore - rateAfter) / rateBefore) * 100
        println("Actual energy drop: ${String.format("%.2f", dropPercent)}%")
    }
}
