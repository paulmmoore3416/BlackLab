package com.blacklab.app.ui.recorder

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.blacklab.app.core.Files
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.recorder.LiveTranscriber
import com.blacklab.app.recorder.PcmDecoder
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun RecordingDetailScreen(id: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val rec by remember(id) { app.db.recordings().observeOne(id) }.collectAsState(null)
    val r = rec
    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var transcript by remember(r?.id) { mutableStateOf(r?.transcript ?: "") }
    var notes by remember(r?.id) { mutableStateOf(r?.notes ?: "") }
    var transcribing by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(r?.filePath) {
        val path = r?.filePath ?: return@LaunchedEffect
        if (player.currentMediaItem == null) {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(app.storage.resolve(path))))
            player.prepare()
        }
    }
    LaunchedEffect(Unit) { while (true) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0); delay(200) } }
    LaunchedEffect(transcript, notes) {
        val cur = rec ?: return@LaunchedEffect
        if (transcript != cur.transcript || notes != cur.notes) { delay(800); app.db.recordings().update(cur.copy(transcript = transcript, notes = notes)) }
    }

    Scaffold(topBar = {
        BlTopBar(r?.title ?: "Recording", subtitle = r?.let { Time.dateTime(it.createdAt) }, onBack = { shell.back() }, actions = {
            if (r != null) {
                IconButton(onClick = { dialog = "rename" }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
                IconButton(onClick = { Files.share(ctx, listOf(app.storage.resolve(r.filePath)), r.title) }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
            }
        })
    }) { pad ->
        if (r == null) { Box(Modifier.padding(pad)) { EmptyState(Icons.Default.GraphicEq, "Recording not found", "It may have been deleted.") }; return@Scaffold }
        val file = app.storage.resolve(r.filePath)
        val markers = r.markers.lines().mapNotNull { l -> l.split('|', limit = 2).takeIf { it.size == 2 }?.let { (it[0].toLongOrNull() ?: 0L) to it[1] } }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            BlCard(Modifier.fillMaxWidth()) {
                Text("${r.format.ifBlank { file.extension.uppercase() }} · ${Files.formatSize(file.length())}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Slider(value = if (duration > 0) position.toFloat() / duration else 0f, onValueChange = { player.seekTo((it * duration).toLong()) })
                Row {
                    Text(Time.duration(position), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    Text(Time.duration(duration.takeIf { it > 0 } ?: r.durationMs), style = MaterialTheme.typography.labelMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Back 10s", Modifier.size(32.dp)) }
                    Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable {
                        if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
                    }, contentAlignment = Alignment.Center) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { player.seekTo(player.currentPosition + 30_000) }) { Icon(Icons.Default.Forward30, "Forward 30s", Modifier.size(32.dp)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { sp ->
                        FilterChip(speed == sp, { speed = sp; player.setPlaybackSpeed(sp) }, label = { Text("${sp}×".replace(".0×", "×")) })
                    }
                }
            }

            SectionHeader("Markers (${markers.size})", action = "Add at ${Time.duration(position)}") {
                val newMarkers = (markers + (position to "Marker ${markers.size + 1}")).sortedBy { it.first }
                app.io { app.db.recordings().update(r.copy(markers = newMarkers.joinToString("\n") { "${it.first}|${it.second}" })) }
            }
            if (markers.isEmpty()) Text("Tap “Add marker” while recording or here during playback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            markers.forEach { (ms, label) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { player.seekTo(ms); player.play() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Flag, null, tint = LocalBlColors.current.warning, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(Time.duration(ms), fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Text(label, Modifier.weight(1f))
                }
            }

            SectionHeader("Transcript")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(enabled = transcribing == null, onClick = {
                    transcribing = "Transcribing… (plays through the audio on-device)"
                    app.io {
                        val sb = StringBuilder()
                        var err: String? = null
                        val t = LiveTranscriber(ctx, object : LiveTranscriber.Listener {
                            override fun onPartial(text: String) {}
                            override fun onFinal(text: String) { synchronized(sb) { sb.append(text.trim()).append(' ') } }
                            override fun onError(message: String) { err = message }
                        })
                        t.start(blockingFeed = true)
                        runCatching { PcmDecoder.decode(file) { buf, n, ch, rate -> if (t.active) t.feed(buf, n, ch, rate) } }
                        t.stop()
                        delay(5000)
                        withContext(Dispatchers.Main) {
                            val text = synchronized(sb) { sb.toString().trim() }
                            if (text.isNotBlank()) transcript = (if (transcript.isBlank()) "" else transcript + "\n\n") + text
                            transcribing = null
                            shell.toast(err ?: if (text.isBlank()) "No speech recognized" else "Transcript added")
                        }
                    }
                }) { Icon(Icons.Default.Subtitles, null); Spacer(Modifier.width(6.dp)); Text("Transcribe (beta)") }
                OutlinedButton(enabled = transcript.isNotBlank(), onClick = {
                    app.io {
                        val profile = Profile.from(r.profile)
                        val title = r.title + " — transcript"
                        val html = "<h1>${ContentStore.escape(r.title)}</h1><p><i>${Time.dateTime(r.createdAt)} · ${Time.duration(r.durationMs)}</i></p>" +
                            (if (notes.isNotBlank()) "<h2>Notes</h2>" + ContentStore.textToHtml(notes) else "") +
                            (if (markers.isNotEmpty()) "<h2>Markers</h2><ul>" + markers.joinToString("") { "<li><b>${Time.duration(it.first)}</b> ${ContentStore.escape(it.second)}</li>" } + "</ul>" else "") +
                            "<h2>Transcript</h2>" + ContentStore.textToHtml(transcript)
                        val rel = app.content.writeDocument(profile, title, "Transcripts", "", html)
                        val text = ContentStore.plainText(html)
                        val docId = app.db.documents().insert(DocumentEntity(profile = profile.name, title = title, folder = "Transcripts", filePath = rel, preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text)))
                        withContext(Dispatchers.Main) { shell.go("doc/$docId") }
                    }
                }) { Text("Save as document") }
            }
            transcribing?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
            OutlinedTextField(transcript, { transcript = it }, placeholder = { Text("No transcript yet") }, minLines = 4, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            SectionHeader("Notes")
            OutlinedTextField(notes, { notes = it }, placeholder = { Text("Meeting notes, action items, context…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 16.dp)) {
                TextButton(onClick = { Files.open(ctx, file) }) { Text("Open with…") }
                Text("BlackLab/${r.filePath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Spacer(Modifier.height(40.dp))
        }
    }
    val cur = rec ?: return
    when (dialog) {
        "rename" -> TextInputDialog("Rename recording", cur.title, "Title", onDismiss = { dialog = null }) { t -> app.io { app.db.recordings().update(cur.copy(title = t)) } }
        "delete" -> ConfirmDialog("Delete recording?", "The audio file and its transcript will be permanently deleted.", onDismiss = { dialog = null }) {
            app.io {
                val f = app.storage.resolve(cur.filePath)
                java.io.File(f.parentFile, f.nameWithoutExtension + ".txt").delete()
                f.delete(); app.db.recordings().delete(cur)
            }
            shell.back()
        }
    }
}
