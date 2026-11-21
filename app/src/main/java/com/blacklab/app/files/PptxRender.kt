package com.blacklab.app.files

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders PowerPoint slides as they look in PowerPoint: every shape at its position and size, fills and outlines,
 * text with its size, weight, color, alignment and bullets, pictures, tables, groups, backgrounds inherited from the
 * slide layout and master, and theme colors (with their tint/shade/luminance tweaks). Each slide becomes a fixed-size
 * HTML box (1 px = 9525 EMU, PowerPoint's 96-dpi grid) that the viewer scales to the screen. Charts and SmartArt show
 * as a labelled placeholder (open the deck in PowerPoint for those). Pure Kotlin + jsoup; nothing leaves the phone.
 */
object PptxRender {
    data class RSlide(val number: Int, val title: String, val html: String, val notes: String, val hidden: Boolean)
    data class Deck(val width: Int, val height: Int, val slides: List<RSlide>)

    private const val EMU_PER_PX = 9525.0
    private fun px(emu: String?): Double = (emu?.toDoubleOrNull() ?: 0.0) / EMU_PER_PX
    private fun xml(s: String): Document = Jsoup.parse(s, "", Parser.xmlParser())
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun f(d: Double) = if (d == d.roundToInt().toDouble()) d.roundToInt().toString() else "%.2f".format(java.util.Locale.US, d)

    private class Pkg(val z: ZipFile) {
        private val cache = HashMap<String, Document?>()
        fun text(n: String): String? = z.getEntry(n)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        fun doc(n: String): Document? = cache.getOrPut(n) { text(n)?.let { xml(it) } }
        fun bytes(n: String): ByteArray? = z.getEntry(n)?.let { e -> z.getInputStream(e).use { it.readBytes() } }
        fun rels(part: String): Map<String, String> {
            val relPath = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"
            return doc(relPath)?.select("Relationship")?.associate { it.attr("Id") to OfficeReader.resolve(part, it.attr("Target")) } ?: emptyMap()
        }
        fun relOfType(part: String, type: String): String? {
            val relPath = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"
            return doc(relPath)?.select("Relationship")?.firstOrNull { it.attr("Type").endsWith("/$type") }?.let { OfficeReader.resolve(part, it.attr("Target")) }
        }
    }

    /** One slide's context: its part, rels, layout, master and theme colors/fonts. */
    private class Ctx(val pkg: Pkg, val part: String, val layout: String?, val master: String?, val theme: Map<String, String>, val fonts: Pair<String, String>) {
        val rels = pkg.rels(part)
    }

    fun render(f: File, maxImageBytes: Int = 6_000_000): Deck = ZipFile(f).use { z ->
        val pkg = Pkg(z)
        val pres = pkg.doc("ppt/presentation.xml") ?: error("Not a PowerPoint file")
        val sz = pres.selectFirst("p|sldSz")
        val w = px(sz?.attr("cx") ?: "12192000"); val h = px(sz?.attr("cy") ?: "6858000")
        val presRels = pkg.rels("ppt/presentation.xml")
        val order = pres.select("p|sldIdLst > p|sldId").mapNotNull { presRels[it.attr("r:id")] }
        val slides = order.mapIndexedNotNull { i, part ->
            val sd = pkg.doc(part) ?: return@mapIndexedNotNull null
            val layout = pkg.relOfType(part, "slideLayout")
            val master = layout?.let { pkg.relOfType(it, "slideMaster") }
            val themePart = master?.let { pkg.relOfType(it, "theme") }
            val (colors, fonts) = theme(themePart?.let { pkg.doc(it) })
            val ctx = Ctx(pkg, part, layout, master, colors, fonts)
            val bg = background(ctx, sd) ?: layout?.let { l -> pkg.doc(l)?.let { background(Ctx(pkg, l, null, null, colors, fonts), it) } }
                ?: master?.let { m -> pkg.doc(m)?.let { background(Ctx(pkg, m, null, null, colors, fonts), it) } } ?: "background:#FFFFFF"
            val out = StringBuilder()
            // the layout/master's own pictures and shapes (logos, bands) show behind the slide unless it hides them
            if (sd.selectFirst("p|cSld")?.parent()?.attr("showMasterSp") != "0") {
                master?.let { m -> pkg.doc(m)?.selectFirst("p|cSld > p|spTree")?.let { tree -> shapes(Ctx(pkg, m, null, null, colors, fonts), tree, out, Xf(), maxImageBytes, decorOnly = true) } }
                layout?.let { l -> pkg.doc(l)?.selectFirst("p|cSld > p|spTree")?.let { tree -> shapes(Ctx(pkg, l, null, master, colors, fonts), tree, out, Xf(), maxImageBytes, decorOnly = true) } }
            }
            sd.selectFirst("p|cSld > p|spTree")?.let { shapes(ctx, it, out, Xf(), maxImageBytes) }
            val title = sd.select("p|sp").firstOrNull { it.selectFirst("p|nvPr > p|ph")?.attr("type") in setOf("title", "ctrTitle") }
                ?.select("a|t")?.joinToString("") { it.text() }?.trim().orEmpty()
            val notes = pkg.relOfType(part, "notesSlide")?.let { n -> pkg.doc(n) }?.let { nd ->
                nd.select("p|sp").filter { it.selectFirst("p|ph")?.attr("type") == "body" }.flatMap { sp -> sp.select("a|p").map { p -> p.select("a|t").joinToString("") { it.text() } } }
                    .filter { it.isNotBlank() }.joinToString("\n")
            }.orEmpty()
            RSlide(i + 1, title.ifBlank { sd.select("a|t").firstOrNull()?.text()?.take(80).orEmpty() },
                "<div class=\"sl\" style=\"width:${f(w)}px;height:${f(h)}px;$bg\">$out</div>", notes, sd.root().children().firstOrNull()?.attr("show") == "0")
        }
        Deck(w.roundToInt(), h.roundToInt(), slides)
    }

    // ------------------------------------------------------------------ theme & colors

    private fun theme(d: Document?): Pair<Map<String, String>, Pair<String, String>> {
        val m = HashMap<String, String>()
        d?.selectFirst("a|clrScheme")?.children()?.forEach { c ->
            val v = c.selectFirst("a|srgbClr")?.attr("val") ?: c.selectFirst("a|sysClr")?.attr("lastClr")
            if (v != null) m[c.tagName().substringAfter(':')] = v
        }
        m.putIfAbsent("dk1", "000000"); m.putIfAbsent("lt1", "FFFFFF")
        m["tx1"] = m["dk1"]!!; m["bg1"] = m["lt1"]!!; m["tx2"] = m["dk2"] ?: m["dk1"]!!; m["bg2"] = m["lt2"] ?: m["lt1"]!!
        val major = d?.selectFirst("a|majorFont > a|latin")?.attr("typeface")?.ifBlank { null } ?: "Calibri Light"
        val minor = d?.selectFirst("a|minorFont > a|latin")?.attr("typeface")?.ifBlank { null } ?: "Calibri"
        return m to (major to minor)
    }

    /** The CSS color for a fill/line element's color child (srgbClr, schemeClr, sysClr, prstClr) with its modifiers. */
    private fun color(ctx: Ctx, holder: Element?): String? {
        val c = holder?.children()?.firstOrNull { it.tagName().substringAfter(':') in setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr") } ?: return null
        var hex = when (c.tagName().substringAfter(':')) {
            "srgbClr" -> c.attr("val")
            "schemeClr" -> ctx.theme[c.attr("val")] ?: "000000"
            "sysClr" -> c.attr("lastClr").ifBlank { if (c.attr("val") == "window") "FFFFFF" else "000000" }
            "prstClr" -> mapOf("black" to "000000", "white" to "FFFFFF", "red" to "FF0000", "blue" to "0000FF", "green" to "008000", "yellow" to "FFFF00", "gray" to "808080")[c.attr("val")] ?: "000000"
            else -> "000000"
        }.padStart(6, '0').takeLast(6)
        var r = hex.substring(0, 2).toInt(16) / 255.0; var g = hex.substring(2, 4).toInt(16) / 255.0; var b = hex.substring(4, 6).toInt(16) / 255.0
        var alpha = 1.0
        c.children().forEach { mod ->
            val v = (mod.attr("val").toDoubleOrNull() ?: 100000.0) / 100000.0
            when (mod.tagName().substringAfter(':')) {
                "alpha" -> alpha = v
                "lumMod", "lumOff" -> {
                    val hsl = hsl(r, g, b)
                    hsl[2] = if (mod.tagName().endsWith("lumMod")) hsl[2] * v else hsl[2] + v
                    hsl[2] = hsl[2].coerceIn(0.0, 1.0)
                    val rgb = rgb(hsl); r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
                "tint" -> { r += (1 - r) * (1 - v); g += (1 - g) * (1 - v); b += (1 - b) * (1 - v) }
                "shade" -> { r *= v; g *= v; b *= v }
            }
        }
        hex = "%02X%02X%02X".format((r * 255).roundToInt().coerceIn(0, 255), (g * 255).roundToInt().coerceIn(0, 255), (b * 255).roundToInt().coerceIn(0, 255))
        return if (alpha >= 0.999) "#$hex" else "rgba(${hex.substring(0, 2).toInt(16)},${hex.substring(2, 4).toInt(16)},${hex.substring(4, 6).toInt(16)},${"%.3f".format(java.util.Locale.US, alpha)})"
    }

    private fun hsl(r: Double, g: Double, b: Double): DoubleArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn; val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 } / 6
        return doubleArrayOf(h, s, l)
    }

    private fun rgb(hsl: DoubleArray): DoubleArray {
        val (h, s, l) = Triple(hsl[0], hsl[1], hsl[2])
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s; val p = 2 * l - q
        fun hue(t0: Double): Double { var t = t0; if (t < 0) t += 1; if (t > 1) t -= 1; return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p } }
        return doubleArrayOf(hue(h + 1.0 / 3), hue(h), hue(h - 1.0 / 3))
    }

    /** CSS for a fill (solid, gradient, picture) found under [props] (spPr, bgPr, tcPr…); null when there's none / noFill. */
    private fun fill(ctx: Ctx, props: Element?, maxImg: Int): String? {
        props ?: return null
        props.children().forEach { ch ->
            when (ch.tagName().substringAfter(':')) {
                "noFill" -> return "background:transparent"
                "solidFill" -> return color(ctx, ch)?.let { "background:$it" }
                "gradFill" -> {
                    val stops = ch.select("a|gs").mapNotNull { gs -> color(ctx, gs)?.let { c -> "$c ${(gs.attr("pos").toDoubleOrNull() ?: 0.0) / 1000}%" } }
                    val ang = ((ch.selectFirst("a|lin")?.attr("ang")?.toDoubleOrNull() ?: 0.0) / 60000 + 90) % 360
                    if (stops.size >= 2) return "background:linear-gradient(${f(ang)}deg,${stops.joinToString(",")})"
                    if (stops.size == 1) return "background:${stops[0].substringBefore(' ')}"
                }
                "blipFill" -> return image(ctx, ch, maxImg)?.let { "background:url('$it') center/cover no-repeat" }
            }
        }
        return null
    }

    private fun background(ctx: Ctx, d: Document): String? {
        val bg = d.selectFirst("p|cSld > p|bg") ?: return null
        bg.selectFirst("p|bgPr")?.let { pr -> return fill(ctx, pr, 8_000_000) }
        bg.selectFirst("p|bgRef")?.let { ref -> return color(ctx, ref)?.let { "background:$it" } }
        return null
    }

    private fun image(ctx: Ctx, blipHolder: Element, maxImg: Int): String? {
        val id = blipHolder.selectFirst("a|blip")?.attr("r:embed") ?: return null
        val target = ctx.rels[id] ?: return null
        val bytes = ctx.pkg.bytes(target)?.takeIf { it.size <= maxImg } ?: return null
        val mime = when (target.substringAfterLast('.').lowercase()) { "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "bmp" -> "image/bmp"; "emf", "wmf" -> return null; else -> "image/jpeg" }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    // ------------------------------------------------------------------ shapes

    /** Group transform: child coordinates (chOff/chExt) mapped onto the group's box (off/ext). */
    private data class Xf(val ox: Double = 0.0, val oy: Double = 0.0, val sx: Double = 1.0, val sy: Double = 1.0, val cx: Double = 0.0, val cy: Double = 0.0) {
        fun x(v: Double) = ox + (v - cx) * sx
        fun y(v: Double) = oy + (v - cy) * sy
    }

    private data class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Double, val flipH: Boolean, val flipV: Boolean)

    private fun box(xfrm: Element?, t: Xf): Box? {
        val off = xfrm?.selectFirst("a|off") ?: return null
        val ext = xfrm.selectFirst("a|ext") ?: return null
        return Box(t.x(px(off.attr("x"))), t.y(px(off.attr("y"))), px(ext.attr("cx")) * t.sx, px(ext.attr("cy")) * t.sy,
            (xfrm.attr("rot").toDoubleOrNull() ?: 0.0) / 60000, xfrm.attr("flipH") == "1", xfrm.attr("flipV") == "1")
    }

    /** Where a placeholder without its own position sits: the matching placeholder on the layout, then the master. */
    private fun inherited(ctx: Ctx, ph: Element?): Pair<Element?, Element?> {
        ph ?: return null to null
        val type = ph.attr("type").ifBlank { "body" }; val idx = ph.attr("idx")
        for (part in listOfNotNull(ctx.layout, ctx.master)) {
            val d = ctx.pkg.doc(part) ?: continue
            val m = d.select("p|sp").firstOrNull { sp -> sp.selectFirst("p|nvPr > p|ph")?.let { p -> (idx.isNotEmpty() && p.attr("idx") == idx) || p.attr("type").ifBlank { "body" } == type } == true }
            if (m != null && m.selectFirst("p|spPr > a|xfrm") != null) return m.selectFirst("p|spPr > a|xfrm") to m
        }
        return null to null
    }

    private fun shapes(ctx: Ctx, tree: Element, out: StringBuilder, t: Xf, maxImg: Int, decorOnly: Boolean = false) {
        for (el in tree.children()) {
            when (el.tagName().substringAfter(':')) {
                "sp", "cxnSp" -> {
                    val ph = el.selectFirst("p|nvSpPr > p|nvPr > p|ph, p|nvCxnSpPr > p|nvPr > p|ph")
                    if (decorOnly && ph != null) continue // layout/master placeholders are prompts, not content
                    val spPr = el.selectFirst("p|spPr")
                    val (inhXfrm, inhSp) = if (spPr?.selectFirst("a|xfrm") == null) inherited(ctx, ph) else null to null
                    val b = box(spPr?.selectFirst("a|xfrm") ?: inhXfrm, t) ?: continue
                    shape(ctx, el, spPr, b, ph, inhSp, out, maxImg)
                }
                "pic" -> {
                    if (decorOnly && el.selectFirst("p|nvPicPr > p|nvPr > p|ph") != null) continue
                    val b = box(el.selectFirst("p|spPr > a|xfrm"), t) ?: continue
                    val src = el.selectFirst("p|blipFill")?.let { image(ctx, it, maxImg) } ?: continue
                    val round = el.selectFirst("p|spPr a|prstGeom")?.attr("prst") == "ellipse"
                    out.append("<img class=\"pic\" src=\"$src\" style=\"${pos(b)}${if (round) "border-radius:50%;" else ""}object-fit:fill\">")
                }
                "graphicFrame" -> {
                    if (decorOnly) continue
                    val b = box(el.selectFirst("p|xfrm"), t) ?: continue
                    val tbl = el.selectFirst("a|tbl")
                    val chartId = el.selectFirst("c|chart")?.attr("r:id")
                    val chartSvg = chartId?.let { ctx.rels[it] }?.let { part -> ctx.pkg.doc(part) }?.let { runCatching { PptxCharts.svg(it, b.w, b.h) { holder -> color(ctx, holder) } }.getOrNull() }
                    if (tbl != null) table(ctx, tbl, b, out)
                    else if (chartSvg != null) out.append("<div class=\"sp\" style=\"${pos(b)}\">$chartSvg</div>")
                    else {
                        val what = when { el.selectFirst("c|chart") != null || el.html().contains("/chart\"") -> "Chart"; el.html().contains("diagram") -> "SmartArt diagram"; else -> "Embedded object" }
                        out.append("<div class=\"obj\" style=\"${pos(b)}\"><span>$what · open in PowerPoint to see it</span></div>")
                    }
                }
                "grpSp" -> {
                    val gx = el.selectFirst("p|grpSpPr > a|xfrm") ?: continue
                    val off = gx.selectFirst("a|off"); val ext = gx.selectFirst("a|ext"); val chOff = gx.selectFirst("a|chOff"); val chExt = gx.selectFirst("a|chExt")
                    if (off == null || ext == null || chOff == null || chExt == null) continue
                    val cw = px(chExt.attr("cx")).takeIf { it > 0 } ?: 1.0; val ch = px(chExt.attr("cy")).takeIf { it > 0 } ?: 1.0
                    val sx = px(ext.attr("cx")) / cw * t.sx; val sy = px(ext.attr("cy")) / ch * t.sy
                    shapes(ctx, el, out, Xf(t.x(px(off.attr("x"))), t.y(px(off.attr("y"))), sx, sy, px(chOff.attr("x")), px(chOff.attr("y"))), maxImg, decorOnly)
                }
                "AlternateContent" -> el.selectFirst("mc|Fallback")?.let { shapes(ctx, it, out, t, maxImg, decorOnly) }
            }
        }
    }

    private fun pos(b: Box): String {
        val tr = buildList {
            if (b.rot != 0.0) add("rotate(${f(b.rot)}deg)")
            if (b.flipH) add("scaleX(-1)"); if (b.flipV) add("scaleY(-1)")
        }
        return "left:${f(b.x)}px;top:${f(b.y)}px;width:${f(b.w)}px;height:${f(b.h)}px;" + if (tr.isNotEmpty()) "transform:${tr.joinToString(" ")};" else ""
    }

    private fun shape(ctx: Ctx, el: Element, spPr: Element?, b: Box, ph: Element?, inh: Element?, out: StringBuilder, maxImg: Int) {
        val geom = spPr?.selectFirst("a|prstGeom")?.attr("prst").orEmpty()
        val style = el.selectFirst("p|style")
        val fillCss = fill(ctx, spPr, maxImg) ?: style?.selectFirst("a|fillRef")?.takeIf { (it.attr("idx").toIntOrNull() ?: 0) > 0 }?.let { r -> color(ctx, r)?.let { "background:$it" } }
        val ln = spPr?.selectFirst("a|ln")
        val lineColor = when {
            ln?.selectFirst("a|noFill") != null -> null
            ln?.selectFirst("a|solidFill") != null -> color(ctx, ln.selectFirst("a|solidFill"))
            ln != null || (style?.selectFirst("a|lnRef")?.attr("idx")?.toIntOrNull() ?: 0) > 0 -> style?.selectFirst("a|lnRef")?.let { color(ctx, it) }
            else -> null
        }
        val lineW = max(1.0, px(ln?.attr("w") ?: "12700"))
        val css = StringBuilder(pos(b))
        if (el.tagName().endsWith("cxnSp") || geom == "line" || geom.startsWith("straightConnector")) {
            // a line: draw it as an SVG across its box
            val c = lineColor ?: "#000"
            val (x1, y1, x2, y2) = if (b.flipV) listOf(0.0, b.h, b.w, 0.0) else listOf(0.0, 0.0, b.w, b.h)
            out.append("<svg class=\"ln\" style=\"left:${f(b.x)}px;top:${f(b.y)}px;width:${f(max(b.w, 1.0))}px;height:${f(max(b.h, 1.0))}px;overflow:visible\"><line x1=\"${f(x1)}\" y1=\"${f(y1)}\" x2=\"${f(x2)}\" y2=\"${f(y2)}\" stroke=\"$c\" stroke-width=\"${f(lineW)}\"/></svg>")
            return
        }
        fillCss?.let { css.append(it).append(';') }
        lineColor?.let { css.append("border:${f(lineW)}px solid $it;") }
        when (geom) {
            "ellipse", "flowChartConnector" -> css.append("border-radius:50%;")
            "roundRect" -> {
                val adj = (spPr?.selectFirst("a|gd[name=adj]")?.attr("fmla")?.removePrefix("val ")?.toDoubleOrNull() ?: 16667.0) / 100000
                css.append("border-radius:${f(min(b.w, b.h) * adj)}px;")
            }
            "round2SameRect" -> css.append("border-radius:${f(min(b.w, b.h) * 0.16667)}px ${f(min(b.w, b.h) * 0.16667)}px 0 0;")
            "triangle" -> css.append("clip-path:polygon(50% 0,100% 100%,0 100%);")
            "rightArrow" -> css.append("clip-path:polygon(0 25%,60% 25%,60% 0,100% 50%,60% 100%,60% 75%,0 75%);")
            "chevron" -> css.append("clip-path:polygon(0 0,75% 0,100% 50%,75% 100%,0 100%,25% 50%);")
            "homePlate" -> css.append("clip-path:polygon(0 0,80% 0,100% 50%,80% 100%,0 100%);")
            "diamond" -> css.append("clip-path:polygon(50% 0,100% 50%,50% 100%,0 50%);")
        }
        val body = el.selectFirst("p|txBody")
        val text = body?.let { paragraphs(ctx, it, ph, inh, b) }.orEmpty()
        val bodyPr = body?.selectFirst("a|bodyPr")
        val anchor = bodyPr?.attr("anchor")?.ifBlank { null } ?: inh?.selectFirst("a|bodyPr")?.attr("anchor")?.ifBlank { null }
            ?: if (ph?.attr("type") in setOf("ctrTitle", "title")) "ctr" else "t"
        val ins = listOf("tIns" to 45720, "rIns" to 91440, "bIns" to 45720, "lIns" to 91440).joinToString(" ") { (k, d) -> f(px(bodyPr?.attr(k)?.ifBlank { null } ?: d.toString())) + "px" }
        val justify = when (anchor) { "ctr" -> "center"; "b" -> "flex-end"; else -> "flex-start" }
        val vert = bodyPr?.attr("vert").orEmpty().let { if (it == "vert" || it == "eaVert") "writing-mode:vertical-rl;" else if (it == "vert270") "writing-mode:vertical-rl;transform:rotate(180deg);" else "" }
        val wrap = if (bodyPr?.attr("wrap") == "none") "white-space:nowrap;" else ""
        out.append("<div class=\"sp\" style=\"$css\"><div class=\"tx\" style=\"padding:$ins;justify-content:$justify;$vert$wrap\">$text</div></div>")
    }

    private fun paragraphs(ctx: Ctx, body: Element, ph: Element?, inh: Element?, b: Box): String {
        val type = ph?.attr("type").orEmpty()
        val isTitle = type == "title" || type == "ctrTitle"
        val scale = (body.selectFirst("a|bodyPr > a|normAutofit")?.attr("fontScale")?.toDoubleOrNull() ?: 100000.0) / 100000
        // default size: from the shape's list style, the inherited placeholder, then the usual PowerPoint defaults
        fun lvlDefault(lvl: Int): Double? = listOfNotNull(body.selectFirst("a|lstStyle"), inh?.selectFirst("p|txBody > a|lstStyle"))
            .firstNotNullOfOrNull { ls -> ls.selectFirst("a|lvl${lvl + 1}pPr > a|defRPr")?.attr("sz")?.toDoubleOrNull() }?.div(100)
        val fallbackPt = when { isTitle -> if (type == "ctrTitle") 44.0 else 36.0; type == "subTitle" -> 20.0; ph != null -> 20.0; else -> 18.0 }
        val sb = StringBuilder()
        var autoNum = 0
        for (p in body.select("> a|p")) {
            val pPr = p.selectFirst("> a|pPr")
            val lvl = pPr?.attr("lvl")?.toIntOrNull() ?: 0
            val algn = when (pPr?.attr("algn") ?: inh?.selectFirst("a|lvl${lvl + 1}pPr")?.attr("algn")) { "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; else -> if (isTitle && type == "ctrTitle") "center" else "left" }
            val endSz = p.selectFirst("> a|endParaRPr")?.attr("sz")?.toDoubleOrNull()?.div(100)
            val runs = StringBuilder()
            var firstPt: Double? = null
            var firstColor: String? = null
            for (r in p.children()) {
                when (r.tagName().substringAfter(':')) {
                    "r", "fld" -> {
                        val rPr = r.selectFirst("> a|rPr")
                        val pt = (rPr?.attr("sz")?.toDoubleOrNull()?.div(100) ?: lvlDefault(lvl) ?: fallbackPt) * scale
                        if (firstPt == null) firstPt = pt
                        val c = rPr?.selectFirst("> a|solidFill")?.let { color(ctx, it) } ?: inh?.selectFirst("a|lvl${lvl + 1}pPr a|defRPr > a|solidFill")?.let { color(ctx, it) }
                            ?: el(ctx, r)
                        if (firstColor == null) firstColor = c
                        val face = rPr?.selectFirst("> a|latin")?.attr("typeface")?.takeIf { it.isNotBlank() && !it.startsWith("+") } ?: if (isTitle) ctx.fonts.first else ctx.fonts.second
                        val st = StringBuilder("font-size:${f(pt * 96 / 72)}px;font-family:${fontStack(face)};")
                        if (rPr?.attr("b") == "1") st.append("font-weight:700;")
                        if (rPr?.attr("i") == "1") st.append("font-style:italic;")
                        if (rPr?.attr("u").let { !it.isNullOrEmpty() && it != "none" }) st.append("text-decoration:underline;")
                        if (rPr?.attr("strike")?.let { it != "noStrike" && it.isNotEmpty() } == true) st.append("text-decoration:line-through;")
                        rPr?.attr("baseline")?.toIntOrNull()?.let { if (it > 0) st.append("vertical-align:super;font-size:${f(pt * 0.7 * 96 / 72)}px;") else if (it < 0) st.append("vertical-align:sub;font-size:${f(pt * 0.7 * 96 / 72)}px;") }
                        rPr?.attr("spc")?.toDoubleOrNull()?.let { st.append("letter-spacing:${f(it / 100 * 96 / 72)}px;") }
                        if (rPr?.attr("cap") == "all") st.append("text-transform:uppercase;")
                        c?.let { st.append("color:$it;") }
                        val txt = esc(r.selectFirst("> a|t")?.wholeText().orEmpty())
                        runs.append("<span style=\"$st\">$txt</span>")
                    }
                    "br" -> runs.append("<br>")
                }
            }
            val pt = firstPt ?: (endSz ?: lvlDefault(lvl) ?: fallbackPt) * scale
            // bullets: explicit character or numbering; body placeholders bullet by default (titles never)
            val bu = when {
                pPr?.selectFirst("a|buNone") != null -> null
                pPr?.selectFirst("a|buChar") != null -> esc(pPr.selectFirst("a|buChar")!!.attr("char"))
                pPr?.selectFirst("a|buAutoNum") != null -> { autoNum++; "$autoNum." }
                type == "body" || (ph != null && type.isEmpty() && !isTitle && runs.isNotEmpty() && inh?.selectFirst("a|buNone") == null && body.selectFirst("a|lstStyle a|buNone") == null) -> "•"
                else -> null
            }
            if (pPr?.selectFirst("a|buAutoNum") == null) autoNum = 0
            val indent = lvl * 28 + (if (bu != null) 24 else 0)
            val spaceBefore = pPr?.selectFirst("a|spcBef > a|spcPts")?.attr("val")?.toDoubleOrNull()?.let { it / 100 * 96 / 72 } ?: 0.0
            val lineH = pPr?.selectFirst("a|lnSpc > a|spcPct")?.attr("val")?.toDoubleOrNull()?.let { it / 100000 * 1.2 } ?: 1.2
            val buHtml = bu?.let { "<span class=\"bu\" style=\"font-size:${f(pt * 96 / 72)}px;${firstColor?.let { c -> "color:$c;" } ?: ""}\">$it</span>" } ?: ""
            sb.append("<p style=\"text-align:$algn;padding-left:${indent}px;margin-top:${f(spaceBefore)}px;line-height:$lineH;font-size:${f(pt * 96 / 72)}px\">$buHtml${if (runs.isEmpty()) "&nbsp;" else runs}</p>")
        }
        return sb.toString()
    }

    /** Text color from the shape style's fontRef (e.g. white text on accent-filled shapes), else none. */
    private fun el(ctx: Ctx, run: Element): String? {
        var e: Element? = run
        while (e != null && !e.tagName().endsWith(":sp")) e = e.parent()
        return e?.selectFirst("p|style > a|fontRef")?.let { color(ctx, it) }
    }

    private fun fontStack(face: String): String {
        val alt = when (face.lowercase()) {
            "calibri", "calibri light" -> "'Carlito'"; "cambria" -> "'Caladea'"; "arial" -> "'Liberation Sans'"; "times new roman" -> "'Liberation Serif'"
            "segoe ui", "segoe ui light", "segoe ui semibold" -> "'Segoe UI Variable','Inter'"; "aptos", "aptos display" -> "'Inter'"; else -> null
        }
        val generic = if (face.lowercase().let { it.contains("serif") && !it.contains("sans") || it.contains("times") || it.contains("georgia") || it.contains("cambria") }) "serif" else "sans-serif"
        return "'${face.replace("'", "")}',${alt?.let { "$it," } ?: ""}$generic"
    }

    private fun table(ctx: Ctx, tbl: Element, b: Box, out: StringBuilder) {
        val cols = tbl.select("a|tblGrid > a|gridCol").map { px(it.attr("w")) }
        val sb = StringBuilder("<table class=\"tb\" style=\"left:${f(b.x)}px;top:${f(b.y)}px;width:${f(cols.sum().takeIf { it > 0 } ?: b.w)}px\"><colgroup>")
        cols.forEach { sb.append("<col style=\"width:${f(it)}px\">") }
        sb.append("</colgroup>")
        val firstRow = tbl.selectFirst("a|tblPr")?.attr("firstRow") == "1"
        tbl.select("> a|tr").forEachIndexed { ri, tr ->
            sb.append("<tr style=\"height:${f(px(tr.attr("h")))}px\">")
            tr.select("> a|tc").forEach { tc ->
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") return@forEach
                val span = (tc.attr("gridSpan").toIntOrNull() ?: 1).let { if (it > 1) " colspan=\"$it\"" else "" } + (tc.attr("rowSpan").toIntOrNull() ?: 1).let { if (it > 1) " rowspan=\"$it\"" else "" }
                val tcPr = tc.selectFirst("> a|tcPr")
                val bg = tcPr?.let { fill(ctx, it, 0) } ?: if (firstRow && ri == 0) "background:#${ctx.theme["accent1"] ?: "4472C4"}" else ""
                val txt = tc.selectFirst("> a|txBody")?.let { paragraphs(ctx, it, null, null, b) }.orEmpty()
                sb.append("<td$span style=\"$bg;${if (firstRow && ri == 0) "color:#fff;font-weight:700;" else ""}\">$txt</td>")
            }
            sb.append("</tr>")
        }
        out.append(sb.append("</table>"))
    }

    // ------------------------------------------------------------------ page

    /**
     * A self-contained HTML page for the deck. [present] = one slide at a time, centered on black, scaled to fit;
     * otherwise every slide stacked, scaled to the width, with its number. JS: show(i) jumps to slide i (0-based).
     */
    fun page(deck: Deck, present: Boolean, dark: Boolean): String {
        val bg = if (present) "#000" else if (dark) "#141414" else "#EDEDED"
        val css = """
            *{box-sizing:border-box}html,body{margin:0;background:$bg;-webkit-text-size-adjust:none;overflow-x:hidden}
            .wrap{position:relative;margin:${if (present) "0" else "0 auto 14px"};overflow:hidden;${if (present) "" else "box-shadow:0 2px 10px rgba(0,0,0,.25);border-radius:4px;"}}
            .sl{position:absolute;left:0;top:0;transform-origin:0 0;overflow:hidden;font-family:Calibri,Carlito,'Liberation Sans',sans-serif;color:#000}
            .sp,.pic,.obj,.ln,.tb{position:absolute}
            .sp{display:flex}.tx{display:flex;flex-direction:column;width:100%;height:100%;overflow:visible}
            .tx p{margin:0;position:relative;word-wrap:break-word}
            .bu{position:absolute;margin-left:-22px}
            .obj{display:flex;align-items:center;justify-content:center;border:1px dashed rgba(0,0,0,.35);background:rgba(0,0,0,.04);color:#555;font:600 18px sans-serif;text-align:center;padding:8px}
            .tb{border-collapse:collapse;table-layout:fixed}.tb td{border:1px solid rgba(0,0,0,.25);padding:4px 8px;vertical-align:top;overflow:hidden}
            .tb td p{margin:0}
            .num{color:${if (dark) "#9a9a9a" else "#666"};font:600 12px system-ui,sans-serif;margin:8px auto 4px;max-width:100%}
            ${if (present) ".wrap{display:none;position:absolute}.wrap.on{display:block}" else ""}
        """.trimIndent()
        val slides = deck.slides.joinToString("") { s ->
            (if (present) "" else "<div class=\"num\" data-n=\"${s.number - 1}\">${s.number}${if (s.hidden) " · hidden" else ""}${if (s.title.isNotBlank()) " · " + esc(s.title.take(70)) else ""}</div>") +
                "<div class=\"wrap\" id=\"s${s.number - 1}\">${s.html}</div>"
        }
        val js = """
            var W=${deck.width},H=${deck.height},N=${deck.slides.size},cur=0,PRESENT=$present;
            function fit(){var ws=document.querySelectorAll('.wrap');
              var vw=window.innerWidth||document.documentElement.clientWidth,vh=window.innerHeight||document.documentElement.clientHeight;
              if(vw<50||(PRESENT&&vh<50)){setTimeout(fit,120);return;} // the view isn't laid out yet
              var sc=PRESENT?Math.min(vw/W,vh/H):Math.min((vw-16)/W,2.5);
              for(var i=0;i<ws.length;i++){var w=ws[i];w.style.width=(W*sc)+'px';w.style.height=(H*sc)+'px';
                if(PRESENT){w.style.left=((vw-W*sc)/2)+'px';w.style.top=((vh-H*sc)/2)+'px';}
                w.firstChild.style.transform='scale('+sc+')';}
              var ns=document.querySelectorAll('.num');for(var j=0;j<ns.length;j++)ns[j].style.width=(W*sc)+'px';}
            function show(i){cur=Math.max(0,Math.min(N-1,i));
              if(PRESENT){var ws=document.querySelectorAll('.wrap');for(var k=0;k<ws.length;k++)ws[k].classList.toggle('on',k===cur);}
              else{var e=document.getElementById('s'+cur);if(e)window.scrollTo(0,e.offsetTop-30);}}
            function visible(){var ws=document.querySelectorAll('.wrap'),y=window.scrollY+window.innerHeight/3;for(var i=ws.length-1;i>=0;i--){if(ws[i].offsetTop<=y)return i;}return 0;}
            window.addEventListener('resize',fit);window.addEventListener('load',fit);fit();if(PRESENT)show(0);
        """.trimIndent()
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>$css</style></head><body>$slides<script>$js</script></body></html>"
    }
}
