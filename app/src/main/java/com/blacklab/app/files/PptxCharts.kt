package com.blacklab.app.files

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Office charts (the chartN.xml part of a deck) drawn as SVG from the values PowerPoint cached in the file: column and
 * bar (clustered, stacked, 100 %), line, area, pie and doughnut, with series colors, data labels, a legend, the title
 * and value axis gridlines. Anything else falls back to the viewer's "open in PowerPoint" placeholder.
 */
object PptxCharts {
    private class Series(val name: String, val values: List<Double?>, val color: String?, val pointColors: Map<Int, String>, val labels: Boolean, val fmt: String)

    private val FALLBACK = listOf("#4472C4", "#ED7D31", "#A5A5A5", "#FFC000", "#5B9BD5", "#70AD47", "#264478", "#9E480E")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun f(d: Double) = "%.1f".format(Locale.US, d)

    private fun strs(el: Element?): List<String> {
        // multi-level categories: the first level (the innermost labels) is what PowerPoint shows next to the bars
        val cache = el?.selectFirst("c|strCache, c|numCache, c|strLit, c|numLit") ?: el?.selectFirst("c|multiLvlStrCache")?.let { m ->
            m.selectFirst("c|lvl")?.also { lvl -> m.selectFirst("c|ptCount")?.let { lvl.prependChild(it.clone()) } }
        } ?: return emptyList()
        val n = cache.selectFirst("c|ptCount")?.attr("val")?.toIntOrNull() ?: 0
        val out = MutableList(n) { "" }
        cache.select("c|pt").forEach { p -> val i = p.attr("idx").toIntOrNull() ?: return@forEach; if (i in out.indices) out[i] = p.selectFirst("c|v")?.text().orEmpty() else if (i == out.size) out += p.selectFirst("c|v")?.text().orEmpty() }
        return out
    }

    fun svg(doc: Document, w: Double, h: Double, color: (Element?) -> String?): String? {
        val plot = doc.selectFirst("c|plotArea") ?: return null
        val kind = plot.children().firstOrNull { it.tagName().substringAfter(':') in setOf("barChart", "bar3DChart", "lineChart", "areaChart", "pieChart", "pie3DChart", "doughnutChart") } ?: return null
        val type = kind.tagName().substringAfter(':')
        val series = kind.select("> c|ser").mapIndexed { i, s ->
            val vals = strs(s.selectFirst("> c|val")).map { it.toDoubleOrNull() }
            val col = color(s.selectFirst("> c|spPr > a|solidFill")) ?: color(s.selectFirst("> c|spPr > a|ln > a|solidFill"))
            val pts = s.select("> c|dPt").mapNotNull { d -> val idx = d.selectFirst("c|idx")?.attr("val")?.toIntOrNull() ?: return@mapNotNull null; color(d.selectFirst("c|spPr > a|solidFill"))?.let { idx to it } }.toMap()
            val lbl = (s.selectFirst("> c|dLbls > c|showVal") ?: kind.selectFirst("> c|dLbls > c|showVal"))?.attr("val") == "1"
            val fmt = s.selectFirst("> c|val c|formatCode")?.text() ?: "General"
            Series(strs(s.selectFirst("> c|tx")).firstOrNull() ?: s.selectFirst("> c|tx > c|v")?.text() ?: "Series ${i + 1}", vals, col, pts, lbl, fmt)
        }.ifEmpty { return null }
        val cats = strs(kind.selectFirst("> c|ser > c|cat")).ifEmpty { (1..(series.maxOf { it.values.size })).map { it.toString() } }
        val title = if (doc.selectFirst("c|autoTitleDeleted")?.attr("val") == "1") "" else doc.selectFirst("c|chart > c|title")?.select("a|t")?.joinToString("") { it.text() }.orEmpty()
        val legendPos = doc.selectFirst("c|legend > c|legendPos")?.attr("val")?.takeIf { doc.selectFirst("c|legend") != null }
        val fs = max(10.0, min(16.0, h / 22))
        val sb = StringBuilder("<svg viewBox=\"0 0 ${f(w)} ${f(h)}\" width=\"100%\" height=\"100%\" style=\"font-family:Calibri,Carlito,'Liberation Sans',sans-serif;font-size:${f(fs)}px;fill:#404040\">")
        var top = 6.0; var bottom = h - 6.0
        if (title.isNotBlank()) { sb.append("<text x=\"${f(w / 2)}\" y=\"${f(top + fs * 1.2)}\" text-anchor=\"middle\" style=\"font-size:${f(fs * 1.25)}px;font-weight:700\">${esc(title)}</text>"); top += fs * 1.9 }
        val pie = type.startsWith("pie") || type == "doughnutChart"
        // legend at the bottom (or right, drawn at the bottom on phones for room)
        val legendItems = if (pie) cats.mapIndexed { i, c -> c to colorFor(series[0], i, color, true) } else series.mapIndexed { i, s -> s.name to (s.color ?: FALLBACK[i % FALLBACK.size]) }
        if (legendPos != null) {
            val rowH = fs * 1.6; var x = 8.0; var y = bottom - rowH * 0.3
            val lines = ArrayList<MutableList<Pair<String, String>>>().apply { add(mutableListOf()) }
            var lineW = 0.0
            legendItems.forEach { li -> val wItem = fs * 1.4 + li.first.length * fs * 0.52 + 14; if (lineW + wItem > w - 16 && lines.last().isNotEmpty()) { lines += mutableListOf<Pair<String, String>>(); lineW = 0.0 }; lines.last() += li; lineW += wItem }
            y = bottom - rowH * (lines.size - 1) - fs * 0.3
            lines.forEach { line ->
                val total = line.sumOf { fs * 1.4 + it.first.length * fs * 0.52 + 14 }
                x = (w - total) / 2
                line.forEach { (name, c) ->
                    sb.append("<rect x=\"${f(x)}\" y=\"${f(y - fs * 0.8)}\" width=\"${f(fs * 0.8)}\" height=\"${f(fs * 0.8)}\" fill=\"$c\"/><text x=\"${f(x + fs * 1.1)}\" y=\"${f(y)}\">${esc(name)}</text>")
                    x += fs * 1.4 + name.length * fs * 0.52 + 14
                }
                y += rowH
            }
            bottom -= rowH * lines.size + 4
        }
        if (pie) pie(sb, series[0], cats, w, top, bottom, fs, if (type == "doughnutChart") (kind.selectFirst("c|holeSize")?.attr("val")?.toDoubleOrNull() ?: 50.0) / 100 else 0.0, color)
        else axes(sb, type, kind, series, cats, w, top, bottom, fs)
        return sb.append("</svg>").toString()
    }

