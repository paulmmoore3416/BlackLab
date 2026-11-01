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

/**
 * Accent palettes. Each pairs a main color with a complementary secondary and a third (tertiary)
 * color, in a light-mode and a dark-mode shade. Every light shade has at least 4.5:1 contrast on
 * white and every dark shade on the dark background (WCAG AA), so text and icons stay readable.
 */
enum class AccentPalette(
    val label: String,
    val darkAccent: Long, val lightAccent: Long,
    val darkSecondary: Long, val lightSecondary: Long,
    val darkTertiary: Long, val lightTertiary: Long,
) {
    MINT("Mint", 0xFF2BD9A8, 0xFF00785A, 0xFFFF8A70, 0xFFC2410C, 0xFF8C9EFF, 0xFF4F46E5),
    COBALT("Cobalt", 0xFF4F8CFF, 0xFF1D5FD6, 0xFFFFB74D, 0xFFA3500B, 0xFF4DD0C4, 0xFF0F766E),
    VIOLET("Violet", 0xFFAB7CFF, 0xFF6D3FD4, 0xFFE6C84A, 0xFF806200, 0xFF4DD0E1, 0xFF0E7490),
    CORAL("Coral", 0xFFFF7A59, 0xFFC2410C, 0xFF2BCFC1, 0xFF0F766E, 0xFF8C9EFF, 0xFF4338CA),
    ROSE("Rose", 0xFFF472B6, 0xFFBE185D, 0xFF6EE7A0, 0xFF15803D, 0xFFFBBF24, 0xFF975A00),
    FOREST("Forest", 0xFF66BB6A, 0xFF2E7D32, 0xFFE07ACB, 0xFF9D2C8A, 0xFFF2C14E, 0xFF836000),
    AMBER("Amber", 0xFFFFB74D, 0xFF9A5200, 0xFF7AA7FF, 0xFF2457C5, 0xFF4DD0C4, 0xFF0F766E),
    OCEAN("Ocean", 0xFF26C6DA, 0xFF00707F, 0xFFFF8A65, 0xFFC2410C, 0xFFB39DFF, 0xFF6D3FD4),
    GRAPHITE("Graphite", 0xFFB0BEC5, 0xFF37474F, 0xFF4DD0E1, 0xFF00707F, 0xFFFFB74D, 0xFF9A5200);

    fun accent(dark: Boolean) = Color(if (dark) darkAccent else lightAccent)
    fun secondary(dark: Boolean) = Color(if (dark) darkSecondary else lightSecondary)
    fun tertiary(dark: Boolean) = Color(if (dark) darkTertiary else lightTertiary)

    companion object {
        fun of(name: String?, fallback: AccentPalette) = entries.firstOrNull { it.name == name } ?: fallback
    }
}

/** The user's color choices: a palette per account, and an optional secondary override ("AUTO" = the palette's complement). */
data class ThemeChoice(
    val personal: AccentPalette = AccentPalette.MINT,
    val professional: AccentPalette = AccentPalette.COBALT,
    val personalSecondary: String = "AUTO",
    val professionalSecondary: String = "AUTO",
) {
    fun palette(p: Profile) = if (p == Profile.PERSONAL) personal else professional
    fun secondaryChosen(p: Profile) = (if (p == Profile.PERSONAL) personalSecondary else professionalSecondary) != "AUTO"
    fun secondary(p: Profile, dark: Boolean): Color {
        val pick = if (p == Profile.PERSONAL) personalSecondary else professionalSecondary
        val over = AccentPalette.entries.firstOrNull { it.name == pick }
        return over?.accent(dark) ?: palette(p).secondary(dark)
    }

    companion object {
        fun from(s: com.blacklab.app.core.Settings) = ThemeChoice(
            AccentPalette.of(s.accentPersonal, AccentPalette.MINT), AccentPalette.of(s.accentProfessional, AccentPalette.COBALT),
            s.secondaryPersonal, s.secondaryProfessional,
        )
    }
}

val LocalThemeChoice = staticCompositionLocalOf { ThemeChoice() }

/**
 * The theme mode to draw with right now: "Sunset to sunrise" and "Custom hours" resolve to DARK or
 * LIGHT and re-check every minute, so the app switches over while you're using it.
 */
