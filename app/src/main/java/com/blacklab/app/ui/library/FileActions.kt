package com.blacklab.app.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.imaging.PhotoMeta
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.components.OcrResultDialog
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.editor.EditorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

fun openMap(ctx: android.content.Context, lat: Double, lon: Double, label: String = "") {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon" + if (label.isNotBlank()) "(${Uri.encode(label)})" else "")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Files.openUrl(ctx, "https://maps.google.com/?q=$lat,$lon") }
}

/**
 * Long-press menu for any file in BlackLab (photos, videos, scans, exports…). Shows every ability that
 * applies to the file type, and hosts the follow-up dialogs (rename, delete, details, OCR).
 */
@Composable
fun FileActionHost(file: File?, onDismiss: () -> Unit, onChanged: () -> Unit = {}, onSelect: ((File) -> Unit)? = null, onDeleted: (() -> Unit)? = null) {
    val f = file ?: return
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var step by remember(f) { mutableStateOf("menu") }
    var ocr by remember(f) { mutableStateOf<String?>(null) }
    val rel = app.storage.relative(f)
    fun edit(tab: String) = shell.go("imgedit?path=${Uri.encode(rel)}&tab=$tab")
    val image = f.isImage()
    val video = f.isVideo()
    val gps = remember(f) { if (image) PhotoMeta.latLong(f) else null }

    when (step) {
        "menu" -> ActionSheet(
            title = f.name,
            subtitle = "${Files.formatSize(f.length())} · ${Time.dateTime(f.lastModified())}" + if (gps != null) " · 📍" else "",
            onDismiss = { if (step == "menu") onDismiss() },
            leading = if (image || video) ({
                AsyncImage(ImageRequest.Builder(ctx).data(f).size(200).apply { if (video) decoderFactory(VideoFrameDecoder.Factory()) }.build(), null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }) else ({ Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize()) }),
            quick = buildList {
                if (image) add(SheetAction("Edit", Icons.Default.Edit) { edit("draw") })
                add(SheetAction("Share", Icons.Default.Share) { Files.share(ctx, listOf(f)) })
                add(SheetAction("Details", Icons.Default.Info) { step = "info" })
                add(SheetAction("Delete", Icons.Default.Delete, destructive = true) { step = "delete" })
            },
            actions = buildList {
                if (image || video) add(SheetAction(if (video) "Play" else "Open", if (video) Icons.Default.PlayArrow else Icons.Default.OpenInFull) { shell.go("media?path=" + Uri.encode(rel)) })
                if (image) {
                    add(SheetAction("Draw & mark up", Icons.Default.Brush, hint = "Pen, highlighter, arrows, boxes, circles") { edit("draw") })
                    add(SheetAction("Add text", Icons.Default.TextFields, hint = "Fonts, colors, bold, italic, underline, cursive") { edit("text") })
                    add(SheetAction("Looks & filters", Icons.Default.AutoAwesome, hint = "1970s, Noir, Cinematic and more") { edit("looks") })
                    add(SheetAction("Add watermark", Icons.Default.BrandingWatermark) { edit("watermark") })
                    add(SheetAction("Remove background", Icons.Default.ContentCut, hint = "On-device subject cut-out") { edit("cutout") })
                    add(SheetAction("Extract text (OCR)", Icons.Default.TextSnippet) {
                        app.io { val t = Ocr.recognize(ctx, f); withContext(Dispatchers.Main) { ocr = t; step = "ocr" } }
                        step = "wait"
                    })
                    add(SheetAction("Make a photo report", Icons.Default.NoteAdd, hint = "New document with this picture") {
                        app.io {
                            val profile = app.settings.current.activeProfile
                            val data = EditorController.imageDataUri(f) ?: return@io
                            val title = "Photo report ${Time.date(f.lastModified())}"
                            val where = PhotoMeta.read(f).filter { it.first in setOf("Address", "Coordinates", "Facing") }.joinToString("<br>") { "<b>${it.first}:</b> ${ContentStore.escape(it.second)}" }
                            val html = "<h1>${ContentStore.escape(title)}</h1><p><i>${Time.dateTime(f.lastModified())}</i></p>" + (if (where.isNotBlank()) "<p>$where</p>" else "") + "<p><img src=\"$data\"></p><h2>Notes</h2><p><br></p>"
                            val r = app.content.writeDocument(profile, title, "", "", html)
                            val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, filePath = r, preview = title))
                            withContext(Dispatchers.Main) { shell.go("doc/$id") }
                        }
                    })
                }
                if (gps != null) add(SheetAction("Show on map", Icons.Default.Map, hint = String.format(Locale.US, "%.5f, %.5f", gps[0], gps[1])) { openMap(ctx, gps[0], gps[1], f.nameWithoutExtension) })
                if (onSelect != null) add(SheetAction("Select", Icons.Default.CheckCircle, hint = "Pick several to share or delete") { onSelect(f) })
                add(SheetAction("Rename", Icons.Default.DriveFileRenameOutline) { step = "rename" })
                add(SheetAction("Open with…", Icons.Default.OpenInNew) { Files.open(ctx, f) })
                add(SheetAction("Copy path", Icons.Default.ContentCopy, hint = "BlackLab/$rel") {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Path", f.absolutePath))
                    shell.toast("Path copied")
                })
            },
        )
        "rename" -> TextInputDialog("Rename", f.nameWithoutExtension, "Name", onDismiss = onDismiss) { n ->
            val t = Files.unique(f.parentFile!!, n, f.extension)
            if (f.renameTo(t)) { app.storage.scan(t); onChanged() }
        }
        "delete" -> ConfirmDialog("Delete?", "“${f.name}” will be permanently deleted.", onDismiss = onDismiss) {
            f.delete(); app.storage.scan(f); onChanged(); onDeleted?.invoke()
        }
        "info" -> {
            val details = remember(f) { mediaInfo(f) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Details") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        details.forEach { (k, v) ->
                            Text(k, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            Text(v, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
                dismissButton = { if (gps != null) TextButton(onClick = { openMap(ctx, gps[0], gps[1], f.nameWithoutExtension) }) { Text("Map") } },
            )
        }
        "ocr" -> ocr?.let { OcrResultDialog(it, f.nameWithoutExtension) { onDismiss() } }
    }
}
