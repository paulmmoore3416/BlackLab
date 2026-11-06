package com.blacklab.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG contrast math on plain ARGB ints (unit tested on the JVM). [ensure] nudges a color's
 * lightness — keeping its hue and saturation — until it reaches a target contrast against a
 * background, so a bright accent made for dark mode still reads as text on a light screen.
 */
object Contrast {
    const val AA = 4.5
    const val AAA = 7.0
    /** Borders and other non-text UI (WCAG 1.4.11). */
    const val UI = 3.0

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Returns [fg] if it already meets [target] on [bg]; otherwise the nearest shade of it that does. */
    fun ensure(fg: Int, bg: Int, target: Double = AA): Int {
        if (ratio(fg, bg) >= target) return fg
        val hsl = toHsl(fg)
        val darken = luminance(bg) > 0.18 // light background → go darker, dark background → go lighter
        var l = hsl[2]
        repeat(100) {
            l = if (darken) l - 0.01f else l + 0.01f
            if (l <= 0f || l >= 1f) return if (darken) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val c = fromHsl(hsl[0], hsl[1], l, fg ushr 24)
            if (ratio(c, bg) >= target) return c
        }
        return if (darken) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    fun toHsl(argb: Int): FloatArray {
        val r = ((argb shr 16) and 0xFF) / 255f; val g = ((argb shr 8) and 0xFF) / 255f; val b = (argb and 0xFF) / 255f
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val l = (mx + mn) / 2f
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> ((g - b) / d + (if (g < b) 6f else 0f)); g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        return floatArrayOf(h, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 0xFF): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f); h < 120f -> Triple(x, c, 0f); h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c); h < 300f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}

fun Color.ensureContrast(bg: Color, target: Double = Contrast.AA): Color = Color(Contrast.ensure(toArgb(), bg.toArgb(), target))

/**
 * This color as text or an icon on the current surface: unchanged when it already reads well,
 * otherwise the nearest shade that meets WCAG AA (AAA with Stronger contrast on).
 */
@Composable
@ReadOnlyComposable
fun Color.readable(): Color {
    if (this == Color.Unspecified) return this
    val target = if (LocalBlColors.current.highContrast) Contrast.AAA else Contrast.AA
    return ensureContrast(MaterialTheme.colorScheme.surface, target)
}
