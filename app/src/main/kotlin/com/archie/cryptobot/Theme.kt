package com.archie.cryptobot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One dark palette for the whole app, in the style of a trading terminal. */
object Palette {
    val Background = Color(0xFF0A0E13)
    val Surface = Color(0xFF121821)
    val SurfaceHigh = Color(0xFF1A222D)
    val Outline = Color(0xFF243040)
    val Text = Color(0xFFE9EEF4)
    val TextMuted = Color(0xFF8592A3)
    val Accent = Color(0xFF3DDC97)
    val Gain = Color(0xFF3DDC97)
    val Loss = Color(0xFFFF5C6C)
    val Warning = Color(0xFFFFB547)
    val Info = Color(0xFF5AA9FF)
    val Purple = Color(0xFFB18CFF)

    /** Chart series colors, in order: benchmark first, the user's strategy last. */
    val Series = listOf(Color(0xFF6B7788), Info, Purple, Accent)

    fun gain(v: Double) = if (v >= 0) Gain else Loss
}

private val Numbers = TextStyle(fontFeatureSettings = "tnum")

private val AppTypography = Typography(
    displaySmall = Numbers.copy(fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = Numbers.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Color(0xFF00281A),
            secondary = Palette.Info,
            background = Palette.Background,
            onBackground = Palette.Text,
            surface = Palette.Surface,
            onSurface = Palette.Text,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.TextMuted,
            surfaceContainer = Palette.Surface,
            surfaceContainerHigh = Palette.SurfaceHigh,
            surfaceContainerHighest = Palette.SurfaceHigh,
            outline = Palette.Outline,
            outlineVariant = Palette.Outline,
            error = Palette.Loss,
        ),
        typography = AppTypography,
        content = content,
    )
}
