package com.setu.mesh.sim

import com.setu.mesh.core.model.GeoPoint

/**
 * Position strategies for simulated nodes.
 *
 * Kept deliberately minimal — mobility is not the story SETU is telling.
 */
sealed interface Mobility {
    fun position(tickMillis: Long, random: kotlin.random.Random): GeoPoint

    /** Node stays put. */
    class Static(private val point: GeoPoint) : Mobility {
        override fun position(tickMillis: Long, random: kotlin.random.Random): GeoPoint = point
    }

    /**
     * Brownian walk around an origin. Each tick the position shifts by up to
     * [speedMetresPerSecond] × dt in a random direction.
     *
     * Coordinates are in the GeoPoint int32 ×10^7 encoding. One degree of latitude is
     * about 111 km, so 1 metre ≈ 90 raw units.
     */
    class RandomWalk(
        private val origin: GeoPoint,
        private val speedMetresPerSecond: Double = 1.0,
    ) : Mobility {
        private var lat = origin.latitudeE7
        private var lon = origin.longitudeE7

        override fun position(tickMillis: Long, random: kotlin.random.Random): GeoPoint {
            val dt = tickMillis / 1000.0
            val displacement = speedMetresPerSecond * dt * UNITS_PER_METRE
            lat += (random.nextDouble(-1.0, 1.0) * displacement).toInt()
            lon += (random.nextDouble(-1.0, 1.0) * displacement).toInt()
            return GeoPoint(lat, lon)
        }

        companion object {
            /** Approximate raw GeoPoint units per metre of surface distance. */
            const val UNITS_PER_METRE = 90
        }
    }
}
