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
import com.setu.mesh.app.ui.PositionConfidence
import com.setu.mesh.app.ui.formatPositionConfidenceLine
import com.setu.mesh.app.ui.positionConfidence
import com.setu.mesh.app.ui.sigmaMetresOrNull
import com.setu.mesh.app.ui.theme.LocalIsDarkTheme
import com.setu.mesh.core.geo.relativeOffsetMetres
import com.setu.mesh.core.model.GeoPoint
import com.setu.mesh.core.model.Severity
import com.setu.mesh.core.model.SosBeacon
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos

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
 * Every plotted beacon also gets an **uncertainty disc** (radius = its combined 1-sigma error,
 * see [com.setu.mesh.app.ui.positionConfidence]) at ~25% alpha under its marker. This is the
 * honest rendering of what a three-phone field test showed: two decent GPS fixes a few metres
 * apart produce a bearing that can genuinely point anywhere across a wide cone, and a solid dot
 * drawn over that is a lie by omission. When two discs overlap, "we cannot tell you which
 * direction" becomes visible without reading a single number.
 *
 * The map is a compass: up is wherever [self]'s device is currently pointing, not north, via
 * [rememberHeadingReading]. On hardware with no usable rotation sensor that call returns a null
 * heading, and the map stays north-up -- labelled as such, never silently wrong.
 */