@Composable
fun rememberThemeMode(s: com.blacklab.app.core.Settings): String {
    if (s.themeMode != com.blacklab.app.core.AutoTheme.AUTO && s.themeMode != com.blacklab.app.core.AutoTheme.SCHEDULE) return s.themeMode
    val ctx = LocalContext.current
    val now = com.blacklab.app.ui.components.rememberNow()
    val sun = androidx.compose.runtime.produceState<com.blacklab.app.core.AutoTheme.SunTimes?>(null, now.toLocalDate(), s.themeMode) {
        value = if (s.themeMode != com.blacklab.app.core.AutoTheme.AUTO) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.blacklab.app.core.AutoTheme.location(ctx)?.let { (lat, lon) -> com.blacklab.app.core.AutoTheme.sunTimes(now.toLocalDate(), lat, lon, java.time.ZoneId.systemDefault()) }
        }
    }.value
    return com.blacklab.app.core.AutoTheme.resolve(s.themeMode, now, sun, s.themeDarkFrom, s.themeDarkTo)
}

/** The full color scheme for an account outside Compose screens (home-screen widgets). */
fun colorSchemeFor(choice: ThemeChoice, profile: Profile, dark: Boolean): ColorScheme {
    val pal = choice.palette(profile)
    return if (dark) dark(pal.accent(true), choice.secondary(profile, true), pal.tertiary(true), choice.secondaryChosen(profile))
    else light(pal.accent(false), choice.secondary(profile, false), pal.tertiary(false))
}

/** The accent of an account (used for its badge and switcher), in the current light/dark shade. */
@Composable
fun profileAccent(p: Profile): Color = LocalThemeChoice.current.palette(p).accent(LocalBlColors.current.isDark)

data class BlColors(val accent: Color, val accentSoft: Color, val warning: Color, val danger: Color, val success: Color, val isDark: Boolean)

val LocalBlColors = staticCompositionLocalOf { BlColors(Mint, Mint.copy(alpha = 0.15f), Amber, Danger, Success, true) }

private fun mix(c: Color, other: Color, t: Float) = Color(
    c.red * (1 - t) + other.red * t, c.green * (1 - t) + other.green * t, c.blue * (1 - t) + other.blue * t, 1f,
)

/**
 * Dark mode: selections (secondary containers) use a deep tint of the main color, because warm
 * complements turn muddy over near-black. A secondary color you pick yourself is used as chosen.
 */
private fun dark(accent: Color, secondary: Color, tertiary: Color, secondaryChosen: Boolean): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = mix(accent, Color.Black, 0.85f),
    primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Color(0xFF0E1014)),
    onPrimaryContainer = mix(accent, Color.White, 0.82f),
    secondary = secondary,
    onSecondary = mix(secondary, Color.Black, 0.85f),
    secondaryContainer = (if (secondaryChosen) secondary.copy(alpha = 0.24f) else accent.copy(alpha = 0.26f)).compositeOver(Color(0xFF0E1014)),
    onSecondaryContainer = mix(if (secondaryChosen) secondary else accent, Color.White, 0.82f),
    tertiary = tertiary,
    onTertiary = mix(tertiary, Color.Black, 0.85f),
    tertiaryContainer = tertiary.copy(alpha = 0.20f).compositeOver(Color(0xFF0E1014)),
    onTertiaryContainer = mix(tertiary, Color.White, 0.82f),
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
    inversePrimary = accent,
    surfaceTint = accent,
)

/**
 * Light mode: the accent, its complementary secondary and the tertiary each get a soft container
 * (selected chips, the navigation highlight, badges) and a deep "on" color, and the neutral
 * surfaces carry a faint tint of the accent so the whole screen feels of a piece.
 */
private fun light(accent: Color, secondary: Color, tertiary: Color): ColorScheme {
    val base = Color(0xFFF7F8FA)
    fun tint(t: Float, over: Color = base) = accent.copy(alpha = t).compositeOver(over)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.16f).compositeOver(Color.White),
        onPrimaryContainer = mix(accent, Color.Black, 0.55f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondary.copy(alpha = 0.16f).compositeOver(Color.White),
        onSecondaryContainer = mix(secondary, Color.Black, 0.55f),
        tertiary = tertiary,
        onTertiary = Color.White,
        tertiaryContainer = tertiary.copy(alpha = 0.16f).compositeOver(Color.White),
        onTertiaryContainer = mix(tertiary, Color.Black, 0.55f),
        background = tint(0.03f),
        onBackground = Color(0xFF15171B),
        surface = tint(0.03f),
        onSurface = Color(0xFF15171B),
        surfaceVariant = tint(0.06f, Color(0xFFE6E8EC)),
        onSurfaceVariant = Color(0xFF4F5560),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = tint(0.025f, Color(0xFFFBFBFC)),
        surfaceContainer = tint(0.04f, Color(0xFFF0F2F5)),
        surfaceContainerHigh = tint(0.05f, Color(0xFFEAECF0)),
        surfaceContainerHighest = tint(0.06f, Color(0xFFE3E6EA)),
        outline = Color(0xFFC3C8D0),
        outlineVariant = Color(0xFFE1E4E9),
        error = Color(0xFFD32F2F),
        inverseSurface = Color(0xFF2A2D33),
        inverseOnSurface = Color(0xFFF1F2F4),
        inversePrimary = mix(accent, Color.White, 0.35f),
        surfaceTint = accent,
    )
}

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

