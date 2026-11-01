package com.blacklab.app.files

import com.blacklab.app.docs.DocxWriter
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** One worksheet: raw cell contents, row-major. Formulas start with "=". */
class Sheet(var name: String, val cells: MutableList<MutableList<String>> = mutableListOf()) {
    val rowCount get() = cells.size
    val colCount get() = cells.maxOfOrNull { it.size } ?: 0
    operator fun get(r: Int, c: Int): String = cells.getOrNull(r)?.getOrNull(c) ?: ""
    operator fun set(r: Int, c: Int, v: String) {
        while (cells.size <= r) cells += mutableListOf<String>()
        val row = cells[r]; while (row.size <= c) row += ""
        row[c] = v
    }
    fun normalize(minRows: Int = 0, minCols: Int = 0) {
        val cols = maxOf(colCount, minCols)
        while (cells.size < minRows) cells += mutableListOf<String>()
        cells.forEach { row -> while (row.size < cols) row += "" }
    }
    fun copy() = Sheet(name, cells.map { it.toMutableList() }.toMutableList())
}

object Sheets {
    fun colName(c: Int): String { var n = c + 1; val sb = StringBuilder(); while (n > 0) { val m = (n - 1) % 26; sb.insert(0, ('A' + m)); n = (n - 1) / 26 }; return sb.toString() }
    fun colIndex(s: String): Int = s.uppercase(Locale.US).fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1

    // ------------------------------------------------------------------ CSV / TSV

    fun detectDelimiter(firstLine: String): Char = listOf(',', ';', '\t', '|').maxByOrNull { d -> firstLine.count { it == d } } ?: ','

