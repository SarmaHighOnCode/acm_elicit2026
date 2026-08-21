package com.setu.mesh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Near-black background chosen for OLED power savings, which ties the UI
// directly to the project's energy thesis: even the screen draws less power.
private val SetuBackground = Color(0xFF0A0A0B)
private val SetuSurface = Color(0xFF141416)
private val SetuSurfaceVariant = Color(0xFF1E1E22)

// Amber/red accents — the SOS UI is designed for darkness and high contrast.
private val SetuAmber = Color(0xFFFFA726)
private val SetuAmberDark = Color(0xFFC77800)
private val SetuRed = Color(0xFFEF5350)
private val SetuRedDark = Color(0xFFB71C1C)
private val SetuGreen = Color(0xFF66BB6A)

private val SetuDarkColors = darkColorScheme(
    primary = SetuAmber,
    onPrimary = Color.Black,
    primaryContainer = SetuAmberDark,
    onPrimaryContainer = Color.White,
    secondary = SetuRed,
    onSecondary = Color.White,
    secondaryContainer = SetuRedDark,
    onSecondaryContainer = Color.White,
    tertiary = SetuGreen,
    onTertiary = Color.Black,
    background = SetuBackground,
    onBackground = Color(0xFFE0E0E0),
    surface = SetuSurface,
    onSurface = Color(0xFFE0E0E0),
    surfaceVariant = SetuSurfaceVariant,
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = SetuRed,
    onError = Color.White,
    outline = Color(0xFF444444),
)

private val SetuTypography = Typography(
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

@Composable
fun SetuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SetuDarkColors,
        typography = SetuTypography,
        content = content,
    )
}
