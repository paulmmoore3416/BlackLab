package com.blacklab.app.ui.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Time
import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.recorder.RecConfig
import com.blacklab.app.recorder.RecState
import com.blacklab.app.recorder.RecorderService
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import java.util.Locale

private val RecRed = Color(0xFFE53935)

private data class Preset(val name: String, val desc: String, val apply: (RecConfig) -> RecConfig)

private val Presets = listOf(
    Preset("Voice memo", "M4A 64 kbps mono") { it.copy(format = "M4A", sampleRate = 32_000, channels = 1, bitrate = 64_000, source = "VOICE_RECOGNITION", noiseSuppress = true, agc = false) },
    Preset("Meeting", "M4A 128 kbps · noise reduction") { it.copy(format = "M4A", sampleRate = 48_000, channels = 1, bitrate = 128_000, source = "MIC", noiseSuppress = true, agc = true) },
    Preset("High quality", "M4A 256 kbps stereo") { it.copy(format = "M4A", sampleRate = 48_000, channels = 2, bitrate = 256_000, source = "CAMCORDER", noiseSuppress = false, agc = false) },
    Preset("Lossless", "WAV 48 kHz stereo · raw") { it.copy(format = "WAV", sampleRate = 48_000, channels = 2, source = "UNPROCESSED", noiseSuppress = false, agc = false, echoCancel = false) },
)

