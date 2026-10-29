package com.blacklab.app.ui.library

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.components.OcrResultDialog
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.editor.EditorController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MediaViewerScreen(relPath: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val start = remember(relPath) { app.storage.resolve(relPath) }
    var version by remember { mutableStateOf(0) }
    val siblings by produceState(listOf(start), start, version) {
        value = withContext(Dispatchers.IO) {
            start.parentFile?.listFiles()?.filter { it.isFile && (it.isImage() || it.isVideo()) }?.sortedByDescending { it.lastModified() }?.ifEmpty { null } ?: listOf(start)
        }
    }
    val initial = siblings.indexOfFirst { it.path == start.path }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial) { siblings.size }
    var chrome by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<File?>(null) }
    val current = siblings.getOrNull(pager.currentPage) ?: start
    FileActionHost(menu, onDismiss = { menu = null }, onChanged = { version++ }, onDeleted = { if (siblings.size <= 1) shell.back() })

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { siblings.getOrNull(it)?.path ?: it }) { page ->
            val f = siblings.getOrNull(page) ?: return@HorizontalPager
            if (f.isVideo()) VideoPage(f, active = page == pager.currentPage) else ZoomableImage(f, onLongPress = { menu = f }) { chrome = !chrome }
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shell.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text("${Time.dateTime(current.lastModified())} · ${Files.formatSize(current.length())}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { dialog = "info" }) { Icon(Icons.Default.Info, "Info", tint = Color.White) }
                IconButton(onClick = { menu = current }) { Icon(Icons.Default.MoreVert, "More", tint = Color.White) }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).navigationBarsPadding().padding(8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                ViewerAction(Icons.Default.Share, "Share") { Files.share(ctx, listOf(current)) }
                if (current.isImage()) ViewerAction(Icons.Default.Edit, "Edit") { shell.go("imgedit?path=" + android.net.Uri.encode(app.storage.relative(current)) + "&tab=draw") }
                if (current.isImage()) ViewerAction(Icons.Default.TextSnippet, "Text") {
                    app.io { val t = Ocr.recognize(ctx, current); withContext(Dispatchers.Main) { ocrText = t } }
                }
                if (current.isImage()) ViewerAction(Icons.Default.NoteAdd, "To doc") {
                    app.io {
                        val profile = app.settings.current.activeProfile
                        val data = EditorController.imageDataUri(current) ?: return@io
                        val title = "Photo report ${Time.date(current.lastModified())}"
                        val html = "<h1>${ContentStore.escape(title)}</h1><p><i>${Time.dateTime(current.lastModified())}</i></p><p><img src=\"$data\"></p><h2>Notes</h2><p><br></p>"
                        val rel = app.content.writeDocument(profile, title, "", "", html)
                        val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, filePath = rel, preview = title))
                        withContext(Dispatchers.Main) { shell.go("doc/$id") }
                    }
                }
                ViewerAction(Icons.Default.Delete, "Delete") { dialog = "delete" }
            }
        }
    }
    when (dialog) {
        "delete" -> ConfirmDialog("Delete?", "“${current.name}” will be permanently deleted.", onDismiss = { dialog = null }) {
            current.delete(); app.storage.scan(current)
            if (siblings.size <= 1) shell.back() else version++
        }
        "rename" -> TextInputDialog("Rename", current.nameWithoutExtension, "Name", onDismiss = { dialog = null }) { n ->
            val t = Files.unique(current.parentFile!!, n, current.extension)
            if (current.renameTo(t)) { app.storage.scan(t); version++ }
        }
        "info" -> {
            val details = remember(current) { mediaInfo(current) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Details") },
                text = { Column { details.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.bodyMedium) } } },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
    }
    ocrText?.let { OcrResultDialog(it, current.nameWithoutExtension) { ocrText = null } }
}

internal fun mediaInfo(f: File): List<Pair<String, String>> {
    val out = mutableListOf("Name" to f.name, "Size" to Files.formatSize(f.length()), "Modified" to Time.dateTime(f.lastModified()), "Location" to f.parent.orEmpty())
    if (f.isImage()) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0) out += "Resolution" to "${o.outWidth} × ${o.outHeight} (${"%.1f".format(o.outWidth * o.outHeight / 1_000_000f)} MP)"
        runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(f)
            listOf("Camera" to exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL),
                "Exposure" to exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME)?.let { "1/${(1 / it.toDouble()).toInt()} s" },
                "ISO" to exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                "Aperture" to exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
                "Focal length" to exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH),
            ).forEach { (k, v) -> if (!v.isNullOrBlank()) out += k to v }
        }
        out += com.blacklab.app.imaging.PhotoMeta.read(f)
    } else if (f.isVideo()) {
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(f.path)
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH); val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                out += "Resolution" to "$w × $h"
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += "Duration" to Time.duration(it) }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let { out += "Frame rate" to "${it.toFloat().toInt()} fps" }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += "Bitrate" to "${it / 1_000_000} Mbps" }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.let { if (it == "7") out += "HDR" to "HLG" else if (it == "6") out += "HDR" to "HDR10 (PQ)" }
            }
        }
    }
    return out
}

@Composable
private fun ViewerAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomableImage(f: File, onLongPress: () -> Unit, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    AsyncImage(
        model = coil.request.ImageRequest.Builder(LocalContext.current).data(f).memoryCacheKey(f.path + f.lastModified()).build(), contentDescription = f.name, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(f) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(f) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

@Composable
private fun VideoPage(f: File, active: Boolean) {
    val ctx = LocalContext.current
    val player = remember(f) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f))); prepare() } }
    DisposableEffect(f) { onDispose { player.release() } }
    if (!active) player.pause()
    AndroidView({ PlayerView(it).apply { this.player = player; useController = true } }, Modifier.fillMaxSize())
}
