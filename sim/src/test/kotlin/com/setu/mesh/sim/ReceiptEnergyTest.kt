package com.setu.mesh.sim

import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReceiptEnergyTest {

    @Test
    fun `delivery confirmation reclaims energy and clears outboxes`() {
        // Run dying-chain with 2 nodes so RECEIPT reliably reaches the originator
        val world = Scenario.build("dying-chain", 2, 80.0, 0.05, Random(11))
        val gateway = world.gatewayNode()

        val energyAtTick = mutableListOf<Double>()
        var deliveryTick = -1

        val ticksPerMinute = (60_000 / World.TICK_MILLIS).toInt() // 240
        val testDurationTicks = ticksPerMinute * 30 // run for 30 virtual minutes

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
        val outboxesWithSos = world.nodes.filter { !it.battery.isDead }.count { node ->
            val beacons = node.meshNode.beaconsToAdvertise(100, world.nodes.first().clock.nowMillis())
            beacons.any { encoded ->
                val decoded = BeaconCodec.decode(encoded)
                decoded != null && decoded.type == MessageType.SOS && decoded.messageId == deliveredMessageId
            }
        }
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