    fun parseCsv(text: String, delimiter: Char = detectDelimiter(text.lineSequence().firstOrNull().orEmpty())): MutableList<MutableList<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val s = text.removePrefix("\uFEFF")
        while (i < s.length) {
            val ch = s[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                delimiter -> { row += cell.toString(); cell.clear() }
                '\r' -> {}
                '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    fun toCsv(rows: List<List<String>>, delimiter: Char = ','): String = rows.joinToString("\r\n") { r ->
        r.joinToString(delimiter.toString()) { v -> if (v.any { it == delimiter || it == '"' || it == '\n' || it == '\r' } || v.startsWith(" ")) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    }

    // ------------------------------------------------------------------ XLSX

    private fun xml(s: String) = Jsoup.parse(s, "", Parser.xmlParser())

    fun readXlsx(f: File): MutableList<Sheet> = ZipFile(f).use { z ->
        fun text(n: String) = z.getEntry(n)?.let { e -> z.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
        val shared = text("xl/sharedStrings.xml")?.let { s -> xml(s).select("si").map { si -> si.select("t").joinToString("") { it.wholeText() } } } ?: emptyList()
        val wb = xml(text("xl/workbook.xml") ?: error("Not a valid .xlsx file"))
        val rels = text("xl/_rels/workbook.xml.rels")?.let { r -> xml(r).select("Relationship").associate { it.attr("Id") to OfficeReader.resolve("xl/workbook.xml", it.attr("Target")) } } ?: emptyMap()
        wb.select("sheet").mapIndexed { i, sh ->
            val path = rels[sh.attr("r:id")] ?: "xl/worksheets/sheet${i + 1}.xml"
            val sheet = Sheet(sh.attr("name").ifBlank { "Sheet${i + 1}" })
            text(path)?.let { sx ->
                xml(sx).select("sheetData > row").forEachIndexed { ri, row ->
                    val r = (row.attr("r").toIntOrNull() ?: (ri + 1)) - 1
                    row.select("c").forEachIndexed { ci, c ->
                        val ref = c.attr("r")
                        val col = if (ref.isNotEmpty()) colIndex(ref.takeWhile { it.isLetter() }) else ci
                        val f = c.selectFirst("f")?.text()
                        val v = c.selectFirst("v")?.text().orEmpty()
                        val value = when {
                            !f.isNullOrBlank() -> "=$f"
                            c.attr("t") == "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                            c.attr("t") == "inlineStr" -> c.select("is t").joinToString("") { it.wholeText() }
                            c.attr("t") == "b" -> if (v == "1") "TRUE" else "FALSE"
                            else -> v.toDoubleOrNull()?.let { fmt(it) } ?: v
                        }
                        if (value.isNotEmpty() && r < 100_000 && col < 1_000) sheet[r, col] = value
                    }
                }
            }
            sheet
        }.toMutableList().ifEmpty { mutableListOf(Sheet("Sheet1")) }
    }

    fun writeXlsx(sheets: List<Sheet>, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(n: String, s: String) { zip.putNextEntry(ZipEntry(n)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            put("[Content_Types].xml", head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
                sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } + "</Types>")
            put("_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            put("xl/workbook.xml", head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>" +
                sheets.mapIndexed { i, s -> "<sheet name=\"${DocxWriter.esc(s.name.take(31).ifBlank { "Sheet${i + 1}" })}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
                "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            put("xl/styles.xml", head + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>")
            sheets.forEachIndexed { i, s ->
                val eval = Evaluator(s)
                val sb = StringBuilder(head + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                s.cells.forEachIndexed { r, row ->
                    if (row.all { it.isEmpty() }) return@forEachIndexed
                    sb.append("<row r=\"${r + 1}\">")
                    row.forEachIndexed { c, raw ->
                        if (raw.isEmpty()) return@forEachIndexed
                        val ref = colName(c) + (r + 1)
                        when {
                            raw.startsWith("=") && raw.length > 1 -> {
                                val v = eval.value(r, c)
                                val cached = if (v is Double) "<v>${fmt(v)}</v>" else "<v>${DocxWriter.esc(v.toString())}</v>"
                                sb.append("<c r=\"$ref\"${if (v !is Double) " t=\"str\"" else ""}><f>${DocxWriter.esc(raw.substring(1))}</f>$cached</c>")
                            }
                            raw.toDoubleOrNull() != null && !raw.startsWith("0") || raw == "0" || raw.matches(Regex("0\\.\\d+")) -> sb.append("<c r=\"$ref\"><v>$raw</v></c>")
                            else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${DocxWriter.esc(raw)}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
    }

    fun read(f: File): MutableList<Sheet> = when (f.extension.lowercase()) {
        "xlsx", "xlsm" -> readXlsx(f)
        "tsv" -> mutableListOf(Sheet(f.nameWithoutExtension, parseCsv(f.readText(), '\t')))
        else -> mutableListOf(Sheet(f.nameWithoutExtension, parseCsv(f.readText())))
    }

    fun write(sheets: List<Sheet>, f: File, ext: String = f.extension) {
        when (ext.lowercase()) {
            "xlsx", "xlsm" -> writeXlsx(sheets, f)
            "tsv" -> f.writeText(toCsv(values(sheets.first()), '\t'))
            else -> f.writeText(toCsv(values(sheets.first())))
        }
    }

    /** Computed values (formulas evaluated) — what CSV stores. */
    fun values(s: Sheet): List<List<String>> { val e = Evaluator(s); return s.cells.mapIndexed { r, row -> row.indices.map { c -> e.display(r, c) } } }

    fun fmt(d: Double): String = when {
        d.isNaN() -> "#NUM!"
        d.isInfinite() -> "#DIV/0!"
        d == d.roundToLong().toDouble() && abs(d) < 1e15 -> d.roundToLong().toString()
        else -> "%.10f".format(Locale.US, d).trimEnd('0').trimEnd('.')
    }

    fun html(s: Sheet): String {
        val v = values(s)
        return "<table>" + v.joinToString("") { r -> "<tr>" + r.joinToString("") { "<td>${DocxWriter.esc(it)}</td>" } + "</tr>" } + "</table>"
    }
}

/**
 * Small spreadsheet formula engine: + - * / ^ &, comparisons, cell refs and ranges, and the everyday
 * functions (SUM, AVERAGE, MIN, MAX, COUNT, COUNTA, MEDIAN, PRODUCT, ROUND, ABS, SQRT, POWER, IF,
 * AND, OR, NOT, CONCAT, LEN, UPPER, LOWER, TRIM, LEFT, RIGHT, TODAY).
 */
class Evaluator(private val sheet: Sheet) {
    private val cache = HashMap<Long, Any>()
    private val visiting = HashSet<Long>()

    class FormulaError(val code: String) : Exception(code)

    fun display(r: Int, c: Int): String = when (val v = value(r, c)) { is Double -> Sheets.fmt(v); is Boolean -> if (v) "TRUE" else "FALSE"; else -> v.toString() }

    fun value(r: Int, c: Int): Any {
        val key = r.toLong() shl 20 or c.toLong()
        cache[key]?.let { return it }
        val raw = sheet[r, c]
        if (!raw.startsWith("=") || raw.length == 1) return (raw.toDoubleOrNull() ?: raw).also { cache[key] = it }
        if (!visiting.add(key)) return "#CIRC!"
        val v: Any = try { Expr(raw.substring(1)).parseAll() } catch (e: FormulaError) { e.code } catch (e: Exception) { "#ERROR!" }
        visiting.remove(key)
        cache[key] = v
        return v
    }

    private fun num(v: Any): Double = when (v) {
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> if (v.isBlank()) 0.0 else v.toDoubleOrNull() ?: if (v.startsWith("#")) throw FormulaError(v) else throw FormulaError("#VALUE!")
        else -> 0.0
    }
    private fun str(v: Any): String = when (v) { is Double -> Sheets.fmt(v); is Boolean -> if (v) "TRUE" else "FALSE"; else -> v.toString() }
    private fun bool(v: Any): Boolean = when (v) { is Boolean -> v; is Double -> v != 0.0; is String -> v.equals("TRUE", true) || (v.toDoubleOrNull() ?: 0.0) != 0.0; else -> false }

    private inner class Expr(val s: String) {
        var i = 0
        fun parseAll(): Any { val v = compare(); skip(); if (i < s.length) throw FormulaError("#ERROR!"); return v }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun peek(t: String): Boolean { skip(); return s.startsWith(t, i, ignoreCase = true) }
        fun eat(t: String): Boolean { if (peek(t)) { i += t.length; return true }; return false }

        fun compare(): Any {
            var l = concat()
            while (true) {
                val op = listOf("<=", ">=", "<>", "=", "<", ">").firstOrNull { peek(it) } ?: return l
                i += op.length
                val r = concat()
                val cmp = if (l is Double || r is Double) num(l).compareTo(num(r)) else str(l).compareTo(str(r), ignoreCase = true)
                l = when (op) { "<=" -> cmp <= 0; ">=" -> cmp >= 0; "<>" -> cmp != 0; "=" -> cmp == 0; "<" -> cmp < 0; else -> cmp > 0 }
            }
        }
        fun concat(): Any { var l = add(); while (eat("&")) l = str(l) + str(add()); return l }
        fun add(): Any {
            var l = mul()
            while (true) { l = when { eat("+") -> num(l) + num(mul()); eat("-") -> num(l) - num(mul()); else -> return l } }
        }
        fun mul(): Any {
            var l = pow()
            while (true) {
                l = when {
                    eat("*") -> num(l) * num(pow())
                    eat("/") -> { val d = num(pow()); if (d == 0.0) throw FormulaError("#DIV/0!"); num(l) / d }
                    else -> return l
                }
            }
        }
        fun pow(): Any { val b = unary(); return if (eat("^")) num(b).pow(num(pow())) else b }
        fun unary(): Any = when { eat("-") -> -num(unary()); eat("+") -> num(unary()); else -> postfix() }
        fun postfix(): Any { val v = primary(); return if (eat("%")) num(v) / 100 else v }

        fun primary(): Any {
            skip()
            if (i >= s.length) throw FormulaError("#ERROR!")
            val ch = s[i]
            if (ch == '(') { i++; val v = compare(); if (!eat(")")) throw FormulaError("#ERROR!"); return v }
            if (ch == '"') { val end = s.indexOf('"', i + 1); if (end < 0) throw FormulaError("#ERROR!"); val t = s.substring(i + 1, end); i = end + 1; return t }
            if (ch.isDigit() || ch == '.') { val st = i; while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'E' || s[i] == 'e')) i++; return s.substring(st, i).toDouble() }
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$' || s[i] == '_' || s[i] == '.')) i++
            val word = s.substring(st, i).uppercase(Locale.US)
            if (eat("(")) return call(word)
            if (word == "TRUE") return true
            if (word == "FALSE") return false
            val ref = parseRef(word) ?: throw FormulaError("#NAME?")
            if (eat(":")) { // a bare range outside a function: use its first cell
                val st2 = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
                parseRef(s.substring(st2, i).uppercase(Locale.US)) ?: throw FormulaError("#REF!")
            }
            return value(ref.first, ref.second)
        }

        fun parseRef(w: String): Pair<Int, Int>? {
            val m = Regex("\\$?([A-Z]{1,3})\\$?(\\d{1,6})").matchEntire(w) ?: return null
            return (m.groupValues[2].toInt() - 1) to Sheets.colIndex(m.groupValues[1])
        }

        /** Parses one argument; ranges expand to all their values. */
        fun arg(): List<Any> {
            skip()
            val save = i
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
            val a = parseRef(s.substring(st, i).uppercase(Locale.US))
            if (a != null && eat(":")) {
                val st2 = i; while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '$')) i++
                val b = parseRef(s.substring(st2, i).uppercase(Locale.US)) ?: throw FormulaError("#REF!")
                val out = ArrayList<Any>()
                for (r in minOf(a.first, b.first)..maxOf(a.first, b.first)) for (c in minOf(a.second, b.second)..maxOf(a.second, b.second)) {
                    val v = value(r, c); if (!(v is String && v.isEmpty())) out += v
                }
                return out
            }
            i = save
            return listOf(compare())
        }

        fun args(): List<List<Any>> {
            val out = mutableListOf<List<Any>>()
            if (eat(")")) return out
            do { out += arg() } while (eat(",") || eat(";"))
            if (!eat(")")) throw FormulaError("#ERROR!")
            return out
        }

        fun call(name: String): Any {
            val a = args()
            val flat = a.flatten()
            fun nums() = flat.filter { it is Double || (it is String && it.toDoubleOrNull() != null) }.map { num(it) }
            fun one(k: Int = 0) = a.getOrNull(k)?.firstOrNull() ?: throw FormulaError("#VALUE!")
            return when (name) {
                "SUM" -> nums().sum()
                "AVERAGE", "AVG" -> nums().takeIf { it.isNotEmpty() }?.average() ?: throw FormulaError("#DIV/0!")
                "MIN" -> nums().minOrNull() ?: 0.0
                "MAX" -> nums().maxOrNull() ?: 0.0
                "COUNT" -> nums().size.toDouble()
                "COUNTA" -> flat.count { !(it is String && it.isEmpty()) }.toDouble()
                "MEDIAN" -> nums().sorted().let { l -> if (l.isEmpty()) 0.0 else if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 }
                "PRODUCT" -> nums().fold(1.0) { x, y -> x * y }
                "ROUND" -> { val p = 10.0.pow(a.getOrNull(1)?.firstOrNull()?.let { num(it) } ?: 0.0); (num(one()) * p).roundToLong() / p }
                "ABS" -> abs(num(one()))
                "SQRT" -> kotlin.math.sqrt(num(one()))
                "POWER" -> num(one()).pow(num(one(1)))
                "IF" -> if (bool(one())) a.getOrNull(1)?.firstOrNull() ?: true else a.getOrNull(2)?.firstOrNull() ?: false
                "AND" -> flat.all { bool(it) }
                "OR" -> flat.any { bool(it) }
                "NOT" -> !bool(one())
                "CONCAT", "CONCATENATE" -> flat.joinToString("") { str(it) }
                "LEN" -> str(one()).length.toDouble()
                "UPPER" -> str(one()).uppercase()
                "LOWER" -> str(one()).lowercase()
                "TRIM" -> str(one()).trim().replace(Regex("\\s+"), " ")
                "LEFT" -> str(one()).take((a.getOrNull(1)?.firstOrNull()?.let { num(it) } ?: 1.0).toInt())
                "RIGHT" -> str(one()).takeLast((a.getOrNull(1)?.firstOrNull()?.let { num(it) } ?: 1.0).toInt())
                "TODAY" -> java.time.LocalDate.now().toString()
                "PI" -> Math.PI
                else -> throw FormulaError("#NAME?")
            }
        }
    }
}