@Composable
fun RelativeMap(
    self: GeoPoint,
    beacons: List<SosBeacon>,
    onTapBeacon: (SosBeacon) -> Unit,
    nowMillis: Long,
    selfFix: SelfFix?,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<SosBeacon?>(null) }
    val plotted = remember(self, beacons, selfFix, nowMillis) {
        beacons.map { beacon ->
            PlottedBeacon(
                beacon = beacon,
                offsetMetres = relativeOffsetMetres(self, beacon.position),
                confidence = positionConfidence(selfFix, beacon, nowMillis),
            )
        }
    }
    val maxRangeMetres = remember(plotted) { autoRangeMetres(plotted) }
    val headingReading = rememberHeadingReading(self)
    val heading = headingReading.degrees ?: 0f
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
                    .pointerInput(plotted, maxRangeMetres, headingReading.degrees) {
                        detectTapGestures { tap ->
                            val scale = min(size.width, size.height) / 2f / maxRangeMetres.toFloat()
                            val centre = Offset(size.width / 2f, size.height / 2f)
                            val hit = plotted.minByOrNull { candidate ->
                                val p = plotPoint(candidate.offsetMetres, maxRangeMetres, centre, scale, heading)
                                (p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y)
                            }
                            if (hit != null) {
                                val p = plotPoint(hit.offsetMetres, maxRangeMetres, centre, scale, heading)
                                val distSq = (p.x - tap.x) * (p.x - tap.x) + (p.y - tap.y) * (p.y - tap.y)
                                if (distSq <= TAP_RADIUS_PX * TAP_RADIUS_PX) {
                                    selected = hit.beacon
                                    onTapBeacon(hit.beacon)
                                }
                            }
                        }
                    },
            ) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val scale = min(size.width, size.height) / 2f / maxRangeMetres.toFloat()

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
                if (headingReading.degrees != null) {
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

                plotted.forEach { candidate ->
                    val point = plotPoint(candidate.offsetMetres, maxRangeMetres, centre, scale, heading)
                    val clamped = hypot(candidate.offsetMetres.first, candidate.offsetMetres.second) > maxRangeMetres
                    val color = severityColor(candidate.beacon.flags.severity)
                    val isSelected = candidate.beacon == selected

                    // The uncertainty disc: the single most important addition here. Radius is
                    // the real combined 1-sigma error, never invented -- skipped entirely when
                    // that figure isn't known (self fix or sender accuracy missing) rather than
                    // drawn at some plausible-looking default size.
                    candidate.confidence.sigmaMetresOrNull?.let { sigmaMetres ->
                        val discRadiusPx = (sigmaMetres * scale).toFloat()
                        if (discRadiusPx > 0f) {
                            drawCircle(color = color.copy(alpha = 0.25f), radius = discRadiusPx, center = point)
                        }
                    }

                    when (candidate.confidence) {
                        is PositionConfidence.Confident -> {
                            // Direction and distance both check out: the one case that earns a
                            // solid, full-opacity marker.
                            drawCircle(color = color, radius = if (isSelected) 12f else 8f, center = point)
                        }
                        is PositionConfidence.Approximate -> {
                            // Distance is real; direction is not confident enough to emphasise --
                            // a muted, smaller marker rather than the solid dot above.
                            drawCircle(color = color.copy(alpha = 0.6f), radius = if (isSelected) 8f else 5f, center = point)
                        }
                        is PositionConfidence.Unusable -> {
                            // No solid marker implying a precise point or direction -- the disc
                            // above (when a sigma is known at all) is the whole honest story.
                            drawCircle(color = color.copy(alpha = 0.4f), radius = if (isSelected) 6f else 4f, center = point)
                        }
                    }
                    if (clamped) {
                        // A short ring around the clamped point marking "further than shown"
                        // rather than silently placing it on the boundary unlabelled.
                        drawCircle(color = color, radius = 3f, center = point, style = Stroke(width = 2f))
                    }
                }
            }
        }

        selected?.let { beacon ->
            val confidence = positionConfidence(selfFix, beacon, nowMillis)
            val peopleWord = if (beacon.souls == 1) "person" else "people"
            Text(
                text = buildString {
                    append("${beacon.souls} $peopleWord")
                    formatPositionConfidenceLine(confidence)?.let { append(", $it") }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = formatSenderAccuracyLine(beacon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            headingReading.degrees == null -> {
                Text(
                    text = "North up",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            headingReading.needsCalibration -> {
                // An uncalibrated magnetometer used to draw a confidently wrong arrow with no
                // warning at all -- this is the fix, surfacing the sensor's own accuracy status
                // that was previously read and discarded (onAccuracyChanged was a no-op).
                Text(
                    text = "Compass needs calibration — move the phone in a figure 8",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Real values only, or an honest statement that there is nothing to show -- a
        // plausible-looking placeholder here would read as a measurement.
        Text(
            text = formatSelfFixLine(selfFix, nowMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One beacon plus its screen-space offset and trust band, computed once per composition tick
 *  and shared between drawing, hit-testing and auto-ranging so none of the three can drift. */
private data class PlottedBeacon(
    val beacon: SosBeacon,
    val offsetMetres: Pair<Double, Double>,
    val confidence: PositionConfidence,
)

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
 *
 * Accounts for each beacon's uncertainty disc too (distance + its sigma, when known): a large
 * disc clipped off the edge of the canvas would defeat the entire point of drawing it.
 */
private fun autoRangeMetres(plotted: List<PlottedBeacon>): Double {
    val furthestMetres = plotted.maxOfOrNull { candidate ->
        val distance = hypot(candidate.offsetMetres.first, candidate.offsetMetres.second)
        distance + (candidate.confidence.sigmaMetresOrNull ?: 0.0)
    } ?: 0.0
    return RANGE_STEPS_METRES.firstOrNull { it >= furthestMetres } ?: RANGE_STEPS_METRES.last()
}

/** Real values only. No fix at all is reported as such rather than blank or a fake number. */
internal fun formatSelfFixLine(selfFix: SelfFix?, nowMillis: Long): String {
    if (selfFix == null) return "Your fix: none yet"
    val ageSeconds = ((nowMillis - selfFix.atMillis) / 1000).coerceAtLeast(0)
    val accuracy = selfFix.accuracyMetres?.let { "±${it.roundToInt()} m" } ?: "accuracy unknown"
    return "Your fix: $accuracy · ${ageSeconds}s ago · ${providerLabel(selfFix.provider)}"
}

/**
 * The sender's own reported fix quality for the selected beacon, matching docs/PROTOCOL.md §2's
 * class boundaries exactly -- the same wording SosScreen uses for this node's own outgoing
 * beacon (`formatTransmittedPositionLine`), so a responder and a victim read the same phrase for
 * the same underlying figure.
 */
private fun formatSenderAccuracyLine(beacon: SosBeacon): String {
    val accuracyText = when (beacon.positionAccuracyClass) {
        1 -> "≤10 m"
        2 -> "≤30 m"
        3 -> "≤100 m"
        else -> "unknown"
    }
    return "Their fix: $accuracyText (reported)"
}

private fun providerLabel(provider: String): String = when (provider.lowercase()) {
    "gps" -> "GPS"
    "network" -> "Network"
    else -> provider
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.CRITICAL -> Color(0xFFEF5350)
    Severity.HIGH -> Color(0xFFFF7043)
    Severity.MODERATE -> Color(0xFFFFA726)
    Severity.LOW -> Color(0xFF66BB6A)
}

private val RANGE_STEPS_METRES = listOf(25.0, 50.0, 100.0, 250.0, 500.0, 1000.0)
private val MAP_MAX_SIZE = 220.dp
private const val TAP_RADIUS_PX = 28f
