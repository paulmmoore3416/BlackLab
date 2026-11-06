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
    // more distinctive choices: aurora teal, magenta, electric indigo, lime, copper, plum, sky, crimson
    0xFF3EE6C4, 0xFFE040FB, 0xFF536DFE, 0xFFB8F15A, 0xFFE0955E, 0xFFD58BE6, 0xFF7CC8FF, 0xFFFF5C6C,
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
    ROSE("Rose", 0xFFF472B6, 0xFFBE185D, 0xFF6EE7A0, 0xFF137535, 0xFFFBBF24, 0xFF975A00),
    FOREST("Forest", 0xFF66BB6A, 0xFF2A732E, 0xFFE07ACB, 0xFF9D2C8A, 0xFFF2C14E, 0xFF836000),
    AMBER("Amber", 0xFFFFB74D, 0xFF9A5200, 0xFF7AA7FF, 0xFF2457C5, 0xFF4DD0C4, 0xFF0F766E),
    OCEAN("Ocean", 0xFF26C6DA, 0xFF00707F, 0xFFFF8A65, 0xFFC2410C, 0xFFB39DFF, 0xFF6D3FD4),
    GRAPHITE("Graphite", 0xFFB0BEC5, 0xFF37474F, 0xFF4DD0E1, 0xFF00707F, 0xFFFFB74D, 0xFF9A5200),
    AURORA("Aurora", 0xFF3EE6C4, 0xFF00796B, 0xFFF06BD4, 0xFFA21C8C, 0xFF9D8CFF, 0xFF5B3FD4),
    CRIMSON("Crimson", 0xFFFF5C6C, 0xFFB3122A, 0xFF5CD6C0, 0xFF0B6E61, 0xFFFFC857, 0xFF8A5A00),
    MIDNIGHT("Midnight", 0xFF8C9EFF, 0xFF3949AB, 0xFFFFD166, 0xFF8A5A00, 0xFF64D8CB, 0xFF00695C),
    SAGE("Sage", 0xFF9CC5A1, 0xFF3F6B47, 0xFFE8A87C, 0xFF9A4A1C, 0xFFA8B8E8, 0xFF3E5BA9),
    COPPER("Copper", 0xFFE0955E, 0xFF8F4513, 0xFF6EC6E6, 0xFF0F5F7F, 0xFFB7D96B, 0xFF4F6B12),
    LIME("Lime", 0xFFB8F15A, 0xFF436E00, 0xFFB18CFF, 0xFF6236C7, 0xFF5CE1E6, 0xFF00727A),
    PLUM("Plum", 0xFFD58BE6, 0xFF8B2F9E, 0xFFFFD27A, 0xFF8A5800, 0xFF7FD6B2, 0xFF0E6E4E),
    SKY("Sky", 0xFF7CC8FF, 0xFF0B66B2, 0xFFFFA07A, 0xFFB2441A, 0xFFA5E887, 0xFF2F6E12);

    fun accent(dark: Boolean) = Color(if (dark) darkAccent else lightAccent)
    fun secondary(dark: Boolean) = Color(if (dark) darkSecondary else lightSecondary)
    fun tertiary(dark: Boolean) = Color(if (dark) darkTertiary else lightTertiary)

    companion object {
        fun of(name: String?, fallback: AccentPalette) = entries.firstOrNull { it.name == name } ?: fallback
    }
}

/**
 * How light mode looks: the page background, the card color and the shades between them. Every
 * style keeps body text at 12:1 or better and borders at 3:1 or better.
 */
