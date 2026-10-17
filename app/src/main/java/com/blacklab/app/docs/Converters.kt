package com.blacklab.app.docs

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.blacklab.app.core.Files
import com.blacklab.app.data.ContentStore
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

enum class ExportFormat(val ext: String, val label: String) {
    PDF("pdf", "PDF document"),
    DOCX("docx", "Word document (.docx)"),
    HTML("html", "Web page (.html)"),
    MD("md", "Markdown (.md)"),
    TXT("txt", "Plain text (.txt)"),
}

object DocExporter {
    /** Exports [bodyHtml] into [dir] and returns the written file. */
    fun export(bodyHtml: String, title: String, page: PageSetup, format: ExportFormat, dir: File): File {
        val out = Files.unique(dir, title.ifBlank { "Untitled" }, format.ext)
        when (format) {
            ExportFormat.HTML -> out.writeText(ContentStore.wrapHtml(title, bodyHtml, page.css()))
            ExportFormat.DOCX -> DocxWriter.write(HtmlToBlocks.parse(bodyHtml), out, title, page)
            ExportFormat.PDF -> PdfWriter.write(HtmlToBlocks.parse(bodyHtml), out, title, page)
            ExportFormat.MD -> out.writeText(TextExport.markdown(HtmlToBlocks.parse(bodyHtml)))
            ExportFormat.TXT -> out.writeText(TextExport.plain(HtmlToBlocks.parse(bodyHtml)))
        }
        return out
    }
}

object TextExport {
    fun plain(blocks: List<Block>): String = buildString {
        blocks.forEach { b ->
            when (b) {
                is Para -> {
                    val prefix = when (b.style.list) {
                        ListKind.BULLET -> "  ".repeat(b.style.level) + "• "
                        ListKind.NUMBER -> "  ".repeat(b.style.level) + "${b.style.number}. "
                        ListKind.CHECK -> if (b.style.checked) "[x] " else "[ ] "
                        null -> ""
                    }
                    append(prefix).append(b.text).append('\n')
                    if (b.style.list == null) append('\n')
                }
                is TableBlock -> {
                    b.rows.forEach { row -> append(row.joinToString("\t") { cell -> cell.joinToString(" ") { it.text } }).append('\n') }
                    append('\n')
                }
                is ImageBlock -> append("[image]\n\n")
                RuleBlock -> append("————————————————\n\n")
                PageBreakBlock -> append("\u000C\n")
            }
        }
    }.trim() + "\n"

    fun markdown(blocks: List<Block>): String = buildString {
        var prevList = false
        blocks.forEach { b ->
            when (b) {
                is Para -> {
                    val s = b.style
                    val text = b.runs.joinToString("") { md(it) }
                    if (prevList && s.list == null) append('\n')
                    when {
                        s.heading == 9 -> append("# ").append(text).append("\n\n")
                        s.heading in 1..6 -> append("#".repeat((s.heading + if (blocks.any { it is Para && it.style.heading == 9 }) 1 else 0).coerceAtMost(6))).append(' ').append(text).append("\n\n")
                        s.list == ListKind.BULLET -> append("  ".repeat(s.level)).append("- ").append(text).append('\n')
                        s.list == ListKind.NUMBER -> append("   ".repeat(s.level)).append("${s.number}. ").append(text).append('\n')
                        s.list == ListKind.CHECK -> append("- [${if (s.checked) "x" else " "}] ").append(text).append('\n')
                        s.quote -> append("> ").append(text).append("\n\n")
                        s.code -> append("```\n").append(b.text).append("\n```\n\n")
                        else -> append(text).append("\n\n")
                    }
                    prevList = s.list != null
                }
                is TableBlock -> {
                    if (prevList) append('\n')
                    val cols = b.rows.maxOfOrNull { it.size } ?: 0
                    b.rows.forEachIndexed { i, row ->
                        append("| ").append((0 until cols).joinToString(" | ") { c -> row.getOrNull(c)?.joinToString(" ") { it.text.replace("|", "\\|") } ?: "" }).append(" |\n")
                        if (i == 0) append("|").append(" --- |".repeat(cols)).append('\n')
                    }
                    append('\n'); prevList = false
                }
                is ImageBlock -> { append("![image](data:${b.mime};base64,").append(Base64.encodeToString(b.bytes, Base64.NO_WRAP)).append(")\n\n"); prevList = false }
                RuleBlock -> { append("---\n\n"); prevList = false }
                PageBreakBlock -> { append("\n<div style=\"page-break-after: always\"></div>\n\n"); prevList = false }
            }
        }
    }.trim() + "\n"

