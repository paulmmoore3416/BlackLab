package com.blacklab.app.ui.audioedit

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.files.FileOps
import com.blacklab.app.media.AudioEdit
import com.blacklab.app.media.Clip
import com.blacklab.app.media.ClipPlayer
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.viewer.BusyOverlay
import com.blacklab.app.ui.viewer.openInApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val EditTabs = listOf("Edit", "Volume", "Effects", "Speed", "Mix")

private fun t(ms: Long): String {
    val s = ms / 1000.0
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%04.1f", (s / 3600).toInt(), ((s % 3600) / 60).toInt(), s % 60)
    else String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60)
}

/**
 * Multi-track-free but capable audio editor: waveform with selection & zoom, cut/copy/paste, trim,
 * fades, gain, normalize, reverse, echo, noise gate, filters, tempo & pitch, insert/append/mix files.
 */
@Composable
fun AudioEditorScreen(path: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(path) { app.storage.resolve(path) }

    var clip by remember { mutableStateOf<Clip?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val undo = remember { mutableStateListOf<Clip>() }
    val redo = remember { mutableStateListOf<Clip>() }
    var selA by remember { mutableIntStateOf(0) }
    var selB by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableIntStateOf(0) }
    var viewStart by remember { mutableIntStateOf(0) }
    var viewLen by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(EditTabs[0]) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var clipboard by remember { mutableStateOf<Clip?>(null) }
    var saveMenu by remember { mutableStateOf(false) }
    val player = remember { ClipPlayer() }
    var playing by remember { mutableStateOf(false) }
    var playhead by remember { mutableIntStateOf(-1) }
    var pendingFileOp by remember { mutableStateOf("") }

    LaunchedEffect(file) {
        val r = withContext(Dispatchers.Default) { runCatching { AudioEdit.load(file) } }
        r.onSuccess { clip = it; viewLen = it.frames }.onFailure { error = it.message ?: "Couldn't open this audio file" }
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    LaunchedEffect(playing) { while (playing) { playhead = player.position; if (!player.playing) { playing = false; playhead = -1 }; delay(33) } }

    val c = clip
    val hasSel = selB > selA
    fun a() = if (hasSel) selA else 0
    fun b() = if (hasSel) selB else (c?.frames ?: 0)
    val dirty = undo.isNotEmpty()

    /** Applies an edit off the main thread with undo, keeping the view sensible. */
    fun apply(label: String, keepSel: Boolean = true, op: (Clip) -> Clip) {
        val cur = clip ?: return
        player.stop(); playing = false
        busy = "$label…"
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { op(cur) } }
            busy = null
            r.onSuccess { n ->
                undo += cur; redo.clear()
                // keep the undo stack within ~120 M samples
                var total = undo.sumOf { it.pcm.size.toLong() }
                while (undo.size > 1 && total > 120_000_000L) { total -= undo.removeAt(0).pcm.size }
                clip = n
                if (!keepSel || selB > n.frames) { selA = 0; selB = 0 }
                cursor = cursor.coerceIn(0, n.frames)
                if (viewStart + viewLen > n.frames || viewLen == cur.frames) { viewStart = 0; viewLen = n.frames }
            }.onFailure { shell.toast("$label failed: ${it.message}") }
        }
    }

    fun save(target: File, replace: Boolean) {
        val cur = clip ?: return
        player.stop(); playing = false
        busy = "Saving ${target.extension.uppercase()}…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { FileOps.writeAtomically(target) { tmp -> AudioEdit.save(cur, tmp, wav = target.extension.equals("wav", true)) }; app.storage.scan(target); target } }
            busy = null
            r.onSuccess { f ->
                undo.clear(); redo.clear()
                shell.toast(if (replace) "Saved over ${f.name}" else "Saved ${f.name}", "Play") { openInApp(shell, app, f) }
            }.onFailure { shell.toast("Couldn't save: ${it.message}") }
        }
    }
    fun outDir(): File = if (app.storage.contains(file)) file.parentFile!! else app.storage.dir(app.settings.current.activeProfile, Folder.RECORDINGS)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Reading audio…"
        scope.launch {
            val other = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(app.storage.openedDir, "mix_" + Files.sanitize(Files.displayName(ctx, uri)))
                    Files.copyFromUri(ctx, uri, tmp); AudioEdit.load(tmp).also { tmp.delete() }
                }
            }
            busy = null
            other.onSuccess { o ->
                when (pendingFileOp) {
                    "append" -> apply("Appending", false) { AudioEdit.insert(it, it.frames, o) }
                    "insert" -> apply("Inserting", false) { AudioEdit.insert(it, cursor, o) }
                    "mix" -> { dialog = "mixgain"; clipboard = o }
                }
            }.onFailure { shell.toast("Couldn't read that file: ${it.message}") }
        }
    }

    BackHandler(enabled = dirty) { dialog = "exit" }

    Scaffold(topBar = {
        BlTopBar(file.nameWithoutExtension, subtitle = c?.let { "${t(it.durationMs)} · ${it.rate / 1000.0} kHz · ${if (it.channels == 1) "mono" else "stereo"}" + if (dirty) " · edited" else "" } ?: "Audio editor",
            onBack = { if (dirty) dialog = "exit" else shell.back() }, actions = {
                IconButton(enabled = undo.isNotEmpty(), onClick = { clip?.let { redo += it }; clip = undo.removeAt(undo.lastIndex); viewStart = 0; viewLen = clip!!.frames; selA = 0; selB = 0 }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                IconButton(enabled = redo.isNotEmpty(), onClick = { clip?.let { undo += it }; clip = redo.removeAt(redo.lastIndex); viewStart = 0; viewLen = clip!!.frames; selA = 0; selB = 0 }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                Box {
                    TextButton(enabled = c != null, onClick = { saveMenu = true }) { Text("Save", fontWeight = FontWeight.Bold) }
                    DropdownMenu(saveMenu, { saveMenu = false }) {
                        DropdownMenuItem(text = { Text("Save as M4A (AAC)") }, leadingIcon = { Icon(Icons.Default.AudioFile, null) }, onClick = { saveMenu = false; save(Files.unique(outDir(), file.nameWithoutExtension.removeSuffix("_edit") + "_edit", "m4a"), false) })
                        DropdownMenuItem(text = { Text("Save as WAV (lossless)") }, leadingIcon = { Icon(Icons.Default.GraphicEq, null) }, onClick = { saveMenu = false; save(Files.unique(outDir(), file.nameWithoutExtension.removeSuffix("_edit") + "_edit", "wav"), false) })
                        if (app.storage.contains(file) && file.extension.lowercase() in setOf("m4a", "wav")) DropdownMenuItem(text = { Text("Replace original") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { saveMenu = false; dialog = "replace" })
                        DropdownMenuItem(text = { Text("Export selection only") }, enabled = hasSel, leadingIcon = { Icon(Icons.Default.ContentCut, null) }, onClick = {
                            saveMenu = false
                            val part = AudioEdit.slice(clip!!, selA, selB)
                            busy = "Saving selection…"
                            scope.launch {
                                val out = Files.unique(outDir(), file.nameWithoutExtension + "_clip", "m4a")
                                val r = withContext(Dispatchers.IO) { runCatching { AudioEdit.save(part, out); app.storage.scan(out) } }
                                busy = null
                                r.onSuccess { shell.toast("Saved ${out.name}") }.onFailure { shell.toast("Couldn't save") }
                            }
                        })
                    }
                }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (c == null) {
                if (error != null) Text(error!!, Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.error)
                else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(10.dp)); Text("Decoding audio…") }
                return@Box
            }
            Column(Modifier.fillMaxSize()) {
                // ---------------- waveform
                val vs = viewStart.coerceIn(0, max(0, c.frames - 1)); val vl = viewLen.coerceIn(1, c.frames - vs)
                val wave = MaterialTheme.colorScheme.primary
                val selColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.28f)
                val grid = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                    .pointerInput(c, vs, vl) {
                        detectTapGestures(onTap = { o -> cursor = (vs + o.x / size.width * vl).toInt().coerceIn(0, c.frames); selA = 0; selB = 0 },
                            onDoubleTap = { selA = vs; selB = vs + vl })
                    }
                    .pointerInput(c, vs, vl) {
                        var start = 0
                        detectDragGestures(
                            onDragStart = { o -> start = (vs + o.x / size.width * vl).toInt().coerceIn(0, c.frames); selA = start; selB = start },
                            onDrag = { ch, _ -> val f = (vs + ch.position.x / size.width * vl).toInt().coerceIn(0, c.frames); selA = min(start, f); selB = max(start, f) },
                            onDragEnd = { cursor = selA },
                        )
                    }) {
                    val w = size.width; val h = size.height; val mid = h / 2
                    // second ticks
                    val msView = vl * 1000L / c.rate
                    val step = listOf(100L, 250L, 500L, 1000L, 2000L, 5000L, 10_000L, 30_000L, 60_000L, 300_000L).first { msView / it <= 12 }
                    var tick = (c.frameToMs(vs) / step + 1) * step
                    while (c.msToFrame(tick) < vs + vl) { val x = (c.msToFrame(tick) - vs).toFloat() / vl * w; drawLine(grid, Offset(x, 0f), Offset(x, h), 1f); tick += step }
                    drawLine(grid, Offset(0f, mid), Offset(w, mid), 1f)
                    // selection
                    if (selB > selA) {
                        val x0 = ((selA - vs).toFloat() / vl * w).coerceIn(0f, w); val x1 = ((selB - vs).toFloat() / vl * w).coerceIn(0f, w)
                        drawRect(selColor, Offset(x0, 0f), Size(x1 - x0, h))
                    }
                    // peaks
                    val peaks = c.peaks
                    val cols = w.toInt()
                    for (x in 0 until cols) {
                        val f0 = vs + (x.toLong() * vl / cols).toInt(); val f1 = vs + ((x + 1).toLong() * vl / cols).toInt()
                        var m = 0f
                        if (f1 - f0 >= Clip.PEAK_BLOCK) { for (bIdx in f0 / Clip.PEAK_BLOCK..min(peaks.size - 1, f1 / Clip.PEAK_BLOCK)) m = max(m, peaks[bIdx]) }
                        else for (f in f0 until max(f0 + 1, f1)) { if (f < c.frames) for (ch in 0 until c.channels) m = max(m, kotlin.math.abs(c.pcm[f * c.channels + ch].toInt()) / 32768f) }
                        val hh = max(1f, m * mid * 0.95f)
                        drawLine(wave, Offset(x.toFloat(), mid - hh), Offset(x.toFloat(), mid + hh), 1.2f)
                    }
                    // cursor & playhead
                    val cx = (cursor - vs).toFloat() / vl * w
                    if (cx in 0f..w) drawLine(Color(0xFFFFB74D), Offset(cx, 0f), Offset(cx, h), 2f)
                    if (playhead >= 0) { val px = (playhead - vs).toFloat() / vl * w; if (px in 0f..w) drawLine(Color.White, Offset(px, 0f), Offset(px, h), 2.5f) }
                }
                // view scrollbar
                if (vl < c.frames) Slider(vs.toFloat(), { viewStart = it.toInt() }, valueRange = 0f..(c.frames - vl).toFloat().coerceAtLeast(1f), modifier = Modifier.padding(horizontal = 8.dp))
                // ---------------- info & zoom
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (hasSel) "Selection ${t(c.frameToMs(selA))} – ${t(c.frameToMs(selB))}  (${t(c.frameToMs(selB - selA))})" else "Cursor ${t(c.frameToMs(cursor))} · drag to select",
                            style = MaterialTheme.typography.labelMedium)
                        Text("Peak ${String.format(Locale.US, "%.1f", AudioEdit.peakDb(c, a(), b()))} dBFS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { val center = vs + vl / 2; val nl = max(c.rate / 10, vl / 2); viewLen = nl; viewStart = (center - nl / 2).coerceIn(0, c.frames - nl) }) { Icon(Icons.Default.ZoomIn, "Zoom in") }
                    IconButton(onClick = { val center = vs + vl / 2; val nl = min(c.frames, vl * 2); viewLen = nl; viewStart = (center - nl / 2).coerceIn(0, c.frames - nl) }) { Icon(Icons.Default.ZoomOut, "Zoom out") }
                    IconButton(enabled = hasSel, onClick = { viewStart = selA; viewLen = max(c.rate / 20, selB - selA) }) { Icon(Icons.Default.CenterFocusStrong, "Zoom to selection") }
                    IconButton(onClick = { viewStart = 0; viewLen = c.frames }) { Icon(Icons.Default.FitScreen, "Show all") }
                }
                // ---------------- transport
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { cursor = 0; selA = 0; selB = 0 }) { Icon(Icons.Default.SkipPrevious, "To start") }
                    FilledIconButton(onClick = {
                        if (playing) { player.stop(); playing = false; playhead = -1 }
                        else { player.play(c, if (hasSel) selA else cursor, if (hasSel) selB else c.frames) { }; playing = true }
                    }, Modifier.size(60.dp)) { Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, if (playing) "Stop" else "Play", Modifier.size(34.dp)) }
                    IconButton(onClick = { cursor = c.frames; selA = 0; selB = 0 }) { Icon(Icons.Default.SkipNext, "To end") }
                    TextButton(onClick = { selA = 0; selB = c.frames }) { Text("Select all") }
                }
                // ---------------- tools
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditTabs.forEach { tb -> FilterChip(tab == tb, { tab = tb }, label = { Text(tb) }) }
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    val scope2 = if (hasSel) "selection" else "whole clip"
                    when (tab) {
                        "Edit" -> {
                            ToolRow {
                                Tool(Icons.Default.Crop, "Trim to selection", hasSel) { apply("Trimming", false) { AudioEdit.trim(it, selA, selB) } }
                                Tool(Icons.Default.Delete, "Delete", hasSel) { apply("Deleting", false) { AudioEdit.delete(it, selA, selB) } }
                                Tool(Icons.Default.VolumeOff, "Silence", hasSel) { apply("Silencing") { AudioEdit.silence(it, selA, selB) } }
                            }
                            ToolRow {
                                Tool(Icons.Default.ContentCopy, "Copy", hasSel) { clipboard = AudioEdit.slice(c, selA, selB); shell.toast("Copied ${t(clipboard!!.durationMs)}") }
                                Tool(Icons.Default.ContentCut, "Cut", hasSel) { clipboard = AudioEdit.slice(c, selA, selB); apply("Cutting", false) { AudioEdit.delete(it, selA, selB) } }
                                Tool(Icons.Default.ContentPaste, "Paste at cursor", clipboard != null) { val p = clipboard!!; apply("Pasting", false) { AudioEdit.insert(it, cursor, p) } }
                            }
                            ToolRow {
                                Tool(Icons.Default.HourglassEmpty, "Insert silence", true) { dialog = "silence" }
                                Tool(Icons.Default.Repeat, "Duplicate selection", hasSel) { val p = AudioEdit.slice(c, selA, selB); apply("Duplicating", false) { AudioEdit.insert(it, selB, p) } }
                                Tool(Icons.Default.CallSplit, "Split here (save 2 files)", cursor in 1 until c.frames) {
                                    busy = "Splitting…"
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            runCatching {
                                                listOf(AudioEdit.slice(c, 0, cursor), AudioEdit.slice(c, cursor, c.frames)).mapIndexed { i, part ->
                                                    Files.unique(outDir(), file.nameWithoutExtension + " part ${i + 1}", "m4a").also { AudioEdit.save(part, it); app.storage.scan(it) }
                                                }
                                            }
                                        }
                                        busy = null
                                        r.onSuccess { shell.toast("Saved ${it.joinToString { f -> f.name }}") }.onFailure { shell.toast("Split failed") }
                                    }
                                }
                            }
                        }
                        "Volume" -> {
                            Text("Applies to the $scope2.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ToolRow {
                                Tool(Icons.Default.TrendingUp, "Fade in", true) { val (x, y) = fadeRange(c, hasSel, selA, selB, true); apply("Fading in") { AudioEdit.fadeIn(it, x, y) } }
                                Tool(Icons.Default.TrendingDown, "Fade out", true) { val (x, y) = fadeRange(c, hasSel, selA, selB, false); apply("Fading out") { AudioEdit.fadeOut(it, x, y) } }
                                Tool(Icons.Default.Tune, "Normalize (−1 dB)", true) { apply("Normalizing") { AudioEdit.normalize(it, a(), b()) } }
                            }
                            var db by remember { mutableFloatStateOf(3f) }
                            Text("Gain ${if (db >= 0) "+" else ""}${String.format(Locale.US, "%.1f", db)} dB", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(db, { db = (it * 2).toInt() / 2f }, valueRange = -24f..24f, modifier = Modifier.weight(1f))
                                TextButton(onClick = { apply("Changing volume") { AudioEdit.gain(it, a(), b(), db) } }) { Text("Apply") }
                            }
                            ToolRow {
                                Tool(Icons.Default.SurroundSound, if (c.channels == 2) "Convert to mono" else "Convert to stereo", true) { apply("Converting", false) { if (it.channels == 2) AudioEdit.toMono(it) else AudioEdit.toStereo(it) } }
                            }
                        }
                        "Effects" -> {
                            Text("Applies to the $scope2.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ToolRow {
                                Tool(Icons.Default.SwapHoriz, "Reverse", true) { apply("Reversing") { AudioEdit.reverse(it, a(), b()) } }
                                Tool(Icons.Default.GraphicEq, "Remove rumble (high-pass 80 Hz)", true) { apply("Filtering") { AudioEdit.filter(it, a(), b(), true, 80f) } }
                                Tool(Icons.Default.BlurOn, "Soften hiss (low-pass 8 kHz)", true) { apply("Filtering") { AudioEdit.filter(it, a(), b(), false, 8000f) } }
                            }
                            var delay by remember { mutableFloatStateOf(280f) }
                            var decay by remember { mutableFloatStateOf(0.4f) }
                            Text("Echo — ${delay.toInt()} ms, ${(decay * 100).toInt()}% feedback", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Slider(delay, { delay = it }, valueRange = 40f..1000f)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(decay, { decay = it }, valueRange = 0.1f..0.8f, modifier = Modifier.weight(1f))
                                TextButton(onClick = { apply("Adding echo") { AudioEdit.echo(it, a(), b(), delay.toInt(), decay) } }) { Text("Apply") }
                            }
                            var gate by remember { mutableFloatStateOf(-45f) }
                            Text("Noise gate — mute below ${gate.toInt()} dB", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(gate, { gate = it }, valueRange = -70f..-20f, modifier = Modifier.weight(1f))
                                TextButton(onClick = { apply("Gating noise") { AudioEdit.noiseGate(it, a(), b(), gate) } }) { Text("Apply") }
                            }
                        }
                        "Speed" -> {
                            var tempo by remember { mutableFloatStateOf(1f) }
                            var semis by remember { mutableFloatStateOf(0f) }
                            Text("Applies to the whole clip. Tempo keeps the pitch; pitch keeps the tempo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tempo ${String.format(Locale.US, "%.2f", tempo)}× → ${t((c.durationMs / tempo).toLong())}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                            Slider(tempo, { tempo = (it * 20).toInt() / 20f }, valueRange = 0.5f..2f)
                            Text("Pitch ${if (semis >= 0) "+" else ""}${semis.toInt()} semitones", style = MaterialTheme.typography.labelLarge)
                            Slider(semis, { semis = it.toInt().toFloat() }, valueRange = -12f..12f, steps = 23)
                            Row {
                                TextButton(enabled = tempo != 1f || semis != 0f, onClick = {
                                    val p = Math.pow(2.0, semis / 12.0).toFloat()
                                    apply("Processing", false) { AudioEdit.speedPitch(it, tempo, p) }
                                }) { Text("Apply") }
                                TextButton(onClick = { tempo = 1f; semis = 0f }) { Text("Reset") }
                            }
                        }
                        "Mix" -> {
                            Text("Bring in another audio file (any format). It's matched to this clip's sample rate.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ToolRow {
                                Tool(Icons.Default.Add, "Append file to end", true) { pendingFileOp = "append"; picker.launch(arrayOf("audio/*", "video/*")) }
                                Tool(Icons.Default.Input, "Insert file at cursor", true) { pendingFileOp = "insert"; picker.launch(arrayOf("audio/*", "video/*")) }
                                Tool(Icons.Default.Layers, "Mix file in at cursor", true) { pendingFileOp = "mix"; picker.launch(arrayOf("audio/*", "video/*")) }
                            }
                        }
                    }
                }
            }
            busy?.let { BusyOverlay(it) }
        }
    }

    when (dialog) {
        "exit" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Discard edits?") },
            text = { Text("Your changes haven't been saved. Save first from the Save menu, or discard them.") },
            confirmButton = { TextButton(onClick = { dialog = null; shell.back() }) { Text("Discard", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep editing") } },
        )
        "replace" -> ConfirmDialog("Replace original?", "“${file.name}” will be overwritten with your edits.", confirmLabel = "Replace", onDismiss = { dialog = null }) { save(file, true) }
        "silence" -> TextInputDialog("Insert silence", "1.0", "Seconds", confirmLabel = "Insert", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, onDismiss = { dialog = null }) { v ->
            val sec = v.toDoubleOrNull()?.coerceIn(0.05, 600.0) ?: return@TextInputDialog
            apply("Inserting silence", false) { AudioEdit.insert(it, cursor, AudioEdit.silenceClip(it.rate, it.channels, (sec * 1000).toLong())) }
        }
        "mixgain" -> {
            var g by remember { mutableFloatStateOf(-6f) }
            AlertDialog(
                onDismissRequest = { dialog = null; clipboard = null },
                title = { Text("Mix level") },
                text = { Column { Text("Overlay volume ${g.toInt()} dB"); Slider(g, { g = it }, valueRange = -30f..6f) } },
                confirmButton = { TextButton(onClick = { val o = clipboard!!; dialog = null; clipboard = null; apply("Mixing", false) { AudioEdit.mix(it, cursor, o, g) } }) { Text("Mix") } },
                dismissButton = { TextButton(onClick = { dialog = null; clipboard = null }) { Text("Cancel") } },
            )
        }
    }
}

/** With no selection, fades cover the first/last 3 seconds. */
private fun fadeRange(c: Clip, hasSel: Boolean, a: Int, b: Int, start: Boolean): Pair<Int, Int> =
    if (hasSel) a to b else if (start) 0 to min(c.frames, c.rate * 3) else max(0, c.frames - c.rate * 3) to c.frames

@Composable
private fun ToolRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (enabled) 1f else 0.4f))
        .clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}