enum class LightStyle(
    val label: String,
    val blurb: String,
    val background: Long, val card: Long, val low: Long, val high: Long, val highest: Long,
    val ink: Long, val inkSoft: Long,
    /** How much of the main color washes into the background (0 = none). */
    val wash: Float,
) {
    CLEAN("Clean", "Soft gray page, crisp white cards", 0xFFF2F3F6, 0xFFFFFFFF, 0xFFF9FAFB, 0xFFE9ECF0, 0xFFE1E4E9, 0xFF14161A, 0xFF474D57, 0.02f),
    PURE("Pure white", "Bright white page, outlined cards", 0xFFFFFFFF, 0xFFF5F6F8, 0xFFFFFFFF, 0xFFECEEF1, 0xFFE3E6EA, 0xFF111316, 0xFF434952, 0f),
    WARM("Warm paper", "Cream, easy on the eyes at night", 0xFFF4EFE5, 0xFFFFFCF6, 0xFFF9F5EC, 0xFFEAE3D5, 0xFFE1D9C8, 0xFF1D1A14, 0xFF51493C, 0f),
    COOL("Cool mist", "Blue-gray calm", 0xFFECF0F5, 0xFFFFFFFF, 0xFFF5F7FA, 0xFFE1E6ED, 0xFFD8DFE8, 0xFF121720, 0xFF434C5A, 0f),
    WASH("Accent wash", "Your main color tints the page", 0xFFF4F5F7, 0xFFFFFFFF, 0xFFFAFAFB, 0xFFEAECEF, 0xFFE2E5E9, 0xFF14161A, 0xFF474D57, 0.055f);

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: CLEAN
    }
}

/** The user's color choices: a palette per account, and an optional secondary override ("AUTO" = the palette's complement). */
data class ThemeChoice(
    val personal: AccentPalette = AccentPalette.MINT,
    val professional: AccentPalette = AccentPalette.COBALT,
    val personalSecondary: String = "AUTO",
    val professionalSecondary: String = "AUTO",
    val lightStyle: LightStyle = LightStyle.CLEAN,
    /** Stronger contrast: AAA (7:1) text and accents, darker borders, in light and dark. */
    val highContrast: Boolean = false,
    /** GLASS (aurora backdrop + frosted cards) or SOLID (classic). */
    val visualStyle: String = "GLASS",
    /** AURORA, GRADIENT or PLAIN. */
    val backdrop: String = "AURORA",
    /** 0–100. */
    val intensity: Int = 60,
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
            s.secondaryPersonal, s.secondaryProfessional, LightStyle.of(s.lightStyle), s.highContrast,
            s.visualStyle, s.backdrop, s.backdropIntensity,
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
    return if (dark) dark(pal.accent(true), choice.secondary(profile, true), pal.tertiary(true), choice.secondaryChosen(profile), choice.highContrast)
    else light(pal.accent(false), choice.secondary(profile, false), pal.tertiary(false), choice.lightStyle, choice.highContrast)
}

/** The accent of an account (used for its badge and switcher), in the current light/dark shade. */
@Composable
fun profileAccent(p: Profile): Color = LocalThemeChoice.current.palette(p).accent(LocalBlColors.current.isDark)

data class BlColors(
    val accent: Color, val accentSoft: Color, val warning: Color, val danger: Color, val success: Color, val isDark: Boolean, val highContrast: Boolean = false,
    /** The solid page color — for things that must hide what scrolls beneath them (sticky headers) even when the page itself is see-through. */
    val page: Color = Color(0xFF0E1014),
)

val LocalBlColors = staticCompositionLocalOf { BlColors(Mint, Mint.copy(alpha = 0.15f), Amber, Danger, Success, true) }

private fun mix(c: Color, other: Color, t: Float) = Color(
    c.red * (1 - t) + other.red * t, c.green * (1 - t) + other.green * t, c.blue * (1 - t) + other.blue * t, 1f,
)

/**
 * Dark mode: selections (secondary containers) use a deep tint of the main color, because warm
 * complements turn muddy over near-black. A secondary color you pick yourself is used as chosen.
 * Stronger contrast deepens the background, brightens text and borders and lifts accents to 7:1.
 */
