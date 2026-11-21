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
