package com.blacklab.app.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The glass design language: an "aurora" backdrop made from the account palette (main, secondary
 * and tertiary colors drifting slowly behind every screen) with frosted, see-through cards and bars
 * on top. [enabled] = false is the classic solid look.
 */
data class GlassStyle(
    val enabled: Boolean = true,
    /** AURORA (slowly moving), GRADIENT (still) or PLAIN (no color field). */
    val backdrop: String = "AURORA",
    /** 0–1: how strongly the palette colors show in the backdrop. */
    val intensity: Float = 0.6f,
    val dark: Boolean = true,
) {
    val animated: Boolean get() = enabled && backdrop == "AURORA" && !Motion.reduced

    /** Card fill: frosted white in light mode, a faint lift in dark mode. */
    fun cardFill(tint: Color? = null): Color {
        val base = if (dark) Color.White.copy(alpha = 0.065f) else Color.White.copy(alpha = 0.68f)
        return if (tint == null) base else tint.copy(alpha = if (dark) 0.10f else 0.07f).over(base)
    }

    /** A light-catching edge: bright at the top-left, fading toward the bottom-right. */
    fun edge(tint: Color? = null): Brush {
        val hi = tint?.copy(alpha = if (dark) 0.55f else 0.65f) ?: Color.White.copy(alpha = if (dark) 0.20f else 0.95f)
        val lo = tint?.copy(alpha = 0.14f) ?: (if (dark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.06f))
        return Brush.linearGradient(listOf(hi, lo))
    }

    /** Bars (top app bar when scrolled, navigation bar, drawer) over the backdrop. */
    fun barFill(surface: Color, alpha: Float = 0.82f): Color = surface.copy(alpha = if (dark) alpha - 0.06f else alpha)
}

val LocalGlass = staticCompositionLocalOf { GlassStyle(enabled = false) }

private fun Color.over(bg: Color): Color {
    val a = alpha + bg.alpha * (1 - alpha)
    if (a <= 0f) return Color.Transparent
    return Color((red * alpha + bg.red * bg.alpha * (1 - alpha)) / a, (green * alpha + bg.green * bg.alpha * (1 - alpha)) / a, (blue * alpha + bg.blue * bg.alpha * (1 - alpha)) / a, a)
}

/**
 * The aurora: four soft color fields in the palette colors over the page color. They drift on slow
 * Lissajous paths (a full cycle takes about half a minute) and breathe a little. With Reduce
 * motion, or the Gradient backdrop, they hold still. Only the draw phase runs each frame.
 */
@Composable
fun Backdrop(page: Color, accent: Color, secondary: Color, tertiary: Color, style: GlassStyle, modifier: Modifier = Modifier.fillMaxSize()) {
    val phase: State<Float> = if (style.animated) {
        rememberInfiniteTransition(label = "aurora").animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    } else remember { mutableFloatStateOf(0.18f) }
    val k = style.intensity.coerceIn(0f, 1f)
    val strength = if (style.dark) 0.34f else 0.42f
    Canvas(modifier) {
        drawRect(page)
        if (style.backdrop == "PLAIN" || k == 0f) return@Canvas
        val t = phase.value * 2f * PI.toFloat()
        val w = size.width; val h = size.height; val r = max(w, h)
        fun blob(c: Color, a: Float, cx: Float, cy: Float, radius: Float) {
            val center = Offset(cx, cy)
            drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * k * strength), c.copy(alpha = 0f)), center, radius), radius, center)
        }
        blob(accent, 1.0f, w * (0.18f + 0.22f * sin(t)), h * (0.10f + 0.10f * cos(t * 2f)), r * (0.62f + 0.05f * sin(t * 3f)))
        blob(secondary, 0.8f, w * (0.92f + 0.10f * cos(t)), h * (0.42f + 0.14f * sin(t + 1.2f)), r * (0.55f + 0.05f * cos(t * 2f)))
        blob(tertiary, 0.75f, w * (0.25f + 0.20f * sin(t * 2f + 2.1f)), h * (0.86f + 0.08f * cos(t + 0.6f)), r * (0.58f + 0.04f * sin(t + 1f)))
        blob(accent, 0.5f, w * (0.70f + 0.18f * cos(t * 2f + 0.8f)), h * (0.70f + 0.10f * sin(t * 2f)), r * 0.40f)
        // A soft veil keeps body text calm over the brightest part of the field
        drawRect(Brush.verticalGradient(listOf(page.copy(alpha = 0f), page.copy(alpha = if (style.dark) 0.25f else 0.35f))))
    }
}