private fun dark(accent0: Color, secondary0: Color, tertiary0: Color, secondaryChosen: Boolean, hc: Boolean = false): ColorScheme {
    val bg = if (hc) Color(0xFF07080A) else Color(0xFF0E1014)
    val target = if (hc) Contrast.AAA else Contrast.AA
    val accent = accent0.ensureContrast(bg, target)
    val secondary = secondary0.ensureContrast(bg, target)
    val tertiary = tertiary0.ensureContrast(bg, target)
    return darkColorScheme(
        primary = accent,
        onPrimary = mix(accent, Color.Black, 0.85f),
        primaryContainer = accent.copy(alpha = 0.22f).compositeOver(bg),
        onPrimaryContainer = mix(accent, Color.White, 0.82f),
        secondary = secondary,
        onSecondary = mix(secondary, Color.Black, 0.85f),
        secondaryContainer = (if (secondaryChosen) secondary.copy(alpha = 0.24f) else accent.copy(alpha = 0.26f)).compositeOver(bg),
        onSecondaryContainer = mix(if (secondaryChosen) secondary else accent, Color.White, 0.82f),
        tertiary = tertiary,
        onTertiary = mix(tertiary, Color.Black, 0.85f),
        tertiaryContainer = tertiary.copy(alpha = 0.20f).compositeOver(bg),
        onTertiaryContainer = mix(tertiary, Color.White, 0.82f),
        background = bg,
        onBackground = if (hc) Color(0xFFF7F8FA) else Color(0xFFE9EBEF),
        surface = bg,
        onSurface = if (hc) Color(0xFFF7F8FA) else Color(0xFFE9EBEF),
        surfaceVariant = Color(0xFF1D2027),
        onSurfaceVariant = if (hc) Color(0xFFCDD2DA) else Color(0xFFA6ADB8),
        surfaceContainerLowest = Color(0xFF0A0B0E),
        surfaceContainerLow = Color(0xFF13161B),
        surfaceContainer = Color(0xFF171A20),
        surfaceContainerHigh = Color(0xFF1D2128),
        surfaceContainerHighest = Color(0xFF242830),
        outline = if (hc) Color(0xFF6E7682) else Color(0xFF4A505B),
        outlineVariant = if (hc) Color(0xFF3A404A) else Color(0xFF262A32),
        error = Danger,
        onError = Color.White,
        inverseSurface = Color(0xFFE8EAED),
        inverseOnSurface = Color(0xFF121418),
        inversePrimary = accent,
        surfaceTint = accent,
    )
}

/**
 * Light mode, built from a [LightStyle]: a calm page color with brighter cards on top, so cards
 * read as raised surfaces. Accents, secondary and tertiary are checked against the page and
 * darkened where needed so text in those colors always meets WCAG AA (AAA with Stronger contrast);
 * borders meet the 3:1 non-text minimum. Containers (selected chips, the navigation highlight) are
 * soft tints with a deep "on" color.
 */
