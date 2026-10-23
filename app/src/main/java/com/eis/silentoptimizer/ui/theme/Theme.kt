package com.eis.silentoptimizer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * EIS identity (spec 11): deep charcoal/black surfaces, restrained silver
 * and muted gold accents, sharp typography, high contrast, no decorative
 * animation. The palette is dark-only by design (tactical/system-control
 * aesthetic); system dark mode is always assumed for this utility.
 */

val Charcoal = Color(0xFF0B0B0C)        // base surface
val CharcoalElevated = Color(0xFF141416) // cards
val CharcoalBorder = Color(0xFF26262B)   // hairlines
val Silver = Color(0xFFB8BCC4)           // primary text
val SilverDim = Color(0xFF7E838C)        // secondary text
val MutedGold = Color(0xFFC9A227)        // accent
val MutedGoldDim = Color(0xFF8A6F1C)

// Risk indicators (spec 11: Safe / Caution / High Risk)
val RiskSafe = Color(0xFF4E9A5B)
val RiskCaution = Color(0xFFC9A227)
val RiskHigh = Color(0xFFB4443C)

val StatusGood = Color(0xFF4E9A5B)
val StatusWarn = Color(0xFFC9A227)
val StatusBad = Color(0xFFB4443C)
val StatusOff = Color(0xFF5A5D63)

private val EisColorScheme = darkColorScheme(
    primary = MutedGold,
    onPrimary = Color(0xFF0B0B0C),
    secondary = Silver,
    onSecondary = Charcoal,
    background = Charcoal,
    onBackground = Silver,
    surface = CharcoalElevated,
    onSurface = Silver,
    surfaceVariant = CharcoalElevated,
    onSurfaceVariant = SilverDim,
    outline = CharcoalBorder,
    error = RiskHigh,
    onError = Color.White,
)

private val EisTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = 1.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        letterSpacing = 0.4.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun EISTheme(content: @Composable () -> Unit) {
    // Always dark: the brand and the domain (system control) mandate it.
    isSystemInDarkTheme() // read for completeness; palette is dark-only
    MaterialTheme(
        colorScheme = EisColorScheme,
        typography = EisTypography,
        content = content,
    )
}
