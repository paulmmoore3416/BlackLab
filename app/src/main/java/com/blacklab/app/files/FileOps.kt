package com.blacklab.app.files

import com.blacklab.app.AppContainer
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.docs.DocImporter
import com.blacklab.app.docs.DocxReader
import com.blacklab.app.docs.Markdown
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** File-level operations shared by the viewers, the Library and the editors. */
object FileOps {

    /** Where a file of this kind belongs inside the active profile. */
    fun folderFor(kind: FileKind): Folder = when (kind) {
        FileKind.IMAGE -> Folder.PHOTOS
        FileKind.VIDEO -> Folder.VIDEOS
        FileKind.AUDIO -> Folder.RECORDINGS
        else -> Folder.IMPORTS
    }

    /** Copies a file opened from another app (or an archive entry) into BlackLab. */
    fun saveToBlackLab(app: AppContainer, f: File): File {
        val dir = app.storage.dir(app.settings.current.activeProfile, folderFor(FileKinds.of(f)))
        val dest = Files.unique(dir, f.nameWithoutExtension, f.extension.ifBlank { "bin" })
        f.copyTo(dest, overwrite = true)
        app.storage.scan(dest)
        return dest
    }

    /** "name_suffix.ext" next to [f] (or in [dir]) that doesn't exist yet. */
    fun sibling(f: File, suffix: String, ext: String = f.extension, dir: File? = null): File =
        Files.unique(dir ?: f.parentFile!!, f.nameWithoutExtension + suffix, ext)

    /** [parent]/[name], or "name (2)", "name (3)" … when that folder already exists. */
    fun uniqueDir(parent: File, name: String): File {
        val clean = Files.sanitize(name)
        var d = File(parent, clean); var i = 2
        while (d.exists()) { d = File(parent, "$clean ($i)"); i++ }
        return d
    }

    /** Atomic replace: write to a temp file next to the target, then swap it in. */
    fun writeAtomically(target: File, write: (File) -> Unit) {
        val tmp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
        try {
            write(tmp)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, true); tmp.delete() }
        } finally { if (tmp.exists()) tmp.delete() }
    }

    // ------------------------------------------------------------------ documents

    /** Converts any document-like file into editor HTML for BlackLab Documents. */
    fun toDocumentHtml(f: File): String = when (f.extension.lowercase()) {
        "docx", "docm", "dotx" -> DocxReader.toHtml(f.readBytes())
        "html", "htm", "xhtml" -> Jsoup.parse(f, "UTF-8").body().html()
        "md", "markdown" -> Markdown.toHtml(f.readText())
        "rtf" -> ContentStore.textToHtml(DocImporter.stripRtf(f.readText()))
        "odt" -> OfficeReader.odtToHtml(f)
        "pptx", "ppsx" -> OfficeReader.pptxSlides(f).joinToString("") { s ->
            "<h2>Slide ${s.number}${if (s.title.isNotBlank()) " — " + ContentStore.escape(s.title) else ""}</h2>" +
                s.body.joinToString("") { "<p>${ContentStore.escape(it)}</p>" } + s.images.joinToString("") { "<p><img src=\"$it\"></p>" }
        }
        "epub" -> OfficeReader.epubChapters(f).joinToString("<hr>") { it.second }
        else -> ContentStore.textToHtml(f.readText())
    }

    /** Imports [f] as a new editable BlackLab document and returns its id. */
    suspend fun importAsDocument(app: AppContainer, f: File, html: String = toDocumentHtml(f)): Long {
        val profile = app.settings.current.activeProfile
        val title = f.nameWithoutExtension
        val rel = app.content.writeDocument(profile, title, "", "", html)
        val text = ContentStore.plainText(html)
        return app.db.documents().insert(
            DocumentEntity(profile = profile.name, title = title, filePath = rel, preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)),
        )
    }

    // ------------------------------------------------------------------ archives

    data class Entry(val name: String, val size: Long, val compressed: Long, val time: Long, val dir: Boolean)

    fun listZip(f: File): List<Entry> = ZipFile(f).use { z ->
        z.entries().asSequence().map { Entry(it.name, it.size, it.compressedSize, it.time, it.isDirectory) }.toList()
    }

    /** Extracts [only] (or everything) into [dest], refusing entries that would escape it ("zip slip"). */
    fun unzip(f: File, dest: File, only: Set<String>? = null): Int {
        val root = dest.canonicalFile.also { it.mkdirs() }
        var n = 0
        ZipFile(f).use { z ->
            z.entries().asSequence().filter { only == null || it.name in only || only.any { o -> o.endsWith("/") && it.name.startsWith(o) } }.forEach { e ->
                val out = File(root, e.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator) && out != root) return@forEach
                if (e.isDirectory) { out.mkdirs(); return@forEach }
                out.parentFile?.mkdirs()
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                n++
            }
        }
        return n
    }

    /** Extracts one entry to a cache file so it can be opened in a viewer. */
    fun extractEntry(f: File, entry: String, cacheDir: File): File {
        val out = File(File(cacheDir, "zip_" + f.nameWithoutExtension.hashCode()), entry.substringAfterLast('/'))
        out.parentFile?.mkdirs()
        ZipFile(f).use { z -> z.getInputStream(z.getEntry(entry)).use { i -> out.outputStream().use { i.copyTo(it) } } }
        return out
    }

    /** Compresses files and folders into [out] (folders keep their structure). */
    fun zip(sources: List<File>, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            sources.forEach { src ->
                val base = src.parentFile
                src.walkTopDown().filter { it.isFile && it != out }.forEach { file ->
                    val name = file.relativeTo(base!!).path.replace(File.separatorChar, '/')
                    zos.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }

    // ------------------------------------------------------------------ calendars

    /** Lightweight .ics parse for the viewer: summary, start, end, location per VEVENT. */
    fun icsEvents(text: String): List<Map<String, String>> {
        val unfolded = text.replace(Regex("\r?\n[ \t]"), "")
        return Regex("BEGIN:VEVENT(.*?)END:VEVENT", RegexOption.DOT_MATCHES_ALL).findAll(unfolded).map { m ->
            m.groupValues[1].lines().mapNotNull { l ->
                val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).substringBefore(';').uppercase() to Parser.unescapeEntities(l.substring(i + 1).replace("\\n", "\n").replace("\\,", ","), false)
            }.toMap()
        }.toList()
    }
}