    private fun md(r: Run): String {
        if (r.text.isBlank()) return r.text
        var t = r.text.replace("*", "\\*").replace("_", "\\_").replace("\n", "  \n")
        val lead = t.takeWhile { it == ' ' }
        val trail = t.takeLastWhile { it == ' ' }
        t = t.trim()
        val s = r.style
        if (s.code) t = "`$t`"
        if (s.bold) t = "**$t**"
        if (s.italic) t = "*$t*"
        if (s.strike) t = "~~$t~~"
        if (s.link != null) t = "[$t](${s.link})"
        return lead + t + trail
    }
}

object DocImporter {
    data class Imported(val title: String, val html: String)

    fun import(context: Context, uri: Uri): Imported {
        val name = Files.displayName(context, uri)
        val title = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val html = when (ext) {
            "docx" -> DocxReader.toHtml(bytes)
            "html", "htm", "xhtml" -> Jsoup.parse(String(bytes)).body().html()
            "md", "markdown" -> Markdown.toHtml(String(bytes))
            "rtf" -> ContentStore.textToHtml(stripRtf(String(bytes)))
            else -> ContentStore.textToHtml(String(bytes))
        }
        return Imported(title, html)
    }

    private fun stripRtf(rtf: String): String =
        rtf.replace(Regex("\\\\par[d]?"), "\n").replace(Regex("\\{\\\\\\*[^}]*\\}"), "").replace(Regex("\\\\[a-z]+-?\\d* ?"), "")
            .replace("{", "").replace("}", "").trim()
}

/** Reads .docx (Office Open XML) into editor HTML: styles, lists, tables, links and images. */
object DocxReader {
    fun toHtml(bytes: ByteArray): String {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) parts[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        val docXml = parts["word/document.xml"]?.toString(Charsets.UTF_8) ?: return "<p>(empty document)</p>"
        val rels = parts["word/_rels/document.xml.rels"]?.toString(Charsets.UTF_8)?.let { xml ->
            Jsoup.parse(xml, "", Parser.xmlParser()).getElementsByTag("Relationship").associate { it.attr("Id") to it.attr("Target") }
        } ?: emptyMap()
        val numFormats = parts["word/numbering.xml"]?.toString(Charsets.UTF_8)?.let { parseNumbering(it) } ?: emptyMap()
        val doc = Jsoup.parse(docXml, "", Parser.xmlParser())
        val body = doc.getElementsByTag("w:body").firstOrNull() ?: return ""
        val out = StringBuilder()
        val listStack = ArrayList<String>()
        fun closeLists(to: Int) { while (listStack.size > to) out.append("</${listStack.removeAt(listStack.lastIndex)}>") }

        fun walkBody(container: Element) {
            for (el in container.children()) {
                when (el.tagName()) {
                    "w:p" -> {
                        val numPr = el.getElementsByTag("w:numPr").firstOrNull()
                        if (numPr != null) {
                            val lvl = numPr.getElementsByTag("w:ilvl").firstOrNull()?.attr("w:val")?.toIntOrNull() ?: 0
                            val numId = numPr.getElementsByTag("w:numId").firstOrNull()?.attr("w:val") ?: "0"
                            val tag = if ((numFormats[numId]?.getOrNull(lvl) ?: "bullet") == "bullet") "ul" else "ol"
                            if (listStack.size > lvl + 1) closeLists(lvl + 1)
                            if (listStack.size == lvl + 1 && listStack.last() != tag) closeLists(lvl)
                            while (listStack.size < lvl + 1) { out.append("<$tag>"); listStack.add(tag) }
                            out.append("<li>").append(runs(el, rels, parts)).append("</li>")
                        } else {
                            closeLists(0)
                            out.append(paragraph(el, rels, parts))
                        }
                    }
                    "w:tbl" -> {
                        closeLists(0)
                        out.append("<table>")
                        el.children().filter { it.tagName() == "w:tr" }.forEach { tr ->
                            out.append("<tr>")
                            tr.children().filter { it.tagName() == "w:tc" }.forEach { tc ->
                                out.append("<td>")
                                tc.children().filter { it.tagName() == "w:p" }.forEach { out.append(paragraph(it, rels, parts)) }
                                out.append("</td>")
                            }
                            out.append("</tr>")
                        }
                        out.append("</table>")
                    }
                    "w:sdt" -> el.getElementsByTag("w:sdtContent").firstOrNull()?.let { walkBody(it) }
                }
            }
        }
        walkBody(body)
        closeLists(0)
        return out.toString()
    }

    private fun parseNumbering(xml: String): Map<String, List<String>> {
        val d = Jsoup.parse(xml, "", Parser.xmlParser())
        val abstract = d.getElementsByTag("w:abstractNum").associate { a ->
            a.attr("w:abstractNumId") to a.getElementsByTag("w:lvl").map { it.getElementsByTag("w:numFmt").firstOrNull()?.attr("w:val") ?: "bullet" }
        }
        return d.getElementsByTag("w:num").associate { n ->
            n.attr("w:numId") to (abstract[n.getElementsByTag("w:abstractNumId").firstOrNull()?.attr("w:val")] ?: emptyList())
        }
    }

