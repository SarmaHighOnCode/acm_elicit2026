package com.setu.mesh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Dark mode colours ────────────────────────────────────────────────────────
// Near-black background chosen for OLED power savings, which ties the UI
// directly to the project's energy thesis: even the screen draws less power.
private val DarkBackground = Color(0xFF0A0A0B)
private val DarkSurface = Color(0xFF141416)
private val DarkSurfaceVariant = Color(0xFF1E1E22)

// ── Light mode colours (neumorphic pastel palette from design.md) ─────────────
// Soft grey base with neumorphic depth — lighter face, darker shadow.
private val LightBackground = Color(0xFFE8E8E8)   // design.md: Soft Grey
private val LightSurface = Color(0xFFF0F0F3)       // slightly elevated surface
private val LightSurfaceVariant = Color(0xFFDCDCE0) // recessed variant

// ── Emergency accent colours — same in both modes ────────────────────────────
// These are safety-critical; saturation/contrast must never be compromised.
private val Amber = Color(0xFFFFA726)
private val AmberDark = Color(0xFFC77800)
private val Red = Color(0xFFEF5350)
private val RedDark = Color(0xFFB71C1C)
private val Green = Color(0xFF66BB6A)

private val SafeHopDarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color.Black,
    primaryContainer = AmberDark,
    onPrimaryContainer = Color.White,
    secondary = Red,
    onSecondary = Color.White,
    secondaryContainer = RedDark,
    onSecondaryContainer = Color.White,
    tertiary = Green,
    onTertiary = Color.Black,
    background = DarkBackground,
    onBackground = Color(0xFFE0E0E0),
    surface = DarkSurface,
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Red,
    onError = Color.White,
    outline = Color(0xFF444444),
)

// Light scheme: neumorphic pastels from design.md.
// Emergency accents (amber/red/green) unchanged — safety colours must hold in any mode.
// No pure black (#000000) per design doc rules — use off-black (#1A1A1A).
private val SafeHopLightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFFFFF3E0),  // warm tint for amber container
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Red,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEBEE),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Green,
    onTertiary = Color(0xFF1A1A1A),
    background = LightBackground,
    onBackground = Color(0xFF1A1A1A),
    surface = LightSurface,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF555555),
    error = Red,
    onError = Color.White,
    outline = Color(0xFFBBBBBB),
)

// ── Typography ───────────────────────────────────────────────────────────────
// System UI stack matches design.md: -apple-system equivalent on Android is the
// default FontFamily which resolves to the system sans-serif.
private val SafeHopTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
)

/**
 * Composition local that canvas-based components can read to choose
 * drawing colours that work against the current background. Components
 * that only use MaterialTheme.colorScheme tokens don't need this.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

/** Corner-radius scale from `docs/design.md`'s `rounded` tokens (sm/md). Reused by [neumorphic]. */
object SafeHopShapes {
    val cornerSmall: Dp = 14.dp
    val cornerLarge: Dp = 28.dp
}

@Composable
fun SetuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) SafeHopDarkColors else SafeHopLightColors,
            typography = SafeHopTypography,
            content = content,
        )
    }
}
