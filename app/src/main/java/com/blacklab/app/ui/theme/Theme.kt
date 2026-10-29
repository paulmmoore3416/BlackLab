package com.blacklab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Profile

val Mint = Color(0xFF2BD9A8)
val Cobalt = Color(0xFF4F8CFF)
val Amber = Color(0xFFFFB74D)
val Coral = Color(0xFFFF7A59)
val Violet = Color(0xFFAB7CFF)
val Danger = Color(0xFFEF5350)
val Success = Color(0xFF4CD787)

val Palette: List<Int> = listOf(
    0xFF2BD9A8, 0xFF4F8CFF, 0xFFAB7CFF, 0xFFFF7A59, 0xFFFFB74D, 0xFFFFD54F, 0xFF26C6DA, 0xFFEF5350,
    0xFF81C784, 0xFFF06292, 0xFF9575CD, 0xFF4DB6AC, 0xFFA1887F, 0xFF90A4AE, 0xFF7986CB, 0xFFE57373,
).map { it.toInt() }

fun profileAccent(p: Profile): Color = if (p == Profile.PERSONAL) Mint else Cobalt

data class BlColors(val accent: Color, val accentSoft: Color, val warning: Color, val danger: Color, val success: Color, val isDark: Boolean)

val LocalBlColors = staticCompositionLocalOf { BlColors(Mint, Mint.copy(alpha = 0.15f), Amber, Danger, Success, true) }

private fun dark(accent: Color): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF00140E),
    primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF0E1014)),
    onPrimaryContainer = Color(0xFFE6FFF6),
    secondary = Violet,
    onSecondary = Color(0xFF14002E),
    secondaryContainer = Color(0xFF2A2140),
    onSecondaryContainer = Color(0xFFEDE3FF),
    tertiary = Amber,
    onTertiary = Color(0xFF2A1700),
    tertiaryContainer = Color(0xFF3A2A12),
    onTertiaryContainer = Color(0xFFFFE6C4),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE9EBEF),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE9EBEF),
    surfaceVariant = Color(0xFF1D2027),
    onSurfaceVariant = Color(0xFFA6ADB8),
    surfaceContainerLowest = Color(0xFF0A0B0E),
    surfaceContainerLow = Color(0xFF13161B),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF1D2128),
    surfaceContainerHighest = Color(0xFF242830),
    outline = Color(0xFF363B44),
    outlineVariant = Color(0xFF22262D),
    error = Danger,
    onError = Color.White,
    inverseSurface = Color(0xFFE8EAED),
    inverseOnSurface = Color(0xFF121418),
)

private fun light(accent: Color): ColorScheme = lightColorScheme(
    primary = if (accent == Mint) Color(0xFF008F6B) else Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.18f).compositeOver(Color.White),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF6D4AD1),
    tertiary = Color(0xFFB26A00),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF15171B),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF15171B),
    surfaceVariant = Color(0xFFE6E8EC),
    onSurfaceVariant = Color(0xFF545A63),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainer = Color(0xFFF0F2F5),
    surfaceContainerHigh = Color(0xFFEAECF0),
    surfaceContainerHighest = Color(0xFFE3E6EA),
    outline = Color(0xFFC8CDD5),
    outlineVariant = Color(0xFFE3E6EB),
    error = Color(0xFFD32F2F),
)

private fun Color.compositeOver(bg: Color): Color {
    val a = alpha
    return Color(red * a + bg.red * (1 - a), green * a + bg.green * (1 - a), blue * a + bg.blue * (1 - a), 1f)
}

/** Rounder, softer corners everywhere (chips, fields, cards, dialogs, sheets). */
private val BlShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(26.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

private val BlTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(lineHeight = 25.sp, letterSpacing = 0.2.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 21.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.05.sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val MonoDigits = TextStyle(fontFeatureSettings = "tnum")

/** Shared motion: gentle ease-out for screen and content transitions. */
object Motion {
    val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> enter(ms: Int = 360) = androidx.compose.animation.core.tween<T>(ms, easing = ease)
    fun <T> exit(ms: Int = 220) = androidx.compose.animation.core.tween<T>(ms, easing = androidx.compose.animation.core.FastOutLinearInEasing)
}

@Composable
fun BlackLabTheme(themeMode: String, dynamic: Boolean, profile: Profile, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    val accent = profileAccent(profile)
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> dark(accent)
        else -> light(accent)
    }
    val bl = BlColors(
        accent = scheme.primary,
        accentSoft = scheme.primary.copy(alpha = 0.14f),
        warning = Amber,
        danger = if (dark) Danger else Color(0xFFD32F2F),
        success = if (dark) Success else Color(0xFF2E7D32),
        isDark = dark,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalBlColors provides bl) {
        MaterialTheme(colorScheme = scheme, typography = BlTypography, shapes = BlShapes) {
            // Root surface so every screen inherits the correct background and on-background text color
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                color = scheme.background,
                contentColor = scheme.onBackground,
                content = content,
            )
        }
    }
}