    private fun paragraph(p: Element, rels: Map<String, String>, parts: Map<String, ByteArray>): String {
        val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val") ?: ""
        val jc = p.getElementsByTag("w:pPr").firstOrNull()?.children()?.firstOrNull { it.tagName() == "w:jc" }?.attr("w:val")
        val align = when (jc) { "center" -> "center"; "right", "end" -> "right"; "both", "distribute" -> "justify"; else -> null }
        val attr = align?.let { " style=\"text-align:$it\"" } ?: ""
        val inner = runs(p, rels, parts).ifBlank { "<br>" }
        val s = style.lowercase(Locale.US)
        return when {
            s == "title" -> "<h1 class=\"title\"$attr>$inner</h1>"
            s.startsWith("heading") -> { val n = s.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6) ?: 2; "<h$n$attr>$inner</h$n>" }
            s.contains("quote") -> "<blockquote$attr>$inner</blockquote>"
            else -> "<p$attr>$inner</p>"
        }
    }

    private fun runs(p: Element, rels: Map<String, String>, parts: Map<String, ByteArray>): String {
        val sb = StringBuilder()
        fun run(r: Element) {
            val rpr = r.children().firstOrNull { it.tagName() == "w:rPr" }
            val styles = ArrayList<String>()
            var open = ""; var close = ""
            fun has(tag: String) = rpr?.children()?.firstOrNull { it.tagName() == tag }?.let { it.attr("w:val") != "0" && it.attr("w:val") != "false" && it.attr("w:val") != "none" } == true
            if (has("w:b")) { open += "<b>"; close = "</b>$close" }
            if (has("w:i")) { open += "<i>"; close = "</i>$close" }
            if (has("w:u")) { open += "<u>"; close = "</u>$close" }
            if (has("w:strike")) { open += "<s>"; close = "</s>$close" }
            rpr?.children()?.firstOrNull { it.tagName() == "w:vertAlign" }?.attr("w:val")?.let {
                if (it == "superscript") { open += "<sup>"; close = "</sup>$close" }
                if (it == "subscript") { open += "<sub>"; close = "</sub>$close" }
            }
            rpr?.children()?.firstOrNull { it.tagName() == "w:color" }?.attr("w:val")?.takeIf { it.length == 6 }?.let { styles += "color:#$it" }
            rpr?.children()?.firstOrNull { it.tagName() == "w:sz" }?.attr("w:val")?.toIntOrNull()?.let { styles += "font-size:${it / 2.0}pt" }
            rpr?.children()?.firstOrNull { it.tagName() == "w:rFonts" }?.attr("w:ascii")?.takeIf { it.isNotBlank() }?.let { styles += "font-family:'$it'" }
            rpr?.children()?.firstOrNull { it.tagName() == "w:shd" }?.attr("w:fill")?.takeIf { it.length == 6 && it != "auto" }?.let { styles += "background-color:#$it" }
            rpr?.children()?.firstOrNull { it.tagName() == "w:highlight" }?.attr("w:val")?.let { highlight[it] }?.let { styles += "background-color:$it" }
            if (styles.isNotEmpty()) { open = "<span style=\"${styles.joinToString(";")}\">$open"; close = "$close</span>" }
            val content = StringBuilder()
            for (c in r.children()) {
                when (c.tagName()) {
                    "w:t" -> content.append(ContentStore.escape(c.text()))
                    "w:tab" -> content.append("&emsp;")
                    "w:br" -> if (c.attr("w:type") == "page") content.append("</p><div class=\"page-break\"></div><p>") else content.append("<br>")
                    "w:drawing", "w:pict" -> {
                        val embed = c.getElementsByTag("a:blip").firstOrNull()?.attr("r:embed")
                            ?: c.getElementsByTag("v:imagedata").firstOrNull()?.attr("r:id")
                        val target = embed?.let { rels[it] }
                        val data = target?.let { parts["word/" + it.removePrefix("/word/").removePrefix("./")] }
                        if (data != null) {
                            val mime = if (target.endsWith(".png", true)) "image/png" else if (target.endsWith(".gif", true)) "image/gif" else "image/jpeg"
                            content.append("<img src=\"data:$mime;base64,").append(Base64.encodeToString(data, Base64.NO_WRAP)).append("\">")
                        }
                    }
                }
            }
            if (content.isNotEmpty()) sb.append(open).append(content).append(close)
        }
        fun walk(el: Element) {
            for (c in el.children()) {
                when (c.tagName()) {
                    "w:r" -> run(c)
                    "w:hyperlink" -> {
                        val href = rels[c.attr("r:id")] ?: c.attr("w:anchor").let { if (it.isNotBlank()) "#$it" else "" }
                        if (href.isNotBlank()) sb.append("<a href=\"").append(ContentStore.escape(href)).append("\">")
                        walk(c)
                        if (href.isNotBlank()) sb.append("</a>")
                    }
                    "w:smartTag", "w:fldSimple", "w:ins", "w:customXml", "w:sdt", "w:sdtContent" -> walk(c)
                }
            }
        }
        walk(p)
        return sb.toString()
    }

    private val highlight = mapOf(
        "yellow" to "#FFFF00", "green" to "#00FF00", "cyan" to "#00FFFF", "magenta" to "#FF00FF", "blue" to "#0000FF",
        "red" to "#FF0000", "darkBlue" to "#000080", "darkCyan" to "#008080", "darkGreen" to "#008000",
        "darkMagenta" to "#800080", "darkRed" to "#800000", "darkYellow" to "#808000", "lightGray" to "#C0C0C0", "darkGray" to "#808080",
    )
}

