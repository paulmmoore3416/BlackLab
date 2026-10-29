package com.blacklab.app.ui.dictation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Geo
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Time
import com.blacklab.app.dictation.DictState
import com.blacklab.app.dictation.DictationMeta
import com.blacklab.app.dictation.DictationService
import com.blacklab.app.dictation.DictationStore
import com.blacklab.app.dictation.VoiceCommands
import com.blacklab.app.recorder.RecConfig
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.library.openMap
import com.blacklab.app.ui.recorder.Waveform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

val DictViolet = Color(0xFF8B6CFF)

@Composable
fun DictationScreen() {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val st by DictationService.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micOk = granted(Manifest.permission.RECORD_AUDIO)
        if (micOk) pendingAction?.let { DictationService.send(ctx, it) }
        pendingAction = null
    }
    fun launch(action: String) {
        val need = buildList {
            if (!micOk) add(Manifest.permission.RECORD_AUDIO)
            if (s.dictGeotag && !Geo.hasPermission(ctx)) addAll(Geo.permissions)
            if (!granted(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isEmpty()) DictationService.send(ctx, action) else { pendingAction = action; perms.launch(need.toTypedArray()) }
    }

    val root = app.storage.dir(s.activeProfile, Folder.DICTATION)
    val sessions by produceState(emptyList<Pair<File, DictationMeta>>(), root, refresh, st.savedFolder) {
        value = withContext(Dispatchers.IO) { DictationStore.list(root) }
    }
    LaunchedEffect(st.savedFolder) {
        val saved = st.savedFolder ?: return@LaunchedEffect
        refresh++
        shell.toast("Dictation saved", "Open") { shell.go("dictation/session?path=" + Uri.encode(saved)) }
        if (!st.active) DictationService.state.value = DictState()
    }

    Scaffold(topBar = {
        BlTopBar("Dictation", subtitle = "BlackLab/${app.settings.profileName(s.activeProfile)}/Dictation", actions = {
            IconButton(onClick = { settingsOpen = true }) { Icon(Icons.Default.Tune, "Dictation settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { LivePanel(st, s.dictVoiceCommands, onStart = { launch(DictationService.ACTION_START) }, onStandby = { launch(DictationService.ACTION_STANDBY) }) }
            item {
                SectionHeader("Sessions (${sessions.size})")
                if (sessions.size > 4) OutlinedTextField(query, { query = it }, placeholder = { Text("Search transcripts & places") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
            }
            val shown = sessions.filter { (_, m) ->
                query.isBlank() || m.title.contains(query, true) || (m.geo?.address ?: "").contains(query, true) || DictationStore.plainText(m, false).contains(query, true)
            }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Default.RecordVoiceOver, "No dictations yet", "Tap record and speak. Each session is saved to its own dated folder with the audio, transcript and location.")
            }
            items(shown, key = { it.first.path }) { (dir, m) -> SessionRow(dir, m) { refresh++ } }
        }
    }
    if (settingsOpen) DictationSettingsSheet { settingsOpen = false }
}

@Composable
private fun LivePanel(st: DictState, commands: Boolean, onStart: () -> Unit, onStandby: () -> Unit) {
    val ctx = LocalContext.current
    val recording = st.status == DictState.Status.RECORDING
    val color = when (st.status) { DictState.Status.RECORDING -> Color(0xFFE53935); DictState.Status.PAUSED -> Color(0xFFFFB74D); DictState.Status.STANDBY -> DictViolet; else -> DictViolet }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.12f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    BlCard(Modifier.fillMaxWidth().padding(top = 4.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(when (st.status) {
                DictState.Status.IDLE -> "Ready to dictate"
                DictState.Status.STANDBY -> "Standby — say “start recording”"
                DictState.Status.RECORDING -> "Recording & transcribing"
                DictState.Status.PAUSED -> "Paused — say “resume recording”"
            }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (st.active) Text(Time.duration(st.elapsedMs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
        st.geo?.let { g ->
            Row(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { openMap(ctx, g.lat, g.lon) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(g.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (st.active) {
            Spacer(Modifier.height(10.dp))
            Waveform(st.levels, color, Modifier.fillMaxWidth().height(64.dp))
        }
        // live transcript
        Spacer(Modifier.height(10.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp).heightIn(min = 120.dp, max = 320.dp).verticalScroll(rememberScrollState(), reverseScrolling = true)) {
                val paras = DictationStore.paragraphs(DictationMeta("", 0, segments = st.segments))
                if (paras.isEmpty() && st.partial.isBlank()) Text(
                    if (st.active) "Listening…" else "Your words appear here as you speak. Works offline with the on-device speech model.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                paras.forEach { (ms, t) ->
                    Text(Time.duration(ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    Text(t, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp)
                }
                if (st.partial.isNotBlank() && recording) Text(st.partial, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        st.sttError?.let { Text(it, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        st.error?.let { Text(it, color = Color(0xFFEF5350), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        val showCmd = st.lastCommand != null && System.currentTimeMillis() - st.commandAt < 4000
        AnimatedVisibility(showCmd, enter = fadeIn(), exit = fadeOut()) {
            Text("✓ Heard “${st.lastCommand}”", color = DictViolet, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        }
        // controls
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (st.active) Round(Icons.Default.Close, "Discard", MaterialTheme.colorScheme.surfaceContainerHighest) { DictationService.send(ctx, DictationService.ACTION_CANCEL) }
            else Round(Icons.Default.Hearing, "Hands-free standby", DictViolet.copy(alpha = 0.22f), onStandby)
            Box(
                Modifier.size(88.dp).scale(if (recording) pulse.value else 1f).clip(CircleShape).background(color.copy(alpha = 0.2f)).clickable {
                    when (st.status) {
                        DictState.Status.IDLE -> onStart()
                        DictState.Status.RECORDING -> DictationService.send(ctx, DictationService.ACTION_PAUSE)
                        else -> DictationService.send(ctx, DictationService.ACTION_RESUME)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(66.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                    Icon(when (st.status) { DictState.Status.RECORDING -> Icons.Default.Pause; DictState.Status.IDLE -> Icons.Default.Mic; else -> Icons.Default.FiberManualRecord },
                        "Record", tint = Color.White, modifier = Modifier.size(32.dp))
                }
            }
            if (st.active) Round(Icons.Default.Check, "Stop & save", MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) { DictationService.send(ctx, DictationService.ACTION_STOP) }
            else Spacer(Modifier.size(56.dp))
        }
        if (st.active) Row(Modifier.fillMaxWidth().padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { DictationService.send(ctx, DictationService.ACTION_MARK) }, label = { Text("Add marker${if (st.markers.isNotEmpty()) " (${st.markers.size})" else ""}") },
                leadingIcon = { Icon(Icons.Default.Flag, null, Modifier.size(16.dp)) })
        }
        if (commands) {
            Text("Voice commands: " + VoiceCommands.help.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        if (!st.active) Text("Hands-free: tap the ear to listen without recording until you say “start recording”.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Round(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, desc) }
}

@Composable
private fun SessionRow(dir: File, m: DictationMeta, onChanged: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val rel = app.storage.relative(dir)
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    fun open() = shell.go("dictation/session?path=" + Uri.encode(rel))
    if (menu) ActionSheet(
        title = m.title, subtitle = "${Time.dateTime(m.startedAt)} · ${Time.duration(m.durationMs)} · ${m.wordCount} words", onDismiss = { menu = false },
        quick = listOf(
            SheetAction("Open", Icons.Default.Article) { open() },
            SheetAction("Share text", Icons.Default.Share) { Files.share(ctx, listOf(DictationStore.transcriptFile(dir)), m.title) },
            SheetAction("Share audio", Icons.Default.AudioFile) { Files.share(ctx, listOf(DictationStore.audioFile(dir, m)), m.title) },
            SheetAction("Delete", Icons.Default.Delete, destructive = true) { confirm = true },
        ),
        actions = buildList {
            add(SheetAction("Copy transcript", Icons.Default.ContentCopy) {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(m.title, DictationStore.plainText(m, false)))
                shell.toast("Transcript copied")
            })
            add(SheetAction("Save as document", Icons.Default.NoteAdd, hint = "Edit, then export to Word or PDF") { saveAsDocument(app, shell, m) })
            m.geo?.let { g -> add(SheetAction("Show on map", Icons.Default.Map, hint = g.label) { openMap(ctx, g.lat, g.lon, m.title) }) }
            add(SheetAction("Open transcript file", Icons.Default.Description, hint = "BlackLab/$rel") { Files.open(ctx, DictationStore.transcriptFile(dir)) })
        },
    )
    if (confirm) ConfirmDialog("Delete dictation?", "“${m.title}” — audio, transcript and folder will be permanently deleted.", onDismiss = { confirm = false }) {
        dir.deleteRecursively(); onChanged()
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).pressable(onClick = { open() }, onLongClick = { menu = true })) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(DictViolet.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.RecordVoiceOver, null, tint = DictViolet)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Time.dateTime(m.startedAt)} · ${Time.duration(m.durationMs)} · ${m.wordCount} words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                m.geo?.let { Text("📍 ${it.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DictationStore.paragraphs(m).firstOrNull()?.let { Text(it.second, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

fun saveAsDocument(app: com.blacklab.app.AppContainer, shell: com.blacklab.app.ui.components.Shell, m: DictationMeta) {
    app.io {
        val profile = app.settings.current.activeProfile
        val esc = com.blacklab.app.data.ContentStore::escape
        val header = buildString {
            append("<h1>${esc(m.title)}</h1><p><i>${esc(Time.dateTime(m.startedAt))} · ${Time.duration(m.durationMs)}</i>")
            m.geo?.let { append("<br>📍 ${esc(it.label)} (${it.coords})") }
            append("</p>")
        }
        val body = DictationStore.paragraphs(m).joinToString("") { (ms, t) ->
            "<p>" + (if (ms >= 0) "<b>[${Time.duration(ms)}]</b> " else "") + esc(t).replace("\n", "<br>") + "</p>"
        }
        val rel = app.content.writeDocument(profile, m.title, "Dictation", "", header + body)
        val text = com.blacklab.app.data.ContentStore.plainText(header + body)
        val id = app.db.documents().insert(com.blacklab.app.data.DocumentEntity(profile = profile.name, title = m.title, folder = "Dictation", filePath = rel,
            preview = com.blacklab.app.data.ContentStore.preview(text), wordCount = com.blacklab.app.data.ContentStore.wordCount(text)))
        withContext(Dispatchers.Main) { shell.go("doc/$id") }
    }
}

@Composable
private fun DictationSettingsSheet(onDismiss: () -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var vocab by remember { mutableStateOf(s.dictVocabulary) }
    ModalBottomSheet(onDismissRequest = { app.settings.update { putString(Keys.DICT_VOCAB, vocab) }; onDismiss() }, sheetState = sheet) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Dictation settings", style = MaterialTheme.typography.titleLarge)
            LabeledSwitchRow("Voice commands", "Say “pause recording”, “resume recording”, “stop recording”, “new paragraph”", s.dictVoiceCommands) { v -> app.settings.update { putBoolean(Keys.DICT_COMMANDS, v) } }
            LabeledSwitchRow("Hands-free after saving", "Return to standby and wait for “start recording” after each session", s.dictHandsFree) { v -> app.settings.update { putBoolean(Keys.DICT_HANDS_FREE, v) } }
            LabeledSwitchRow("Save location", "GPS + street address in every session", s.dictGeotag) { v -> app.settings.update { putBoolean(Keys.DICT_GEOTAG, v) } }
            LabeledSwitchRow("Timestamps in transcript", "[mm:ss] at the start of each paragraph", s.dictTimestamps) { v -> app.settings.update { putBoolean(Keys.DICT_TIMESTAMPS, v) } }
            LabeledSwitchRow("On-device recognition", "Private & offline. Turn off to use the system speech service.", s.dictOnDevice) { v -> app.settings.update { putBoolean(Keys.DICT_ON_DEVICE, v) } }
            Text("Accuracy", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            DropdownField("Microphone", listOf(
                "MIC" to "Standard — best for one speaker", "CAMCORDER" to "Directional — across a table",
                "VOICE_RECOGNITION" to "Voice recognition — tuned for speech-to-text", "VOICE_COMMUNICATION" to "Conference room — echo & noise cancel",
            ), s.dictSource, { v -> app.settings.update { putString(Keys.DICT_SOURCE, v) } })
            val (nsOk, agcOk, _) = remember { RecConfig.effectsAvailable() }
            LabeledSwitchRow("Noise suppression", if (nsOk) "Filters HVAC hum, fans, traffic" else "Not available", s.dictNoiseSuppress, enabled = nsOk) { v -> app.settings.update { putBoolean(Keys.DICT_NS, v) } }
            LabeledSwitchRow("Automatic gain", if (agcOk) "Levels near and far voices (meetings)" else "Not available", s.dictAgc, enabled = agcOk) { v -> app.settings.update { putBoolean(Keys.DICT_AGC, v) } }
            OutlinedTextField(vocab, { vocab = it }, label = { Text("Custom vocabulary") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Names, acronyms and jargon, comma-separated — e.g. CMMS, HVAC, chiller, BACnet") })
            Text("Tip: for best accuracy install the offline speech pack — Settings › System › Languages › On-device speech.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(false, {}, label = { Text("Audio: AAC 48 kHz mono · 128 kbps") }, enabled = false) }
        }
    }
}
