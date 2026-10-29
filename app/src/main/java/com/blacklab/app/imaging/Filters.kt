package com.blacklab.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import java.time.LocalDate
import java.util.Locale
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Photo looks. Each is a colour matrix (so it can run live on the camera preview as a RenderEffect)
 * plus optional film "texture" — vignette, grain, light leak, faded edges and a date imprint — that is
 * baked in when the photo is saved.
 */
enum class PhotoFilter(
    val label: String,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val lightLeak: Boolean = false,
    val retroDate: Boolean = false,
    val build: (ColorMatrix) -> Unit = {},
) {
    NONE("Original"),
    SEVENTIES("1970s", vignette = 0.55f, grain = 0.10f, lightLeak = true, retroDate = true, build = { m ->
        // Faded, warm Kodachrome/Ektachrome-era print: lifted blacks, softened contrast, amber cast, olive shadows
        m.postConcat(saturation(0.78f))
        m.postConcat(contrast(0.86f))
        m.postConcat(ColorMatrix(floatArrayOf(
            1.08f, 0.06f, 0.00f, 0f, 14f,
            0.02f, 1.00f, 0.02f, 0f, 6f,
            0.00f, 0.08f, 0.78f, 0f, 4f,
            0f, 0f, 0f, 1f, 0f,
        )))
        m.postConcat(lift(18f))
    }),
    VIVID("Vivid", build = { m -> m.postConcat(saturation(1.38f)); m.postConcat(contrast(1.10f)) }),
    NOIR("Noir", vignette = 0.35f, grain = 0.05f, build = { m -> m.postConcat(saturation(0f)); m.postConcat(contrast(1.38f)); m.postConcat(brightness(-6f)) }),
    SILVER("Silver", build = { m ->
        m.postConcat(saturation(0f)); m.postConcat(contrast(0.92f))
        m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, -2f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
    }),
    GOLDEN("Golden hour", vignette = 0.2f, build = { m ->
        m.postConcat(saturation(1.12f))
        m.postConcat(ColorMatrix(floatArrayOf(1.10f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 6f, 0f, 0f, 0.82f, 0f, -4f, 0f, 0f, 0f, 1f, 0f)))
    }),
    ARCTIC("Arctic", build = { m ->
        m.postConcat(saturation(0.85f))
        m.postConcat(ColorMatrix(floatArrayOf(0.90f, 0f, 0f, 0f, -4f, 0f, 1.0f, 0.04f, 0f, 4f, 0f, 0.04f, 1.14f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(brightness(4f))
    }),
    CINEMATIC("Cinematic", vignette = 0.4f, build = { m ->
        // teal shadows / warm skin tones
        m.postConcat(contrast(1.14f))
        m.postConcat(ColorMatrix(floatArrayOf(
            1.10f, 0.04f, -0.06f, 0f, 4f,
            -0.02f, 1.00f, 0.04f, 0f, 2f,
            -0.08f, 0.14f, 0.94f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
        )))
        m.postConcat(saturation(0.92f))
    }),
    MATTE("Matte fade", grain = 0.03f, build = { m -> m.postConcat(contrast(0.82f)); m.postConcat(saturation(0.82f)); m.postConcat(lift(26f)) }),
    CROSS("Cross-process", vignette = 0.25f, build = { m ->
        m.postConcat(contrast(1.18f))
        m.postConcat(ColorMatrix(floatArrayOf(1.12f, 0f, 0f, 0f, -6f, 0f, 1.06f, 0f, 0f, 8f, 0f, 0f, 0.74f, 0f, 34f, 0f, 0f, 0f, 1f, 0f)))
        m.postConcat(saturation(1.2f))
    });

    val matrix: ColorMatrix get() = ColorMatrix().also(build)
    val isIdentity: Boolean get() = this == NONE

    fun colorFilter(): ColorMatrixColorFilter? = if (isIdentity) null else ColorMatrixColorFilter(matrix)

    /** For the live camera preview (TextureView-backed PreviewView). */
    fun renderEffect(): RenderEffect? = colorFilter()?.let { RenderEffect.createColorFilterEffect(it) }

    /** Compose colour matrix (row-major 4×5) for previews in Image composables. */
    fun composeMatrix(): androidx.compose.ui.graphics.ColorMatrix = androidx.compose.ui.graphics.ColorMatrix(matrix.array)

    companion object {
        fun from(name: String?): PhotoFilter = entries.firstOrNull { it.name == name } ?: NONE

        fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }
        fun contrast(c: Float): ColorMatrix {
            val t = (1f - c) * 128f
            return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f))
        }
        fun brightness(b: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))
        /** Raise the black point (faded print look) while keeping whites. */
        fun lift(amount: Float): ColorMatrix {
            val k = (255f - amount) / 255f
            return ColorMatrix(floatArrayOf(k, 0f, 0f, 0f, amount, 0f, k, 0f, 0f, amount, 0f, 0f, k, 0f, amount, 0f, 0f, 0f, 1f, 0f))
        }
    }
}

