package com.blacklab.app.ui.assistant

import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.assistant.GoogleAuth
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.Secrets
import com.blacklab.app.core.Keys
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.core.Time
import com.blacklab.app.link.CloudSync
import com.blacklab.app.link.LinkService
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.findActivity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.settings.SchemaItem
import kotlinx.coroutines.launch

@Composable
fun AssistantSettingsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val nano by Nano.status.collectAsState()
    val nanoBytes by Nano.downloadProgress.collectAsState()
    val linkRunning by app.link.running.collectAsState()
    val clients by app.link.clients.collectAsState()
    val lastSync by CloudSync.lastSync.collectAsState()
    val syncError by CloudSync.lastError.collectAsState()
    var keyEdit by remember { mutableStateOf<Pair<String, String>?>(null) }
    var keysVersion by remember { mutableIntStateOf(0) }
    var token by remember { mutableStateOf(app.secrets.linkToken()) }
    var syncing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Nano.refresh() }
    LaunchedEffect(s.linkEnabled, s.linkLan, s.linkPort) { LinkService.sync(ctx) }

    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        GoogleAuth.resultFromIntent(ctx, r.data)?.accessToken?.let { t -> scope.launch { GoogleAuth.rememberAccount(app, t); shell.toast("Google connected") } }
            ?: shell.toast("Google sign-in was cancelled")
    }
    var googleError by remember { mutableStateOf("") }
    fun googleSignIn() {
        googleError = ""
        val act = ctx.findActivity() ?: return
        scope.launch {
            GoogleAuth.authorize(act).onSuccess { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) signIn.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else r.accessToken?.let { GoogleAuth.rememberAccount(app, it); shell.toast("Google connected") }
            }.onFailure {
                googleError = GoogleAuth.explain(ctx, it)
            }
        }
    }

    Scaffold(topBar = { BlTopBar("Assistant & desktop", "${s.asstName} · voice, keys, Google, sync", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            item { SectionHeader("On-device AI") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Gemini Nano", style = MaterialTheme.typography.titleSmall)
                            Text(nano.label + (Nano.modelName.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when (nano) {
                            Nano.Status.DOWNLOADABLE -> Button(onClick = { Nano.download() }) { Text("Download") }
                            Nano.Status.ERROR, Nano.Status.UNKNOWN -> OutlinedButton(onClick = { scope.launch { Nano.refresh() } }) { Text("Retry") }
                            else -> {}
                        }
                    }
                    if (nano == Nano.Status.DOWNLOADING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        if (nanoBytes > 0) Text("${nanoBytes / 1_048_576} MB", style = MaterialTheme.typography.labelSmall)
                    }
                    Text("Runs inside Android's AICore on your Pixel — no network, nothing leaves the phone. Without it, ${s.asstName} still handles every command with its offline rules engine.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            item { SectionHeader("Desktop AI") }
            item {
                val dst by com.blacklab.app.assistant.DesktopAi.status.collectAsState()
                BlCard(Modifier.fillMaxWidth(), accent = if (dst.state == com.blacklab.app.assistant.DesktopAi.State.ONLINE) MaterialTheme.colorScheme.primary else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (dst.state == com.blacklab.app.assistant.DesktopAi.State.ONLINE) "Connected · ${dst.model}" else dst.state.label, style = MaterialTheme.typography.titleSmall)
                            Text(when (dst.state) {
                                com.blacklab.app.assistant.DesktopAi.State.ONLINE -> "${dst.latencyMs} ms · ${dst.models.size} models on the computer" + (dst.host.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                                else -> dst.note.ifBlank { "Uses Ollama on the computer through BlackLab Desktop over USB or Wireless debugging" }
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { scope.launch { com.blacklab.app.assistant.DesktopAi.probe() } }) { Text("Test") }
                    }
                    com.blacklab.app.ui.components.LabeledSwitchRow("Use the desktop AI when it's reachable", "Assistant, summaries, Talk, Ask my files and Brainstorm tools use it first, then Gemini Nano", s.asstDesktopAi) { v ->
                        app.settings.update { putBoolean(com.blacklab.app.core.Keys.ASST_DESKTOP_AI, v) }; scope.launch { com.blacklab.app.assistant.DesktopAi.probe() }
                    }
                    com.blacklab.app.ui.components.LabeledSwitchRow("Hand off work to the computer", "Recordings → meeting notes and tasks, nameplates → equipment registry, morning brief and Friday summary", s.asstDesktopJobs) { v ->
                        app.settings.update { putBoolean(com.blacklab.app.core.Keys.ASST_DESKTOP_JOBS, v) }
                    }
                    val jobs by com.blacklab.app.assistant.DesktopJobs.jobs.collectAsState()
                    val waiting by com.blacklab.app.assistant.DesktopJobs.outbox.collectAsState()
                    if (jobs.isNotEmpty() || waiting > 0) Text(
                        (jobs.joinToString("\n") { "• ${it.title}: ${it.progress}" } + if (waiting > 0) "\n• $waiting waiting to send" else "").trim(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    Text("Set up: plug the phone into the computer (or pair Wireless debugging), run ./daddyshome there, then open BlackLab once. Requests stay inside the USB / adb link and carry a key only this phone has.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }

            item { SectionHeader("Cloud AI · Gemini") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Text(if (com.blacklab.app.assistant.Gemini.configured) "Key stored on this phone · ${s.asstGeminiModel}" else "Add a free Gemini API key below (Google AI Studio)",
                        style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        listOf("OFF" to "Off", "FALLBACK" to "Fallback", "PREFER" to "Prefer").forEachIndexed { i, (k, l) ->
                            SegmentedButton(s.asstCloudAi == k, { app.settings.update { putString(com.blacklab.app.core.Keys.ASST_CLOUD_AI, k) } },
                                SegmentedButtonDefaults.itemShape(i, 3)) { Text(l) }
                        }
                    }
                    Text(when (s.asstCloudAi) {
                        "OFF" -> "Never used. Everything stays on the phone and your computer."
                        "PREFER" -> "Used before Gemini Nano when the computer isn't linked — smartest answers, sent to Google."
                        else -> "Only when neither your computer nor Gemini Nano can answer. Photo features (nameplates, Describe, Smart sort) ask Gemini directly."
                    } + " The Behavioral Health workspace never uses it. On the free tier Google may use requests to improve its products.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))) } }) { Text("Get a free key") }
                }
            }

            item { SectionHeader("Wake word · Picovoice") }
            item {
                val wake by com.blacklab.app.assistant.WakeWordService.status.collectAsState()
                val ppn = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val f = com.blacklab.app.assistant.WakeWordService.customFile(ctx); f.parentFile?.mkdirs()
                        val ok = runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
                        if (ok) { app.settings.update { putString(com.blacklab.app.core.Keys.WAKE_KEYWORD, com.blacklab.app.assistant.WakeWordService.CUSTOM) }
                            com.blacklab.app.assistant.WakeWordService.stop(ctx); if (s.wakeWord) com.blacklab.app.assistant.WakeWordService.start(ctx) }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { shell.toast(if (ok) "Custom wake word imported" else "Couldn't read that file") }
                    }
                }
                BlCard(Modifier.fillMaxWidth()) {
                    com.blacklab.app.ui.components.LabeledSwitchRow("Listen for the wake word", when (wake.state) {
                        com.blacklab.app.assistant.WakeWordService.State.OFF -> if (app.secrets.has(Secrets.PICOVOICE)) "Off" else "Add your free Picovoice AccessKey below first"
                        com.blacklab.app.assistant.WakeWordService.State.ERROR -> wake.message
                        com.blacklab.app.assistant.WakeWordService.State.PAUSED -> wake.message
                        else -> "Listening for “${wake.word}” — no audio leaves the phone"
                    }, s.wakeWord, enabled = app.secrets.has(Secrets.PICOVOICE)) { v ->
                        app.settings.update { putBoolean(com.blacklab.app.core.Keys.WAKE_WORD, v) }
                        if (v) com.blacklab.app.assistant.WakeWordService.start(ctx) else com.blacklab.app.assistant.WakeWordService.stop(ctx)
                    }
                    com.blacklab.app.ui.components.DropdownField("Wake word", com.blacklab.app.assistant.WakeWordService.BUILT_IN + (com.blacklab.app.assistant.WakeWordService.CUSTOM to "Custom (.ppn) — e.g. “Hey Todd”"),
                        s.wakeKeyword, { k -> app.settings.update { putString(com.blacklab.app.core.Keys.WAKE_KEYWORD, k) }
                            if (s.wakeWord) { com.blacklab.app.assistant.WakeWordService.stop(ctx); com.blacklab.app.assistant.WakeWordService.start(ctx) } })
                    var sens by remember(s.wakeSensitivity) { mutableStateOf(s.wakeSensitivity.toFloat()) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Sensitivity", Modifier.width(90.dp), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.material3.Slider(sens, { sens = it }, Modifier.weight(1f), valueRange = 10f..95f,
                            onValueChangeFinished = { app.settings.update { putInt(com.blacklab.app.core.Keys.WAKE_SENSITIVITY, sens.toInt()) }
                                if (s.wakeWord) { com.blacklab.app.assistant.WakeWordService.stop(ctx); com.blacklab.app.assistant.WakeWordService.start(ctx) } })
                        Text("${sens.toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                    Row {
                        TextButton(onClick = { ppn.launch(arrayOf("*/*")) }) { Text("Import custom .ppn") }
                        TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.picovoice.ai/"))) } }) { Text("Get a free key") }
                    }
                    Text("Say the word, wait for the chime, then ask. ${s.asstName} answers out loud and in a notification. To make “Hey ${s.asstName}”, train it free at console.picovoice.ai (platform: Android) and import the .ppn here. Steps aside while recording or dictating.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Weather · OpenWeatherMap") }
            item {
                val w by com.blacklab.app.assistant.Weather.report.collectAsState()
                val werr by com.blacklab.app.assistant.Weather.error.collectAsState()
                var site by remember(s.weatherSite) { mutableStateOf(s.weatherSite) }
                BlCard(Modifier.fillMaxWidth()) {
                    Text(w?.let { com.blacklab.app.assistant.Weather.summary(it) } ?: (werr ?: if (app.secrets.has(Secrets.OPENWEATHER)) "Tap Refresh" else "Add a free OpenWeatherMap key below"),
                        style = MaterialTheme.typography.bodyMedium)
                    w?.let { r -> com.blacklab.app.assistant.Weather.alerts(r).forEach { a -> Text("⚠ ${a.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) } }
                    com.blacklab.app.ui.components.LabeledSwitchRow("Facility prep tasks", "Freeze, heat, storms, wind, heavy rain and snow become Professional tasks with checklists", s.weatherTasks) { v ->
                        app.settings.update { putBoolean(com.blacklab.app.core.Keys.WEATHER_TASKS, v) }
                    }
                    androidx.compose.material3.OutlinedTextField(site, { site = it }, Modifier.fillMaxWidth(), label = { Text("Facility location (lat, lon)") }, placeholder = { Text("Blank = phone's location") }, singleLine = true,
                        trailingIcon = { if (site != s.weatherSite) IconButton(onClick = { app.settings.update { putString(com.blacklab.app.core.Keys.WEATHER_SITE, site.trim()) }; scope.launch { com.blacklab.app.assistant.Weather.refresh(force = true) } }) { Icon(Icons.Rounded.Check, "Save") } })
                    Row {
                        TextButton(onClick = { scope.launch { com.blacklab.app.assistant.Weather.refresh(force = true) } }) { Text("Refresh") }
                        TextButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://home.openweathermap.org/users/sign_up"))) } }) { Text("Get a free key") }
                    }
                }
            }

            item { SectionHeader("Google account") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.googleAccount.ifBlank { "Not connected" }, style = MaterialTheme.typography.titleSmall)
                            Text("Gmail (read, draft, send) · Drive (search, upload, sync)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.googleAccount.isBlank()) Button(onClick = { googleSignIn() }) { Text("Sign in") }
                        else TextButton(onClick = { GoogleAuth.signOut(app) }) { Text("Disconnect") }
                    }
                    if (googleError.isNotBlank()) Text(googleError, style = MaterialTheme.typography.bodySmall, color = com.blacklab.app.ui.theme.LocalBlColors.current.danger,
                        modifier = Modifier.padding(top = 8.dp))
                    if (s.googleAccount.isBlank()) {
                        val (pkg, sha1) = remember { GoogleAuth.androidClientInfo(ctx) }
                        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                        Text("One-time setup in Google Cloud (console.cloud.google.com): enable the Gmail and Google Drive APIs, set up the OAuth consent screen (External, add yourself as a test user), then Credentials › Create OAuth client ID › Android with:",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Text("Package: $pkg\nSHA-1: $sha1", style = com.blacklab.app.ui.theme.MonoDigits.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), modifier = Modifier.padding(top = 4.dp))
                        TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(sha1)); shell.toast("SHA-1 copied") }) { Text("Copy SHA-1") }
                    }
                    Text("Google Keep has no public API for personal accounts, so “save to Keep” hands the note to the Keep app.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
            }

            item { SectionHeader("API keys") }
            item {
                val keysSet = remember(keysVersion) { Secrets.all.associateWith { app.secrets.has(it) } }
                BlCard(Modifier.fillMaxWidth()) {
                    listOf(
                        Secrets.ELEVENLABS to "ElevenLabs (assistant voice)",
                        Secrets.DEV_KNOWLEDGE to "Google Developer Knowledge (docs search)",
                        Secrets.GEMINI to "Gemini API (cloud AI & photos) · free at aistudio.google.com",
                        Secrets.PICOVOICE to "Picovoice AccessKey (wake word) · free at console.picovoice.ai",
                        Secrets.OPENWEATHER to "OpenWeatherMap (weather & facility alerts) · free at openweathermap.org",
                    ).forEach { (k, label) ->
                        val set = keysSet[k] == true
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (set) Icons.Rounded.Key else Icons.Rounded.KeyOff, null, tint = if (set) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                Text(if (set) "Stored encrypted on this phone" else "Not set", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { keyEdit = k to label }) { Text(if (set) "Replace" else "Add") }
                        }
                    }
                    val used = app.speaker.charsUsedThisMonth()
                    Text("ElevenLabs this month: $used / ${s.asstElevenBudget} characters", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    Text("Keys are encrypted with the phone's hardware keystore. They're never in the app download, backups, the BlackLab folder or the desktop link.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }

            item { SectionHeader("BlackLab Desktop") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    LabeledSwitchRow("Desktop link", if (linkRunning) "Running" + (if (clients.isNotEmpty()) " · ${clients.size} desktop(s) seen" else "") else "Off", s.linkEnabled, icon = Icons.Rounded.Computer) { v ->
                        app.settings.update { putBoolean(Keys.LINK_ENABLED, v) }
                    }
                    LabeledSwitchRow("Allow Wi-Fi", "Otherwise USB only (adb forward)", s.linkLan, enabled = s.linkEnabled, icon = Icons.Rounded.Wifi) { v ->
                        app.settings.update { putBoolean(Keys.LINK_LAN, v) }
                    }
                    Text("Pairing code", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                        Text(token.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { token = app.secrets.resetLinkToken(); shell.toast("New code — re-pair BlackLab Desktop") }) { Text("New code") }
                    }
                    val addr = if (s.linkLan) app.link.lanAddress() else null
                    Text(
                        "USB: run BlackLab Desktop (it sets up `adb forward tcp:${s.linkPort}`), then enter the code.\n" +
                            (addr?.let { "Wi-Fi: http://$it:${s.linkPort}" } ?: "Wi-Fi: turn on “Allow Wi-Fi” to connect without a cable."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp),
                    )
                }
            }

            item { SectionHeader("Cloud sync (web UI from anywhere)") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    LabeledSwitchRow("Sync through Google Drive", if (s.googleAccount.isBlank()) "Sign in with Google first" else "Hidden app folder in ${s.googleAccount}",
                        s.cloudSync, enabled = s.googleAccount.isNotBlank(), icon = Icons.Rounded.CloudSync) { v ->
                        app.settings.update { putBoolean(Keys.CLOUD_SYNC, v) }; CloudSync.schedule(ctx)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            when {
                                syncing -> "Syncing…"
                                syncError != null -> "Last attempt: $syncError"
                                lastSync > 0 -> "Last synced ${Time.ago(lastSync)}"
                                else -> "Not synced yet"
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(enabled = s.cloudSync && !syncing, onClick = {
                            syncing = true
                            scope.launch { val ok = CloudSync.sync(app); syncing = false; shell.toast(if (ok) "Synced to Drive" else "Sync failed") }
                        }) { Text("Sync now") }
                    }
                }
            }

            listOf("assistant", "voice", "knowledge", "google", "sync").forEach { id ->
                val sec = SettingsSchema.sections.first { it.id == id }
                item(key = "h-$id") { SectionHeader(sec.title + " · all options") }
                sec.items.filter { it.key != Keys.LINK_ENABLED && it.key != Keys.LINK_LAN && it.key != Keys.CLOUD_SYNC && it.key != Keys.GOOGLE_ACCOUNT }
                    .forEach { def -> item(key = def.key) { SchemaItem(def) } }
            }
            item { Spacer(Modifier.size(40.dp)) }
        }
    }

    keyEdit?.let { (k, label) ->
        TextInputDialog(label, "", "Paste key", onDismiss = { keyEdit = null }) { v -> app.secrets.put(k, v); keysVersion++; shell.toast("Saved") }
    }
}
