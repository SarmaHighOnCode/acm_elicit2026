package com.setu.mesh.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Own device at centre, known SOS positions plotted around it by real bearing and distance.
 * No online map tiles -- an offline-mesh app depending on the internet for its own map would be
 * absurd, and would fail exactly when it matters. The projection is equirectangular (flat-earth
 * with a per-latitude longitude correction), which is accurate to well under a metre of error
 * at BLE range (~100 m) and is a correct trade against needing a real projection library.
 *
 * Positions beyond [maxRangeMetres] are **clamped to the edge** along their true bearing rather
 * than dropped, with a small arrow marking the clamp -- a report from 400 m away is still worth
 * showing as "that direction, far", not silently discarded.
 */
@Composable
fun RelativeMap(
    self: GeoPoint,
    beacons: List<SosBeacon>,
    onTapBeacon: (SosBeacon) -> Unit,
    modifier: Modifier = Modifier,
    maxRangeMetres: Double = DEFAULT_MAX_RANGE_METRES,
) {
    var selected by remember { mutableStateOf<SosBeacon?>(null) }
    val plotted = remember(self, beacons) { beacons.map { it to relativeOffsetMetres(self, it.position) } }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .pointerInput(plotted) {
                detectTapGestures { tap ->
                    val scale = min(size.width, size.height) / 2f / maxRangeMetres.toFloat()
                    val centre = Offset(size.width / 2f, size.height / 2f)
                    val hit = plotted.minByOrNull { (_, offset) ->
                        val p = toScreenPoint(offset, maxRangeMetres, centre, scale)
                        (p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y)
                    }
                    if (hit != null) {
                        val p = toScreenPoint(hit.second, maxRangeMetres, centre, scale)
                        val distSq = (p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y)
                        if (distSq <= TAP_RADIUS_PX * TAP_RADIUS_PX) {
                            selected = hit.first
                            onTapBeacon(hit.first)
                        }
                    }
                }
            },
    ) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val scale = min(size.width, size.height) / 2f / maxRangeMetres.toFloat()

        // Distance rings at thirds of the display range, each labelled in metres.
        val ringColor = Color.White.copy(alpha = 0.15f)
        for (fraction in listOf(1.0 / 3, 2.0 / 3, 1.0)) {
            val radiusMetres = maxRangeMetres * fraction
            val radiusPx = (radiusMetres * scale).toFloat()
            drawCircle(color = ringColor, radius = radiusPx, center = centre, style = Stroke(width = 1f))
            drawContext.canvas.nativeCanvas.drawText(
                "${radiusMetres.toInt()}m",
                centre.x + 4f,
                centre.y - radiusPx - 4f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(140, 255, 255, 255)
                    textSize = 24f
                },
            )
        }

        // Self, at centre.
        drawCircle(color = Color.White, radius = 8f, center = centre)
        drawCircle(color = Color.White.copy(alpha = 0.25f), radius = 16f, center = centre)

        plotted.forEach { (beacon, offset) ->
            val point = toScreenPoint(offset, maxRangeMetres, centre, scale)
            val clamped = hypot(offset.first, offset.second) > maxRangeMetres
            val color = severityColor(beacon.flags.severity)
            drawCircle(color = color, radius = if (beacon == selected) 12f else 8f, center = point)
            if (clamped) {
                // A short tick along the same bearing, just inside the edge, marking "further
                // than shown" rather than silently placing the point on the boundary unlabelled.
                drawCircle(color = color, radius = 3f, center = point, style = Stroke(width = 2f))
            }
        }
    }

    selected?.let { beacon ->
        val (dx, dy) = relativeOffsetMetres(self, beacon.position)
        val distanceMetres = hypot(dx, dy)
        Text(
            text = "${beacon.souls} people, ${distanceMetres.toInt()}m away",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun toScreenPoint(offsetMetres: Pair<Double, Double>, maxRangeMetres: Double, centre: Offset, scale: Float): Offset {
    val (dx, dy) = offsetMetres
    val distance = hypot(dx, dy)
    val (clampedDx, clampedDy) = if (distance > maxRangeMetres && distance > 0.0) {
        val ratio = maxRangeMetres / distance
        dx * ratio to dy * ratio
    } else {
        dx to dy
    }
    // Screen y grows downward; north (positive dy, latitude increasing) must draw upward.
    return Offset(centre.x + (clampedDx * scale).toFloat(), centre.y - (clampedDy * scale).toFloat())
}

/**
 * Equirectangular offset in metres: (east, north). Correct to well under a metre at BLE range,
 * which is all the precision this display needs or the GPS fix itself can actually support.
 */
private fun relativeOffsetMetres(self: GeoPoint, other: GeoPoint): Pair<Double, Double> {
    val metresPerDegreeLat = 111_320.0
    val metresPerDegreeLon = 111_320.0 * cos(Math.toRadians(self.latitude))
    val dx = (other.longitude - self.longitude) * metresPerDegreeLon
    val dy = (other.latitude - self.latitude) * metresPerDegreeLat
    return dx to dy
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.CRITICAL -> Color(0xFFEF5350)
    Severity.HIGH -> Color(0xFFFF7043)
    Severity.MODERATE -> Color(0xFFFFA726)
    Severity.LOW -> Color(0xFF66BB6A)
}

private const val DEFAULT_MAX_RANGE_METRES = 120.0
private const val TAP_RADIUS_PX = 28f