    private fun colorFor(s: Series, i: Int, color: (Element?) -> String?, pie: Boolean): String = s.pointColors[i] ?: if (pie) FALLBACK[i % FALLBACK.size] else s.color ?: FALLBACK[0]

    private fun label(v: Double, fmt: String): String = XlsxBook.format(v.toString(), fmt).let { if (fmt == "General") Sheets.fmt(if (abs(v) >= 100) Math.round(v).toDouble() else (Math.round(v * 100) / 100.0)) else it }

    private fun pie(sb: StringBuilder, s: Series, cats: List<String>, w: Double, top: Double, bottom: Double, fs: Double, hole: Double, color: (Element?) -> String?) {
        val vals = s.values.map { max(0.0, it ?: 0.0) }; val total = vals.sum().takeIf { it > 0 } ?: return
        val r = min(w, bottom - top) / 2 - 4; val cx = w / 2; val cy = (top + bottom) / 2
        var a = -PI / 2
        vals.forEachIndexed { i, v ->
            val da = v / total * 2 * PI; if (da <= 0) return@forEachIndexed
            val c = colorFor(s, i, color, true)
            val x1 = cx + r * cos(a); val y1 = cy + r * sin(a); val x2 = cx + r * cos(a + da); val y2 = cy + r * sin(a + da)
            val large = if (da > PI) 1 else 0
            if (da >= 2 * PI - 1e-6) sb.append("<circle cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r)}\" fill=\"$c\"/>")
            else sb.append("<path d=\"M${f(cx)},${f(cy)} L${f(x1)},${f(y1)} A${f(r)},${f(r)} 0 $large 1 ${f(x2)},${f(y2)} Z\" fill=\"$c\" stroke=\"#fff\" stroke-width=\"1.5\"/>")
            if (s.labels) { val m = a + da / 2; val lr = if (hole > 0) r * (1 + hole) / 2 else r * 0.62
                sb.append("<text x=\"${f(cx + lr * cos(m))}\" y=\"${f(cy + lr * sin(m) + fs * 0.35)}\" text-anchor=\"middle\" style=\"fill:#fff;font-weight:700\">${esc(label(v, s.fmt))}</text>") }
            a += da
        }
        if (hole > 0) sb.append("<circle cx=\"${f(cx)}\" cy=\"${f(cy)}\" r=\"${f(r * hole)}\" fill=\"#fff\"/>")
    }

