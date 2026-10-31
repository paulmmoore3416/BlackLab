package com.blacklab.app.ui.assistant

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
import androidx.compose.material.icons.filled.*
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
    fun googleSignIn() {
        val act = ctx.findActivity() ?: return
        scope.launch {
            GoogleAuth.authorize(act).onSuccess { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) signIn.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else r.accessToken?.let { GoogleAuth.rememberAccount(app, it); shell.toast("Google connected") }
            }.onFailure {
                shell.toast("Google sign-in needs an Android OAuth client for this app in your Cloud project — see docs/ASSISTANT.md")
            }
        }
    }

    Scaffold(topBar = { BlTopBar("Assistant & desktop", "${s.asstName} · voice, keys, Google, sync", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            item { SectionHeader("On-device AI") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
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

            item { SectionHeader("Google account") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.googleAccount.ifBlank { "Not connected" }, style = MaterialTheme.typography.titleSmall)
                            Text("Gmail (read, draft, send) · Drive (search, upload, sync)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.googleAccount.isBlank()) Button(onClick = { googleSignIn() }) { Text("Sign in") }
                        else TextButton(onClick = { GoogleAuth.signOut(app) }) { Text("Disconnect") }
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
                    ).forEach { (k, label) ->
                        val set = keysSet[k] == true
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (set) Icons.Default.Key else Icons.Default.KeyOff, null, tint = if (set) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    LabeledSwitchRow("Desktop link", if (linkRunning) "Running" + (if (clients.isNotEmpty()) " · ${clients.size} desktop(s) seen" else "") else "Off", s.linkEnabled, icon = Icons.Default.Computer) { v ->
                        app.settings.update { putBoolean(Keys.LINK_ENABLED, v) }
                    }
                    LabeledSwitchRow("Allow Wi-Fi", "Otherwise USB only (adb forward)", s.linkLan, enabled = s.linkEnabled, icon = Icons.Default.Wifi) { v ->
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
                        s.cloudSync, enabled = s.googleAccount.isNotBlank(), icon = Icons.Default.CloudSync) { v ->
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
