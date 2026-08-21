package com.setu.mesh.sim

import com.setu.mesh.core.engine.GatewayRole
import com.setu.mesh.core.engine.MeshNode
import com.setu.mesh.core.link.PeerHandle
import com.setu.mesh.core.codec.BeaconCodec
import com.setu.mesh.core.model.MessageType
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.NodeId
import com.setu.mesh.core.power.PowerGovernor
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * One node in the simulated world.
 */
class SimNode(
    val id: NodeId,
    val host: SimHost,
    val link: SimLink,
    val meshNode: MeshNode,
    val battery: BatteryModel,
    /** Individual clock — usually the shared one, but unsynced scenario uses per-node clocks. */
    val clock: VirtualClock,
    /** True for the node that acts as the gateway (receives SOS from the mesh). */
    val isGateway: Boolean = false,
) {
    val gatewayRole: GatewayRole? = if (isGateway) GatewayRole(meshNode).apply { onUplinkAvailable(true) } else null
}

/**
 * The virtual world. Owns N [SimNode]s, computes range, delivers beacons.
 *
 * This is the orchestrator. It never touches routing, dedup, tier selection, or scanner
 * election — all of that lives in [MeshNode]. The world only provides physics: who is
 * near whom, and whether a given radio transmission survives.
 */
class World(
    val nodes: List<SimNode>,
    /** The set of clocks to advance each tick. Usually one shared clock; unsynced has N. */
    private val clocks: List<VirtualClock>,
    private val rangeMetres: Double = DEFAULT_RANGE_METRES,
    private val lossRate: Double = DEFAULT_LOSS_RATE,
    private val random: Random,
) {
    private var tickCount: Long = 0

    /** Convenience constructor for the common single-clock case. */
    constructor(
        nodes: List<SimNode>,
        clock: VirtualClock,
        rangeMetres: Double = DEFAULT_RANGE_METRES,
        lossRate: Double = DEFAULT_LOSS_RATE,
        random: Random,
    ) : this(nodes, listOf(clock), rangeMetres, lossRate, random)

    /** Run one tick of the simulation: plan, advertise, deliver, drain. */
    fun tick(tickMillis: Long = TICK_MILLIS) {
        // Phase 1: Each alive node plans, updates its advertised beacons, and bills the ledger.
        for (node in nodes) {
            if (node.battery.isDead) continue

            val now = node.clock.nowMillis()

            // Let the mobility model move the node
            node.host.updatePosition(tickMillis)

            // Ask the engine what the radio should do
            val plan = node.meshNode.planNow(now)

            // Update the advertised beacons based on the plan
            val beacons = if (plan.advertising) {
                node.meshNode.beaconsToAdvertise(node.link.capabilities.advertisingSlots, now)
            } else {
                emptyList()
            }
            node.link.currentBeacons = beacons

            // Bill the radio activity to the ledger (the engine does this in its run loop,
            // but since we bypass run(), we do it here)
            if (plan.advertising && beacons.isNotEmpty()) {
                node.meshNode.ledger.billAdvertising(tickMillis, plan.beaconIntervalMillis)
            }
            // Gated on inRendezvousWindow, not just scanThisEpoch: scanThisEpoch says this
            // tier participates in this epoch at all, but the radio must only actually be on
            // during the ~1s rendezvous window inside that epoch. Billing (and delivery, in
            // Phase 2) on scanThisEpoch alone was the defect that made phase-locked rendezvous
            // dead code -- every tick of a participating epoch was treated as "scanning",
            // which both massively over-billed scan energy and made unsynced indistinguishable
            // from flood, since nothing ever depended on phase.
            if (plan.scanThisEpoch && plan.inRendezvousWindow && plan.scanWindowMillis > 0) {
                node.meshNode.ledger.billScan(plan.scanWindowMillis.coerceAtMost(tickMillis))
            }
        }

        // Phase 2: For each scanning node, deliver beacons from in-range advertisers.
        for (receiver in nodes) {
            if (receiver.battery.isDead) continue

            val now = receiver.clock.nowMillis()
            val plan = receiver.meshNode.planNow(now)
            if (!plan.scanThisEpoch || !plan.inRendezvousWindow) continue

            val receiverPos = receiver.host.position() ?: continue

            for (sender in nodes) {
                if (sender === receiver) continue
                if (sender.battery.isDead) continue
                if (sender.link.currentBeacons.isEmpty()) continue

                val senderPos = sender.host.position() ?: continue
                val distMetres = distanceMetres(receiverPos, senderPos)
                if (distMetres > rangeMetres) continue

                // Delivery probability: quadratic falloff × (1 - flat loss)
                val pDistance = 1.0 - (distMetres / rangeMetres) * (distMetres / rangeMetres)
                val pDelivery = pDistance * (1.0 - lossRate)

                for (beacon in sender.link.currentBeacons) {
                    if (random.nextDouble() < pDelivery) {
                        receiver.meshNode.onBeaconHeard(
                            beacon,
                            PeerHandle("sim-${sender.id.raw}"),
                            now,
                        )
                        if (receiver.isGateway) {
                            val decoded = BeaconCodec.decode(beacon)
                            if (decoded != null && decoded.type == MessageType.SOS) {
                                receiver.gatewayRole!!.acceptDelivery(decoded.messageId, now)
                            }
                        }
                    }
                }
            }
        }

        // Phase 3: Drain batteries from ledger deltas + idle draw.
        for (node in nodes) {
            if (node.battery.isDead) continue
            node.battery.drain(node.meshNode.ledger.totalMilliampHours, tickMillis)
        }

        // Advance all clocks
        for (clock in clocks) {
            clock.advance(tickMillis)
        }
        tickCount++
    }

    companion object {
        const val TICK_MILLIS = 250L
        const val DEFAULT_RANGE_METRES = 80.0
        const val DEFAULT_LOSS_RATE = 0.05

        /**
         * Distance between two GeoPoints in metres.
         * 1 raw GeoPoint unit ≈ 1/90 metre.
         */
        fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
            val dLat = (a.latitudeE7 - b.latitudeE7).toDouble() / UNITS_PER_METRE
            val dLon = (a.longitudeE7 - b.longitudeE7).toDouble() / UNITS_PER_METRE
            return sqrt(dLat * dLat + dLon * dLon)
        }

        /** GeoPoint raw units per metre (1 degree = ~111 km, so 1e7 / 111_000 ≈ 90). */
        private const val UNITS_PER_METRE = 90.0
    }
}
