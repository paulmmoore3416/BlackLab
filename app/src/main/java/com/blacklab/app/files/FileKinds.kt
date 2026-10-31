package com.blacklab.app.files

import android.net.Uri
import java.io.File
import java.util.Locale

/** Every kind of file BlackLab can open itself, and which screen does it. */
enum class FileKind(val label: String) {
    IMAGE("Image"), VIDEO("Video"), AUDIO("Audio"), PDF("PDF"),
    TEXT("Text"), CODE("Code"), MARKDOWN("Markdown"), HTML("Web page"),
    SHEET("Spreadsheet"), WORD("Word document"), RTF("Rich text"), ODT("OpenDocument text"),
    SLIDES("Presentation"), EPUB("E-book"), ARCHIVE("Archive"), CALENDAR("Calendar"),
    FOLDER("Folder"), OTHER("File");

    /** True when BlackLab can change and save this kind of file in place. */
    val editable: Boolean get() = this in setOf(IMAGE, VIDEO, AUDIO, PDF, TEXT, CODE, MARKDOWN, HTML, SHEET, CALENDAR)
}

object FileKinds {
    val image = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "dng", "bmp", "avif")
    val video = setOf("mp4", "mov", "3gp", "mkv", "webm", "m4v", "ts", "avi")
    val audio = setOf("m4a", "wav", "mp3", "aac", "ogg", "oga", "flac", "opus", "amr", "mid", "midi", "wma", "3ga", "weba")
    val text = setOf("txt", "log", "text", "ini", "conf", "cfg", "properties", "env", "nfo", "srt", "vtt", "sub", "tex", "bib")
    val code = setOf(
        "json", "xml", "yaml", "yml", "toml", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cc", "cs",
        "go", "rs", "rb", "php", "swift", "sh", "bash", "zsh", "ps1", "bat", "sql", "gradle", "css", "scss", "less", "lua", "r", "dart", "vue", "svg",
    )
    val sheet = setOf("csv", "tsv", "xlsx", "xlsm")
    val archive = setOf("zip", "jar", "apk", "cbz", "aab")

    fun of(f: File): FileKind = if (f.isDirectory) FileKind.FOLDER else ofName(f.name)

    fun ofName(name: String): FileKind {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext in image -> FileKind.IMAGE
            ext == "ts" -> FileKind.CODE // TypeScript is far more common on a phone than MPEG-TS
            ext in video -> FileKind.VIDEO
            ext in audio -> FileKind.AUDIO
            ext == "pdf" -> FileKind.PDF
            ext in setOf("md", "markdown") -> FileKind.MARKDOWN
            ext in setOf("html", "htm", "xhtml", "mht") -> FileKind.HTML
            ext in sheet -> FileKind.SHEET
            ext in setOf("docx", "docm", "dotx") -> FileKind.WORD
            ext == "rtf" -> FileKind.RTF
            ext == "odt" -> FileKind.ODT
            ext in setOf("pptx", "ppsx") -> FileKind.SLIDES
            ext == "epub" -> FileKind.EPUB
            ext in archive -> FileKind.ARCHIVE
            ext in setOf("ics", "vcs") -> FileKind.CALENDAR
            ext in code -> FileKind.CODE
            ext in text || ext.isEmpty() -> FileKind.TEXT
            else -> FileKind.OTHER
        }
    }

    fun ofMime(mime: String?): FileKind? = when {
        mime == null -> null
        mime.startsWith("image/") -> FileKind.IMAGE
        mime.startsWith("video/") -> FileKind.VIDEO
        mime.startsWith("audio/") -> FileKind.AUDIO
        mime == "application/pdf" -> FileKind.PDF
        mime == "text/html" -> FileKind.HTML
        mime == "text/markdown" -> FileKind.MARKDOWN
        mime == "text/csv" || mime.contains("spreadsheet") -> FileKind.SHEET
        mime.contains("wordprocessingml") -> FileKind.WORD
        mime.contains("presentationml") -> FileKind.SLIDES
        mime == "application/epub+zip" -> FileKind.EPUB
        mime == "application/zip" -> FileKind.ARCHIVE
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" -> FileKind.TEXT
        else -> null
    }

    /** Typical extension for a mime type — used when a shared file arrives without a name. */
    fun extFor(mime: String?): String = when (ofMime(mime)) {
        FileKind.IMAGE -> mime!!.substringAfter('/').replace("jpeg", "jpg").substringBefore(';')
        FileKind.VIDEO -> "mp4"
        FileKind.AUDIO -> if (mime == "audio/mpeg") "mp3" else "m4a"
        FileKind.PDF -> "pdf"
        FileKind.HTML -> "html"
        FileKind.MARKDOWN -> "md"
        FileKind.SHEET -> if (mime == "text/csv") "csv" else "xlsx"
        FileKind.WORD -> "docx"
        FileKind.SLIDES -> "pptx"
        FileKind.EPUB -> "epub"
        FileKind.ARCHIVE -> "zip"
        FileKind.TEXT -> if (mime == "application/json") "json" else "txt"
        else -> "bin"
    }

    /**
     * The in-app route that opens [path] (relative to the BlackLab root, or absolute for files from
     * other apps). Photos & videos use the swipeable gallery, audio the music player, the rest the
     * universal viewer.
     */
    fun route(f: File, path: String): String {
        val p = Uri.encode(path)
        return when (of(f)) {
            FileKind.IMAGE, FileKind.VIDEO -> "media?path=$p"
            FileKind.AUDIO -> "player?path=$p"
            else -> "view?path=$p"
        }
    }

    fun isTextLike(k: FileKind) = k == FileKind.TEXT || k == FileKind.CODE || k == FileKind.MARKDOWN || k == FileKind.HTML || k == FileKind.CALENDAR
}
