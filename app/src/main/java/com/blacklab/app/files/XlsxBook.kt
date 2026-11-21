package com.blacklab.app.files

import com.blacklab.app.docs.DocxWriter
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * An Excel workbook read the way Excel shows it: cell contents (formulas as "=…"), the values Excel last calculated,
 * number formats, bold/italic, fills, font colors, alignment, column widths, merged cells and frozen panes.
 *
 * Saving never rebuilds the file. [patch] copies every part of the original package byte for byte and rewrites only
 * the cells you changed (keeping each cell's style), then asks Excel to recalculate everything on open. Charts, named
 * ranges, validation, conditional formatting, pivot tables and macros are untouched.
 */
object XlsxBook {
    data class Style(
        val numFmt: String = "General", val bold: Boolean = false, val italic: Boolean = false,
        val fill: Long? = null, val color: Long? = null, val align: String = "", val wrap: Boolean = false,
    )

    class SheetInfo(
        val name: String,
        val path: String,
        /** Excel's last calculated value for each cell, as stored ("" when none). Key = [key]. */
        val cached: Map<Long, String>,
        /** Index into [Book.styles] per cell. */
        val styleOf: Map<Long, Int>,
        /** Column widths in Excel character units. */
        val widths: Map<Int, Double>,
        val merges: List<IntArray>,
        val frozenRows: Int, val frozenCols: Int,
        val hidden: Boolean,
        /** Shared-formula group id per cell (Excel stores a formula once and reuses it down a column). */
        val sharedGroup: Map<Long, String> = emptyMap(),
    )

    class Book(val sheets: MutableList<Sheet>, val info: List<SheetInfo>, val styles: List<Style>, val names: Map<String, String>) {
        fun style(sheet: Int, r: Int, c: Int): Style = info.getOrNull(sheet)?.styleOf?.get(key(r, c))?.let { styles.getOrNull(it) } ?: Style()
        fun cached(sheet: Int, r: Int, c: Int): String? = info.getOrNull(sheet)?.cached?.get(key(r, c))
    }

    fun key(r: Int, c: Int): Long = r.toLong() shl 20 or c.toLong()

    private fun xml(s: String) = Jsoup.parse(s, "", Parser.xmlParser())

    // ------------------------------------------------------------------ read

    fun load(f: File): Book = ZipFile(f).use { z ->
        fun text(n: String) = z.getEntry(n)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        val shared = text("xl/sharedStrings.xml")?.let { s -> xml(s).select("si").map { si -> si.select("t").joinToString("") { it.wholeText() } } } ?: emptyList()
        val wbXml = text("xl/workbook.xml") ?: error("Not a valid .xlsx file")
        val wb = xml(wbXml)
        val rels = text("xl/_rels/workbook.xml.rels")?.let { r -> xml(r).select("Relationship").associate { it.attr("Id") to OfficeReader.resolve("xl/workbook.xml", it.attr("Target")) } } ?: emptyMap()
        val styles = text("xl/styles.xml")?.let { readStyles(it) } ?: listOf(Style())
        val names = wb.select("definedNames > definedName").filter { it.attr("localSheetId").isEmpty() }
            .associate { it.attr("name").uppercase(Locale.US) to it.text().trim() }
        val sheets = ArrayList<Sheet>(); val info = ArrayList<SheetInfo>()
        wb.select("sheets > sheet").forEachIndexed { i, sh ->
            val path = rels[sh.attr("r:id")] ?: "xl/worksheets/sheet${i + 1}.xml"
            val sheet = Sheet(sh.attr("name").ifBlank { "Sheet${i + 1}" })
            val cached = HashMap<Long, String>(); val styleOf = HashMap<Long, Int>(); val widths = HashMap<Int, Double>(); val merges = ArrayList<IntArray>()
            var fr = 0; var fc = 0
            val masters = HashMap<String, Triple<String, Int, Int>>(); val group = HashMap<Long, String>()
            text(path)?.let { sx ->
                val doc = xml(sx)
                doc.select("cols > col").forEach { col ->
                    val w = col.attr("width").toDoubleOrNull() ?: return@forEach
                    val a = col.attr("min").toIntOrNull() ?: return@forEach; val b = (col.attr("max").toIntOrNull() ?: a).coerceAtMost(a + 200)
                    for (k in a..b) widths[k - 1] = if (col.attr("hidden") == "1") 0.0 else w
                }
                doc.select("mergeCells > mergeCell").forEach { m -> parseRange(m.attr("ref"))?.let { merges += it } }
                doc.selectFirst("sheetViews pane")?.let { p ->
                    if (p.attr("state").startsWith("frozen")) { fr = p.attr("ySplit").toDoubleOrNull()?.toInt() ?: 0; fc = p.attr("xSplit").toDoubleOrNull()?.toInt() ?: 0 }
                }
                doc.select("sheetData > row").forEachIndexed { ri, row ->
                    val r = (row.attr("r").toIntOrNull() ?: (ri + 1)) - 1
                    row.select("c").forEachIndexed { ci, c ->
                        val ref = c.attr("r")
                        val col = if (ref.isNotEmpty()) Sheets.colIndex(ref.takeWhile { it.isLetter() }) else ci
                        if (r >= 100_000 || col >= 1_000) return@forEachIndexed
                        val k = key(r, col)
                        c.attr("s").toIntOrNull()?.let { styleOf[k] = it }
                        val fe = c.selectFirst("f")
                        var f = fe?.text()
                        if (fe != null && fe.attr("t") == "shared") {
                            val si = fe.attr("si")
                            group[k] = si
                            if (!f.isNullOrBlank()) masters[si] = Triple(f, r, col)
                            else masters[si]?.let { (mf, mr, mc) -> f = shift(mf, r - mr, col - mc) }
                        }
                        val v = c.selectFirst("v")?.text().orEmpty()
                        val shown = when (c.attr("t")) {
                            "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                            "inlineStr" -> c.select("is t").joinToString("") { it.wholeText() }
                            "b" -> if (v == "1") "TRUE" else "FALSE"
                            "e", "str" -> v
                            else -> v
                        }
                        if (shown.isNotEmpty()) cached[k] = shown
                        val raw = if (!f.isNullOrBlank()) "=$f" else shown.let { if (c.attr("t").isEmpty() || c.attr("t") == "n") it.toDoubleOrNull()?.let(Sheets::fmt) ?: it else it }
                        if (raw.isNotEmpty()) sheet[r, col] = raw
                    }
                }
            }
            sheets += sheet
            info += SheetInfo(sheet.name, path, cached, styleOf, widths, merges, fr, fc, sh.attr("state") == "hidden" || sh.attr("state") == "veryHidden", group)
        }
        if (sheets.isEmpty()) { sheets += Sheet("Sheet1"); info += SheetInfo("Sheet1", "xl/worksheets/sheet1.xml", emptyMap(), emptyMap(), emptyMap(), emptyList(), 0, 0, false) }
        Book(sheets, info, styles, names)
    }

    /** "B2:D4" → [r1, c1, r2, c2]. */
    fun parseRange(ref: String): IntArray? {
        val m = Regex("\\$?([A-Z]{1,3})\\$?(\\d+)(?::\\$?([A-Z]{1,3})\\$?(\\d+))?").matchEntire(ref.uppercase(Locale.US)) ?: return null
        val r1 = m.groupValues[2].toInt() - 1; val c1 = Sheets.colIndex(m.groupValues[1])
        val r2 = m.groupValues[4].toIntOrNull()?.minus(1) ?: r1; val c2 = if (m.groupValues[3].isEmpty()) c1 else Sheets.colIndex(m.groupValues[3])
        return intArrayOf(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    private val BUILTIN_FORMATS = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00", 9 to "0%", 10 to "0.00%", 11 to "0.00E+00",
        12 to "# ?/?", 14 to "m/d/yyyy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy", 18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM",
        20 to "h:mm", 21 to "h:mm:ss", 22 to "m/d/yyyy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)",
        39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)", 44 to "_(\"$\"* #,##0.00_);_(\"$\"* (#,##0.00);_(\"$\"* \"-\"??_);_(@_)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@",
    )

    private fun argb(e: Element?): Long? {
        val rgb = e?.attr("rgb")?.takeIf { it.length >= 6 } ?: return null
        return (if (rgb.length == 6) "FF$rgb" else rgb).toLongOrNull(16)
    }

    fun readStyles(s: String): List<Style> {
        val d = xml(s)
        val fmts = BUILTIN_FORMATS.toMutableMap()
        d.select("numFmts > numFmt").forEach { n -> n.attr("numFmtId").toIntOrNull()?.let { fmts[it] = n.attr("formatCode") } }
        val fonts = d.select("fonts > font").map { f -> Triple(f.selectFirst("b")?.let { it.attr("val") != "0" } ?: false, f.selectFirst("i")?.let { it.attr("val") != "0" } ?: false, argb(f.selectFirst("color"))) }
        val fills = d.select("fills > fill").map { f -> f.selectFirst("patternFill")?.takeIf { it.attr("patternType") == "solid" }?.let { argb(it.selectFirst("fgColor")) } }
        return d.select("cellXfs > xf").map { x ->
            val font = fonts.getOrNull(x.attr("fontId").toIntOrNull() ?: 0)
            val al = x.selectFirst("alignment")
            Style(
                numFmt = fmts[x.attr("numFmtId").toIntOrNull() ?: 0] ?: "General",
                bold = font?.first ?: false, italic = font?.second ?: false,
                fill = fills.getOrNull(x.attr("fillId").toIntOrNull() ?: 0),
                color = font?.third?.takeIf { it != 0xFF000000 },
                align = al?.attr("horizontal").orEmpty(), wrap = al?.attr("wrapText") == "1",
            )
        }.ifEmpty { listOf(Style()) }
    }

    // ------------------------------------------------------------------ number formats

    private val EPOCH = LocalDate.of(1899, 12, 30)

    /**
     * [value] shown with an Excel number format: thousands separators, fixed decimals, percent, currency/accounting
     * (negatives in parentheses), scientific and dates/times. Text and anything unrecognized pass through.
     */
    fun format(value: String, numFmt: String): String {
        val d = value.toDoubleOrNull() ?: return value
        if (numFmt.isBlank() || numFmt.equals("General", true) || numFmt == "@") return Sheets.fmt(d)
        val sections = splitSections(numFmt)
        val sec = when { d < 0 && sections.size > 1 -> sections[1]; d == 0.0 && sections.size > 2 -> sections[2]; else -> sections[0] }
        val neg = d < 0 && sections.size == 1
        val code = sec.replace(Regex("\\[(Red|Black|Blue|Green|Color\\d+|White|Yellow|Magenta|Cyan)]", RegexOption.IGNORE_CASE), "")
        val x = if (sections.size > 1 && d < 0) -d else d
        if (isDate(code)) return runCatching { date(x, code) }.getOrDefault(Sheets.fmt(d))
        // literal text: "$", "-", spacing (_x), fill (*x), escaped chars (\x), [$$-409] currency
        val lit = code.replace(Regex("\\[\\$([^-\\]]*)(-[0-9A-Fa-f]+)?]"), "$1").replace(Regex("_."), "").replace(Regex("\\*."), "")
        val numPart = Regex("[#0?,.]*[0#?][#0?,.]*(?:E[+-]?0+)?%?").find(lit) ?: return lit.replace("\"", "")
        val p = numPart.value
        val pct = p.endsWith("%") || lit.contains('%')
        val sci = Regex("E[+-]?0+").find(p)
        val core = p.removeSuffix("%").replace(Regex("E[+-]?0+"), "")
        // trailing commas scale by 1,000 each
        val scaleCommas = core.takeLastWhile { it == ',' }.length
        val decimals = core.substringAfter('.', "").count { it == '0' || it == '#' || it == '?' }
        val minDecimals = core.substringAfter('.', "").count { it == '0' }
        val grouping = core.trimEnd(',').contains(',')
        var n = if (pct) x * 100 else x
        repeat(scaleCommas) { n /= 1000 }
        val body = if (sci != null) {
            val digits = sci.value.count { it == '0' }
            val m = Regex("^(-?[\\d.]+)E([+-])(\\d+)$").find("%.${decimals}E".format(Locale.US, n))
            if (m == null) Sheets.fmt(n) else m.groupValues[1] + "E" + m.groupValues[2] + m.groupValues[3].trimStart('0').padStart(digits, '0')
        }
        else {
            val f = "%${if (grouping) "," else ""}.${decimals}f".format(Locale.US, abs(n))
            val trimmed = if (decimals > minDecimals && f.contains('.')) f.trimEnd('0').let { t -> val keep = t.substringAfter('.').length; if (keep < minDecimals) t + "0".repeat(minDecimals - keep) else t }.trimEnd('.') else f
            (if (n < 0 && !neg) "-" else "") + trimmed
        }
        val before = lit.substring(0, numPart.range.first).replace("\"", "").replace("\\", "")
        val after = lit.substring(numPart.range.last + 1).replace("\"", "").replace("\\", "").let { if (pct && !p.endsWith("%")) it else it }
        val out = before + body + (if (pct && p.endsWith("%")) "%" else "") + after
        return (if (neg) "-" else "") + out.trim().replace(Regex("\\s{2,}"), " ")
    }

    private fun splitSections(f: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var q = false
        for (ch in f) { if (ch == '"') q = !q; if (ch == ';' && !q) { out += sb.toString(); sb.clear() } else sb.append(ch) }
        out += sb.toString(); return out
    }

    private fun isDate(code: String): Boolean {
        val c = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "").lowercase(Locale.US)
        return Regex("[dmy]").containsMatchIn(c) && !c.contains('0') && !c.contains('#') || Regex("^[hms:. ]+(am/pm)?$").matches(c.trim())
    }

    private fun date(serial: Double, code: String): String {
        val day = EPOCH.plusDays(serial.toLong())
        val secs = ((serial - kotlin.math.floor(serial)) * 86400).roundToLong()
        val c = code.replace("\"", "").replace("\\", "")
        val lower = c.lowercase(Locale.US)
        val hasTime = lower.contains('h') || lower.contains("ss")
        val hasDate = Regex("[dy]").containsMatchIn(lower) || (lower.contains('m') && !lower.contains('h'))
        val datePart = if (!hasDate) "" else {
            val pat = c.replace(Regex("(?i)\\s*h.*$"), "").trim()
                .replace("yyyy", "yyyy").replace("yy", "yy").replace("mmmm", "MMMM").replace("mmm", "MMM").replace("mm", "MM").replace(Regex("(?<![Mm])m(?![Mm])"), "M")
                .replace("dddd", "EEEE").replace("ddd", "EEE")
            runCatching { day.format(DateTimeFormatter.ofPattern(pat, Locale.US)) }.getOrDefault(day.toString())
        }
        val timePart = if (!hasTime) "" else {
            val h = secs / 3600; val m = (secs % 3600) / 60; val s = secs % 60
            val ampm = lower.contains("am/pm")
            val hh = if (ampm) ((h + 11) % 12 + 1) else h
            "%d:%02d".format(hh, m) + (if (lower.contains("ss")) ":%02d".format(s) else "") + (if (ampm) (if (h < 12) " AM" else " PM") else "")
        }
        return listOf(datePart, timePart).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * [formula] moved by [dr] rows and [dc] columns: relative references shift, `$`-anchored parts stay, text in
     * quotes and sheet names are left alone. How Excel expands a shared formula to the cells that reuse it.
     */
    fun shift(formula: String, dr: Int, dc: Int): String {
        if (dr == 0 && dc == 0) return formula
        val out = StringBuilder(); var i = 0
        val ref = Regex("(\\$?)([A-Za-z]{1,3})(\\$?)(\\d{1,7})")
        while (i < formula.length) {
            val ch = formula[i]
            if (ch == '"' || ch == '\'') { val end = formula.indexOf(ch, i + 1).let { if (it < 0) formula.length - 1 else it }; out.append(formula, i, end + 1); i = end + 1; continue }
            val prev = if (i > 0) formula[i - 1] else ' '
            val m = if (prev.isLetterOrDigit() || prev == '_' || prev == '.') null else ref.find(formula, i)?.takeIf { it.range.first == i }
            val next = m?.let { formula.getOrNull(it.range.last + 1) }
            if (m != null && next != '(' && next?.isLetterOrDigit() != true && next != '_' && next != '!') {
                val (cAbs, col, rAbs, row) = m.destructured
                val c = if (cAbs.isEmpty()) Sheets.colName(Sheets.colIndex(col.uppercase(Locale.US)) + dc) else col
                val r = if (rAbs.isEmpty()) (row.toInt() + dr).toString() else row
                out.append(cAbs).append(c).append(rAbs).append(r); i = m.range.last + 1; continue
            }
            if (ch.isLetterOrDigit() || ch == '_' || ch == '.') { var j = i; while (j < formula.length && (formula[j].isLetterOrDigit() || formula[j] == '_' || formula[j] == '.')) j++; out.append(formula, i, j); i = j; continue }
            out.append(ch); i++
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ save in place

    /**
     * Writes [src] to [out] with [edits] applied: sheet index → (cell key → new raw content, "" to clear). Every other
     * package part is copied unchanged; the calculation chain is dropped and Excel is told to recalculate on open, so
     * formulas that depend on your edits are correct the moment the workbook opens in Excel.
     */
    fun patch(src: File, out: File, book: Book, edits: Map<Int, Map<Long, String>>) {
        // A cell in a shared-formula group can't change alone: write the whole group as ordinary formulas
        val full = edits.mapValues { (si, m) ->
            val groups = m.keys.mapNotNull { book.info[si].sharedGroup[it] }.toSet()
            if (groups.isEmpty()) m else book.info[si].sharedGroup.filterValues { it in groups }.keys.associateWith { k -> book.sheets[si][(k shr 20).toInt(), (k and 0xFFFFF).toInt()] } + m
        }
        // Fresh results for every formula the engine computes for sure, so viewers that don't recalculate (and
        // BlackLab next time) show today's numbers; Excel recalculates everything on open anyway
        val values = HashMap<Int, Map<Long, Any>>()
        if (full.values.any { it.isNotEmpty() }) {
            val sheets = book.sheets.map { it.copy() }
            full.forEach { (si, m) -> m.forEach { (k, raw) -> sheets[si][(k shr 20).toInt(), (k and 0xFFFFF).toInt()] = raw } }
            val ev = BookEvaluator(sheets, book.names)
            sheets.forEachIndexed { si, sh ->
                val m = HashMap<Long, Any>()
                for (r in 0 until sh.rowCount) for (c in 0 until sh.colCount) {
                    if (!sh[r, c].startsWith("=")) continue
                    val v = ev[si].value(r, c)
                    if (Evaluator.isUnsupported(v) || v == "#CIRC!") continue
                    if (!sameValue(v, book.cached(si, r, c)) || full[si]?.containsKey(key(r, c)) == true) m[key(r, c)] = v
                }
                if (m.isNotEmpty()) values[si] = m
            }
        }
        ZipFile(src).use { z ->
            val touched = (full.filterValues { it.isNotEmpty() }.keys + values.keys).toSet()
            val byPath = touched.associate { i -> book.info[i].path to i }
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                for (e in z.entries()) {
                    if (e.name == "xl/calcChain.xml") continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    val data: ByteArray = when {
                        e.name in byPath -> byPath.getValue(e.name).let { si -> patchSheetXml(String(bytes, Charsets.UTF_8), full[si].orEmpty(), values[si].orEmpty()) }.toByteArray(Charsets.UTF_8)
                        e.name == "xl/workbook.xml" -> recalcOnLoad(String(bytes, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                        e.name == "[Content_Types].xml" -> String(bytes, Charsets.UTF_8).replace(Regex("<Override[^>]*PartName=\"/xl/calcChain.xml\"[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                        e.name == "xl/_rels/workbook.xml.rels" -> String(bytes, Charsets.UTF_8).replace(Regex("<Relationship[^>]*Target=\"[^\"]*calcChain.xml\"[^>]*/>"), "").toByteArray(Charsets.UTF_8)
                        else -> bytes
                    }
                    zip.putNextEntry(ZipEntry(e.name).also { n -> if (e.time > 0) n.time = e.time })
                    zip.write(data); zip.closeEntry()
                }
            }
        }
    }

    /** Sets fullCalcOnLoad="1" on calcPr (adding calcPr when missing). */
    fun recalcOnLoad(wb: String): String {
        val m = Regex("<calcPr\\b[^>]*?/?>").find(wb)
        if (m != null) {
            val tag = m.value
            val fixed = if (tag.contains("fullCalcOnLoad=")) tag.replace(Regex("fullCalcOnLoad=\"[^\"]*\""), "fullCalcOnLoad=\"1\"")
            else tag.replaceFirst("<calcPr", "<calcPr fullCalcOnLoad=\"1\"")
            return wb.replaceRange(m.range, fixed)
        }
        val close = wb.lastIndexOf("</workbook>")
        // calcPr goes after definedNames/sheets and before extensions; inserting before </workbook> or <extLst> keeps schema order
        val ext = wb.indexOf("<extLst", maxOf(0, wb.indexOf("</sheets>")))
        val at = if (ext >= 0) ext else close
        return if (at < 0) wb else wb.substring(0, at) + "<calcPr fullCalcOnLoad=\"1\"/>" + wb.substring(at)
    }

    private val CELL = Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row\\b([^>]*?)(?:/>|>(.*?)</row>)", RegexOption.DOT_MATCHES_ALL)

    private fun attr(attrs: String, name: String): String? = Regex("\\b$name=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)

    /** The <c> element for [raw] at [ref], keeping style [s]. Empty raw keeps only the styled, empty cell. */
    fun cellXml(ref: String, raw: String, s: String?): String {
        val st = s?.let { " s=\"$it\"" } ?: ""
        return when {
            raw.isEmpty() -> if (s != null) "<c r=\"$ref\"$st/>" else ""
            raw.startsWith("=") && raw.length > 1 -> "<c r=\"$ref\"$st><f>${DocxWriter.esc(raw.substring(1))}</f></c>"
            raw.equals("TRUE", true) || raw.equals("FALSE", true) -> "<c r=\"$ref\"$st t=\"b\"><v>${if (raw.equals("TRUE", true)) 1 else 0}</v></c>"
            isNumber(raw) -> "<c r=\"$ref\"$st><v>${raw.trim()}</v></c>"
            else -> "<c r=\"$ref\"$st t=\"inlineStr\"><is><t xml:space=\"preserve\">${DocxWriter.esc(raw)}</t></is></c>"
        }
    }

    private fun isNumber(s: String) = s.trim().let { t -> t.isNotEmpty() && t.toDoubleOrNull() != null && !t.startsWith("+") && !(t.length > 1 && t.startsWith("0") && !t.startsWith("0.")) && !t.contains(' ') }

    private fun sameValue(v: Any, cached: String?): Boolean = when (v) {
        is Double -> cached?.toDoubleOrNull()?.let { abs(it - v) <= 1e-9 * maxOf(1.0, abs(it)) } ?: false
        is Boolean -> cached == (if (v) "TRUE" else "FALSE") || cached == (if (v) "1" else "0")
        else -> v.toString() == (cached ?: "")
    }

    /** [cell] (a formula <c> element) with its cached result set to [v] and the matching t= type. */
    fun withValue(cell: String, v: Any): String {
        val open = Regex("^<c\\b[^>]*?(/?)>").find(cell) ?: return cell
        if (open.groupValues[1] == "/") return cell
        val (t, text) = when (v) {
            is Double -> null to (if (v.isNaN() || v.isInfinite()) "0" else if (v == kotlin.math.floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString())
            is Boolean -> "b" to (if (v) "1" else "0")
            is String -> (if (v.startsWith("#") && (v.endsWith("!") || v.endsWith("?") || v == "#N/A")) "e" else "str") to DocxWriter.esc(v)
            else -> "str" to DocxWriter.esc(v.toString())
        }
        val tag = open.value.replace(Regex("\\s+t=\"[^\"]*\""), "").let { if (t == null) it else it.replaceFirst(Regex(">$"), " t=\"$t\">") }
        val inner = cell.substring(open.range.last + 1).removeSuffix("</c>").replace(Regex("<v>.*?</v>|<v/>", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("<is>.*?</is>", RegexOption.DOT_MATCHES_ALL), "")
        return tag + inner + "<v>$text</v></c>"
    }

    /** Rewrites only the edited cells inside <sheetData> (and refreshes formula results in [values]); rows and cells stay in column order. */
    fun patchSheetXml(xmlText: String, edits: Map<Long, String>, values: Map<Long, Any> = emptyMap()): String {
        val start = xmlText.indexOf("<sheetData")
        if (start < 0) return xmlText
        val selfClosing = xmlText.indexOf("/>", start).let { it >= 0 && it < xmlText.indexOf('>', start) + 1 && xmlText[xmlText.indexOf('>', start) - 1] == '/' }
        val openEnd = xmlText.indexOf('>', start) + 1
        val end = if (selfClosing) openEnd else xmlText.indexOf("</sheetData>", openEnd)
        val body = if (selfClosing) "" else xmlText.substring(openEnd, end)
        val byRow = edits.entries.groupBy { (it.key shr 20).toInt() }
        val valRows = values.entries.groupBy { (it.key shr 20).toInt() }
        // parse rows
        data class RowX(val r: Int, val attrs: String, val cells: MutableMap<Int, String>)
        val rows = java.util.TreeMap<Int, RowX>()
        var auto = 0
        ROW.findAll(body).forEach { m ->
            val attrs = m.groupValues[1]
            val r = (attr(attrs, "r")?.toIntOrNull() ?: (auto + 1)) - 1; auto = r + 1
            val cells = java.util.TreeMap<Int, String>()
            var autoC = 0
            CELL.findAll(m.groupValues[2]).forEach { cm ->
                val ref = attr(cm.groupValues[1], "r")
                val c = ref?.let { Sheets.colIndex(it.takeWhile { ch -> ch.isLetter() }) } ?: autoC
                autoC = c + 1
                cells[c] = cm.value
            }
            rows[r] = RowX(r, attrs, cells)
        }
        for ((r, list) in byRow) {
            val row = rows.getOrPut(r) { RowX(r, " r=\"${r + 1}\"", java.util.TreeMap()) }
            for ((k, raw) in list) {
                val c = (k and 0xFFFFF).toInt()
                val ref = Sheets.colName(c) + (r + 1)
                val s = row.cells[c]?.let { attr(CELL.find(it)?.groupValues?.get(1).orEmpty(), "s") }
                val x = cellXml(ref, raw, s)
                if (x.isEmpty()) row.cells.remove(c) else row.cells[c] = x
            }
        }
        for ((r, list) in valRows) {
            val row = rows[r] ?: continue
            for ((k, v) in list) {
                val c = (k and 0xFFFFF).toInt()
                val x = row.cells[c] ?: continue
                if (x.contains("<f")) row.cells[c] = withValue(x, v)
            }
        }
        val newBody = rows.values.joinToString("") { row ->
            // the spans hint goes stale when cells move; Excel treats it as optional
            val attrs = row.attrs.replace(Regex("\\s*spans=\"[^\"]*\""), "")
            if (row.cells.isEmpty() && !attrs.contains(" s=") && !attrs.contains("ht=")) "" else "<row$attrs>${row.cells.values.joinToString("")}</row>"
        }
        val open = xmlText.substring(start, openEnd).let { if (selfClosing) it.removeSuffix("/>").trimEnd() + ">" else it }
        val tail = if (selfClosing) xmlText.substring(openEnd) else xmlText.substring(end)
        return xmlText.substring(0, start) + open + newBody + (if (selfClosing) "</sheetData>" else "") + tail
    }
}
