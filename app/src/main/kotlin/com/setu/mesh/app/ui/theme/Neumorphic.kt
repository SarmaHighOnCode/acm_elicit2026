package com.setu.mesh.app.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Neumorphic soft-shadow accent, per `docs/design.md`: "the technique survived as an accent...
 * It works best in controlled doses." This app uses it on secondary surfaces only -- triage
 * cards, the counter stepper, toggle rows, section containers -- and never on the SOS button,
 * severity selection state, `StatusLadder`, `TierBadge`, or the SIMULATED banner. Those stay flat
 * and saturated on purpose: neumorphism encodes state as a subtle shadow difference, which is the
 * wrong signal for anything read at 4% battery, one-handed, possibly in darkness or water.
 *
 * A real blurred shadow needs either a software-rendered canvas (`BlurMaskFilter`, deprecated and
 * inconsistent under hardware acceleration) or `RenderEffect`/`BlurEffect`, which is API 31+ --
 * this app's `minSdk` is 26. So this fakes the blur by layering a handful of translucent rounded
 * rects with shrinking alpha, offset toward each shadow's light source. Cheap, deterministic, and
 * correct on every API level the app ships to.
 *
 * Draw order matters at the call site: apply this modifier *before* `.background(...)`, e.g.
 * `Modifier.neumorphic(...).background(color, shape)`. `drawBehind` paints under the element's
 * own content but is not clipped to its bounds, so the shadow layers extend past the edges as a
 * halo while the opaque background on top hides the inner portion -- that halo is the visible
 * "soft shadow".
 */
fun Modifier.neumorphic(
    cornerRadius: Dp = SafeHopShapes.cornerSmall,
    elevation: Dp = 6.dp,
    pressed: Boolean = false,
): Modifier = composed {
    val isDark = LocalIsDarkTheme.current

    // Pressing collapses the shadow toward zero rather than swapping to a separate "pressed"
    // visual language -- a soft-touch button compressing, not a colour change.
    val animatedElevation by animateDpAsState(
        targetValue = if (pressed) 0.dp else elevation,
        animationSpec = tween(durationMillis = NEUMORPHIC_PRESS_DURATION_MS),
        label = "neumorphicElevation",
    )

    // Dark mode's near-black OLED background (#0A0A0B) can't carry a lighter-than-surface
    // highlight the way a light pastel surface can, so the "light source" shadow there is a
    // faint lift rather than a bright one -- see Theme.kt's dark-palette comment.
    val lightColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.75f)
    val darkColor = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.16f)

    drawBehind {
        val elevationPx = animatedElevation.toPx()
        if (elevationPx <= 0f) return@drawBehind
        val radiusPx = cornerRadius.toPx()
        drawSoftShadow(direction = Offset(1f, 1f), cornerRadiusPx = radiusPx, elevationPx = elevationPx, color = darkColor)
        drawSoftShadow(direction = Offset(-1f, -1f), cornerRadiusPx = radiusPx, elevationPx = elevationPx, color = lightColor)
    }
}

/** Layers several grown, fading rounded rects toward [direction] to approximate a blurred shadow. */
private fun DrawScope.drawSoftShadow(direction: Offset, cornerRadiusPx: Float, elevationPx: Float, color: Color) {
    for (layer in 1..NEUMORPHIC_SHADOW_LAYERS) {
        val t = layer.toFloat() / NEUMORPHIC_SHADOW_LAYERS
        val grow = elevationPx * t
        val alpha = (color.alpha * (1f - t * 0.7f) / NEUMORPHIC_SHADOW_LAYERS).coerceIn(0f, 1f)
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(-grow, -grow) + direction * (elevationPx * t),
            size = Size(size.width + grow * 2, size.height + grow * 2),
            cornerRadius = CornerRadius(cornerRadiusPx + grow, cornerRadiusPx + grow),
        )
    }
}

private const val NEUMORPHIC_SHADOW_LAYERS = 4
private const val NEUMORPHIC_PRESS_DURATION_MS = 150
