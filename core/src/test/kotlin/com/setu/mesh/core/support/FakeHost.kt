package com.setu.mesh.core.support

import com.setu.mesh.core.engine.NodeHost
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.SETU_EPOCH_MILLIS

class FakeHost(
    var currentTimeMillis: Long = SETU_EPOCH_MILLIS,
    var battery: Int = 100,
    var charging: Boolean = false,
    var location: GeoPoint? = GeoPoint(0, 0),
    var trustedClock: Boolean = true,
    /** 0..3 per docs/PROTOCOL.md §2. Defaults to 0 (unknown), same as [NodeHost]'s default body. */
    var accuracyClass: Int = 0,
) : NodeHost {
    override fun nowMillis(): Long = currentTimeMillis
    override fun batteryPercent(): Int = battery
    override fun isCharging(): Boolean = charging
    override fun position(): GeoPoint? = location
    override fun hasTrustedClock(): Boolean = trustedClock
    override fun positionAccuracyClass(): Int = accuracyClass

    fun advanceTime(millis: Long) {
        currentTimeMillis += millis
    }
}
