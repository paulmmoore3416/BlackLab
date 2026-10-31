package com.blacklab.app.ui.videoedit

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.files.FileKind
import com.blacklab.app.files.FileKinds
import com.blacklab.app.media.VClip
import com.blacklab.app.media.VideoExport
import com.blacklab.app.media.VideoProject
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.viewer.BusyOverlay
import com.blacklab.app.ui.viewer.openInApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Tools = listOf("Trim", "Look", "Text", "Frame", "Speed", "Audio", "Export")
private val Looks = listOf("none" to "Original", "vivid" to "Vivid", "warm" to "Warm", "cool" to "Cool", "bw" to "B&W", "noir" to "Noir", "sepia" to "Sepia", "faded" to "Faded", "invert" to "Invert")

/**
 * Video editor & creator. Route "videoedit?path=…" opens a video; "videoedit?paths=a|b|c" starts a
 * project from several photos/videos (slideshow / montage). Effects preview live on the player.
 */
@Composable
fun VideoEditorScreen(path: String, multi: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(VideoProject()) }
    var sel by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(Tools[0]) }
    var progress by remember { mutableStateOf<Int?>(null) }
    var cancel by remember { mutableStateOf<(() -> Unit)?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmExit by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val source = remember(path) { if (path.isNotBlank()) app.storage.resolve(path) else null }

    fun update(f: (VideoProject) -> VideoProject) { project = f(project); edited = true }

    LaunchedEffect(path, multi) {
        val files = (if (multi.isNotBlank()) multi.split('|').map { app.storage.resolve(Uri.decode(it)) } else listOfNotNull(source)).filter { it.exists() }
        val clips = withContext(Dispatchers.IO) { files.mapNotNull { f -> VideoExport.probe(ctx, Uri.fromFile(f), f.name, FileKinds.of(f) == FileKind.IMAGE) } }
        project = VideoProject(clips = clips)
        if (multi.isNotBlank()) edited = true
    }
    val adder = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = "Adding…"
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    val name = Files.displayName(ctx, u)
                    val local = File(app.storage.openedDir, "clip_${System.nanoTime()}_" + Files.sanitize(name))
                    if (!Files.copyFromUri(ctx, u, local)) null else VideoExport.probe(ctx, Uri.fromFile(local), name, (ctx.contentResolver.getType(u) ?: "").startsWith("image/"))
                }
            }
            busy = null
            update { it.copy(clips = it.clips + added) }
            if (added.isEmpty()) shell.toast("Couldn't read those files")
        }
    }
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u == null) return@rememberLauncherForActivityResult
        scope.launch {
            val local = withContext(Dispatchers.IO) { File(app.storage.openedDir, "music_" + Files.sanitize(Files.displayName(ctx, u))).also { Files.copyFromUri(ctx, u, it) } }
            update { it.copy(music = Uri.fromFile(local), musicName = Files.displayName(ctx, u)) }
        }
    }

    // ---------------- preview player (rebuilt when the effect chain changes)
    val clip = project.clips.getOrNull(sel)
    val fxKey = listOf(project.rotation, project.aspect, project.height, project.filter, project.brightness, project.contrast, project.saturation, project.title, project.titlePos, project.titleSize, project.titleColor, project.titleBox, project.watermark, project.clips.firstOrNull()?.uri)
    var position by remember { mutableLongStateOf(0) }
    val player = remember(clip?.uri, clip?.isImage, fxKey) {
        if (clip == null || clip.isImage) null else ExoPlayer.Builder(ctx).build().apply {
            runCatching { setVideoEffects(VideoExport.videoEffects(project, forPreview = true)) }
            setMediaItem(MediaItem.fromUri(clip.uri)); prepare(); seekTo(clip.startMs); repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    LaunchedEffect(player, project.speed, project.mute, project.volume) { player?.playbackParameters = PlaybackParameters(project.speed); player?.volume = if (project.mute) 0f else project.volume.coerceAtMost(1f) }
    LaunchedEffect(player, clip?.startMs, clip?.endMs) {
        while (player != null && clip != null) {
            position = player.currentPosition
            if (player.isPlaying && position >= clip.endMs) { player.pause(); player.seekTo(clip.startMs) }
            delay(50)
        }
    }

    fun export() {
        if (project.clips.isEmpty()) return
        val dir = if (source != null && app.storage.contains(source)) source.parentFile!! else app.storage.dir(app.settings.current.activeProfile, if (project.audioOnly) Folder.RECORDINGS else Folder.VIDEOS)
        val base = (source?.nameWithoutExtension?.removeSuffix("_edit") ?: "BlackLab video ${com.blacklab.app.core.BlackLabStorage.timestamp()}") + if (project.audioOnly) "_audio" else "_edit"
        val out = Files.unique(dir, base, if (project.audioOnly) "m4a" else "mp4")
        player?.pause()
        progress = 0
        cancel = VideoExport.export(ctx, project, out, onProgress = { progress = it }) { r ->
            progress = null; cancel = null
            r.onSuccess { f -> app.storage.scan(f); edited = false; shell.toast("Saved ${f.name}", "Open") { openInApp(shell, app, f) } }
                .onFailure { shell.toast("Export failed: ${it.message ?: "unsupported on this device"}") }
        }
    }

    BackHandler(enabled = edited && progress == null) { confirmExit = true }

    Scaffold(topBar = {
        BlTopBar(source?.nameWithoutExtension ?: "New video", subtitle = "${project.clips.size} clip(s) · ${Time.duration(project.totalMs)} · ${VideoExport.outputSize(project).let { "${it.first}×${it.second}" }}",
            onBack = { if (edited) confirmExit = true else shell.back() }, actions = {
                TextButton(enabled = project.clips.isNotEmpty() && progress == null, onClick = { export() }) { Text("Export", fontWeight = FontWeight.Bold) }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // ---------------- preview
                Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black), contentAlignment = Alignment.Center) {
                    when {
                        clip == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Add videos or photos to start", color = Color.White)
                            FilledTonalButton(onClick = { adder.launch(arrayOf("video/*", "image/*")) }, Modifier.padding(top = 10.dp)) { Icon(Icons.Default.Add, null); Text("Add media") }
                        }
                        clip.isImage -> AsyncImage(clip.uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        player != null -> AndroidView({ PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 2500 } }, Modifier.fillMaxSize(), update = { it.player = player })
                    }
                }
                // ---------------- timeline
                LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(project.clips) { i, c ->
                        Box(Modifier.size(76.dp, 56.dp).clip(RoundedCornerShape(8.dp)).border(if (i == sel) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).androidxClickable { sel = i }) {
                            AsyncImage(ImageRequest.Builder(ctx).data(c.uri).size(200).apply { if (!c.isImage) decoderFactory(VideoFrameDecoder.Factory()) }.build(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Text(Time.duration(c.lengthMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 4.dp))
                            if (c.isImage) Icon(Icons.Default.Image, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(14.dp))
                        }
                    }
                    item {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).androidxClickable { adder.launch(arrayOf("video/*", "image/*")) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, "Add clip", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
                // ---------------- tool tabs
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tools.forEach { t -> FilterChip(tool == t, { tool = t }, label = { Text(t) }) }
                }
                Column(Modifier.fillMaxWidth().height(230.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    when (tool) {
                        "Trim" -> if (clip != null) {
                            fun setClip(n: VClip) = update { p -> p.copy(clips = p.clips.toMutableList().also { it[sel] = n }) }
                            if (clip.isImage) {
                                Text("Show photo for ${"%.1f".format(clip.imageMs / 1000f)} s", style = MaterialTheme.typography.labelLarge)
                                Slider(clip.imageMs.toFloat(), { setClip(clip.copy(imageMs = (it / 250).toLong() * 250)) }, valueRange = 500f..15000f)
                            } else {
                                Text("Keep ${Time.duration(clip.startMs)} – ${Time.duration(clip.endMs)}  (${Time.duration(clip.lengthMs)})", style = MaterialTheme.typography.labelLarge)
                                RangeSlider(clip.startMs.toFloat()..clip.endMs.toFloat(), { r -> setClip(clip.copy(startMs = r.start.toLong(), endMs = r.endInclusive.toLong().coerceAtLeast(r.start.toLong() + 200))); player?.seekTo(r.start.toLong()) },
                                    valueRange = 0f..clip.durationMs.toFloat().coerceAtLeast(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    FilledTonalButton(onClick = { setClip(clip.copy(startMs = position.coerceIn(0, clip.endMs - 200))) }) { Text("Start here") }
                                    FilledTonalButton(onClick = { setClip(clip.copy(endMs = position.coerceIn(clip.startMs + 200, clip.durationMs))) }) { Text("End here") }
                                    FilledTonalButton(enabled = position > clip.startMs + 200 && position < clip.endMs - 200, onClick = {
                                        update { p -> p.copy(clips = p.clips.toMutableList().also { l -> l[sel] = clip.copy(endMs = position); l.add(sel + 1, clip.copy(startMs = position)) }) }
                                    }) { Icon(Icons.Default.ContentCut, null); Text("Split") }
                                    FilledTonalButton(onClick = {
                                        busy = "Saving frame…"
                                        scope.launch {
                                            val f = withContext(Dispatchers.IO) {
                                                VideoExport.frameAt(ctx, clip.uri, position)?.let { bmp ->
                                                    Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.PHOTOS), clip.name.substringBeforeLast('.') + " frame", "jpg").also { out -> out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }; app.storage.scan(out) }
                                                }
                                            }
                                            busy = null
                                            shell.toast(if (f != null) "Saved ${f.name} to Photos" else "Couldn't grab that frame")
                                        }
                                    }) { Icon(Icons.Default.PhotoCamera, null); Text("Frame → photo") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                IconButton(enabled = sel > 0, onClick = { update { p -> p.copy(clips = p.clips.toMutableList().also { java.util.Collections.swap(it, sel, sel - 1) }) }; sel-- }) { Icon(Icons.Default.ArrowBack, "Move earlier") }
                                IconButton(enabled = sel < project.clips.lastIndex, onClick = { update { p -> p.copy(clips = p.clips.toMutableList().also { java.util.Collections.swap(it, sel, sel + 1) }) }; sel++ }) { Icon(Icons.Default.ArrowForward, "Move later") }
                                IconButton(onClick = { update { p -> p.copy(clips = p.clips.toMutableList().also { it.add(sel + 1, clip) }) } }) { Icon(Icons.Default.ContentCopy, "Duplicate") }
                                IconButton(onClick = { update { p -> p.copy(clips = p.clips.toMutableList().also { it.removeAt(sel) }) }; sel = (sel - 1).coerceAtLeast(0) }) { Icon(Icons.Default.Delete, "Remove clip") }
                            }
                        } else Text("Add a clip to trim it.")
                        "Look" -> {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Looks.forEach { (k, l) -> FilterChip(project.filter == k, { update { it.copy(filter = k) } }, label = { Text(l) }) }
                            }
                            Labeled("Brightness", project.brightness, -0.5f..0.5f) { v -> update { it.copy(brightness = v) } }
                            Labeled("Contrast", project.contrast, -0.5f..0.5f) { v -> update { it.copy(contrast = v) } }
                            Labeled("Saturation", project.saturation, -0.8f..0.8f) { v -> update { it.copy(saturation = v) } }
                            TextButton(onClick = { update { it.copy(filter = "none", brightness = 0f, contrast = 0f, saturation = 0f) } }) { Text("Reset look") }
                        }
                        "Text" -> {
                            OutlinedTextField(project.title, { v -> update { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text("Title / caption") }, minLines = 1, maxLines = 3)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (k, l) -> FilterChip(project.titlePos == k, { update { it.copy(titlePos = k) } }, label = { Text(l) }) }
                                FilterChip(project.titleBox, { update { it.copy(titleBox = !it.titleBox) } }, label = { Text("Background") })
                            }
                            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(0xFFFFFFFF, 0xFFFFD54F, 0xFF4CD787, 0xFF4F8CFF, 0xFFEF5350, 0xFF000000).map { it.toInt() }.forEach { c ->
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c)).border(if (project.titleColor == c) 3.dp else 1.dp, if (project.titleColor == c) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                                        .androidxClickable { update { it.copy(titleColor = c) } })
                                }
                            }
                            Labeled("Size", project.titleSize, 0.03f..0.12f) { v -> update { it.copy(titleSize = v) } }
                            OutlinedTextField(project.watermark, { v -> update { it.copy(watermark = v) } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Corner watermark (optional)") })
                        }
                        "Frame" -> {
                            Text("Rotate", style = MaterialTheme.typography.labelLarge)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { update { it.copy(rotation = (it.rotation + 270) % 360) } }) { Icon(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left") }
                                IconButton(onClick = { update { it.copy(rotation = (it.rotation + 90) % 360) } }) { Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate right") }
                                Text("${project.rotation}°")
                            }
                            Text("Crop to aspect ratio", style = MaterialTheme.typography.labelLarge)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("orig" to "Original", "16:9" to "16:9", "9:16" to "9:16 Story", "1:1" to "1:1 Square", "4:5" to "4:5 Post", "4:3" to "4:3").forEach { (k, l) -> FilterChip(project.aspect == k, { update { it.copy(aspect = k) } }, label = { Text(l) }) }
                            }
                            Text("Resolution", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0 to "Original", 2160 to "4K", 1080 to "1080p", 720 to "720p", 480 to "480p").forEach { (k, l) -> FilterChip(project.height == k, { update { it.copy(height = k) } }, label = { Text(l) }) }
                            }
                        }
                        "Speed" -> {
                            Text("Speed ${"%.2f".format(project.speed)}× → ${Time.duration(project.totalMs)}", style = MaterialTheme.typography.labelLarge)
                            Slider(project.speed, { v -> update { it.copy(speed = (v * 20).toInt() / 20f) } }, valueRange = 0.25f..4f)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f).forEach { v -> FilterChip(project.speed == v, { update { it.copy(speed = v) } }, label = { Text("$v×") }) }
                            }
                        }
                        "Audio" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Mute original sound", Modifier.weight(1f)); Switch(project.mute, { v -> update { it.copy(mute = v) } }) }
                            if (!project.mute) Labeled("Original volume", project.volume, 0f..2f) { v -> update { it.copy(volume = v) } }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Icons.Default.MusicNote, null)
                                Text(project.music?.let { project.musicName } ?: "No background music", Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (project.music != null) IconButton(onClick = { update { it.copy(music = null, musicName = "") } }) { Icon(Icons.Default.Close, "Remove music") }
                                TextButton(onClick = { musicPicker.launch(arrayOf("audio/*")) }) { Text(if (project.music == null) "Add music" else "Change") }
                            }
                            if (project.music != null) Labeled("Music volume", project.musicVolume, 0f..1.5f) { v -> update { it.copy(musicVolume = v) } }
                            Text("Music loops to fill the video and is mixed in when you export.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        "Export" -> {
                            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("HEVC (H.265)"); Text("About half the size; some older devices can't play it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(project.hevc, { v -> update { it.copy(hevc = v) } }) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Convert HDR to SDR"); Text("Best for sharing to apps that show HDR too bright.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(project.toSdr, { v -> update { it.copy(toSdr = v) } }) }
                            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Audio only (.m4a)"); Text("Extract the soundtrack instead of a video.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(project.audioOnly, { v -> update { it.copy(audioOnly = v) } }) }
                            FilledTonalButton(enabled = project.clips.isNotEmpty(), onClick = { export() }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Default.Movie, null); Spacer(Modifier.width(6.dp)); Text("Export now") }
                        }
                    }
                }
            }
            busy?.let { BusyOverlay(it) }
            progress?.let { p ->
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                    Column(Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(24.dp).width(260.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Exporting… $p%", style = MaterialTheme.typography.titleMedium)
                        LinearProgressIndicator(progress = { p / 100f }, Modifier.fillMaxWidth().padding(vertical = 12.dp))
                        Text("Keep BlackLab open until it finishes.", style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { cancel?.invoke(); progress = null }) { Text("Cancel") }
                    }
                }
            }
        }
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false },
        title = { Text("Leave the editor?") },
        text = { Text("Your edits haven't been exported yet.") },
        confirmButton = { TextButton(onClick = { confirmExit = false; shell.back() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } },
    )
}

@Composable
private fun Labeled(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(120.dp), style = MaterialTheme.typography.labelLarge)
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

private fun Modifier.androidxClickable(onClick: () -> Unit) = this.clickable(onClick = onClick)
