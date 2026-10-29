package com.blacklab.app.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class DrawTool(val label: String) {
    PEN("Pen"), HIGHLIGHTER("Highlighter"), LINE("Line"), ARROW("Arrow"), RECT("Box"), CIRCLE("Circle"), ERASER("Eraser");
    val isShape: Boolean get() = this == LINE || this == ARROW || this == RECT || this == CIRCLE
}

/** Something drawn on top of a picture. Coordinates are normalised (0…1) so marks survive any resolution. */
sealed interface Mark { val id: Long }

data class StrokeMark(
    override val id: Long,
    val tool: DrawTool,
    val points: List<PointF>,
    val color: Int,
    /** Line width as a fraction of the image width. */
    val width: Float,
    val opacity: Float,
    val dashed: Boolean = false,
) : Mark

data class TextMark(
    override val id: Long,
    val text: String,
    val x: Float,
    val y: Float,
    /** Text size as a fraction of the image width. */
    val size: Float = 0.06f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val font: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val cursive: Boolean = false,
    val background: Boolean = false,
    val outline: Boolean = true,
) : Mark

data class Watermark(
    val text: String = "",
    val color: Int = 0xFFFFFFFF.toInt(),
    val opacity: Float = 0.55f,
    val size: Float = 0.045f,
    val position: String = "BR", // TL, TR, BL, BR, C, TILE
    val font: String = "sans-serif",
    val bold: Boolean = true,
    val logo: Bitmap? = null,
    val logoScale: Float = 0.18f,
)

object Fonts {
    val families = listOf(
        "sans-serif" to "Sans", "serif" to "Serif", "monospace" to "Mono", "sans-serif-condensed" to "Condensed",
        "sans-serif-light" to "Light", "casual" to "Casual", "serif-monospace" to "Typewriter", "sans-serif-smallcaps" to "Small caps",
    )

    fun typeface(family: String, bold: Boolean, italic: Boolean): Typeface {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        return Typeface.create(family, style)
    }
}

object MarkupRenderer {

    fun draw(c: Canvas, w: Float, h: Float, marks: List<Mark>, watermark: Watermark?, live: StrokeMark? = null, selected: Long? = null) {
        watermark?.takeIf { it.text.isNotBlank() || it.logo != null }?.let { drawWatermark(c, w, h, it) }
        marks.forEach { m -> when (m) { is StrokeMark -> drawStroke(c, w, h, m); is TextMark -> drawText(c, w, h, m, m.id == selected) } }
        live?.let { drawStroke(c, w, h, it) }
    }

    private fun strokePaint(w: Float, m: StrokeMark) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = m.color
        alpha = (m.opacity * 255).toInt().coerceIn(0, 255)
        strokeWidth = (m.width * w).coerceAtLeast(1f)
        strokeCap = if (m.tool == DrawTool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (m.dashed) pathEffect = DashPathEffect(floatArrayOf(strokeWidth * 2.5f, strokeWidth * 1.8f), 0f)
    }