private fun light(accent0: Color, secondary0: Color, tertiary0: Color, style: LightStyle = LightStyle.CLEAN, hc: Boolean = false): ColorScheme {
    val bg = accent0.copy(alpha = style.wash).compositeOver(Color(style.background))
    val card = Color(style.card)
    val target = if (hc) Contrast.AAA else Contrast.AA
    val accent = accent0.ensureContrast(bg, target)
    val secondary = secondary0.ensureContrast(bg, target)
    val tertiary = tertiary0.ensureContrast(bg, target)
    fun wash(c: Long, t: Float = style.wash * 0.6f) = accent0.copy(alpha = t).compositeOver(Color(c))
    val ink = if (hc) Color(0xFF050608) else Color(style.ink)
    val inkSoft = if (hc) mix(Color(style.inkSoft), Color.Black, 0.45f) else Color(style.inkSoft)
    return lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = accent.copy(alpha = 0.15f).compositeOver(card),
        onPrimaryContainer = mix(accent, Color.Black, if (hc) 0.7f else 0.55f),
        secondary = secondary,
        onSecondary = Color.White,
        secondaryContainer = secondary.copy(alpha = 0.16f).compositeOver(card),
        onSecondaryContainer = mix(secondary, Color.Black, if (hc) 0.7f else 0.55f),
        tertiary = tertiary,
        onTertiary = Color.White,
        tertiaryContainer = tertiary.copy(alpha = 0.16f).compositeOver(card),
        onTertiaryContainer = mix(tertiary, Color.Black, if (hc) 0.7f else 0.55f),
        background = bg,
        onBackground = ink,
        surface = bg,
        onSurface = ink,
        surfaceVariant = wash(style.high),
        onSurfaceVariant = inkSoft,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = wash(style.low, style.wash * 0.4f),
        surfaceContainer = card,
        surfaceContainerHigh = wash(style.high),
        surfaceContainerHighest = wash(style.highest),
        outline = if (hc) Color(0xFF5F6670) else Color(0xFF7E858F),
        outlineVariant = if (hc) Color(0xFFAEB4BD) else Color(0xFFD3D7DE),
        error = Color(0xFFC62828).ensureContrast(bg, target),
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
    val solid = when {
        dynamic && dark -> dynamicDarkColorScheme(ctx)
        dynamic -> dynamicLightColorScheme(ctx)
        dark -> dark(accent, secondary, tertiary, choice.secondaryChosen(profile), choice.highContrast)
        else -> light(accent, secondary, tertiary, choice.lightStyle, choice.highContrast)
    }
    // Every color change — palette, secondary, light style, light/dark, contrast — blends over ~half a second
    val scheme = animateScheme(solid)
    val glass = GlassStyle(enabled = choice.visualStyle != "SOLID", backdrop = choice.backdrop, intensity = choice.intensity / 100f, dark = dark)
    val target = if (choice.highContrast) Contrast.AAA else Contrast.AA
    val bl = BlColors(
        accent = scheme.primary,
        accentSoft = scheme.primary.copy(alpha = 0.14f),
        warning = (if (dark) Amber else Color(0xFFB45309)).ensureContrast(solid.surface, target),
        danger = (if (dark) Danger else Color(0xFFC62828)).ensureContrast(solid.surface, target),
        success = (if (dark) Success else Color(0xFF2E7D32)).ensureContrast(solid.surface, target),
        isDark = dark,
        highContrast = choice.highContrast,
        page = scheme.background,
    )
    // With glass on, screens are see-through and the aurora shows behind them
    val drawn = if (glass.enabled) scheme.copy(background = Color.Transparent) else scheme
    androidx.compose.runtime.CompositionLocalProvider(LocalBlColors provides bl, LocalThemeChoice provides choice, LocalGlass provides glass) {
        MaterialTheme(colorScheme = drawn, typography = BlTypography, shapes = BlShapes) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                if (glass.enabled) Backdrop(scheme.background, scheme.primary, scheme.secondary, scheme.tertiary, glass)
                // Root surface so every screen inherits the correct on-background text color
                androidx.compose.material3.Surface(
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = if (glass.enabled) Color.Transparent else scheme.background,
                    contentColor = scheme.onBackground,
                    content = content,
                )
            }
        }
    }
}

/** Animates every color of a scheme toward [target], so theme changes blend instead of snapping. */
@Composable
private fun animateScheme(target: ColorScheme): ColorScheme {
    val spec = Motion.enter<Color>(520)
    @Composable fun a(c: Color) = androidx.compose.animation.animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary), onPrimary = a(target.onPrimary), primaryContainer = a(target.primaryContainer), onPrimaryContainer = a(target.onPrimaryContainer),
        secondary = a(target.secondary), onSecondary = a(target.onSecondary), secondaryContainer = a(target.secondaryContainer), onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary), onTertiary = a(target.onTertiary), tertiaryContainer = a(target.tertiaryContainer), onTertiaryContainer = a(target.onTertiaryContainer),
        background = a(target.background), onBackground = a(target.onBackground), surface = a(target.surface), onSurface = a(target.onSurface),
        surfaceVariant = a(target.surfaceVariant), onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceContainerLowest = a(target.surfaceContainerLowest), surfaceContainerLow = a(target.surfaceContainerLow), surfaceContainer = a(target.surfaceContainer),
        surfaceContainerHigh = a(target.surfaceContainerHigh), surfaceContainerHighest = a(target.surfaceContainerHighest),
        outline = a(target.outline), outlineVariant = a(target.outlineVariant), inversePrimary = a(target.inversePrimary), surfaceTint = a(target.surfaceTint),
    )
}
