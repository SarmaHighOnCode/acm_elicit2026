package com.setu.mesh.core.engine

import com.setu.mesh.core.model.GeoPoint

/**
 * The other half of the seam: everything the protocol needs to know about the machine it is
 * running on. Android reads `BatteryManager` and `FusedLocationProvider`; the simulator reads
 * its virtual battery model and a scripted mobility trace.
 *
 * Battery is a first-class protocol input here, not telemetry. Nearly every routing and
 * scheduling decision in SETU is a function of [batteryPercent].
 */
interface NodeHost {

    /** Wall-clock milliseconds. Used for rendezvous phase, so it must be real time, not uptime. */
    fun nowMillis(): Long

    /** 0..100, or [com.setu.mesh.core.model.BATTERY_UNKNOWN] if the platform will not say. */
    fun batteryPercent(): Int

    /** Charging devices volunteer for the expensive work regardless of their current level. */
    fun isCharging(): Boolean

    /** Last known fix, or null if GPS has not produced one yet. */
    fun position(): GeoPoint?

    /**
     * Accuracy class of [position], 0..3 per docs/PROTOCOL.md §2 (0 unknown/no fix/worse than
     * 100 m, 1 ≤10 m, 2 ≤30 m, 3 ≤100 m). Defaulted to 0 rather than made abstract: `:sim` and
     * the `:core` engine tests implement [NodeHost] with no real GPS behind them and have no
     * accuracy figure to report, so they should not be forced to fabricate one just to compile.
     */
    fun positionAccuracyClass(): Int = 0

    /**
     * True when the clock is believed accurate to within a rendezvous slot — GPS time or a
     * recent NTP sync. When false the scheduler falls back to consensus drift correction using
     * the `epochMin` field carried in every beacon.
     */
    fun hasTrustedClock(): Boolean
}
