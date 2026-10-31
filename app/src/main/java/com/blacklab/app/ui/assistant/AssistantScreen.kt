package com.blacklab.app.ui.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blacklab.app.assistant.GoogleAuth
import com.blacklab.app.assistant.Msg
import com.blacklab.app.assistant.Nameplate
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.UiAction
import com.blacklab.app.assistant.VoiceInput
import com.blacklab.app.assistant.isOnline
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Time
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.CodeResultDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.ScanDoneDialog
import com.blacklab.app.ui.components.ScanResult
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.findActivity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberCodeScanner
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AssistantScreen(startListening: Boolean = false) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val assistant = app.assistant
    val messages by assistant.messages.collectAsState()
    val busy by assistant.busy.collectAsState()
    val pending by assistant.pending.collectAsState()
    val nano by Nano.status.collectAsState()
    val speaking by app.speaker.speaking.collectAsState()
    var input by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var code by remember { mutableStateOf<Barcode?>(null) }
    var nameplateFile by remember { mutableStateOf<File?>(null) }
    var nameplateNote by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()
    val online = remember(messages.size) { ctx.isOnline() }

    fun send(text: String) {
        if (text.isBlank()) return
        input = ""; partial = ""
        app.speaker.stop()
        scope.launch { assistant.handle(text) }
    }

    val voice = remember {
        VoiceInput(ctx,
            onPartial = { partial = it },
            onFinal = { listening = false; level = 0f; send(it) },
            onLevel = { level = it },
            onError = { listening = false; level = 0f; partial = ""; shell.toast(it) })
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { listening = true; voice.start() } }
    fun listen() {
        app.speaker.stop()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        else { listening = true; partial = ""; voice.start() }
    }
    DisposableEffect(Unit) {
        app.speaker.onDone = { if (app.settings.current.asstAutoListen) ContextCompat.getMainExecutor(ctx).execute { listen() } }
        onDispose { voice.stop(); app.speaker.onDone = null }
    }

    // Snap-to-Work-Order camera capture
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = nameplateFile
        if (ok && f != null && f.length() > 0) { app.storage.scan(f); nameplateNote = f } else f?.delete()
    }
    fun captureNameplate() {
        val dir = app.storage.dir(s.activeProfile, Folder.PHOTOS)
        val f = Files.unique(dir, "Nameplate_" + BlackLabStorage.timestamp(), "jpg")
        nameplateFile = f
        takePicture.launch(Files.uri(ctx, f))
    }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) captureNameplate() else shell.toast("Camera permission is needed") }
    fun nameplate() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) camPerm.launch(Manifest.permission.CAMERA) else captureNameplate()
    }
    val docScanner = rememberDocumentScanner(app, s.activeProfile) { scan = it }
    val codeScanner = rememberCodeScanner { code = it }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        GoogleAuth.resultFromIntent(ctx, r.data)?.accessToken?.let { t -> scope.launch { GoogleAuth.rememberAccount(app, t); shell.toast("Google connected") } }
    }
    fun googleSignIn() {
        val act = ctx.findActivity() ?: return
        scope.launch {
            GoogleAuth.authorize(act).onSuccess { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) signIn.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else r.accessToken?.let { GoogleAuth.rememberAccount(app, it); shell.toast("Google connected") }
            }.onFailure { shell.toast("Google sign-in unavailable: ${it.message?.take(80)}") }
        }
    }

    // React to replies: speak, navigate, launch, scan
    LaunchedEffect(Unit) {
        assistant.events.collect { r ->
            if (r.speak && app.settings.current.asstSpeak && r.text.isNotBlank()) app.speaker.speak(r.text)
            r.intent?.let { runCatching { ctx.startActivity(it) }.onFailure { shell.toast("Couldn't open that app") } }
            when (r.ui) {
                UiAction.SCAN_DOCUMENT -> docScanner()
                UiAction.SCAN_CODE -> codeScanner()
                UiAction.NAMEPLATE -> nameplate()
                UiAction.GOOGLE_SIGN_IN -> googleSignIn()
                null -> {}
            }
            r.route?.let { route -> if (route != "assistant") shell.go(route) }
        }
    }
    LaunchedEffect(Unit) {
        if (Nano.status.value == Nano.Status.UNKNOWN) Nano.refresh()
        if (startListening) listen()
        else if (s.asstBriefOnOpen) {
            val prefs = ctx.getSharedPreferences("blacklab_assistant", 0)
            val today = Time.today().toEpochDay()
            if (prefs.getLong("brief_day", 0) != today) { prefs.edit().putLong("brief_day", today).apply(); send("brief me") }
        }
    }
    val ask by com.blacklab.app.MainActivity.pendingAsk.collectAsState()
    LaunchedEffect(ask) { ask?.let { com.blacklab.app.MainActivity.pendingAsk.value = null; send(it) } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Scaffold(
        topBar = {
            BlTopBar(s.asstName, subtitle = listOf(
                if (nano == Nano.Status.AVAILABLE) "Gemini Nano" else "Rules engine",
                if (online && !s.asstOfflineOnly) "online" else "offline",
                if (speaking) "speaking…" else null,
            ).filterNotNull().joinToString(" · "), onBack = { shell.back() }) {
                IconButton(onClick = { if (speaking) app.speaker.stop() else app.settings.update { putBoolean(com.blacklab.app.core.Keys.ASST_SPEAK, !s.asstSpeak) } }) {
                    Icon(if (speaking) Icons.Default.StopCircle else if (s.asstSpeak) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Voice")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Assistant settings") }, leadingIcon = { Icon(Icons.Default.Tune, null) }, onClick = { menu = false; shell.go("assistant/settings") })
                        DropdownMenuItem(text = { Text("What can you do?") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Help, null) }, onClick = { menu = false; send("help") })
                        DropdownMenuItem(text = { Text("Rebuild file index") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = {
                            menu = false; scope.launch { assistant.index.rebuild(); shell.toast("Indexed ${assistant.index.size} passages") }
                        })
                        DropdownMenuItem(text = { Text("Clear conversation") }, leadingIcon = { Icon(Icons.Default.DeleteSweep, null) }, onClick = { menu = false; assistant.clearHistory() })
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (nano == Nano.Status.DOWNLOADABLE || nano == Nano.Status.DOWNLOADING) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text(if (nano == Nano.Status.DOWNLOADING) "Downloading Gemini Nano for offline AI…" else "Get Gemini Nano for smarter offline answers",
                            Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
                        if (nano == Nano.Status.DOWNLOADABLE) OutlinedButton(onClick = { Nano.download() }) { Text("Download") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty()) item { Welcome(s.asstName, s.userName) }
                items(messages, key = { it.id }) { m -> Bubble(m) { link ->
                    when {
                        link.route != null -> shell.go(link.route)
                        link.url != null -> Files.openUrl(ctx, link.url)
                    }
                } }
                if (busy) item { Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            pending?.let { p ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { send("cancel") }) { Text("Cancel") }
                        Spacer(Modifier.size(8.dp))
                        Button(onClick = { send("yes") }) { Text("Yes") }
                    }
                }
            }
            val chips = listOf("Brief me", "What's due?", "What's on my calendar this week?", "Action items from my last meeting", "Nameplate → work order",
                "Search my files for ", "Check email", "Start dictation", "Start focus", "Help")
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { c -> SuggestionChip(onClick = { if (c.endsWith(" ")) input = c else if (c.startsWith("Nameplate")) send("nameplate") else send(c) }, label = { Text(c.trim()) }) }
            }
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = if (listening) partial.ifBlank { "Listening…" } else input,
                    onValueChange = { if (!listening) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask ${s.asstName} anything") },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(input) }),
                    readOnly = listening,
                )
                Spacer(Modifier.size(8.dp))
                if (input.isNotBlank() && !listening) {
                    FilledIconButton(onClick = { send(input) }, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                } else {
                    val pulse by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, label = "mic")
                    FilledIconButton(
                        onClick = { if (listening) { voice.stop(); listening = false } else listen() },
                        modifier = Modifier.size(56.dp).scale(pulse),
                        colors = if (listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error) else IconButtonDefaults.filledIconButtonColors(),
                    ) { Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, "Talk") }
                }
            }
        }
    }

    scan?.let { r -> ScanDoneDialog(r) { scan = null } }
    code?.let { c -> CodeResultDialog(c) { code = null } }
    nameplateNote?.let { f ->
        TextInputDialog("What needs doing?", "", "e.g. compressor short-cycling, check contactor", confirmLabel = "Create work order", singleLine = false,
            onDismiss = {
                nameplateNote = null
                scope.launch { runNameplate(app, ctx, f, "", shell, assistant) }
            }) { note -> nameplateNote = null; scope.launch { runNameplate(app, ctx, f, note, shell, assistant) } }
    }
}

