package com.setu.mesh.sim

import com.setu.mesh.core.engine.NodeHost
import com.setu.mesh.core.model.GeoPoint

/**
 * [NodeHost] implementation backed entirely by virtual-time constructs.
 *
 * Never calls `System.currentTimeMillis()` — all time comes from [clock].
 */
class SimHost(
    private val clock: VirtualClock,
    val battery: BatteryModel,
    private val mobility: Mobility,
    private val charging: Boolean = false,
    private val trustedClock: Boolean = true,
    private val random: kotlin.random.Random,
) : NodeHost {

    private var currentPosition: GeoPoint = when (mobility) {
        is Mobility.Static -> mobility.position(0, random)
        is Mobility.RandomWalk -> mobility.position(0, random)
    }

    override fun nowMillis(): Long = clock.nowMillis()

    override fun batteryPercent(): Int = battery.percent

    override fun isCharging(): Boolean = charging

    override fun position(): GeoPoint = currentPosition

    override fun hasTrustedClock(): Boolean = trustedClock

    /** Called by [World] each tick to let the mobility model advance. */
    fun updatePosition(tickMillis: Long) {
        currentPosition = mobility.position(tickMillis, random)
    }
}