    private fun nice(maxV: Double): Pair<Double, Double> {
        if (maxV <= 0) return 1.0 to 0.25
        val raw = maxV / 4; val mag = 10.0.pow(floor(log10(raw))); val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).first { it * mag >= raw } * mag
        return ceil(maxV / step) * step to step
    }

    private fun axes(sb: StringBuilder, type: String, kind: Element, series: List<Series>, cats: List<String>, w: Double, top: Double, bottom0: Double, fs: Double) {
        val horizontal = kind.selectFirst("c|barDir")?.attr("val") == "bar"
        val grouping = kind.selectFirst("c|grouping")?.attr("val") ?: "clustered"
        val stacked = grouping == "stacked" || grouping == "percentStacked"; val pct = grouping == "percentStacked"
        val n = cats.size.coerceAtLeast(1)
        val totals = (0 until n).map { i -> series.sumOf { max(0.0, it.values.getOrNull(i) ?: 0.0) } }
        val maxV = when { pct -> 1.0; stacked -> totals.maxOrNull() ?: 1.0; else -> series.flatMap { it.values.filterNotNull() }.maxOrNull() ?: 1.0 }
        val minV = if (stacked) 0.0 else min(0.0, series.flatMap { it.values.filterNotNull() }.minOrNull() ?: 0.0)
        val (hi, step) = nice(maxV); val lo = if (minV < 0) -nice(-minV).first else 0.0
        val catLabelW = if (horizontal) min(w * 0.38, (cats.maxOfOrNull { it.length } ?: 4) * fs * 0.52 + 8) else 0.0
        val left = if (horizontal) catLabelW + 6 else fs * 3.2; val right = w - 10; val top = top + 4; val bottom = bottom0 - (if (horizontal) fs * 1.4 else fs * 2.6)
        fun vx(v: Double) = left + (v - lo) / (hi - lo) * (right - left)
        fun vy(v: Double) = bottom - (v - lo) / (hi - lo) * (bottom - top)
        // gridlines + value labels
        var g = lo
        while (g <= hi + step / 2) {
            val lbl = if (pct) "${Math.round(g * 100)}%" else label(g, series.first().fmt)
            if (horizontal) sb.append("<line x1=\"${f(vx(g))}\" x2=\"${f(vx(g))}\" y1=\"${f(top)}\" y2=\"${f(bottom)}\" stroke=\"#D9D9D9\"/><text x=\"${f(vx(g))}\" y=\"${f(bottom + fs * 1.2)}\" text-anchor=\"middle\">${esc(lbl)}</text>")
            else sb.append("<line x1=\"${f(left)}\" x2=\"${f(right)}\" y1=\"${f(vy(g))}\" y2=\"${f(vy(g))}\" stroke=\"#D9D9D9\"/><text x=\"${f(left - 6)}\" y=\"${f(vy(g) + fs * 0.35)}\" text-anchor=\"end\">${esc(lbl)}</text>")
            g += step
        }
        val slot = (if (horizontal) bottom - top else right - left) / n
        // PowerPoint draws a bar chart's first category at the bottom (unless its axis is reversed)
        val reversed = kind.parent()?.selectFirst("c|catAx c|orientation")?.attr("val") == "maxMin"
        fun slotOf(i: Int) = if (horizontal && !reversed) n - 1 - i else i
        cats.forEachIndexed { i, c ->
            val mid = (if (horizontal) top else left) + slot * (slotOf(i) + 0.5)
            if (horizontal) sb.append("<text x=\"${f(left - 6)}\" y=\"${f(mid + fs * 0.35)}\" text-anchor=\"end\">${esc(c.take(40))}</text>")
            else {
                val words = c.split(' '); val half = (words.size + 1) / 2
                val (l1, l2) = if (c.length * fs * 0.5 > slot && words.size > 1) words.take(half).joinToString(" ") to words.drop(half).joinToString(" ") else c to ""
                sb.append("<text x=\"${f(mid)}\" y=\"${f(bottom + fs * 1.2)}\" text-anchor=\"middle\">${esc(l1)}</text>")
                if (l2.isNotEmpty()) sb.append("<text x=\"${f(mid)}\" y=\"${f(bottom + fs * 2.3)}\" text-anchor=\"middle\">${esc(l2)}</text>")
            }
        }
        when {
            type.startsWith("bar") -> {
                val gap = (kind.selectFirst("c|gapWidth")?.attr("val")?.toDoubleOrNull() ?: 150.0) / 100
                val groupW = slot / (1 + gap); val barW = if (stacked) groupW else groupW / series.size
                for (i in 0 until n) {
                    var acc = 0.0
                    series.forEachIndexed { si, s ->
                        val raw = s.values.getOrNull(i) ?: return@forEachIndexed
                        val v = if (pct) (if (totals[i] > 0) max(0.0, raw) / totals[i] else 0.0) else raw
                        val c = s.pointColors[i] ?: s.color ?: FALLBACK[si % FALLBACK.size]
                        val start = (if (horizontal) top else left) + slot * slotOf(i) + (slot - groupW) / 2 + (if (stacked) 0.0 else barW * (if (horizontal && !reversed) series.size - 1 - si else si))
                        val a0 = if (stacked) acc else 0.0; val a1 = a0 + v
                        if (horizontal) {
                            val x0 = vx(min(a0, a1)); val x1 = vx(max(a0, a1))
                            sb.append("<rect x=\"${f(x0)}\" y=\"${f(start)}\" width=\"${f(x1 - x0)}\" height=\"${f(barW * 0.92)}\" fill=\"$c\"/>")
                            if (s.labels) sb.append("<text x=\"${f(if (stacked) (x0 + x1) / 2 else x1 + 4)}\" y=\"${f(start + barW * 0.46 + fs * 0.35)}\" text-anchor=\"${if (stacked) "middle" else "start"}\" style=\"font-weight:600${if (stacked) ";fill:#fff" else ""}\">${esc(if (pct) label(raw, s.fmt) else label(raw, s.fmt))}</text>")
                        } else {
                            val y0 = vy(max(a0, a1)); val y1 = vy(min(a0, a1))
                            sb.append("<rect x=\"${f(start)}\" y=\"${f(y0)}\" width=\"${f(barW * 0.92)}\" height=\"${f(y1 - y0)}\" fill=\"$c\"/>")
                            if (s.labels && y1 - y0 > fs * 0.8 || s.labels && !stacked) sb.append("<text x=\"${f(start + barW * 0.46)}\" y=\"${f(if (stacked) (y0 + y1) / 2 + fs * 0.35 else y0 - 4)}\" text-anchor=\"middle\" style=\"font-weight:600${if (stacked) ";fill:#fff" else ""}\">${esc(label(raw, s.fmt))}</text>")
                        }
                        acc = a1
                    }
                }
            }
            else -> series.forEachIndexed { si, s ->
                val c = s.color ?: FALLBACK[si % FALLBACK.size]
                val pts = (0 until n).mapNotNull { i -> s.values.getOrNull(i)?.let { (left + slot * (i + 0.5)) to vy(it) } }
                if (pts.isEmpty()) return@forEachIndexed
                val path = pts.joinToString(" ") { "${f(it.first)},${f(it.second)}" }
                if (type == "areaChart") sb.append("<polygon points=\"${f(pts.first().first)},${f(vy(0.0))} $path ${f(pts.last().first)},${f(vy(0.0))}\" fill=\"$c\" fill-opacity=\".55\"/>")
                else sb.append("<polyline points=\"$path\" fill=\"none\" stroke=\"$c\" stroke-width=\"2.5\" stroke-linejoin=\"round\" stroke-linecap=\"round\"/>")
                if (s.labels) pts.forEachIndexed { k, p -> s.values.getOrNull(k)?.let { v -> sb.append("<text x=\"${f(p.first)}\" y=\"${f(p.second - 6)}\" text-anchor=\"middle\">${esc(label(v, s.fmt))}</text>") } }
            }
        }
        sb.append("<line x1=\"${f(left)}\" x2=\"${f(if (horizontal) left else right)}\" y1=\"${f(if (horizontal) top else vy(0.0))}\" y2=\"${f(if (horizontal) bottom else vy(0.0))}\" stroke=\"#A6A6A6\"/>")
    }
}
