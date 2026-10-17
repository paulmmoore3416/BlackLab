package com.blacklab.app.docs

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Native, selectable-text PDF renderer with proper pagination (no print dialog needed). */
object PdfWriter {

    private const val BASE_PT = 11f

    private class Pager(val doc: PdfDocument, val page: PageSetup, val title: String) {
        var pageNo = 0
        lateinit var current: PdfDocument.Page
        lateinit var canvas: Canvas
        val left = page.marginPt
        val top = page.marginPt
        val width = page.widthPt - 2 * page.marginPt
        val bottom = page.heightPt - page.marginPt
        var y = 0f
        private val footer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = 0xFF8A8F98.toInt() }

        fun newPage() {
            if (pageNo > 0) finishPage()
            pageNo++
            current = doc.startPage(PdfDocument.PageInfo.Builder(page.widthPt.toInt(), page.heightPt.toInt(), pageNo).create())
            canvas = current.canvas
            y = top
        }

        fun finishPage() {
            val fy = page.heightPt - page.marginPt / 2
            canvas.drawText(title.take(80), left, fy, footer)
            val label = "Page $pageNo"
            canvas.drawText(label, left + width - footer.measureText(label), fy, footer)
            doc.finishPage(current)
        }

        val remaining: Float get() = bottom - y
        fun ensure(h: Float) { if (h > remaining && y > top) newPage() }
    }

    fun write(blocks: List<Block>, out: File, title: String, page: PageSetup) {
        val doc = PdfDocument()
        val pager = Pager(doc, page, title)
        pager.newPage()
        var prev: Block? = null
        for (b in blocks) {
            when (b) {
                is Para -> drawPara(pager, b, prev)
                is TableBlock -> drawTable(pager, b)
                is ImageBlock -> drawImage(pager, b)
                RuleBlock -> {
                    pager.ensure(14f)
                    val p = Paint().apply { color = 0xFF9AA0A6.toInt(); strokeWidth = 0.75f }
                    pager.canvas.drawLine(pager.left, pager.y + 6, pager.left + pager.width, pager.y + 6, p)
                    pager.y += 14f
                }
                PageBreakBlock -> pager.newPage()
            }
            prev = b
        }
        pager.finishPage()
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun baseSize(p: ParaStyle) = when (p.heading) {
        9 -> 26f; 1 -> 20f; 2 -> 16f; 3 -> 13.5f; 4 -> 12f; 5 -> 11f; 6 -> 11f; else -> if (p.code) 9.5f else BASE_PT
    }

    fun spannable(p: Para, forceBold: Boolean = false): Pair<SpannableStringBuilder, TextPaint> {
        val s = p.style
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = baseSize(s)
            color = if (s.heading in 1..6) 0xFF1F3864.toInt() else 0xFF1B1B1F.toInt()
            typeface = when {
                s.code -> Typeface.MONOSPACE
                s.heading in 1..6 || forceBold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                s.heading == 9 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
        }
        val sb = SpannableStringBuilder()
        val prefix = when (s.list) {
            ListKind.BULLET -> listOf("•", "◦", "▪")[s.level % 3] + "  "
            ListKind.NUMBER -> numberLabel(s.number, s.level) + "  "
            ListKind.CHECK -> if (s.checked) "☑  " else "☐  "
            null -> ""
        }
        sb.append(prefix)
        for (r in p.runs) {
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            if (end == start) continue
            val st = r.style
            val flags = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            when {
                st.bold && st.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, flags)
                st.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, flags)
                st.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, flags)
            }
            if (st.underline || st.link != null) sb.setSpan(UnderlineSpan(), start, end, flags)
            if (st.strike || (s.list == ListKind.CHECK && s.checked)) sb.setSpan(StrikethroughSpan(), start, end, flags)
            if (st.sup) { sb.setSpan(SuperscriptSpan(), start, end, flags); sb.setSpan(RelativeSizeSpan(0.7f), start, end, flags) }
            if (st.sub) { sb.setSpan(SubscriptSpan(), start, end, flags); sb.setSpan(RelativeSizeSpan(0.7f), start, end, flags) }
            val color = st.color ?: if (st.link != null) 0xFF0563C1.toInt() else null
            color?.let { sb.setSpan(ForegroundColorSpan(it), start, end, flags) }
            st.background?.let { sb.setSpan(BackgroundColorSpan(it), start, end, flags) }
            if (s.heading == 0 && !s.code) st.sizePt?.let { sb.setSpan(AbsoluteSizeSpan(it.toInt().coerceIn(5, 96), false), start, end, flags) }
            val family = if (st.code) "monospace" else st.font?.let { familyFor(it) }
            family?.let { sb.setSpan(TypefaceSpan(it), start, end, flags) }
            if (st.code) sb.setSpan(BackgroundColorSpan(0xFFF1F3F4.toInt()), start, end, flags)
        }
        if (sb.isEmpty()) sb.append(" ")
        val indent = (s.indent * 27f + s.level * 18f + if (s.list != null) 6f else 0f).toInt()
        val hanging = if (prefix.isNotEmpty()) paint.measureText(prefix).toInt() else 0
        if (indent > 0 || hanging > 0) sb.setSpan(LeadingMarginSpan.Standard(indent, indent + hanging), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (s.quote) sb.setSpan(QuoteSpan(0xFF4F8CFF.toInt(), 3, 10), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return sb to paint
    }

    private fun familyFor(font: String): String {
        val f = font.lowercase(Locale.US)
        return when {
            listOf("times", "georgia", "garamond", "serif", "cambria", "book", "palatino", "baskerville").any { f.contains(it) } && !f.contains("sans") -> "serif"
            listOf("courier", "mono", "consolas", "code").any { f.contains(it) } -> "monospace"
            listOf("cursive", "script", "brush", "comic").any { f.contains(it) } -> "cursive"
            else -> "sans-serif"
        }
    }

    private fun numberLabel(n: Int, level: Int): String = when (level % 3) {
        0 -> "$n."
        1 -> ('a' + ((n - 1) % 26)) + "."
        else -> roman(n) + "."
    }

    private fun roman(n: Int): String {
        val vals = listOf(10 to "x", 9 to "ix", 5 to "v", 4 to "iv", 1 to "i")
        var x = n; val sb = StringBuilder()
        for ((v, s) in vals) while (x >= v) { sb.append(s); x -= v }
        return sb.toString()
    }

    fun layout(text: CharSequence, paint: TextPaint, width: Int, style: ParaStyle): StaticLayout {
        val align = when (style.align) {
            Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(align)
            .setLineSpacing(0f, style.lineHeight?.coerceIn(0.8f, 3f) ?: 1.15f)
            .setIncludePad(false)
            .setJustificationMode(if (style.align == Align.JUSTIFY) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .build()
    }

    private fun drawPara(pager: Pager, p: Para, prev: Block?) {
        val s = p.style
        val before = when {
            s.heading == 9 -> 0f
            s.heading in 1..2 -> 14f
            s.heading in 3..6 -> 10f
            else -> 0f
        }
        val after = when {
            s.heading == 9 -> 10f
            s.heading > 0 -> 4f
            s.list != null -> 2f
            s.code -> 0f
            else -> 7f
        }
        if (prev != null && pager.y > pager.top) pager.y += before
        val (text, paint) = spannable(p)
        val lay = layout(text, paint, pager.width.toInt(), s)
        if (s.heading in 1..6) pager.ensure(min(lay.height.toFloat(), 60f) + 30f) // keep heading with next lines
        var line = 0
        while (line < lay.lineCount) {
            if (pager.remaining < lay.getLineBottom(line) - lay.getLineTop(line)) pager.newPage()
            var last = line
            val topOfChunk = lay.getLineTop(line)
            while (last + 1 < lay.lineCount && lay.getLineBottom(last + 1) - topOfChunk <= pager.remaining) last++
            val chunkHeight = (lay.getLineBottom(last) - topOfChunk).toFloat()
            val c = pager.canvas
            if (s.code) {
                val bg = Paint().apply { color = 0xFFF3F4F6.toInt() }
                c.drawRect(RectF(pager.left - 4, pager.y - 2, pager.left + pager.width + 4, pager.y + chunkHeight + 2), bg)
            }
            c.save()
            c.translate(pager.left, pager.y - topOfChunk)
            c.clipRect(0f, topOfChunk.toFloat(), pager.width + 1, lay.getLineBottom(last).toFloat())
            lay.draw(c)
            c.restore()
            pager.y += chunkHeight
            line = last + 1
            if (line < lay.lineCount) pager.newPage()
        }
        if (s.heading == 1) {
            val rule = Paint().apply { color = 0xFFD0D5DD.toInt(); strokeWidth = 0.6f }
            pager.canvas.drawLine(pager.left, pager.y + 2, pager.left + pager.width, pager.y + 2, rule)
            pager.y += 3
        }
        pager.y += after
    }

    private fun drawTable(pager: Pager, t: TableBlock) {
        val cols = t.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: return
        val colW = pager.width / cols
        val pad = 4f
        val border = Paint().apply { color = 0xFF9AA0A6.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.6f }
        val headerBg = Paint().apply { color = 0xFFEEF1F5.toInt() }
        pager.y += 4f
        t.rows.forEachIndexed { ri, row ->
            val header = t.headerRow && ri == 0
            val layouts = (0 until cols).map { ci ->
                val paras = row.getOrNull(ci) ?: emptyList()
                val texts = paras.map { spannable(it, forceBold = header) }
                val combined = SpannableStringBuilder()
                texts.forEachIndexed { i, (sp, _) -> if (i > 0) combined.append("\n"); combined.append(sp) }
                val paint = texts.firstOrNull()?.second ?: TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = BASE_PT }
                paint.textSize = min(paint.textSize, 10.5f)
                layout(if (combined.isEmpty()) " " else combined, paint, (colW - 2 * pad).toInt(), paras.firstOrNull()?.style ?: ParaStyle())
            }
            val rowH = layouts.maxOf { it.height } + 2 * pad
            if (rowH > pager.remaining && pager.y > pager.top) pager.newPage()
            val c = pager.canvas
            layouts.forEachIndexed { ci, lay ->
                val x = pager.left + ci * colW
                val rect = RectF(x, pager.y, x + colW, pager.y + rowH)
                if (header) c.drawRect(rect, headerBg)
                c.save()
                c.translate(x + pad, pager.y + pad)
                c.clipRect(0f, 0f, colW - 2 * pad, min(rowH, pager.remaining))
                lay.draw(c)
                c.restore()
                c.drawRect(rect, border)
            }
            pager.y += rowH
        }
        pager.y += 8f
    }

    private fun drawImage(pager: Pager, img: ImageBlock) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        var w = min(pager.width, img.widthPx * 0.75f)
        var h = w * bmp.height / bmp.width
        val maxH = pager.bottom - pager.top
        if (h > maxH) { h = maxH; w = h * bmp.width / bmp.height }
        pager.ensure(h + 6)
        val x = when (img.align) {
            Align.CENTER -> pager.left + (pager.width - w) / 2
            Align.RIGHT -> pager.left + pager.width - w
            else -> pager.left
        }
        pager.canvas.drawBitmap(bmp, null, RectF(x, pager.y, x + w, pager.y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        pager.y += h + 8
        bmp.recycle()
    }
}
