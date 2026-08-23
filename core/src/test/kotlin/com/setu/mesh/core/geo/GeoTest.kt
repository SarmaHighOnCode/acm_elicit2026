package com.setu.mesh.core.geo

import com.setu.mesh.core.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoTest {

    private val equator = GeoPoint.of(0.0, 0.0)

    @Test
    fun `due north is bearing zero`() {
        val other = GeoPoint.of(0.001, 0.0)
        assertEquals(0f, bearingDegrees(equator, other), 0.01f)
        assertEquals(111.32, distanceMetres(equator, other), 0.5)
    }

    @Test
    fun `due east is bearing ninety -- the nautical convention, atan2(east, north)`() {
        // A point purely east of self (longitude increases, latitude unchanged) must bearing 90,
        // not 0 or 180 -- the case that would silently flip if bearingDegrees' atan2 arguments
        // were ever swapped to the mathematical atan2(y, x) convention.
        val other = GeoPoint.of(0.0, 0.001)
        assertEquals(90f, bearingDegrees(equator, other), 0.01f)
        assertEquals(111.32, distanceMetres(equator, other), 0.5)
    }

    @Test
    fun `diagonal north-east lands at 45 degrees`() {
        val other = GeoPoint.of(0.001, 0.001)
        assertEquals(45f, bearingDegrees(equator, other), 0.01f)
        // hypot of two equal ~111.32 m legs.
        assertEquals(157.44, distanceMetres(equator, other), 1.0)
    }

    @Test
    fun `compassPoint labels the cardinal and intercardinal points`() {
        assertEquals("N", compassPoint(0f))
        assertEquals("NE", compassPoint(45f))
        assertEquals("E", compassPoint(90f))
        assertEquals("S", compassPoint(180f))
        assertEquals("W", compassPoint(270f))
    }

    @Test
    fun `compassPoint boundaries fall on the half-sector line`() {
        // The N-NNE boundary is at 11.25 degrees (half of the 22.5-degree sector width).
        assertEquals("N", compassPoint(11.24f))
        assertEquals("NNE", compassPoint(11.26f))
    }

    @Test
    fun `compassPoint wraps 359 back to N, not NNW`() {
        // The NNW-N boundary wraps through 360/0, not through 180 -- a naive modulo without the
        // (x % 360 + 360) % 360 normalisation would get this wrong at the wrap seam specifically.
        assertEquals("N", compassPoint(359f))
        assertEquals("N", compassPoint(360f))
        assertEquals("NNW", compassPoint(348.74f))
        assertEquals("N", compassPoint(348.76f))
    }

    @Test
    fun `two ten-metre fixes fifteen metres apart land in the 40-45 degree region`() {
        // The exact field case this task exists for: two phones, each with a real +-10 m fix,
        // 15 m apart. The resulting bearing uncertainty is large enough that a confident arrow
        // reads as "flipped" even though nothing is broken -- this is the number that has to be
        // right for the three-band UI split to land in the correct place.
        val combined = kotlin.math.hypot(10.0, 10.0)
        val sigmaDegrees = bearingUncertaintyDegrees(combined, 15.0)
        assertTrue(sigmaDegrees in 40.0..45.0, "expected 40-45 degrees, got $sigmaDegrees")
    }

    @Test
    fun `bearingUncertaintyDegrees floors separation instead of dividing by zero`() {
        val sigmaDegrees = bearingUncertaintyDegrees(10.0, 0.0)
        assertTrue(sigmaDegrees.isFinite())
        assertTrue(sigmaDegrees < 90.0)
    }

    @Test
    fun `bearingConfidenceBand cutoffs are inclusive at 20 and 50 degrees`() {
        assertEquals(BearingConfidenceBand.CONFIDENT, bearingConfidenceBand(20.0))
        assertEquals(BearingConfidenceBand.APPROXIMATE, bearingConfidenceBand(20.01))
        assertEquals(BearingConfidenceBand.APPROXIMATE, bearingConfidenceBand(50.0))
        assertEquals(BearingConfidenceBand.UNUSABLE, bearingConfidenceBand(50.01))
    }
}
