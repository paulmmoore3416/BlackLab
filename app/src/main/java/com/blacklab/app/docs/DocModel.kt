package com.blacklab.app.docs

import android.graphics.BitmapFactory
import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.io.File
import java.util.Locale

data class RunStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sup: Boolean = false,
    val sub: Boolean = false,
    val code: Boolean = false,
    val color: Int? = null,
    val background: Int? = null,
    val sizePt: Float? = null,
    val font: String? = null,
    val link: String? = null,
)

data class Run(val text: String, val style: RunStyle)

enum class Align { LEFT, CENTER, RIGHT, JUSTIFY }
enum class ListKind { BULLET, NUMBER, CHECK }

data class ParaStyle(
    val heading: Int = 0, // 0 normal, 1..6 headings, 9 = Title
    val align: Align = Align.LEFT,
    val list: ListKind? = null,
    val level: Int = 0,
    val number: Int = 0,
    val checked: Boolean = false,
    val listId: Int = 0,
    val quote: Boolean = false,
    val code: Boolean = false,
    val indent: Int = 0,
    val lineHeight: Float? = null,
)

sealed interface Block

data class Para(val runs: List<Run>, val style: ParaStyle) : Block {
    val text: String get() = runs.joinToString("") { it.text }
}

data class TableBlock(val rows: List<List<List<Para>>>, val headerRow: Boolean) : Block

class ImageBlock(val bytes: ByteArray, val mime: String, val widthPx: Int, val heightPx: Int, val align: Align) : Block

data object RuleBlock : Block
data object PageBreakBlock : Block

data class PageSetup(val size: String = "LETTER", val landscape: Boolean = false, val marginMm: Int = 25) {
    private val portrait: Pair<Float, Float>
        get() = when (size) {
            "A4" -> 595.28f to 841.89f
            "LEGAL" -> 612f to 1008f
            "A5" -> 419.53f to 595.28f
            else -> 612f to 792f
        }
    val widthPt: Float get() = if (landscape) portrait.second else portrait.first
    val heightPt: Float get() = if (landscape) portrait.first else portrait.second
    val marginPt: Float get() = marginMm / 25.4f * 72f

    /** CSS used by the editor and HTML export so the page preview matches. */
    fun css(): String {
        val wIn = widthPt / 72f
        return "@page { size: ${String.format(Locale.US, "%.2fin %.2fin", wIn, heightPt / 72f)}; margin: ${marginMm}mm; }"
    }

    companion object {
        val sizes = listOf("LETTER" to "Letter (8.5 × 11 in)", "A4" to "A4 (210 × 297 mm)", "LEGAL" to "Legal (8.5 × 14 in)", "A5" to "A5 (148 × 210 mm)")
    }
}

/** Converts the editor's HTML into a flat list of blocks consumed by every exporter. */
object HtmlToBlocks {

    fun parse(html: String, baseDir: File? = null): List<Block> {
        val body = Jsoup.parseBodyFragment(html).body()
        val ctx = Ctx(baseDir)
        walkChildren(body, RunStyle(), ctx)
        ctx.flush()
        return ctx.blocks
    }

    private class Ctx(val baseDir: File?) {
        val blocks = ArrayList<Block>()
        var runs = ArrayList<Run>()
        var para = ParaStyle()
        var listSeq = 0
        var pre = false

        fun flush(keepEmpty: Boolean = false) {
            val cleaned = trim(runs)
            if (cleaned.isNotEmpty() || keepEmpty) blocks += Para(cleaned, para)
            runs = ArrayList()
        }