    fun drawStroke(c: Canvas, w: Float, h: Float, m: StrokeMark) {
        if (m.points.isEmpty() || m.tool == DrawTool.ERASER) return
        val p = strokePaint(w, m)
        val pts = m.points.map { PointF(it.x * w, it.y * h) }
        val a = pts.first(); val b = pts.last()
        when (m.tool) {
            DrawTool.PEN, DrawTool.HIGHLIGHTER -> {
                if (pts.size == 1) { c.drawPoint(a.x, a.y, p); return }
                val path = Path().apply {
                    moveTo(a.x, a.y)
                    for (i in 1 until pts.size) {
                        val prev = pts[i - 1]; val cur = pts[i]
                        quadTo(prev.x, prev.y, (prev.x + cur.x) / 2, (prev.y + cur.y) / 2)
                    }
                    lineTo(b.x, b.y)
                }
                c.drawPath(path, p)
            }
            DrawTool.LINE -> c.drawLine(a.x, a.y, b.x, b.y, p)
            DrawTool.ARROW -> {
                c.drawLine(a.x, a.y, b.x, b.y, p)
                val ang = atan2(b.y - a.y, b.x - a.x)
                val len = max(p.strokeWidth * 3.2f, w * 0.035f)
                val spread = Math.toRadians(28.0).toFloat()
                val head = Path().apply {
                    moveTo(b.x - len * cos(ang - spread), b.y - len * sin(ang - spread))
                    lineTo(b.x, b.y)
                    lineTo(b.x - len * cos(ang + spread), b.y - len * sin(ang + spread))
                }
                c.drawPath(head, p)
            }
            DrawTool.RECT -> c.drawRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), p)
            DrawTool.CIRCLE -> c.drawOval(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y), p)
            DrawTool.ERASER -> {}
        }
    }

    fun textPaint(w: Float, m: TextMark) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = m.color
        textSize = (m.size * w).coerceAtLeast(4f)
        typeface = Fonts.typeface(if (m.cursive) "cursive" else m.font, m.bold, m.italic)
        isUnderlineText = m.underline
        textAlign = Paint.Align.CENTER
    }

    /** Bounding box in pixel space (for hit-testing and the selection frame). */
    fun textBounds(w: Float, h: Float, m: TextMark): RectF {
        val p = textPaint(w, m)
        val lines = m.text.split('\n')
        val lh = p.textSize * 1.22f
        val tw = lines.maxOf { p.measureText(it) }
        val th = lh * lines.size
        val cx = m.x * w; val cy = m.y * h
        val pad = p.textSize * 0.3f
        return RectF(cx - tw / 2 - pad, cy - th / 2 - pad, cx + tw / 2 + pad, cy + th / 2 + pad)
    }

    fun drawText(c: Canvas, w: Float, h: Float, m: TextMark, selected: Boolean = false) {
        if (m.text.isBlank()) return
        val p = textPaint(w, m)
        val lines = m.text.split('\n')
        val lh = p.textSize * 1.22f
        val box = textBounds(w, h, m)
        if (m.background) {
            val light = luminance(m.color) > 0.55f
            c.drawRoundRect(box, p.textSize * 0.25f, p.textSize * 0.25f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0xB3000000.toInt() else 0xD9FFFFFF.toInt() })
        }
        val top = m.y * h - lh * lines.size / 2
        val fm = p.fontMetrics
        lines.forEachIndexed { i, line ->
            val base = top + lh * i + (lh - (fm.descent - fm.ascent)) / 2 - fm.ascent
            if (m.outline && !m.background) {
                val o = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = p.textSize * 0.09f; strokeJoin = Paint.Join.ROUND; color = if (luminance(m.color) > 0.5f) 0xCC000000.toInt() else 0xCCFFFFFF.toInt(); isUnderlineText = m.underline }
                c.drawText(line, m.x * w, base, o)
            }
            c.drawText(line, m.x * w, base, p)
        }
        if (selected) c.drawRoundRect(box, 8f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = (w * 0.004f).coerceAtLeast(2f); color = 0xFFFFD54F.toInt()
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        })
    }

    fun drawWatermark(c: Canvas, w: Float, h: Float, wm: Watermark) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = wm.color; alpha = (wm.opacity * 255).toInt().coerceIn(0, 255)
            textSize = (wm.size * w).coerceAtLeast(6f); typeface = Fonts.typeface(wm.font, wm.bold, false)
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, ((wm.opacity * 0.6f * 255).toInt() shl 24))
        }
        val logo = wm.logo
        val logoW = if (logo != null) wm.logoScale * w else 0f
        val logoH = if (logo != null) logoW * logo.height / logo.width else 0f
        val textW = if (wm.text.isNotBlank()) p.measureText(wm.text) else 0f
        val gap = if (logo != null && wm.text.isNotBlank()) p.textSize * 0.4f else 0f
        val blockW = max(textW, logoW)
        val blockH = logoH + gap + if (wm.text.isNotBlank()) p.textSize else 0f
        val logoPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = p.alpha }

        fun block(cx: Float, top: Float) {
            if (logo != null) c.drawBitmap(logo, null, RectF(cx - logoW / 2, top, cx + logoW / 2, top + logoH), logoPaint)
            if (wm.text.isNotBlank()) c.drawText(wm.text, cx - textW / 2, top + logoH + gap + p.textSize * 0.82f, p)
        }

        if (wm.position == "TILE") {
            c.save()
            c.rotate(-30f, w / 2, h / 2)
            val stepX = blockW * 1.8f + w * 0.05f
            val stepY = blockH * 3.2f + h * 0.03f
            val diag = hypot(w, h)
            var yy = h / 2 - diag; var row = 0
            while (yy < h / 2 + diag) {
                var xx = w / 2 - diag + if (row % 2 == 1) stepX / 2 else 0f
                while (xx < w / 2 + diag) { block(xx, yy); xx += stepX }
                yy += stepY; row++
            }
            c.restore()
            return
        }
        val m = w * 0.035f
        val cx = when (wm.position) { "TL", "BL" -> m + blockW / 2; "TR", "BR" -> w - m - blockW / 2; else -> w / 2 }
        val top = when (wm.position) { "TL", "TR" -> m; "BL", "BR" -> h - m - blockH; else -> (h - blockH) / 2 }
        block(cx, top)
    }

    private fun luminance(c: Int): Float = (0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

    /** Distance from a point (pixel space) to a stroke, for the object eraser. */
    fun distance(w: Float, h: Float, m: StrokeMark, x: Float, y: Float): Float {
        val pts = m.points.map { PointF(it.x * w, it.y * h) }
        if (pts.isEmpty()) return Float.MAX_VALUE
        val segs: List<Pair<PointF, PointF>> = when (m.tool) {
            DrawTool.RECT -> { val a = pts.first(); val b = pts.last(); listOf(PointF(a.x, a.y) to PointF(b.x, a.y), PointF(b.x, a.y) to PointF(b.x, b.y), PointF(b.x, b.y) to PointF(a.x, b.y), PointF(a.x, b.y) to PointF(a.x, a.y)) }
            DrawTool.CIRCLE -> {
                val a = pts.first(); val b = pts.last(); val cx = (a.x + b.x) / 2; val cy = (a.y + b.y) / 2; val rx = kotlin.math.abs(b.x - a.x) / 2; val ry = kotlin.math.abs(b.y - a.y) / 2
                val ring = (0..36).map { i -> val t = i * Math.PI.toFloat() / 18; PointF(cx + rx * cos(t), cy + ry * sin(t)) }
                ring.zipWithNext()
            }
            DrawTool.LINE, DrawTool.ARROW -> listOf(pts.first() to pts.last())
            else -> if (pts.size == 1) listOf(pts[0] to pts[0]) else pts.zipWithNext()
        }
        return segs.minOf { (a, b) -> segDist(x, y, a, b) } - m.width * w / 2
    }

    private fun segDist(px: Float, py: Float, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - a.x) * dx + (py - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (a.x + t * dx), py - (a.y + t * dy))
    }
}
