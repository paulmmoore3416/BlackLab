package com.blacklab.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Light & color adjustments, each −1…1 (0 = unchanged). */
data class Adjust(
    val exposure: Float = 0f, val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val warmth: Float = 0f, val tint: Float = 0f, val fade: Float = 0f, val vignette: Float = 0f,
) {
    val isIdentity get() = this == Adjust()

    fun matrix(): ColorMatrix {
        val m = ColorMatrix()
        // exposure: multiplicative gain (±1 stop)
        val e = Math.pow(2.0, exposure.toDouble()).toFloat()
        m.postConcat(ColorMatrix(floatArrayOf(e, 0f, 0f, 0f, 0f, 0f, e, 0f, 0f, 0f, 0f, 0f, e, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        // contrast around mid-grey, plus brightness offset
        val c = 1f + contrast * 0.8f
        val b = brightness * 60f + 128f * (1 - c)
        m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, b, 0f, c, 0f, 0f, b, 0f, 0f, c, 0f, b, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(ColorMatrix().apply { setSaturation(1f + saturation) })
        // warmth (blue↔amber) and tint (green↔magenta)
        val w = warmth * 24f; val t = tint * 20f
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w + t * 0.5f, 0f, 1f, 0f, 0f, -t, 0f, 0f, 1f, 0f, -w + t * 0.5f, 0f, 0f, 0f, 1f, 0f)))
        // fade lifts the blacks for a matte look
        if (fade > 0f) { val f = 1f - fade * 0.25f; val o = fade * 40f; m.postConcat(ColorMatrix(floatArrayOf(f, 0f, 0f, 0f, o, 0f, f, 0f, 0f, o, 0f, 0f, f, 0f, o, 0f, 0f, 0f, 1f, 0f))) }
        return m
    }

    fun apply(src: Bitmap): Bitmap {
        if (isIdentity) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix()) })
        if (vignette != 0f) {
            val cx = out.width / 2f; val cy = out.height / 2f; val r = max(cx, cy) * 1.25f
            val edge = if (vignette > 0) (vignette * 200).toInt() shl 24 else ((-vignette * 160).toInt() shl 24) or 0xFFFFFF
            c.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), Paint().apply {
                shader = RadialGradient(cx, cy, r, intArrayOf(0x00000000, 0x00000000, edge), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            })
        }
        return out
    }
}

/** Crop / rotate / flip / straighten. [crop] is normalized to the rotated, straightened picture. */
data class Geometry(
    val rotate: Int = 0, val flipH: Boolean = false, val flipV: Boolean = false, val straighten: Float = 0f,
    val crop: RectF = RectF(0f, 0f, 1f, 1f),
) {
    val isIdentity get() = rotate == 0 && !flipH && !flipV && straighten == 0f && crop == RectF(0f, 0f, 1f, 1f)

    /** Width/height ratio of the picture after rotation (before crop). */
    fun rotatedAspect(w: Int, h: Int) = if (rotate % 180 == 0) w.toFloat() / h else h.toFloat() / w

    fun apply(src: Bitmap, withCrop: Boolean = true): Bitmap {
        var b = src
        if (rotate != 0 || flipH || flipV) {
            val m = Matrix().apply { postRotate(rotate.toFloat()); postScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f) }
            b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
        if (straighten != 0f) {
            val w = b.width.toFloat(); val h = b.height.toFloat()
            val a = Math.toRadians(abs(straighten).toDouble())
            val s = max((w * cos(a) + h * sin(a)) / w, (w * sin(a) + h * cos(a)) / h).toFloat()
            val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(b, Matrix().apply { postTranslate(-w / 2, -h / 2); postRotate(straighten); postScale(s, s); postTranslate(w / 2, h / 2) }, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            b = out
        }
        if (withCrop && crop != RectF(0f, 0f, 1f, 1f)) {
            val x = (crop.left * b.width).roundToInt().coerceIn(0, b.width - 1); val y = (crop.top * b.height).roundToInt().coerceIn(0, b.height - 1)
            val w = (crop.width() * b.width).roundToInt().coerceIn(1, b.width - x); val h = (crop.height() * b.height).roundToInt().coerceIn(1, b.height - y)
            b = Bitmap.createBitmap(b, x, y, w, h)
        }
        return b
    }
}