        private fun trim(src: List<Run>): List<Run> {
            if (pre) return src.filter { it.text.isNotEmpty() }
            val list = src.filter { it.text.isNotEmpty() }.toMutableList()
            while (list.isNotEmpty() && list.first().text.isBlank() && !list.first().text.contains('\n')) list.removeAt(0)
            if (list.isNotEmpty()) list[0] = list[0].copy(text = list[0].text.trimStart(' '))
            while (list.isNotEmpty() && list.last().text.isBlank() && !list.last().text.contains('\n')) list.removeAt(list.lastIndex)
            if (list.isNotEmpty()) list[list.lastIndex] = list.last().copy(text = list.last().text.trimEnd(' ', '\n'))
            return list.filter { it.text.isNotEmpty() }
        }
    }

    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol", "li", "table",
        "hr", "section", "article", "header", "footer", "figure", "figcaption", "address", "dl", "dt", "dd", "center",
    )

    private fun walkChildren(el: Element, style: RunStyle, ctx: Ctx) {
        for (child in el.childNodes()) walk(child, style, ctx)
    }

    private fun walk(node: Node, style: RunStyle, ctx: Ctx) {
        when (node) {
            is TextNode -> {
                val raw = node.wholeText
                val text = if (ctx.pre) raw else raw.replace(Regex("[\\s\\u00A0&&[^\\n]]+"), " ").replace('\n', ' ').replace(Regex(" {2,}"), " ")
                if (text.isNotEmpty()) {
                    val prevEndsSpace = ctx.runs.lastOrNull()?.text?.endsWith(' ') == true
                    val t = if (!ctx.pre && prevEndsSpace) text.trimStart(' ') else text
                    if (t.isNotEmpty()) ctx.runs += Run(t.replace(' ', ' '), style)
                }
            }
            is Element -> element(node, style, ctx)
            else -> Unit
        }
    }

    private fun element(el: Element, parentStyle: RunStyle, ctx: Ctx) {
        val tag = el.tagName().lowercase(Locale.US)
        if (tag == "script" || tag == "style" || tag == "head") return
        val css = parseCss(el.attr("style"))
        val style = runStyle(tag, el, css, parentStyle)

        when {
            tag == "br" -> { ctx.runs += Run("\n", style); return }
            tag == "img" -> { ctx.flush(); image(el, css, ctx)?.let { ctx.blocks += it }; return }
            tag == "hr" -> { ctx.flush(); ctx.blocks += RuleBlock; return }
            el.hasClass("page-break") -> { ctx.flush(); ctx.blocks += PageBreakBlock; return }
            tag == "table" -> { ctx.flush(); ctx.blocks += table(el, ctx); return }
            tag == "input" && el.attr("type") == "checkbox" -> {
                ctx.runs += Run(if (el.hasAttr("checked")) "☑ " else "☐ ", style); return
            }
        }

        if (tag !in blockTags) {
            walkChildren(el, style, ctx)
            return
        }

        // Block element
        ctx.flush()
        val saved = ctx.para
        val savedPre = ctx.pre
        var p = saved.copy(align = alignOf(el, css) ?: saved.align, lineHeight = css["line-height"]?.toFloatOrNull() ?: saved.lineHeight)
        css["margin-left"]?.let { px(it) }?.let { p = p.copy(indent = saved.indent + (it / 40f).toInt()) }
        css["padding-left"]?.let { px(it) }?.takeIf { tag != "blockquote" }?.let { p = p.copy(indent = saved.indent + (it / 40f).toInt()) }
        when (tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> p = p.copy(heading = if (el.hasClass("title")) 9 else tag[1].digitToInt(), list = null)
            "blockquote" -> p = p.copy(quote = true)
            "pre" -> { p = p.copy(code = true); ctx.pre = true }
            "p", "div" -> if (el.hasClass("title")) p = p.copy(heading = 9)
            "ul", "ol" -> {
                val kind = when {
                    el.hasClass("checklist") -> ListKind.CHECK
                    tag == "ol" -> ListKind.NUMBER
                    else -> ListKind.BULLET
                }
                val depth = el.parents().count { it.tagName() == "ul" || it.tagName() == "ol" }
                val listId = ++ctx.listSeq
                var n = el.attr("start").toIntOrNull()?.minus(1) ?: 0
                for (child in el.children()) {
                    if (child.tagName() != "li") { walk(child, style, ctx); continue }
                    n++
                    ctx.flush()
                    ctx.para = saved.copy(list = kind, level = depth, number = n, listId = listId, checked = child.hasClass("checked"),
                        align = alignOf(child, parseCss(child.attr("style"))) ?: Align.LEFT)
                    walkChildren(child, style, ctx)
                    ctx.flush()
                }
                ctx.para = saved
                return
            }
        }
        ctx.para = p
        walkChildren(el, style, ctx)
        val emptyLine = (tag == "p" || tag == "div") && el.text().isBlank() && el.select("img, table, hr").isEmpty()
        ctx.flush(keepEmpty = emptyLine && el.select("br").isNotEmpty())
        ctx.para = saved
        ctx.pre = savedPre
    }

    private fun table(el: Element, ctx: Ctx): TableBlock {
        val rows = el.select("tr").filter { tr -> tr.parents().firstOrNull { it.tagName() == "table" } == el }
        var header = false
        val out = rows.mapIndexed { i, tr ->
            val cells = tr.children().filter { it.tagName() == "td" || it.tagName() == "th" }
            if (i == 0 && cells.isNotEmpty() && cells.all { it.tagName() == "th" }) header = true
            cells.map { cell ->
                val sub = Ctx(ctx.baseDir)
                walkChildren(cell, if (cell.tagName() == "th") RunStyle(bold = true) else RunStyle(), sub)
                sub.flush()
                sub.blocks.flatMap { b ->
                    when (b) {
                        is Para -> listOf(b)
                        is TableBlock -> b.rows.flatten().flatten()
                        else -> emptyList()
                    }
                }.ifEmpty { listOf(Para(emptyList(), ParaStyle())) }
            }
        }
        return TableBlock(out, header)
    }

    private fun image(el: Element, css: Map<String, String>, ctx: Ctx): ImageBlock? {
        val src = el.attr("src")
        val bytes: ByteArray
        var mime = "image/jpeg"
        if (src.startsWith("data:")) {
            val comma = src.indexOf(',')
            if (comma < 0) return null
            val meta = src.substring(5, comma)
            mime = meta.substringBefore(';').ifBlank { "image/png" }
            bytes = runCatching { Base64.decode(src.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
        } else {
            val f = when {
                src.startsWith("file://") -> File(src.removePrefix("file://"))
                src.startsWith("/") -> File(src)
                ctx.baseDir != null -> File(ctx.baseDir, src)
                else -> return null
            }
            if (!f.exists()) return null
            bytes = f.readBytes()
            mime = if (f.extension.lowercase(Locale.US) == "png") "image/png" else "image/jpeg"
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0) return null
        val w = css["width"]?.let { px(it) }?.toInt() ?: el.attr("width").toIntOrNull() ?: o.outWidth
        val h = (o.outHeight * (w.toFloat() / o.outWidth)).toInt()
        val parentAlign = el.parents().firstNotNullOfOrNull { alignOf(it, parseCss(it.attr("style"))) } ?: Align.LEFT
        return ImageBlock(bytes, mime, w, h, parentAlign)
    }

    private fun runStyle(tag: String, el: Element, css: Map<String, String>, parent: RunStyle): RunStyle {
        var s = parent
        when (tag) {
            "b", "strong" -> s = s.copy(bold = true)
            "i", "em", "cite", "var" -> s = s.copy(italic = true)
            "u", "ins" -> s = s.copy(underline = true)
            "s", "strike", "del" -> s = s.copy(strike = true)
            "sup" -> s = s.copy(sup = true)
            "sub" -> s = s.copy(sub = true)
            "code", "kbd", "samp", "tt" -> s = s.copy(code = true)
            "mark" -> s = s.copy(background = 0xFFFFF59D.toInt())
            "a" -> el.attr("href").takeIf { it.isNotBlank() }?.let { s = s.copy(link = it) }
            "font" -> {
                el.attr("color").takeIf { it.isNotBlank() }?.let { parseColor(it) }?.let { s = s.copy(color = it) }
                el.attr("face").takeIf { it.isNotBlank() }?.let { s = s.copy(font = cleanFont(it)) }
                el.attr("size").toIntOrNull()?.let { s = s.copy(sizePt = fontSizeKeyword(it)) }
            }
        }
        css["font-weight"]?.let { if (it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 0) >= 600) s = s.copy(bold = true) else if (it == "normal") s = s.copy(bold = false) }
        css["font-style"]?.let { if (it == "italic" || it == "oblique") s = s.copy(italic = true) else if (it == "normal") s = s.copy(italic = false) }
        (css["text-decoration"] ?: css["text-decoration-line"])?.let {
            if (it.contains("underline")) s = s.copy(underline = true)
            if (it.contains("line-through")) s = s.copy(strike = true)
        }
        css["vertical-align"]?.let { if (it == "super") s = s.copy(sup = true) else if (it == "sub") s = s.copy(sub = true) }
        css["color"]?.let { parseColor(it) }?.let { s = s.copy(color = it) }
        (css["background-color"] ?: css["background"])?.let { parseColor(it) }?.let { if (Color.alpha(it) > 0) s = s.copy(background = it) }
        css["font-size"]?.let { parseFontSize(it) }?.let { s = s.copy(sizePt = it) }
        css["font-family"]?.let { s = s.copy(font = cleanFont(it)) }
        return s
    }

    private fun alignOf(el: Element, css: Map<String, String>): Align? {
        val v = (css["text-align"] ?: el.attr("align")).lowercase(Locale.US)
        return when (v) {
            "center" -> Align.CENTER
            "right", "end" -> Align.RIGHT
            "justify" -> Align.JUSTIFY
            "left", "start" -> Align.LEFT
            else -> if (el.tagName() == "center") Align.CENTER else null
        }
    }

    fun parseCss(style: String): Map<String, String> =
        style.split(';').mapNotNull {
            val i = it.indexOf(':'); if (i <= 0) null else it.substring(0, i).trim().lowercase(Locale.US) to it.substring(i + 1).trim().removeSuffix("!important").trim()
        }.toMap()

    private fun px(v: String): Float? {
        val s = v.trim().lowercase(Locale.US)
        return when {
            s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull()
            s.endsWith("pt") -> s.removeSuffix("pt").toFloatOrNull()?.times(4f / 3f)
            s.endsWith("em") -> s.removeSuffix("em").toFloatOrNull()?.times(16f)
            s.endsWith("in") -> s.removeSuffix("in").toFloatOrNull()?.times(96f)
            else -> s.toFloatOrNull()
        }
    }

    private fun fontSizeKeyword(n: Int): Float = when (n) {
        1 -> 7.5f; 2 -> 10f; 3 -> 12f; 4 -> 13.5f; 5 -> 18f; 6 -> 24f; else -> 36f
    }

    private fun parseFontSize(v: String): Float? {
        val s = v.trim().lowercase(Locale.US)
        return when {
            s.endsWith("pt") -> s.removeSuffix("pt").toFloatOrNull()
            s.endsWith("px") -> s.removeSuffix("px").toFloatOrNull()?.times(0.75f)
            s.endsWith("em") || s.endsWith("rem") -> s.removeSuffix("rem").removeSuffix("em").toFloatOrNull()?.times(11f)
            s.endsWith("%") -> s.removeSuffix("%").toFloatOrNull()?.times(0.11f)
            s == "xx-small" -> 7f; s == "x-small" -> 7.5f; s == "small" -> 10f; s == "medium" -> 12f
            s == "large" -> 13.5f; s == "x-large" -> 18f; s == "xx-large" -> 24f; s == "xxx-large" -> 36f
            else -> null
        }
    }

    private fun cleanFont(v: String): String = v.split(',').first().trim().trim('"', '\'')

    fun parseColor(v: String): Int? {
        val s = v.trim().lowercase(Locale.US)
        return runCatching {
            when {
                s.startsWith("#") && s.length == 4 -> {
                    val r = s[1].digitToInt(16); val g = s[2].digitToInt(16); val b = s[3].digitToInt(16)
                    Color.rgb(r * 17, g * 17, b * 17)
                }
                s.startsWith("#") && s.length >= 7 -> (0xFF000000 or s.substring(1, 7).toLong(16)).toInt()
                s.startsWith("rgb") -> {
                    val nums = s.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
                    val a = nums.getOrNull(3)?.toFloatOrNull() ?: 1f
                    Color.argb((a * 255).toInt(), nums[0].toFloat().toInt(), nums[1].toFloat().toInt(), nums[2].toFloat().toInt())
                }
                else -> named[s]
            }
        }.getOrNull()
    }

    private val named = mapOf(
        "black" to Color.rgb(0, 0, 0), "white" to Color.rgb(255, 255, 255), "red" to Color.rgb(255, 0, 0),
        "green" to Color.rgb(0, 128, 0), "blue" to Color.rgb(0, 0, 255), "yellow" to Color.rgb(255, 255, 0),
        "orange" to Color.rgb(255, 165, 0), "purple" to Color.rgb(128, 0, 128), "gray" to Color.rgb(128, 128, 128),
        "grey" to Color.rgb(128, 128, 128), "navy" to Color.rgb(0, 0, 128), "teal" to Color.rgb(0, 128, 128),
        "maroon" to Color.rgb(128, 0, 0), "olive" to Color.rgb(128, 128, 0), "silver" to Color.rgb(192, 192, 192),
        "aqua" to Color.rgb(0, 255, 255), "fuchsia" to Color.rgb(255, 0, 255), "lime" to Color.rgb(0, 255, 0),
    )

    /** Minimal ARGB helpers so this file stays usable from plain JVM code. */
    object Color {
        fun rgb(r: Int, g: Int, b: Int) = argb(255, r, g, b)
        fun argb(a: Int, r: Int, g: Int, b: Int) = (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        fun alpha(c: Int) = (c ushr 24) and 0xFF
    }
}

fun Int.toHex6(): String = String.format(Locale.US, "%06X", this and 0xFFFFFF)
