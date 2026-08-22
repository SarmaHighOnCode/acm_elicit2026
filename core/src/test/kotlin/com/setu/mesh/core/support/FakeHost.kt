package com.setu.mesh.core.support

import com.setu.mesh.core.engine.NodeHost
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS

class FakeHost(
    var currentTimeMillis: Long = SETU_EPOCH_MILLIS,
    var battery: Int = 100,
    var charging: Boolean = false,
    var location: GeoPoint? = GeoPoint(0, 0),
    var trustedClock: Boolean = true
) : NodeHost {
    override fun nowMillis(): Long = currentTimeMillis
    override fun batteryPercent(): Int = battery
    override fun isCharging(): Boolean = charging
    override fun position(): GeoPoint? = location
    override fun hasTrustedClock(): Boolean = trustedClock
    
    fun advanceTime(millis: Long) {
        currentTimeMillis += millis
    }
}
