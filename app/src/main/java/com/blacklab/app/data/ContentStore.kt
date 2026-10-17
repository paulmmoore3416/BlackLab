package com.blacklab.app.data

import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Rich-text content (documents + journal) lives as standalone .html files inside the
 * BlackLab folder so it can be opened by any browser or by Word. Room keeps only metadata.
 */
class ContentStore(private val storage: BlackLabStorage) {

    fun readBody(relPath: String): String {
        if (relPath.isBlank()) return ""
        val f = storage.resolve(relPath)
        if (!f.exists()) return ""
        return runCatching { Jsoup.parse(f, "UTF-8").body().html() }.getOrDefault("")
    }

    /** Writes the document and returns its (possibly new) relative path. */
    fun writeDocument(profile: Profile, title: String, folder: String, existingRel: String, bodyHtml: String, pageCss: String = ""): String {
        val base = storage.dir(profile, Folder.DOCUMENTS)
        val dir = if (folder.isBlank()) base else File(base, Files.sanitize(folder))
        return write(dir, title, existingRel, bodyHtml, pageCss)
    }

    fun writeJournal(profile: Profile, day: LocalDate, title: String, existingRel: String, bodyHtml: String): String {
        val dir = File(storage.dir(profile, Folder.JOURNAL), "${day.year}/${String.format(Locale.US, "%02d", day.monthValue)}")
        val name = day.format(DateTimeFormatter.ISO_LOCAL_DATE) + if (title.isNotBlank()) " ${title}" else ""
        return write(dir, name, existingRel, bodyHtml, "")
    }

    private fun write(dir: File, title: String, existingRel: String, bodyHtml: String, pageCss: String): String {
        val existing = existingRel.takeIf { it.isNotBlank() }?.let { storage.resolve(it) }
        val wanted = Files.sanitize(title.ifBlank { "Untitled" })
        val target = if (existing != null && existing.parentFile?.absolutePath == dir.absolutePath &&
            (existing.nameWithoutExtension == wanted || existing.nameWithoutExtension.startsWith("$wanted ("))
        ) existing else Files.unique(dir, wanted, "html", allow = existing)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".${target.name}.tmp")
        tmp.writeText(wrapHtml(title, bodyHtml, pageCss))
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText()); tmp.delete()
        }
        if (existing != null && existing.absolutePath != target.absolutePath) existing.delete()
        return storage.relative(target)
    }

    fun delete(relPath: String) {
        if (relPath.isNotBlank()) storage.resolve(relPath).delete()
    }

    fun file(relPath: String): File = storage.resolve(relPath)

    companion object {
        const val BASE_CSS = """
            body { font-family: Calibri, 'Segoe UI', Roboto, Arial, sans-serif; font-size: 11pt; line-height: 1.4; color: #1b1b1f; max-width: 8.5in; margin: 0 auto; padding: 0.5in; }
            h1.title { font-size: 26pt; font-weight: 300; }
            h1 { font-size: 20pt; } h2 { font-size: 16pt; } h3 { font-size: 13.5pt; }
            table { border-collapse: collapse; width: 100%; margin: 8px 0; }
            td, th { border: 1px solid #9aa0a6; padding: 6px 8px; vertical-align: top; }
            th { background: #eef1f5; }
            blockquote { border-left: 3px solid #4f8cff; margin: 8px 0; padding: 4px 12px; color: #444; }
            pre { background: #f3f4f6; padding: 8px; font-family: 'Courier New', monospace; white-space: pre-wrap; }
            img { max-width: 100%; height: auto; }
            ul.checklist { list-style: none; padding-left: 4px; }
            ul.checklist li::before { content: '☐  '; }
            ul.checklist li.checked::before { content: '☑  '; }
            ul.checklist li.checked { text-decoration: line-through; color: #777; }
            .page-break { page-break-after: always; break-after: page; border-top: 1px dashed #bbb; margin: 16px 0; }
            hr { border: none; border-top: 1px solid #9aa0a6; }
        """

        fun wrapHtml(title: String, body: String, extraCss: String = ""): String = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="generator" content="BlackLab">
<title>${escape(title)}</title>
<style>${BASE_CSS}${extraCss}</style></head>
<body>
$body
</body></html>
"""

        fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun plainText(html: String): String {
            if (html.isBlank()) return ""
            val doc: Document = Jsoup.parseBodyFragment(html)
            doc.select("br").append("\\n")
            doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr, blockquote, pre").prepend("\\n")
            return doc.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }
                .replace(Regex("\n{3,}"), "\n\n").trim()
        }

        fun wordCount(text: String): Int = Regex("[\\p{L}\\p{N}'’]+").findAll(text).count()

        fun preview(text: String, max: Int = 220): String = text.replace(Regex("\\s+"), " ").trim().take(max)

        fun textToHtml(text: String): String = text.split(Regex("\n{2,}")).joinToString("") { para ->
            "<p>" + escape(para.trim()).replace("\n", "<br>") + "</p>"
        }
    }
}
