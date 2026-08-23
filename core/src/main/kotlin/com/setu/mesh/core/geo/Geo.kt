package com.setu.mesh.core.geo

import com.setu.mesh.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Flat-earth geometry for the responder map: real bearings and distances from lat/lon, and how
 * much to trust them. Lives in `:core` (rather than `:app/ui/components`, where it was originally
 * written) purely so it can be unit tested -- `:app` has no test source set and cannot get one,
 * `:core` already has one. Nothing here is Android-specific.
 */

/**
 * Equirectangular offset in metres: (east, north). Correct to well under a metre at BLE range,
 * which is all the precision this display needs or the GPS fix itself can actually support.
 */
fun relativeOffsetMetres(self: GeoPoint, other: GeoPoint): Pair<Double, Double> {
    val metresPerDegreeLat = 111_320.0
    val metresPerDegreeLon = 111_320.0 * cos(Math.toRadians(self.latitude))
    val dx = (other.longitude - self.longitude) * metresPerDegreeLon
    val dy = (other.latitude - self.latitude) * metresPerDegreeLat
    return dx to dy
}

/** Straight-line distance in metres, via the same projection as [relativeOffsetMetres]. */
fun distanceMetres(self: GeoPoint, other: GeoPoint): Double {
    val (dx, dy) = relativeOffsetMetres(self, other)
    return hypot(dx, dy)
}

/**
 * True bearing in degrees `[0, 360)` from [self] to [other]. Nautical convention --
 * `atan2(east, north)`, clockwise from true north -- not the mathematical `atan2(y, x)`
 * convention. Swapping the arguments is the easiest way to silently rotate every bearing this
 * app computes by 90 degrees, so [com.setu.mesh.core.geo.GeoTest] (in the test source set)
 * asserts the convention explicitly rather than only checking numeric results that a swapped
 * bearing could accidentally still pass for a symmetric test case.
 */
fun bearingDegrees(self: GeoPoint, other: GeoPoint): Float {
    val (dx, dy) = relativeOffsetMetres(self, other)
    val degrees = Math.toDegrees(atan2(dx, dy)).toFloat()
    return (degrees + 360f) % 360f
}

/** 16-point compass label for a bearing in degrees. */
fun compassPoint(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    val index = (normalized / 22.5f).roundToInt() % COMPASS_POINTS.size
    return COMPASS_POINTS[index]
}

/**
 * 1-sigma angular uncertainty of a bearing, in degrees, given the combined positional error of
 * the two endpoints and the distance between them.
 *
 * The geometry: each fix is smeared over a disc of radius equal to its own accuracy (Android's
 * `Location.getAccuracy()` is a 68% / 1-sigma radius), and two independent fixes combine in
 * quadrature -- callers pass `hypot(selfAccuracyMetres, senderAccuracyMetres)` as
 * [combinedAccuracyMetres]. At [separationMetres] apart, that combined radius subtends an angle
 * of `atan(combinedAccuracyMetres / separationMetres)` -- this is exactly the field case the
 * whole task exists for: two +-10 m fixes 15 m apart give a bearing whose 1-sigma cone is roughly
 * 43 degrees wide, past 90 degrees at 2-sigma, which reads to a user as "flipped" even though
 * nothing is broken. That is physics, not a defect.
 *
 * [separationMetres] is floored at 0.5 m rather than allowed to reach zero: on an elevated
 * terrace, horizontal separation between two phones can genuinely approach zero, which would
 * otherwise divide by zero. Reporting "extremely uncertain" (a large, finite angle) is exactly as
 * actionable as "infinitely uncertain" and does not require special-casing NaN downstream.
 */
fun bearingUncertaintyDegrees(combinedAccuracyMetres: Double, separationMetres: Double): Double =
    Math.toDegrees(atan2(combinedAccuracyMetres, max(separationMetres, 0.5)))

/**
 * Three-band classification of [bearingUncertaintyDegrees] -- the honest-rendering boundary this
 * task exists to draw. Below 20 degrees, a compass arrow is worth drawing; between 20 and 50, a
 * distance is still worth showing but a direction claim is not; past 50 degrees the bearing is
 * indistinguishable from noise and only a proximity figure remains honest.
 *
 * Deliberately just a function of the angle, not of self-fix staleness or an unknown sender
 * accuracy class (both of which also force the unusable band in
 * [com.setu.mesh.app.ui.PositionConfidence] at the app layer) -- keeping the numeric cutoffs here
 * means they are covered by [GeoTest] even though `:app` has no test source set to verify the
 * rest of that logic in.
 */
enum class BearingConfidenceBand { CONFIDENT, APPROXIMATE, UNUSABLE }

fun bearingConfidenceBand(sigmaBearingDegrees: Double): BearingConfidenceBand = when {
    sigmaBearingDegrees <= 20.0 -> BearingConfidenceBand.CONFIDENT
    sigmaBearingDegrees <= 50.0 -> BearingConfidenceBand.APPROXIMATE
    else -> BearingConfidenceBand.UNUSABLE
}

private val COMPASS_POINTS = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)