object FilterRenderer {
    private val noiseTile: Bitmap by lazy {
        val n = 256
        val rnd = Random(1970)
        val px = IntArray(n * n) { val v = 128 + (rnd.nextFloat() * 2 - 1).let { it * it * it * 127 }.toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Returns a new bitmap with [filter] applied (colour + film texture). [src] is left untouched. */
    fun apply(src: Bitmap, filter: PhotoFilter, dateStamp: Boolean = filter.retroDate, date: LocalDate = LocalDate.now()): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = filter.colorFilter() })
        texture(c, src.width.toFloat(), src.height.toFloat(), filter, dateStamp, date)
        return out
    }

    /** Draws the non-colour part of a look onto an existing canvas of the given size. */
    fun texture(c: Canvas, w: Float, h: Float, filter: PhotoFilter, dateStamp: Boolean, date: LocalDate = LocalDate.now()) {
        val diag = hypot(w, h)
        if (filter.lightLeak) {
            // warm leak from the left edge and a softer one from the top-right corner
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = LinearGradient(0f, 0f, w * 0.42f, 0f, intArrayOf(0x88FF5A1F.toInt(), 0x33FF9A3C, 0x00FF9A3C), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                blendMode = BlendMode.SCREEN
            })
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(w, 0f, diag * 0.35f, intArrayOf(0x55FFC46B, 0x00FFC46B), null, Shader.TileMode.CLAMP)
                blendMode = BlendMode.SCREEN
            })
        }
        if (filter.vignette > 0f) {
            val a = (filter.vignette * 255).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(w / 2, h / 2, diag * 0.62f, intArrayOf(0x00000000, 0x00000000, a shl 24), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                blendMode = BlendMode.MULTIPLY
            })
        }
        if (filter.grain > 0f) {
            val scale = (maxOf(w, h) / 2000f).coerceAtLeast(1f)
            val shader = BitmapShader(noiseTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
            }
            c.drawRect(0f, 0f, w, h, Paint().apply {
                this.shader = shader
                alpha = (filter.grain * 255 * 2.2f).toInt().coerceIn(0, 255)
                blendMode = BlendMode.OVERLAY
            })
        }
        if (dateStamp) {
            val text = date.format(java.time.format.DateTimeFormatter.ofPattern("''yy  M  d", Locale.US))
            val size = minOf(w, h) * 0.05f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFF8A1E.toInt()
                textSize = size
                typeface = Typeface.create("monospace", Typeface.BOLD)
                letterSpacing = 0.06f
                setShadowLayer(size * 0.35f, 0f, 0f, 0xCCFF4D00.toInt())
                textAlign = Paint.Align.RIGHT
            }
            c.drawText(text, w - size * 1.2f, h - size * 1.1f, p)
        }
    }
}