/** Small CommonMark-ish Markdown → HTML converter for imports. */
object Markdown {
    fun toHtml(md: String): String {
        val out = StringBuilder()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        var listTag: String? = null
        fun closeList() { listTag?.let { out.append(if (it.startsWith("ul")) "</ul>" else "</ol>") }; listTag = null }
        val para = StringBuilder()
        fun flushPara() { if (para.isNotBlank()) out.append("<p>").append(inline(para.toString().trim())).append("</p>"); para.clear() }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flushPara(); closeList()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code.append(lines[i]).append('\n'); i++ }
                    out.append("<pre>").append(ContentStore.escape(code.toString().trimEnd())).append("</pre>")
                }
                t.isEmpty() -> { flushPara(); closeList() }
                Regex("^#{1,6} ").containsMatchIn(t) -> {
                    flushPara(); closeList()
                    val level = t.takeWhile { it == '#' }.length
                    out.append("<h$level>").append(inline(t.drop(level).trim())).append("</h$level>")
                }
                t == "---" || t == "***" || t == "___" -> { flushPara(); closeList(); out.append("<hr>") }
                t.startsWith(">") -> { flushPara(); closeList(); out.append("<blockquote>").append(inline(t.removePrefix(">").trim())).append("</blockquote>") }
                Regex("^[-*+] \\[[ xX]\\] ").containsMatchIn(t) -> {
                    flushPara()
                    if (listTag != "ul.check") { closeList(); out.append("<ul class=\"checklist\">"); listTag = "ul.check" }
                    val checked = t[3].lowercaseChar() == 'x'
                    out.append(if (checked) "<li class=\"checked\">" else "<li>").append(inline(t.substring(6))).append("</li>")
                }
                Regex("^[-*+] ").containsMatchIn(t) -> {
                    flushPara()
                    if (listTag != "ul") { closeList(); out.append("<ul>"); listTag = "ul" }
                    out.append("<li>").append(inline(t.substring(2))).append("</li>")
                }
                Regex("^\\d+[.)] ").containsMatchIn(t) -> {
                    flushPara()
                    if (listTag != "ol") { closeList(); out.append("<ol>"); listTag = "ol" }
                    out.append("<li>").append(inline(t.substringAfter(' '))).append("</li>")
                }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{3,}").containsMatchIn(lines[i + 1].trim()) -> {
                    flushPara(); closeList()
                    fun cells(l: String) = l.trim().trim('|').split('|').map { inline(it.trim()) }
                    out.append("<table><tr>").append(cells(t).joinToString("") { "<th>$it</th>" }).append("</tr>")
                    i += 2
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.append("<tr>").append(cells(lines[i]).joinToString("") { "<td>$it</td>" }).append("</tr>"); i++
                    }
                    out.append("</table>")
                    continue
                }
                else -> { closeList(); para.append(line).append(if (line.endsWith("  ")) "\n" else " ") }
            }
            i++
        }
        flushPara(); closeList()
        return out.toString()
    }

    private fun inline(s: String): String {
        var t = ContentStore.escape(s)
        t = t.replace(Regex("!\\[([^\\]]*)]\\(([^)]+)\\)"), "<img alt=\"$1\" src=\"$2\">")
        t = t.replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")
        t = t.replace(Regex("`([^`]+)`"), "<code>$1</code>")
        t = t.replace(Regex("\\*\\*([^*]+)\\*\\*"), "<b>$1</b>").replace(Regex("__([^_]+)__"), "<b>$1</b>")
        t = t.replace(Regex("(?<![*\\\\])\\*([^*]+)\\*"), "<i>$1</i>").replace(Regex("(?<![\\w\\\\])_([^_]+)_(?!\\w)"), "<i>$1</i>")
        t = t.replace(Regex("~~([^~]+)~~"), "<s>$1</s>")
        t = t.replace("\\*", "*").replace("\\_", "_").replace("\n", "<br>")
        return t
    }
}
