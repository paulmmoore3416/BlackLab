package com.blacklab.app.core

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.Settings as AndroidSettings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Folder(val dirName: String) {
    PHOTOS("Photos"),
    VIDEOS("Videos"),
    RECORDINGS("Recordings"),
    DICTATION("Dictation"),
    SCANS("Scans"),
    DOCUMENTS("Documents"),
    JOURNAL("Journal"),
    EDUCATION("Education"),
    EXPORTS("Exports"),
    IMPORTS("Imports"),
}

/**
 * Owns the on-device "BlackLab" folder. With All-files access it lives at
 * /storage/emulated/0/BlackLab (visible in Files, survives reinstall). Without it,
 * it falls back to the app-specific external directory.
 *
 * Database rows store paths relative to [root] so data keeps working if the root moves.
 */
class BlackLabStorage(private val context: Context) {

    val hasAllFilesAccess: Boolean get() = Environment.isExternalStorageManager()

    private val publicRoot: File get() = File(Environment.getExternalStorageDirectory(), "BlackLab")
    private val fallbackRoot: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "BlackLab")

    val root: File get() = if (hasAllFilesAccess) publicRoot else fallbackRoot

    val isPublic: Boolean get() = hasAllFilesAccess

    fun profileDir(profile: Profile): File = File(root, profile.folder)

    fun dir(profile: Profile, folder: Folder): File =
        File(profileDir(profile), folder.dirName).also { if (!it.exists()) it.mkdirs() }

    val backupsDir: File get() = File(root, "Backups").also { it.mkdirs() }

    fun ensureStructure() {
        runCatching {
            if (hasAllFilesAccess) migrateFallbackIfNeeded()
            root.mkdirs()
            for (p in Profile.entries) for (f in Folder.entries) dir(p, f)
            backupsDir
            val readme = File(root, "README.txt")
            if (!readme.exists()) {
                readme.writeText(
                    """
                    BlackLab — your personal & professional workspace
                    =================================================
                    Everything you create in BlackLab is saved here.

                    Personal/       Personal profile files
                    Professional/   Professional profile files
                      Photos/       Camera photos
                      Videos/       Camera videos
                      Recordings/   Audio recordings (+ transcripts)
                      Dictation/    Dictation sessions (audio + transcript + location), one folder each
                      Scans/        Document scans (PDF)
                      Documents/    Documents (.html, open in any browser or Word)
                      Journal/      Journal entries by year/month
                      Education/    Syllabi, certificates and course files
                      Exports/      PDF / DOCX / TXT / MD exports
                      Imports/      Files imported into BlackLab
                    Backups/        Automatic database backups (.zip)
                    """.trimIndent()
                )
            }
        }
    }

    /** Copy anything saved before All-files access was granted into the public folder. */
    private fun migrateFallbackIfNeeded() {
        val src = fallbackRoot
        if (!src.exists()) return
        val marker = File(src, ".migrated")
        if (marker.exists()) return
        src.walkTopDown().filter { it.isFile && it.name != ".migrated" }.forEach { f ->
            val rel = f.relativeTo(src).path
            val dest = File(publicRoot, rel)
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                runCatching { f.copyTo(dest) }
            }
        }
        marker.writeText("migrated")
    }

    fun relative(file: File): String = file.absoluteFile.relativeTo(root.absoluteFile).path

    fun resolve(relativePath: String): File =
        if (relativePath.startsWith("/")) File(relativePath) else File(root, relativePath)

    fun scan(file: File) {
        if (!isPublic) return
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
    }

    fun freeBytes(): Long = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)

    fun allFilesAccessIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    companion object {
        private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)
        fun timestamp(): String = LocalDateTime.now().format(stamp)
    }
}

object Files {
    fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").replace(Regex("\\s+"), " ").take(80).ifBlank { "Untitled" }

    /** Returns [dir]/[base].[ext], adding " (2)", " (3)" … if it already exists (unless it is [allow]). */
    fun unique(dir: File, base: String, ext: String, allow: File? = null): File {
        dir.mkdirs()
        val clean = sanitize(base)
        var f = File(dir, "$clean.$ext")
        var i = 2
        while (f.exists() && f.absolutePath != allow?.absolutePath) {
            f = File(dir, "$clean ($i).$ext")
            i++
        }
        return f
    }

    fun mime(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        return when (ext) {
            "md" -> "text/markdown"
            "m4a" -> "audio/mp4"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "ics" -> "text/calendar"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun share(context: Context, files: List<File>, subject: String? = null) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { uri(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                clipData = ClipData.newRawUri(files.first().name, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        context.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun open(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri(context, file), mime(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun openUrl(context: Context, url: String) {
        val fixed = if (url.contains("://") || url.startsWith("mailto:") || url.startsWith("tel:")) url else "https://$url"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fixed)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "Can't open link", Toast.LENGTH_SHORT).show()
        }
    }

    fun email(context: Context, to: String, subject: String = "", body: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, to.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(context, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    fun openMap(context: Context, query: String) {
        openUrl(context, "geo:0,0?q=" + Uri.encode(query))
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        return String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }

    fun copyFromUri(context: Context, uri: Uri, dest: File): Boolean = runCatching {
        dest.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } != null
    }.getOrDefault(false)

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }
}
