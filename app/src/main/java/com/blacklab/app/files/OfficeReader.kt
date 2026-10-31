package com.blacklab.app.files

import android.util.Base64
import com.blacklab.app.data.ContentStore
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Readers for Office/OpenDocument/EPUB packages — pure Kotlin, nothing leaves the phone. */
object OfficeReader {

    data class Slide(val number: Int, val title: String, val body: List<String>, val images: List<String>, val notes: String)

    private fun ZipFile.text(name: String): String? = getEntry(name)?.let { e -> getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
    private fun ZipFile.bytes(name: String): ByteArray? = getEntry(name)?.let { e -> getInputStream(e).use { it.readBytes() } }
    private fun xml(s: String) = Jsoup.parse(s, "", Parser.xmlParser())

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    private fun dataUri(name: String, bytes: ByteArray) = "data:${mimeOf(name)};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Resolves "../media/x.png" against the folder of "ppt/slides/slide1.xml". */
    fun resolve(base: String, rel: String): String {
        if (rel.startsWith("/")) return rel.trimStart('/')
        val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        rel.substringBefore('#').split('/').forEach { p ->
            when (p) { ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); ".", "" -> {}; else -> parts += java.net.URLDecoder.decode(p, "UTF-8") }
        }
        return parts.joinToString("/")
    }

    private fun rels(z: ZipFile, part: String): Map<String, String> {
        val relPath = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"
        val x = z.text(relPath) ?: return emptyMap()
        return xml(x).select("Relationship").associate { it.attr("Id") to resolve(part, it.attr("Target")) }
    }

    // ------------------------------------------------------------------ PowerPoint

    fun pptxSlides(f: File): List<Slide> = ZipFile(f).use { z ->
        val order = Regex("ppt/slides/slide(\\d+)\\.xml")
        val names = z.entries().asSequence().mapNotNull { e -> order.matchEntire(e.name)?.let { it.groupValues[1].toInt() to e.name } }.sortedBy { it.first }.toList()
        // presentation.xml has the real slide order
        val presOrder = runCatching {
            val pres = z.text("ppt/presentation.xml")!!
            val r = rels(z, "ppt/presentation.xml")
            xml(pres).select("p|sldId").mapNotNull { r[it.attr("r:id")] }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: names.map { it.second }
        presOrder.mapIndexedNotNull { i, part ->
            val x = z.text(part) ?: return@mapIndexedNotNull null
            val doc = xml(x)
            val r = rels(z, part)
            var title = ""
            val body = mutableListOf<String>()
            doc.select("p|sp").forEach { sp ->
                val ph = sp.selectFirst("p|ph")?.attr("type").orEmpty()
                val paras = sp.select("a|p").map { p -> p.select("a|t").joinToString("") { it.text() } }.filter { it.isNotBlank() }
                if ((ph == "title" || ph == "ctrTitle") && title.isEmpty()) title = paras.joinToString(" ") else body += paras
            }
            doc.select("a|tbl a|tr").forEach { tr -> body += tr.select("a|tc").joinToString("  |  ") { it.text() } }
            val images = doc.select("a|blip").mapNotNull { b ->
                val target = r[b.attr("r:embed")] ?: return@mapNotNull null
                z.bytes(target)?.takeIf { it.size < 4_000_000 }?.let { dataUri(target, it) }
            }
            val notes = r.values.firstOrNull { it.contains("notesSlide") }?.let { n -> z.text(n)?.let { xml(it).select("a|t").joinToString(" ") { t -> t.text() } } }.orEmpty()
            Slide(i + 1, title, body, images, notes)
        }
    }

    fun slidesHtml(slides: List<Slide>): String = slides.joinToString("") { s ->
        val esc = ContentStore::escape
        """<section class="slide"><div class="num">${s.number}</div>""" +
            (if (s.title.isNotBlank()) "<h2>${esc(s.title)}</h2>" else "") +
            (if (s.body.isNotEmpty()) "<ul>" + s.body.joinToString("") { "<li>${esc(it)}</li>" } + "</ul>" else "") +
            s.images.joinToString("") { "<img src=\"$it\">" } +
            (if (s.notes.isNotBlank()) "<details><summary>Speaker notes</summary><p>${esc(s.notes)}</p></details>" else "") +
            "</section>"
    }

    // ------------------------------------------------------------------ OpenDocument text

    fun odtToHtml(f: File): String = ZipFile(f).use { z ->
        val doc = xml(z.text("content.xml") ?: return "")
        val out = StringBuilder()
        fun walk(el: Element) {
            el.children().forEach { c ->
                when (c.tagName()) {
                    "text:h" -> { val l = c.attr("text:outline-level").toIntOrNull()?.coerceIn(1, 6) ?: 2; out.append("<h$l>${ContentStore.escape(c.text())}</h$l>") }
                    "text:p" -> out.append("<p>${ContentStore.escape(c.wholeText().ifBlank { c.text() })}</p>")
                    "text:list" -> { out.append("<ul>"); c.select("text|list-item").forEach { out.append("<li>${ContentStore.escape(it.text())}</li>") }; out.append("</ul>") }
                    "table:table" -> {
                        out.append("<table>")
                        c.select("table|table-row").forEach { row -> out.append("<tr>"); row.select("table|table-cell").forEach { out.append("<td>${ContentStore.escape(it.text())}</td>") }; out.append("</tr>") }
                        out.append("</table>")
                    }
                    else -> walk(c)
                }
            }
        }
        doc.selectFirst("office|text")?.let { walk(it) }
        out.toString()
    }

    // ------------------------------------------------------------------ EPUB

    /** Returns (chapter title, sanitized body HTML with images inlined) in reading order. */
    fun epubChapters(f: File): List<Pair<String, String>> = ZipFile(f).use { z ->
        val container = z.text("META-INF/container.xml") ?: return emptyList()
        val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path") ?: return emptyList()
        val opf = xml(z.text(opfPath) ?: return emptyList())
        val manifest = opf.select("manifest > item").associate { it.attr("id") to (resolve(opfPath, it.attr("href")) to it.attr("media-type")) }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")]?.first }
        spine.mapIndexedNotNull { i, part ->
            val html = z.text(part) ?: return@mapIndexedNotNull null
            val d = Jsoup.parse(html)
            d.select("script, iframe, object, embed, link, style").remove()
            d.select("img[src], image").forEach { img ->
                val attr = if (img.tagName() == "image") "xlink:href" else "src"
                val target = resolve(part, img.attr(attr))
                z.bytes(target)?.takeIf { it.size < 4_000_000 }?.let { img.attr("src", dataUri(target, it)) }
                if (img.tagName() == "image") img.tagName("img")
            }
            d.select("a[href]").forEach { it.removeAttr("href") }
            val title = d.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() } ?: d.title().ifBlank { "Chapter ${i + 1}" }
            title to d.body().html()
        }.filter { it.second.isNotBlank() }
    }

    fun epubTitle(f: File): String? = runCatching {
        ZipFile(f).use { z ->
            val opfPath = xml(z.text("META-INF/container.xml")!!).selectFirst("rootfile")!!.attr("full-path")
            xml(z.text(opfPath)!!).selectFirst("dc|title")?.text()
        }
    }.getOrNull()
}
