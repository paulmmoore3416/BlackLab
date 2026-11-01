package com.blacklab.app.ui.brainstorm

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.blacklab.app.brainstorm.LiveShare
import com.blacklab.app.brainstorm.PageTemplate
import com.blacklab.app.brainstorm.PageTemplates
import com.blacklab.app.brainstorm.PlaceReminders
import com.blacklab.app.core.Files
import com.blacklab.app.core.Geo
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CommentEntity
import com.blacklab.app.data.NotebookEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.data.ShareLinkEntity
import com.blacklab.app.recorder.AudioEngine
import com.blacklab.app.recorder.LiveTranscriber
import com.blacklab.app.recorder.RecConfig
import com.blacklab.app.recorder.SttOptions
import com.blacklab.app.ui.components.DatePickerDialogBox
import com.blacklab.app.ui.components.RepeatDialog
import com.blacklab.app.ui.components.TimePickerDialogBox
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.coroutines.resume

// ====================================================================== voice note (records + transcribes at once)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderSheet(onDismiss: () -> Unit, onDone: (file: File, transcript: String, durationMs: Long) -> Unit) {
    val ctx = LocalContext.current
    var micOk by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micOk = it }
    var engine by remember { mutableStateOf<AudioEngine?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val finals = remember { mutableStateListOf<String>() }
    var partial by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    val file = remember { File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.m4a") }
    val scope = rememberCoroutineScope()

    fun start() {
        val t = LiveTranscriber(ctx, object : LiveTranscriber.Listener {
            override fun onPartial(text: String) { partial = text }
            override fun onFinal(text: String) { if (text.isNotBlank()) finals += text.trim(); partial = "" }
            override fun onError(message: String) { error = message }
        }, SttOptions(formatting = true))
        val e = AudioEngine(ctx, RecConfig(format = "M4A", sampleRate = 48_000, channels = 1, bitrate = 96_000, liveTranscript = true), file,
            onLevel = { l, _ -> level = l }, transcriber = t)
        val err = e.start()
        if (err != null) { error = err; return }
        engine = e
    }
    LaunchedEffect(engine) { while (engine != null) { elapsed = engine?.elapsedMs ?: 0; delay(100) } }
    DisposableEffect(Unit) { onDispose { engine?.stop() } }

    fun finish() {
        val e = engine ?: return
        finishing = true
        val ms = e.elapsedMs
        scope.launch {
            withContext(Dispatchers.IO) { e.stop() }
            engine = null
            delay(1600) // let the recogniser deliver its last words
            onDone(file, (finals + partial).filter { it.isNotBlank() }.joinToString(" ").trim(), ms)
        }
    }

    ModalBottomSheet(onDismissRequest = { if (engine == null && !finishing) onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Voice note", style = MaterialTheme.typography.titleLarge)
            Text("Records and transcribes at the same time — on this phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Text(Time.duration(elapsed), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(level.coerceIn(0.02f, 1f)).background(MaterialTheme.colorScheme.primary))
            }
            Spacer(Modifier.height(16.dp))
            Surface(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    (finals.joinToString(" ") + if (partial.isNotBlank()) " $partial" else "").ifBlank { if (engine != null) "Listening…" else "Your words will appear here." },
                    Modifier.verticalScroll(rememberScrollState()).padding(14.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            error?.let { Text(it, color = LocalBlColors.current.warning, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(18.dp))
            when {
                !micOk -> Button(onClick = { perm.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }
                finishing -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("Finishing transcript…") }
                engine == null -> FilledIconButton(onClick = { start() }, Modifier.size(76.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFE53935))) {
                    Icon(Icons.Rounded.Mic, "Record", Modifier.size(36.dp), tint = Color.White)
                }
                else -> FilledIconButton(onClick = { finish() }, Modifier.size(76.dp)) { Icon(Icons.Rounded.Stop, "Stop", Modifier.size(36.dp)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ====================================================================== drawing pad (Keep drawings)

private data class PadStroke(val points: List<Offset>, val color: Color, val width: Float, val highlighter: Boolean)

/** Full-screen sketch pad. Saves a PNG (white background) into the cache and hands it back. */
@Composable
fun DrawingPadDialog(base: File? = null, onDismiss: () -> Unit, onSave: (File) -> Unit) {
    val ctx = LocalContext.current
    val strokes = remember { mutableStateListOf<PadStroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color(0xFF1B1B1F)) }
    var width by remember { mutableFloatStateOf(6f) }
    var tool by remember { mutableStateOf("pen") }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val baseBmp = remember(base) { base?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) } }
    val colors = listOf(0xFF1B1B1F, 0xFF4F8CFF, 0xFFE53935, 0xFF43A047, 0xFFFFB300, 0xFF8E24AA, 0xFFFFFFFF).map { Color(it) }

    fun render(): File {
        val w = size.width.coerceAtLeast(1); val h = size.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        baseBmp?.let { b -> c.drawBitmap(b, null, fitRect(b.width, b.height, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
        strokes.forEach { s ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = s.width; this.color = s.color.toArgb(); strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
                if (s.highlighter) alpha = 100
            }
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            if (s.points.size == 1) path.lineTo(s.points[0].x + 0.1f, s.points[0].y)
            c.drawPath(path, p)
        }
        val f = File(ctx.cacheDir, "drawing_${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Cancel") }
                    Text("Drawing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton(onClick = { strokes.clear() }) { Icon(Icons.Rounded.DeleteSweep, "Clear") }
                    TextButton(onClick = { onSave(render()) }, enabled = strokes.isNotEmpty() || baseBmp != null) { Text("Save") }
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).onSizeChanged { size = it }
                        .pointerInput(tool, color, width) {
                            detectDragGestures(
                                onDragStart = { o -> if (tool == "eraser") eraseAt(strokes, o, 24f) else current = listOf(o) },
                                onDrag = { ch, _ -> if (tool == "eraser") eraseAt(strokes, ch.position, 24f) else current = current + ch.position },
                                onDragEnd = { if (current.isNotEmpty()) strokes += PadStroke(current, color, if (tool == "marker") width * 3 else width, tool == "marker"); current = emptyList() },
                            )
                        }
                        .pointerInput(tool, color, width) { detectTapGestures { o -> if (tool != "eraser") strokes += PadStroke(listOf(o), color, width, tool == "marker") } },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        baseBmp?.let { b ->
                            val r = fitRect(b.width, b.height, size.width, size.height)
                            drawImage(b.asImageBitmap(), dstOffset = androidx.compose.ui.unit.IntOffset(r.left, r.top), dstSize = IntSize(r.width(), r.height()))
                        }
                        (strokes + listOfNotNull(current.takeIf { it.isNotEmpty() }?.let { PadStroke(it, color, if (tool == "marker") width * 3 else width, tool == "marker") })).forEach { s ->
                            if (s.points.size == 1) drawCircle(s.color.copy(alpha = if (s.highlighter) 0.4f else 1f), s.width / 2, s.points[0])
                            else {
                                val p = Path().apply { s.points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                                drawPath(p, s.color.copy(alpha = if (s.highlighter) 0.4f else 1f), style = Stroke(s.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("pen" to Icons.Rounded.Create, "marker" to Icons.Rounded.BorderColor, "eraser" to Icons.Rounded.AutoFixOff).forEach { (t, ic) ->
                        FilledTonalIconToggleButton(checked = tool == t, onCheckedChange = { tool = t }) { Icon(ic, t) }
                    }
                    Spacer(Modifier.width(8.dp))
                    colors.forEach { c ->
                        Box(Modifier.padding(3.dp).size(30.dp).clip(CircleShape).background(c).border(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape).clickable { color = c; if (tool == "eraser") tool = "pen" })
                    }
                    Spacer(Modifier.width(8.dp))
                    Slider(width, { width = it }, valueRange = 2f..28f, modifier = Modifier.width(140.dp))
                }
            }
        }
    }
}

private fun fitRect(bw: Int, bh: Int, w: Int, h: Int): android.graphics.Rect {
    val s = minOf(w.toFloat() / bw, h.toFloat() / bh)
    val dw = (bw * s).toInt(); val dh = (bh * s).toInt()
    return android.graphics.Rect((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh)
}

private fun eraseAt(strokes: MutableList<PadStroke>, o: Offset, r: Float) {
    strokes.removeAll { s -> s.points.any { (it - o).getDistance() < r + s.width / 2 } }
}

// ====================================================================== reminders (time, repeat, place)

data class PlacePick(val name: String, val lat: Double, val lon: Double, val radius: Int, val onLeave: Boolean)
data class ReminderPick(val at: Long?, val repeat: String?, val place: PlacePick?)

@Composable
fun ReminderDialog(
    at: Long?, repeat: String?, place: PlacePick?, allowPlace: Boolean = true,
    onDismiss: () -> Unit, onSave: (ReminderPick) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (place != null && at == null) 1 else 0) }
    val start = at?.let { Time.toDate(it) } ?: Time.today()
    var day by remember { mutableStateOf(start) }
    var minute by remember { mutableIntStateOf(at?.let { val t = java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()); t.hour * 60 + t.minute } ?: 18 * 60) }
    var rule by remember { mutableStateOf(repeat) }
    var pickDay by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickRepeat by remember { mutableStateOf(false) }
    var placeName by remember { mutableStateOf(place?.name ?: "") }
    var lat by remember { mutableStateOf(place?.lat) }
    var lon by remember { mutableStateOf(place?.lon) }
    var radius by remember { mutableIntStateOf(place?.radius ?: 150) }
    var onLeave by remember { mutableStateOf(place?.onLeave ?: false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var locOk by remember { mutableStateOf(PlaceReminders.hasPermission(ctx)) }
    var bgOk by remember { mutableStateOf(PlaceReminders.hasBackground(ctx)) }
    val bgPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bgOk = it }
    val finePerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locOk = it; if (it && !bgOk) bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (allowPlace) {
                    TabRow(tab, containerColor = Color.Transparent) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Time") }, icon = { Icon(Icons.Rounded.Schedule, null) })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Place") }, icon = { Icon(Icons.Rounded.Place, null) })
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (tab == 0) {
                    val now = LocalDateTime.now()
                    val presets = listOf(
                        "Later today" to (if (now.hour < 18) Time.today() to 18 * 60 else Time.today() to minOf(23 * 60, (now.hour + 2) * 60)),
                        "Tomorrow morning" to (Time.today().plusDays(1) to 8 * 60),
                        "Next Monday" to (Time.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY)) to 8 * 60),
                    )
                    presets.forEach { (label, v) ->
                        ListItem(headlineContent = { Text(label) }, supportingContent = { Text(Time.relativeDay(v.first) + " · " + Time.time(v.second)) },
                            leadingContent = { RadioButton(day == v.first && minute == v.second, null) },
                            modifier = Modifier.clickable { day = v.first; minute = v.second }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                    HorizontalDivider()
                    ListItem(headlineContent = { Text(Time.longDate(day)) }, leadingContent = { Icon(Icons.Rounded.Event, null) }, modifier = Modifier.clickable { pickDay = true }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    ListItem(headlineContent = { Text(Time.time(minute)) }, leadingContent = { Icon(Icons.Rounded.AccessTime, null) }, modifier = Modifier.clickable { pickTime = true }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    ListItem(headlineContent = { Text(com.blacklab.app.core.Recurrence.parse(rule)?.describe(day) ?: "Does not repeat") }, leadingContent = { Icon(Icons.Rounded.Repeat, null) },
                        modifier = Modifier.clickable { pickRepeat = true }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                } else {
                    if (!locOk || !bgOk) {
                        Text(if (!locOk) "Location reminders need your precise location." else "Choose “Allow all the time” so the reminder works while BlackLab is closed.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { if (!locOk) finePerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) else bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                            Text(if (!locOk) "Allow location" else "Allow all the time")
                        }
                    }
                    OutlinedTextField(placeName, { placeName = it }, label = { Text("Place or address") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = {
                                busy = "Finding…"
                                scope.launch {
                                    val r = geocode(ctx, placeName)
                                    if (r != null) { lat = r.first; lon = r.second; busy = null } else busy = "Couldn't find that place"
                                }
                            }, enabled = placeName.isNotBlank()) { Icon(Icons.Rounded.Search, "Find") }
                        })
                    TextButton(onClick = {
                        if (!locOk) { finePerm.launch(Manifest.permission.ACCESS_FINE_LOCATION); return@TextButton }
                        busy = "Getting your location…"
                        scope.launch {
                            val l = Geo.current(ctx)
                            if (l == null) { busy = "Location unavailable"; return@launch }
                            lat = l.latitude; lon = l.longitude
                            if (placeName.isBlank()) placeName = Geo.address(ctx, l.latitude, l.longitude) ?: "Current location"
                            busy = null
                        }
                    }) { Icon(Icons.Rounded.MyLocation, null); Spacer(Modifier.width(6.dp)); Text("Use where I am now") }
                    busy?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (lat != null) Text(String.format(java.util.Locale.US, "📍 %.5f, %.5f", lat, lon), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Radius: $radius m", style = MaterialTheme.typography.labelLarge)
                    Slider(radius.toFloat(), { radius = it.toInt() / 10 * 10 }, valueRange = 80f..1000f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(!onLeave, { onLeave = false }, label = { Text("When I arrive") }); Spacer(Modifier.width(8.dp))
                        FilterChip(onLeave, { onLeave = true }, label = { Text("When I leave") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tab == 0) onSave(ReminderPick(Time.millis(day, minute), rule, null))
                else if (lat != null && lon != null) onSave(ReminderPick(null, null, PlacePick(placeName.ifBlank { "Place" }, lat!!, lon!!, radius, onLeave)))
                onDismiss()
            }, enabled = tab == 0 || lat != null) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (at != null || place != null) TextButton(onClick = { onSave(ReminderPick(null, null, null)); onDismiss() }) { Text("Remove", color = LocalBlColors.current.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (pickDay) DatePickerDialogBox(day, onDismiss = { pickDay = false }) { it?.let { d -> day = d } }
    if (pickTime) TimePickerDialogBox(minute, onDismiss = { pickTime = false }) { minute = it }
    if (pickRepeat) RepeatDialog(rule, day, onDismiss = { pickRepeat = false }) { rule = it }
}

private suspend fun geocode(ctx: Context, q: String): Pair<Double, Double>? {
    if (!Geocoder.isPresent() || q.isBlank()) return null
    return withTimeoutOrNull(8000) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                Geocoder(ctx).getFromLocationName(q, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) { if (cont.isActive) cont.resume(addresses.firstOrNull()?.let { it.latitude to it.longitude }) }
                    override fun onError(errorMessage: String?) { if (cont.isActive) cont.resume(null) }
                })
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }
}

// ====================================================================== pick a section (move / copy / send to notebook)

@Composable
fun SectionPickerDialog(title: String, profile: Profile, excludeSection: Long? = null, onDismiss: () -> Unit, onPick: (SectionEntity) -> Unit) {
    val app = rememberApp()
    var data by remember { mutableStateOf<List<Pair<NotebookEntity, List<SectionEntity>>>>(emptyList()) }
    LaunchedEffect(profile) {
        data = withContext(Dispatchers.IO) { app.db.brainstorm().notebooks(profile.name).map { it to app.db.brainstorm().sections(it.id) } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                if (data.isEmpty()) item { Text("No notebooks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                data.forEach { (nb, secs) ->
                    item(key = "nb${nb.id}") {
                        Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp, 18.dp).clip(RoundedCornerShape(3.dp)).background(Color(nb.color)))
                            Spacer(Modifier.width(8.dp))
                            Text(nb.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    items(secs, key = { "s${it.id}" }) { s ->
                        val disabled = s.id == excludeSection
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = !disabled) { onPick(s); onDismiss() }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(s.color)))
                            Spacer(Modifier.width(10.dp))
                            Text((if (s.groupName.isNotBlank()) s.groupName + " › " else "") + s.name, modifier = Modifier.weight(1f),
                                color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface)
                            if (s.locked) Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ====================================================================== templates

@Composable
fun TemplatePickerDialog(onDismiss: () -> Unit, onPick: (PageTemplate) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New page from template") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(PageTemplates.all) { t ->
                    ListItem(headlineContent = { Text(t.label) }, supportingContent = { Text(t.hint) }, leadingContent = { Icon(templateIcon(t.id), null) },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(t); onDismiss() }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun templateIcon(id: String) = when (id) {
    "lined" -> Icons.AutoMirrored.Rounded.Notes; "grid", "dots" -> Icons.Rounded.GridOn; "todo" -> Icons.Rounded.Checklist; "meeting" -> Icons.Rounded.Groups
    "lecture" -> Icons.Rounded.School; "project" -> Icons.Rounded.Flag; "mindmap" -> Icons.Rounded.Hub; "swot" -> Icons.Rounded.Dashboard
    "decision" -> Icons.Rounded.Balance; "daily" -> Icons.Rounded.Today; "weekly" -> Icons.Rounded.DateRange; "retro" -> Icons.Rounded.Replay
    else -> Icons.Rounded.Description
}

// ====================================================================== passwords

@Composable
fun PasswordDialog(title: String, body: String, hint: String = "", confirm: Boolean = false, withHint: Boolean = false, onDismiss: () -> Unit, onDone: (password: String, hint: String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var h by remember { mutableStateOf("") }
    val ok = pw.length >= (if (confirm) 4 else 1) && (!confirm || pw == pw2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hint.isNotBlank()) Text("Hint: $hint", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                if (confirm) OutlinedTextField(pw2, { pw2 = it }, label = { Text("Confirm password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = pw2.isNotEmpty() && pw2 != pw, modifier = Modifier.fillMaxWidth())
                if (withHint) OutlinedTextField(h, { h = it }, label = { Text("Hint (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onDone(pw, h.trim()) }, enabled = ok) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ====================================================================== comments

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(pageId: Long?, noteId: Long?, onDismiss: () -> Unit) {
    val app = rememberApp()
    val me = settingsState().userName.ifBlank { "Me" }
    val flow = remember(pageId, noteId) { if (pageId != null) app.db.brainstorm().observePageComments(pageId) else app.db.brainstorm().observeNoteComments(noteId ?: 0) }
    val comments by flow.collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    var showResolved by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Comments", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilterChip(showResolved, { showResolved = !showResolved }, label = { Text("Show resolved") })
            }
            val visible = comments.filter { showResolved || !it.resolved }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (visible.isEmpty()) item { Text("No comments yet. Notes to yourself and feedback from Live Share guests appear here.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
                items(visible, key = { it.id }) { c -> CommentRow(c) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                OutlinedTextField(text, { text = it }, placeholder = { Text("Add a comment") }, modifier = Modifier.weight(1f), maxLines = 4)
                IconButton(onClick = {
                    val t = text.trim(); if (t.isEmpty()) return@IconButton
                    app.io { app.db.brainstorm().insertComment(CommentEntity(pageId = pageId, noteId = noteId, author = me, text = t)) }
                    text = ""
                }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send") }
            }
        }
    }
}

@Composable
private fun CommentRow(c: CommentEntity) {
    val app = rememberApp()
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (c.guest) Icons.Rounded.Wifi else Icons.Rounded.Person, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(c.author + if (c.guest) " · guest" else "", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(Time.dateTime(c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(c.text, style = MaterialTheme.typography.bodyMedium, color = if (c.resolved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Row {
                TextButton(onClick = { app.io { app.db.brainstorm().updateComment(c.copy(resolved = !c.resolved)) } }) { Text(if (c.resolved) "Reopen" else "Resolve") }
                TextButton(onClick = { app.io { app.db.brainstorm().deleteComment(c) } }) { Text("Delete", color = LocalBlColors.current.danger) }
            }
        }
    }
}

// ====================================================================== Live Share

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveShareSheet(kind: String, refId: Long, title: String, locked: Boolean = false, onDismiss: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by app.link.running.collectAsState()
    val settings = settingsState()
    val ready = settings.linkEnabled && settings.linkLan && running
    val all by app.db.brainstorm().observeShares().collectAsState(initial = emptyList())
    val mine = all.filter { it.kind == kind && it.refId == refId && !it.revoked && it.expiresAt > Time.now() }
    var permission by remember { mutableStateOf("COMMENT") }
    var hours by remember { mutableIntStateOf(24) }
    var showQr by remember { mutableStateOf<ShareLinkEntity?>(null) }
    var consent by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Live Share", style = MaterialTheme.typography.titleLarge)
            Text("People on the same Wi-Fi open “$title” in any browser — live, no account, nothing uploaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            when {
                locked -> Text("Pages in password-protected sections can't be shared live. Move the page out of the locked section first.", color = LocalBlColors.current.warning)
                !ready -> {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(14.dp)) {
                            Text("Turn on Wi-Fi sharing", style = MaterialTheme.typography.titleSmall)
                            Text("Live Share uses BlackLab's local server (the same one BlackLab Desktop uses). Guests only reach the single item you share, through a private link you can turn off any time. The desktop API itself still needs your pairing code.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { consent = true; LiveShare.enableServer(app) }) { Text(if (consent && !running) "Starting…" else "Turn on") }
                        }
                    }
                }
                else -> {
                    Text("Guests can", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(permission == "VIEW", { permission = "VIEW" }, label = { Text("View") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) })
                        FilterChip(permission == "COMMENT", { permission = "COMMENT" }, label = { Text("Comment") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Comment, null) })
                        if (kind == "NOTE") FilterChip(permission == "EDIT", { permission = "EDIT" }, label = { Text("Edit list") }, leadingIcon = { Icon(Icons.Rounded.Checklist, null) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Link works for", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "1 hour", 8 to "8 hours", 24 to "1 day", 168 to "1 week").forEach { (h, l) -> FilterChip(hours == h, { hours = h }, label = { Text(l) }) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { scope.launch { showQr = LiveShare.create(app, kind, refId, title, permission, hours) } }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(8.dp)); Text("Create link")
                    }
                    if (app.link.lanAddress() == null) Text("Connect the phone to Wi-Fi so others can reach it.", color = LocalBlColors.current.warning, style = MaterialTheme.typography.labelMedium)
                    if (mine.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text("Active links", style = MaterialTheme.typography.titleSmall)
                        mine.forEach { s -> ShareRow(s, onQr = { showQr = s }) }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    showQr?.let { s -> QrDialog(s) { showQr = null } }
}

@Composable
fun ShareRow(s: ShareLinkEntity, onQr: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val url = LiveShare.url(app, s.token)
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${s.permission.lowercase().replaceFirstChar { it.uppercase() }} · until ${Time.dateTime(s.expiresAt)} · opened ${s.opens}×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onQr) { Icon(Icons.Rounded.QrCode2, "QR code") }
            IconButton(onClick = { url?.let { copy(ctx, it) } }, enabled = url != null) { Icon(Icons.Rounded.ContentCopy, "Copy link") }
            IconButton(onClick = { url?.let { Files.shareText(ctx, "Open “${s.title}” (same Wi-Fi): $it", s.title) } }, enabled = url != null) { Icon(Icons.Rounded.Share, "Send link") }
            IconButton(onClick = { app.io { LiveShare.revoke(app, s) } }) { Icon(Icons.Rounded.LinkOff, "Turn off", tint = LocalBlColors.current.danger) }
        }
    }
}

@Composable
fun QrDialog(s: ShareLinkEntity, onDismiss: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val url = LiveShare.url(app, s.token)
    val bmp = remember(url) { url?.let { LiveShare.qr(it) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan to open") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (bmp != null) Image(bmp.asImageBitmap(), "QR code", Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)))
                else Text("Connect to Wi-Fi to get a link.")
                Spacer(Modifier.height(10.dp))
                Text(url ?: "", style = MaterialTheme.typography.labelMedium)
                Text("Works for people on the same Wi-Fi until ${Time.dateTime(s.expiresAt)}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { url?.let { copy(ctx, it) } }) { Text("Copy link") } },
    )
}

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("BlackLab", text))
    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