private suspend fun runNameplate(app: com.blacklab.app.AppContainer, ctx: android.content.Context, f: File, note: String, shell: com.blacklab.app.ui.components.Shell, assistant: com.blacklab.app.assistant.Assistant) {
    shell.toast("Reading the nameplate…")
    val out = runCatching { Nameplate.process(ctx, app, f, note) }.getOrElse { shell.toast("Couldn't read it: ${it.message}"); return }
    val fields = out.asset.fields.entries.take(8).joinToString("\n") { "• ${it.key}: ${it.value}" }.ifBlank { "No nameplate fields recognized — the photo and raw text are in the work order." }
    assistant.post("Work order created for ${out.asset.label}:\n$fields", listOf(
        com.blacklab.app.assistant.Link("Work order", "doc/${out.docId}"), com.blacklab.app.assistant.Link("Task", "task/${out.taskId}"),
    ), "Snap-to-Work-Order · on-device OCR")
    if (app.settings.current.asstSpeak) app.speaker.speak("Work order created for ${out.asset.label}.")
}

@Composable
private fun Welcome(name: String, user: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("Hey${user.substringBefore(' ').takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}, I'm $name.", style = MaterialTheme.typography.titleLarge)
        Text("Tap the mic and talk, or type. I run on your phone — most things work with no signal at all.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
    }
}

@Composable
private fun Bubble(m: Msg, onLink: (com.blacklab.app.assistant.Link) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.user) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (m.user) 20.dp else 6.dp, bottomEnd = if (m.user) 6.dp else 20.dp),
            color = if (m.user) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                if (!m.user && m.source.isNotBlank()) Text(m.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (m.user && m.source != "phone" && m.source.isNotBlank()) Text("from ${m.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(m.links.isNotEmpty()) {
            LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(m.links) { l -> AssistChip(onClick = { onLink(l) }, label = { Text(l.label, fontWeight = FontWeight.Medium) },
                    leadingIcon = { Icon(if (l.url != null) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.ChevronRight, null, Modifier.size(16.dp)) }) }
            }
        }
    }
}
