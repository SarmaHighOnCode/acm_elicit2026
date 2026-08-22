package com.setu.mesh.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.setu.mesh.app.service.SelfFix
import com.setu.mesh.app.ui.theme.LocalIsDarkTheme
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Own device at centre, known SOS positions plotted around it by real bearing and distance.
 * No online map tiles -- an offline-mesh app depending on the internet for its own map would be
 * absurd, and would fail exactly when it matters. The projection is equirectangular (flat-earth
 * with a per-latitude longitude correction), which is accurate to well under a metre of error
 * at BLE range (~100 m) and is a correct trade against needing a real projection library.
 *
 * Positions beyond the auto-picked range are **clamped to the edge** along their true bearing
 * rather than dropped, with a small ring marking the clamp -- a report from 400 m away is still
 * worth showing as "that direction, far", not silently discarded.
 *
 * The map is a compass: up is wherever [self]'s device is currently pointing, not north, via
 * [rememberTrueHeadingDegrees]. On hardware with no usable rotation sensor that call returns
 * null, and the map stays north-up -- labelled as such, never silently wrong.
 */
@Composable
fun RelativeMap(
    self: GeoPoint,
    beacons: List<SosBeacon>,
    onTapBeacon: (SosBeacon) -> Unit,
    nowMillis: Long,
    selfFix: SelfFix?,
    degraded: Boolean,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<SosBeacon?>(null) }
    val plotted = remember(self, beacons) { beacons.map { it to relativeOffsetMetres(self, it.position) } }
    val maxRangeMetres = remember(plotted) { autoRangeMetres(plotted) }
    val headingDegrees = rememberTrueHeadingDegrees(self)
    val isDark = LocalIsDarkTheme.current
    // In dark mode, overlay colours are white-tinted; in light mode, dark-tinted for contrast.
    val overlayColor = if (isDark) Color.White else Color(0xFF1A1A1A)

    Column(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // The plot area must be square: rotating a non-square canvas clips the corners once
            // heading rotation is applied. Centred square of min(available width, the old fixed
            // height) rather than a fixed height alone, so a narrow phone doesn't overflow either.
            val squareSize = minOf(maxWidth, MAP_MAX_SIZE)

            Canvas(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(squareSize)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    .pointerInput(plotted, maxRangeMetres, headingDegrees) {
                        detectTapGestures { tap ->
                            val scale = min(size.width, size.height) / 2f / maxRangeMetres.toFloat()
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val heading = headingDegrees ?: 0f
                            val hit = plotted.minByOrNull { (_, offset) ->
                                val p = plotPoint(offset, maxRangeMetres, centre, scale, heading)
                                (p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y)
                            }
                            if (hit != null) {
                                val p = plotPoint(hit.second, maxRangeMetres, centre, scale, heading)
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
                val heading = headingDegrees ?: 0f

                // Distance rings at thirds of the display range, each labelled in metres. Rings
                // and their labels are rotation-invariant by construction: a circle centred on
                // the self dot looks the same at any heading, and the labels are drawn without
                // any rotation applied so they always read upright.
                val ringColor = overlayColor.copy(alpha = 0.15f)
                for (fraction in listOf(1.0 / 3, 2.0 / 3, 1.0)) {
                    val radiusMetres = maxRangeMetres * fraction
                    val radiusPx = (radiusMetres * scale).toFloat()
                    drawCircle(color = ringColor, radius = radiusPx, center = centre, style = Stroke(width = 1f))
                    val paintColor = overlayColor.copy(alpha = 0.55f).toArgb()
                    drawContext.canvas.nativeCanvas.drawText(
                        "${radiusMetres.toInt()}m",
                        centre.x + 4f,
                        centre.y - radiusPx - 4f,
                        android.graphics.Paint().apply {
                            color = paintColor
                            textSize = 24f
                        },
                    )
                }

                // Compass rose: only the N tick and the heading cone rotate with the device --
                // everything else on this canvas is either rotation-invariant (rings, self dot)
                // or plotted through the same heading-aware plotPoint as the beacons below.
                val roseRadiusPx = min(size.width, size.height) / 2f - 14f
                val northPoint = bearingToScreen(0f, heading, centre, roseRadiusPx)
                drawContext.canvas.nativeCanvas.drawText(
                    "N",
                    northPoint.x - 6f,
                    northPoint.y + 8f,
                    android.graphics.Paint().apply {
                        color = overlayColor.copy(alpha = 0.8f).toArgb()
                        textSize = 28f
                    },
                )
                if (headingDegrees != null) {
                    // Up is always where the phone points once we have a real heading, so the
                    // cone is a fixed upward wedge -- it never rotates itself, the map rotates
                    // around it. Drawn only when the heading is real: a fake "facing" indicator
                    // on a device with no compass would be exactly the invented number this
                    // screen is not allowed to show.
                    val coneTip = Offset(centre.x, centre.y - 20f)
                    val coneLeft = Offset(centre.x - 8f, centre.y + 6f)
                    val coneRight = Offset(centre.x + 8f, centre.y + 6f)
                    drawContext.canvas.nativeCanvas.drawPath(
                        android.graphics.Path().apply {
                            moveTo(coneTip.x, coneTip.y)
                            lineTo(coneLeft.x, coneLeft.y)
                            lineTo(coneRight.x, coneRight.y)
                            close()
                        },
                        android.graphics.Paint().apply {
                            color = overlayColor.copy(alpha = 0.35f).toArgb()
                            isAntiAlias = true
                        },
                    )
                }

                // Self, at centre.
                drawCircle(color = overlayColor, radius = 8f, center = centre)
                drawCircle(color = overlayColor.copy(alpha = 0.25f), radius = 16f, center = centre)

                plotted.forEach { (beacon, offset) ->
                    val point = plotPoint(offset, maxRangeMetres, centre, scale, heading)
                    val clamped = hypot(offset.first, offset.second) > maxRangeMetres
                    val color = severityColor(beacon.flags.severity)
                    drawCircle(color = color, radius = if (beacon == selected) 12f else 8f, center = point)
                    if (clamped) {
                        // A short ring around the clamped point marking "further than shown"
                        // rather than silently placing it on the boundary unlabelled.
                        drawCircle(color = color, radius = 3f, center = point, style = Stroke(width = 2f))
                    }
                }
            }
        }

        selected?.let { beacon ->
            val distanceMetres = distanceMetres(self, beacon.position)
            Text(
                text = "${beacon.souls} people, ${distanceMetres.roundToInt()}m away",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
        }

        if (headingDegrees == null) {
            Text(
                text = "North up",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Real values only, or an honest statement that there is nothing to show -- a
        // plausible-looking placeholder here would read as a measurement.
        Text(
            text = formatSelfFixLine(selfFix, nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (degraded) {
            Text(
                text = "Position accuracy is poor; bearings are approximate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The single transform shared by drawing and hit-testing. It used to be duplicated (`toScreenPoint`
 * inline in `detectTapGestures` plus a copy in the draw loop); once rotation exists a second copy
 * drifts silently -- invisible in review, obvious the moment someone taps a report on a phone and
 * selects the wrong one.
 */
private fun plotPoint(
    offsetMetres: Pair<Double, Double>,
    maxRangeMetres: Double,
    centre: Offset,
    scale: Float,
    headingDegrees: Float,
): Offset {
    val (dx, dy) = offsetMetres
    val distance = hypot(dx, dy)
    if (distance <= 0.0) return centre
    val clampedDistance = if (distance > maxRangeMetres) maxRangeMetres else distance
    // Bearing from true north, clockwise, in the world frame -- independent of which way the
    // phone happens to be pointing.
    val bearingDegrees = Math.toDegrees(atan2(dx, dy)).toFloat()
    return bearingToScreen(bearingDegrees, headingDegrees, centre, (clampedDistance * scale).toFloat())
}

/**
 * Converts a true-north bearing and a screen radius into a screen point, rotated so that "up"
 * is wherever [headingDegrees] currently points -- the one piece of trigonometry both [plotPoint]
 * (for beacons) and the compass rose's N tick share.
 */
private fun bearingToScreen(bearingDegrees: Float, headingDegrees: Float, centre: Offset, radiusPx: Float): Offset {
    val screenBearingRadians = Math.toRadians((bearingDegrees - headingDegrees).toDouble())
    val screenDx = radiusPx * sin(screenBearingRadians)
    val screenDy = -radiusPx * cos(screenBearingRadians)
    return Offset(centre.x + screenDx.toFloat(), centre.y + screenDy.toFloat())
}

/**
 * Picks the display range from the furthest plotted beacon rather than a fixed 120 m: a cluster
 * of reports 10 m away used to land on top of the centre dot because everything was squeezed into
 * a 120 m circle regardless of how close it actually was. Snapped up to a human-readable step so
 * the ring labels are round numbers, with a 25 m floor so a single very-close report doesn't zoom
 * in to an unreadable few metres.
 */
private fun autoRangeMetres(plotted: List<Pair<SosBeacon, Pair<Double, Double>>>): Double {
    val furthestMetres = plotted.maxOfOrNull { (_, offset) -> hypot(offset.first, offset.second) } ?: 0.0
    return RANGE_STEPS_METRES.firstOrNull { it >= furthestMetres } ?: RANGE_STEPS_METRES.last()
}

/** Real values only. No fix at all is reported as such rather than blank or a fake number. */
internal fun formatSelfFixLine(selfFix: SelfFix?, nowMillis: Long): String {
    if (selfFix == null) return "Your fix: none yet"
    val ageSeconds = ((nowMillis - selfFix.atMillis) / 1000).coerceAtLeast(0)
    val accuracy = selfFix.accuracyMetres?.let { "±${it.roundToInt()} m" } ?: "accuracy unknown"
    return "Your fix: $accuracy · ${ageSeconds}s ago · ${providerLabel(selfFix.provider)}"
}

private fun providerLabel(provider: String): String = when (provider.lowercase()) {
    "gps" -> "GPS"
    "network" -> "Network"
    else -> provider
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

/** Straight-line distance in metres, via the same projection as [relativeOffsetMetres]. */
internal fun distanceMetres(self: GeoPoint, other: GeoPoint): Double {
    val (dx, dy) = relativeOffsetMetres(self, other)
    return hypot(dx, dy)
}

/** True bearing in degrees [0, 360) from [self] to [other]. */
internal fun bearingDegrees(self: GeoPoint, other: GeoPoint): Float {
    val (dx, dy) = relativeOffsetMetres(self, other)
    val degrees = Math.toDegrees(atan2(dx, dy)).toFloat()
    return (degrees + 360f) % 360f
}

/** 16-point compass label for a bearing in degrees. */
internal fun compassPoint(degrees: Float): String {
    val normalized = ((degrees % 360f) + 360f) % 360f
    val index = (normalized / 22.5f).roundToInt() % COMPASS_POINTS.size
    return COMPASS_POINTS[index]
}

private val COMPASS_POINTS = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
)

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.CRITICAL -> Color(0xFFEF5350)
    Severity.HIGH -> Color(0xFFFF7043)
    Severity.MODERATE -> Color(0xFFFFA726)
    Severity.LOW -> Color(0xFF66BB6A)
}

private val RANGE_STEPS_METRES = listOf(25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)
private val MAP_MAX_SIZE = 220.dp
private const val TAP_RADIUS_PX = 28f
