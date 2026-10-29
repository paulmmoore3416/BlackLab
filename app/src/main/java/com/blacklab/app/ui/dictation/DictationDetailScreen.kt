package com.blacklab.app.ui.dictation

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.dictation.DictationMeta
import com.blacklab.app.dictation.DictationStore
import com.blacklab.app.dictation.Segment
import com.blacklab.app.recorder.LiveTranscriber
import com.blacklab.app.recorder.PcmDecoder
import com.blacklab.app.recorder.SttOptions
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.library.openMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun DictationDetailScreen(relPath: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val dir = remember(relPath) { app.storage.resolve(relPath) }
    var meta by remember { mutableStateOf<DictationMeta?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(dir) { meta = withContext(Dispatchers.IO) { DictationStore.read(dir) }; loaded = true }
    val m = meta
    fun persist(n: DictationMeta) { meta = n; app.io { DictationStore.write(dir, n, app.settings.current.dictTimestamps) } }

    val player = remember { ExoPlayer.Builder(ctx).build() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    DisposableEffect(Unit) {
        val l = object : Player.Listener { override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying } }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(m?.audio) {
        val mm = m ?: return@LaunchedEffect
        if (player.currentMediaItem == null) { player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(DictationStore.audioFile(dir, mm)))); player.prepare() }
    }
    LaunchedEffect(Unit) { while (true) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0); delay(200) } }

    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    var retranscribe by remember { mutableStateOf<Float?>(null) }
    var showTimes by remember { mutableStateOf(s.dictTimestamps) }

    Scaffold(topBar = {
        BlTopBar(m?.title ?: "Dictation", subtitle = m?.let { "${Time.dateTime(it.startedAt)} · ${Time.duration(it.durationMs)} · ${it.wordCount} words" }, onBack = { shell.back() }, actions = {
            if (m != null) {
                IconButton(onClick = { dialog = "rename" }) { Icon(Icons.Default.DriveFileRenameOutline, "Rename") }
                IconButton(onClick = { Files.share(ctx, listOf(DictationStore.transcriptFile(dir), DictationStore.audioFile(dir, m)), m.title) }) { Icon(Icons.Default.Share, "Share") }
                IconButton(onClick = { dialog = "delete" }) { Icon(Icons.Default.Delete, "Delete") }
            }
        })
    }) { pad ->
        if (m == null) { if (loaded) Box(Modifier.padding(pad)) { EmptyState(Icons.Default.RecordVoiceOver, "Session not found", "It may have been deleted or moved.") }; return@Scaffold }
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // ----- where & when
            BlCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Event, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("${Time.longDate(Time.toDate(m.startedAt))} · ${Time.time(m.startedAt)} – ${Time.time(m.endedAt)}", style = MaterialTheme.typography.bodyMedium)
                }
                m.geo?.let { g ->
                    Row(Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { openMap(ctx, g.lat, g.lon, m.title) }, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(g.address ?: "Location", style = MaterialTheme.typography.bodyMedium)
                            Text(g.coords + (g.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.Map, "Map", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } ?: Text("No location saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                Text("BlackLab/${relPath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            // ----- player
            BlCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Slider(value = if (duration > 0) position.toFloat() / duration else 0f, onValueChange = { player.seekTo((it * duration).toLong()) })
                Row { Text(Time.duration(position), style = MaterialTheme.typography.labelMedium); Spacer(Modifier.weight(1f)); Text(Time.duration(duration.takeIf { it > 0 } ?: m.durationMs), style = MaterialTheme.typography.labelMedium) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, "Back 10s", Modifier.size(30.dp)) }
                    Box(Modifier.size(64.dp).clip(CircleShape).background(DictViolet).clickable {
                        if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() }
                    }, contentAlignment = Alignment.Center) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(36.dp)) }
                    IconButton(onClick = { player.seekTo(player.currentPosition + 30_000) }) { Icon(Icons.Default.Forward30, "Forward 30s", Modifier.size(30.dp)) }
                    TextButton(onClick = { speed = listOf(1f, 1.25f, 1.5f, 2f, 0.75f).let { l -> l[(l.indexOf(speed) + 1) % l.size] }; player.playbackParameters = PlaybackParameters(speed) }) {
                        Text(String.format(Locale.US, "%.2g×", speed))
                    }
                }
                if (m.markers.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    m.markers.forEach { (ms, l) -> AssistChip(onClick = { player.seekTo(ms); player.play() }, label = { Text("🚩 ${Time.duration(ms)} $l") }) }
                }
            }
            // ----- actions
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { if (editing) { persist(m.copy(editedText = draft.trim())); editing = false } else { draft = DictationStore.plainText(m, false); editing = true } },
                    label = { Text(if (editing) "Done editing" else "Edit transcript") }, leadingIcon = { Icon(if (editing) Icons.Default.Check else Icons.Default.Edit, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { saveAsDocument(app, shell, m) }, label = { Text("Save as document") }, leadingIcon = { Icon(Icons.Default.NoteAdd, null, Modifier.size(16.dp)) })
                AssistChip(onClick = {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(m.title, DictationStore.plainText(m, false)))
                    shell.toast("Transcript copied")
                }, label = { Text("Copy") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { dialog = "retranscribe" }, enabled = retranscribe == null, label = { Text("Re-transcribe") }, leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(16.dp)) })
                AssistChip(onClick = { showTimes = !showTimes }, label = { Text(if (showTimes) "Hide times" else "Show times") }, leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(16.dp)) })
            }
            retranscribe?.let { p ->
                Text("Re-transcribing from the audio for accuracy…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            // ----- transcript
            Spacer(Modifier.height(10.dp))
            if (editing) {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 10, textStyle = MaterialTheme.typography.bodyLarge)
            } else {
                val paras = DictationStore.paragraphs(m)
                if (paras.isEmpty()) Text("No speech was transcribed. You can still play the audio, or try Re-transcribe.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.editedText != null) Text("Edited transcript", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                paras.forEach { (ms, t) ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = ms >= 0) { player.seekTo(ms); player.play() }.padding(6.dp)) {
                        if (showTimes && ms >= 0) Text(Time.duration(ms), style = MaterialTheme.typography.labelSmall, color = DictViolet, fontWeight = FontWeight.Bold)
                        Text(t, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    when (dialog) {
        "rename" -> TextInputDialog("Rename", m?.title ?: "", "Title", onDismiss = { dialog = null }) { t -> m?.let { persist(it.copy(title = t)) } }
        "delete" -> ConfirmDialog("Delete dictation?", "Audio, transcript and folder will be permanently deleted.", onDismiss = { dialog = null }) {
            dir.deleteRecursively(); shell.back()
        }
        "retranscribe" -> ConfirmDialog("Re-transcribe?", "Runs speech recognition again over the saved audio and replaces the current transcript. Takes about as long as the recording.",
            confirmLabel = "Re-transcribe", destructive = false, onDismiss = { dialog = null }) {
            val mm = m ?: return@ConfirmDialog
            retranscribe = 0f
            scope.launch {
                val segs = mutableListOf<Segment>()
                var fed = 0L
                val total = (mm.durationMs.coerceAtLeast(1)) * 48L
                val vocab = s.dictVocabulary.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                val t = LiveTranscriber(ctx, object : LiveTranscriber.Listener {
                    override fun onPartial(text: String) {}
                    override fun onFinal(text: String) { segs += Segment(fed * 1000 / 48_000, text.trim().replaceFirstChar { it.titlecase(Locale.getDefault()) }, para = segs.isEmpty() || segs.size % 4 == 0) }
                    override fun onError(message: String) { shell.toast(message) }
                }, SttOptions(formatting = true, vocabulary = vocab, preferOnDevice = s.dictOnDevice))
                t.start(blockingFeed = true)
                withContext(Dispatchers.IO) {
                    runCatching {
                        PcmDecoder.decode(DictationStore.audioFile(dir, mm)) { buf, n, ch, rate ->
                            if (t.active) { t.feed(buf, n, ch, rate); fed += (n / ch).toLong() * 48_000 / rate; retranscribe = (fed.toFloat() / total).coerceIn(0f, 1f) }
                        }
                    }
                }
                delay(2500)
                t.stop()
                delay(1500)
                retranscribe = null
                if (segs.isNotEmpty()) { persist(mm.copy(segments = segs.toList(), editedText = null)); shell.toast("Transcript updated · ${segs.size} segments") }
                else shell.toast("No speech recognised")
            }
        }
    }
}