/**
 * Inter (variable, SIL OFL; license in assets/licenses) at real weights, with tabular figures
 * everywhere so times, counts and timers don't shift as their digits change.
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val InterFamily = androidx.compose.ui.text.font.FontFamily(
    listOf(300, 400, 500, 600, 700).map { w ->
        androidx.compose.ui.text.font.Font(
            com.blacklab.app.R.font.inter, FontWeight(w),
            variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(w)),
        )
    },
)

private fun TextStyle.bl() = copy(fontFamily = InterFamily)
/** Display sizes are mostly numbers (timers, big counts): tabular figures so they don't shift. */
private fun TextStyle.blNum() = copy(fontFamily = InterFamily, fontFeatureSettings = "tnum")

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
    ).let { c ->
        c.copy(
            displayLarge = c.displayLarge.blNum(), displayMedium = c.displayMedium.blNum(), displaySmall = c.displaySmall.blNum(),
            headlineLarge = c.headlineLarge.bl(), headlineMedium = c.headlineMedium.bl(), headlineSmall = c.headlineSmall.bl(),
            titleLarge = c.titleLarge.bl(), titleMedium = c.titleMedium.bl(), titleSmall = c.titleSmall.bl(),
            bodyLarge = c.bodyLarge.bl(), bodyMedium = c.bodyMedium.bl(), bodySmall = c.bodySmall.bl(),
            labelLarge = c.labelLarge.bl(), labelMedium = c.labelMedium.bl(), labelSmall = c.labelSmall.bl(),
        )
    }
}

/** For live numbers (timers, positions, counters) in body text: Inter with tabular figures. */
val MonoDigits = TextStyle(fontFamily = InterFamily, fontFeatureSettings = "tnum")

/** Shared motion: gentle ease-out for screen and content transitions. */
object Motion {
    /** Set from Settings › Reduce motion and the system "Remove animations" switch; animations become instant. */
    @Volatile var reduced: Boolean = false
    val ease = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Emphasized decelerate (Material 3) for things arriving on screen. */
    val emphasized = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    fun <T> enter(ms: Int = 360) = androidx.compose.animation.core.tween<T>(if (reduced) 0 else ms, easing = ease)
    fun <T> exit(ms: Int = 220) = androidx.compose.animation.core.tween<T>(if (reduced) 0 else ms, easing = androidx.compose.animation.core.FastOutLinearInEasing)
    fun <T> emphasized(ms: Int = 420) = androidx.compose.animation.core.tween<T>(if (reduced) 0 else ms, easing = emphasized)
    /** Springy settle for small things (checks, chips, icons). */
    fun <T> settle() = if (reduced) androidx.compose.animation.core.snap<T>() else androidx.compose.animation.core.spring<T>(dampingRatio = 0.72f, stiffness = 520f)
}

@Composable
fun BlackLabTheme(themeMode: String, dynamic: Boolean, profile: Profile, choice: ThemeChoice = ThemeChoice(), content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    val palette = choice.palette(profile)
    val accent = palette.accent(dark)
    val secondary = choice.secondary(profile, dark)
    val tertiary = palette.tertiary(dark)
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> dark(accent, secondary, tertiary, choice.secondaryChosen(profile))
        else -> light(accent, secondary, tertiary)
    }
    val bl = BlColors(
        accent = scheme.primary,
        accentSoft = scheme.primary.copy(alpha = 0.14f),
        warning = if (dark) Amber else Color(0xFFB45309),
        danger = if (dark) Danger else Color(0xFFD32F2F),
        success = if (dark) Success else Color(0xFF2E7D32),
        isDark = dark,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalBlColors provides bl, LocalThemeChoice provides choice) {
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