@Composable
fun RecorderScreen() {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val rec by RecorderService.state.collectAsState()
    val recordings by remember(s.activeProfile) { app.db.recordings().observe(s.activeProfile.name) }.collectAsState(emptyList())
    var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    var settingsOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    val device by RecorderService.preferredDevice.collectAsState()
    val config = RecConfig(
        deviceId = device,
        format = s.recFormat, sampleRate = s.recSampleRate, channels = s.recChannels, bitrate = s.recBitrate, source = s.recSource,
        noiseSuppress = s.recNoiseSuppress, agc = s.recAgc, echoCancel = s.recEchoCancel, gainDb = s.recGainDb, liveTranscript = s.recLiveTranscript,
    )

    // Adopt audio files that were added to the Recordings folder outside the app
    LaunchedEffect(s.activeProfile) {
        app.io {
            val dir = app.storage.dir(s.activeProfile, Folder.RECORDINGS)
            dir.listFiles()?.filter { it.isFile && it.extension.lowercase(Locale.US) in setOf("m4a", "wav", "mp3", "aac", "ogg", "opus", "3gp", "flac") }?.forEach { f ->
                val rel = app.storage.relative(f)
                if (app.db.recordings().byPath(rel) == null) {
                    val dur = runCatching { MediaMetadataRetriever().use { r -> r.setDataSource(f.path); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } }.getOrDefault(0L)
                    app.db.recordings().upsert(RecordingEntity(profile = s.activeProfile.name, filePath = rel, title = f.nameWithoutExtension, durationMs = dur, createdAt = f.lastModified(), format = f.extension.uppercase(Locale.US)))
                }
            }
            // drop rows whose files were deleted
            recordings.filter { !app.storage.resolve(it.filePath).exists() }.forEach { app.db.recordings().delete(it) }
        }
    }
    LaunchedEffect(rec.lastSavedId) {
        rec.lastSavedId?.let { id -> shell.toast("Recording saved", "Open") { shell.go("recording/$id") }; RecorderService.state.value = RecState() }
    }

    Scaffold(topBar = {
        BlTopBar("Recorder", subtitle = config.summary(), actions = {
            IconButton(onClick = { settingsOpen = true }, enabled = rec.status == RecState.Status.IDLE) { Icon(Icons.Default.Tune, "Recording settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { RecorderPanel(rec, config, micOk, onRequestMic = { perm.launch(Manifest.permission.RECORD_AUDIO) }) }
            item {
                SectionHeader("Recordings (${recordings.size})")
                if (recordings.size > 5) OutlinedTextField(query, { query = it }, placeholder = { Text("Search titles & transcripts") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            }
            val shown = recordings.filter { query.isBlank() || it.title.contains(query, true) || it.transcript.contains(query, true) || it.notes.contains(query, true) }
            if (shown.isEmpty()) item { EmptyState(Icons.Default.GraphicEq, "No recordings yet", "Recordings are saved to BlackLab/${app.settings.profileName(s.activeProfile)}/Recordings.") }
            items(shown, key = { it.id }) { r -> RecordingRow(r) { shell.go("recording/${r.id}") } }
        }
    }
    if (settingsOpen) RecorderSettingsSheet(config) { settingsOpen = false }
}

@Composable
private fun RecorderPanel(rec: RecState, config: RecConfig, micOk: Boolean, onRequestMic: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val idle = rec.status == RecState.Status.IDLE
    BlCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(Time.duration(rec.elapsedMs) + if (rec.elapsedMs < 3_600_000) "." + ((rec.elapsedMs / 100) % 10) else "",
            fontSize = 56.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.CenterHorizontally),
            color = if (rec.status == RecState.Status.RECORDING) RecRed else MaterialTheme.colorScheme.onSurface)
        Text(
            when (rec.status) { RecState.Status.IDLE -> "Ready"; RecState.Status.RECORDING -> "Recording"; RecState.Status.PAUSED -> "Paused" } +
                if (!idle) " · ${Files.formatSize(rec.bytes)} · ${rec.markers.size} markers" else " · ${Files.formatSize(app.storage.freeBytes())} free",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        Waveform(rec.levels, if (rec.status == RecState.Status.RECORDING) RecRed else MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(96.dp))
        Spacer(Modifier.height(8.dp))
        // dB meter
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("dBFS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            val norm = ((rec.db + 60f) / 60f).coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { norm }, color = if (rec.db > -3) RecRed else if (rec.db > -12) LocalBlColors.current.warning else LocalBlColors.current.success,
                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
            Text(if (idle) "—" else String.format(Locale.US, "%.0f", rec.db), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(30.dp))
        }
        rec.error?.let { Text(it, color = LocalBlColors.current.danger, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (!idle) RoundBtn(Icons.Default.Close, "Discard", MaterialTheme.colorScheme.surfaceContainerHighest) { RecorderService.send(ctx, RecorderService.ACTION_CANCEL) }
            else Spacer(Modifier.size(56.dp))
            Box(
                Modifier.size(84.dp).clip(CircleShape).background(RecRed.copy(alpha = 0.18f)).clickable {
                    if (!micOk) { onRequestMic(); return@clickable }
                    when (rec.status) {
                        RecState.Status.IDLE -> { RecorderService.pendingConfig = config; RecorderService.send(ctx, RecorderService.ACTION_START) }
                        RecState.Status.RECORDING -> RecorderService.send(ctx, RecorderService.ACTION_PAUSE)
                        RecState.Status.PAUSED -> RecorderService.send(ctx, RecorderService.ACTION_RESUME)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(RecRed), contentAlignment = Alignment.Center) {
                    when (rec.status) {
                        RecState.Status.IDLE -> Icon(Icons.Default.Mic, "Record", tint = Color.White, modifier = Modifier.size(32.dp))
                        RecState.Status.RECORDING -> Icon(Icons.Default.Pause, "Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                        RecState.Status.PAUSED -> Icon(Icons.Default.FiberManualRecord, "Resume", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
            if (!idle) RoundBtn(Icons.Default.Check, "Save", MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) { RecorderService.send(ctx, RecorderService.ACTION_STOP) }
            else Spacer(Modifier.size(56.dp))
        }
        if (!idle) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { RecorderService.mark(ctx) }, label = { Text("Add marker") }, leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(16.dp)) })
                listOf("Action item", "Decision", "Question").forEach { l ->
                    AssistChip(onClick = { RecorderService.mark(ctx, l) }, label = { Text(l, maxLines = 1) })
                }
            }
            if (rec.markers.isNotEmpty()) Text(rec.markers.takeLast(3).joinToString("  ·  ") { "🚩 ${Time.duration(it.first)} ${it.second}" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (config.liveTranscript || rec.transcript.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                    Text("LIVE TRANSCRIPT · BETA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    rec.transcriptError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalBlColors.current.warning) }
                    Text(rec.transcript + rec.partial.let { if (it.isNotBlank()) "… $it" else "" }.ifBlank { if (idle) "Transcript appears here while recording." else "Listening…" },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, desc) }
}

@Composable
fun Waveform(levels: List<Float>, color: Color, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier) {
        val bars = 80
        val w = size.width / bars
        val data = levels.takeLast(bars)
        for (i in 0 until bars) {
            val v = data.getOrNull(i - (bars - data.size)) ?: 0f
            val h = (size.height * (0.04f + kotlin.math.sqrt(v) * 0.96f)).coerceAtMost(size.height)
            drawRoundRect(if (i - (bars - data.size) >= 0) color else track, Offset(i * w + w * 0.2f, (size.height - h) / 2), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
        }
    }
}

@Composable
private fun RecordingRow(r: RecordingEntity, onClick: () -> Unit) {
    val app = rememberApp()
    val f = app.storage.resolve(r.filePath)
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Time.dateTime(r.createdAt)} · ${Time.duration(r.durationMs)} · ${Files.formatSize(f.length())}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.transcript.isNotBlank()) Text("📝 " + r.transcript.lineSequence().first().substringAfter("] "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.markers.isNotBlank()) Text("🚩${r.markers.lines().size}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RecorderSettingsSheet(config: RecConfig, onDismiss: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val devices = remember { RecConfig.inputDevices(ctx) }
    val (nsOk, agcOk, aecOk) = remember { RecConfig.effectsAvailable() }
    fun save(c: RecConfig) = app.settings.update {
        putString(Keys.REC_FORMAT, c.format); putInt(Keys.REC_RATE, c.sampleRate); putInt(Keys.REC_CHANNELS, c.channels); putInt(Keys.REC_BITRATE, c.bitrate)
        putString(Keys.REC_SOURCE, c.source); putBoolean(Keys.REC_NS, c.noiseSuppress); putBoolean(Keys.REC_AGC, c.agc); putBoolean(Keys.REC_AEC, c.echoCancel)
        putInt(Keys.REC_GAIN, c.gainDb); putBoolean(Keys.REC_TRANSCRIPT, c.liveTranscript)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recording settings", style = MaterialTheme.typography.titleLarge)
            Text("Presets", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Presets.forEach { p ->
                    Surface(onClick = { save(p.apply(config)) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(12.dp).width(130.dp)) { Text(p.name, style = MaterialTheme.typography.titleSmall); Text(p.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Text("Format", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(config.format == "M4A", { save(config.copy(format = "M4A")) }, label = { Text("M4A (AAC)") })
                FilterChip(config.format == "WAV", { save(config.copy(format = "WAV")) }, label = { Text("WAV (lossless)") })
            }
            Text("Sample rate", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(16_000, 22_050, 32_000, 44_100, 48_000, 96_000).filter { config.format == "WAV" || it <= 48_000 }.forEach { r ->
                    FilterChip(config.sampleRate == r, { save(config.copy(sampleRate = r)) }, label = { Text("${r / 1000.0}".removeSuffix(".0") + " kHz") })
                }
            }
            Text("Channels", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(config.channels == 1, { save(config.copy(channels = 1)) }, label = { Text("Mono") })
                FilterChip(config.channels == 2, { save(config.copy(channels = 2)) }, label = { Text("Stereo") })
            }
            if (config.format == "M4A") {
                Text("Bitrate", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(64, 96, 128, 192, 256, 320).forEach { k -> FilterChip(config.bitrate == k * 1000, { save(config.copy(bitrate = k * 1000)) }, label = { Text("$k kbps") }) }
                }
            }
            DropdownField("Microphone source", RecConfig.sources, config.source, { save(config.copy(source = it)) })
            DropdownField("Input device", listOf<Pair<Int?, String>>(null to "Automatic") + devices.map { it.first to it.second }, config.deviceId, { RecorderService.preferredDevice.value = it })
            LabeledSwitchRow("Noise suppression", if (nsOk) "Reduce background hum and fans" else "Not available on this device", config.noiseSuppress, enabled = nsOk) { save(config.copy(noiseSuppress = it)) }
            LabeledSwitchRow("Automatic gain", if (agcOk) "Even out quiet and loud speakers" else "Not available", config.agc, enabled = agcOk) { save(config.copy(agc = it)) }
            LabeledSwitchRow("Echo cancellation", if (aecOk) "For speakerphone / conference rooms" else "Not available", config.echoCancel, enabled = aecOk) { save(config.copy(echoCancel = it)) }
            Text("Digital gain: +${config.gainDb} dB", style = MaterialTheme.typography.labelLarge)
            Slider(config.gainDb.toFloat(), { save(config.copy(gainDb = it.toInt())) }, valueRange = 0f..24f, steps = 7)
            LabeledSwitchRow("Live transcript (beta)", "On-device speech-to-text while recording. Needs Android's offline speech pack.", config.liveTranscript) { save(config.copy(liveTranscript = it)) }
        }
    }
}
