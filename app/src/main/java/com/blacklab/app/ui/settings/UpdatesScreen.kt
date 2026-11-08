package com.blacklab.app.ui.settings

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.blacklab.app.BuildConfig
import com.blacklab.app.core.Files
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Time
import com.blacklab.app.update.Updater
import com.blacklab.app.update.Updater.State
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.launch

@Composable
fun UpdatesScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val state by Updater.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canInstall = Updater.canInstall(ctx) }
    val signer = remember { Updater.installedSigner(ctx) }
    val colors = LocalBlColors.current

    val titleScroll = com.blacklab.app.ui.components.rememberCollapsingTitle()
    Scaffold(Modifier.nestedScroll(titleScroll.nestedScrollConnection), topBar = { BlTopBar("App updates", onBack = { shell.back() }, scrollBehavior = titleScroll, large = true) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            BlCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("BlackLab ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                Text("Build ${BuildConfig.VERSION_CODE}" + if (s.updateLastCheck > 0) " · last checked ${Time.ago(s.updateLastCheck)}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (signer.isNotBlank()) Text("Signing key ${Updater.prettyFingerprint(signer)}", style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (BuildConfig.DEBUG) Text("This is a debug build. Release updates install over the release build only.",
                    style = MaterialTheme.typography.bodySmall, color = colors.warning, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.size(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { scope.launch { Updater.check(ctx) } }, enabled = state !is State.Checking && state !is State.Downloading && state !is State.Installing) {
                        Icon(Icons.Rounded.Autorenew, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Check now")
                    }
                    if (state is State.Checking) { Spacer(Modifier.size(12.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                }
            }

            when (val st = state) {
                is State.UpToDate -> StatusCard(Icons.Rounded.CheckCircle, colors.success, "You're up to date",
                    "The latest release on GitHub is ${st.latest}.")
                is State.Failed -> {
                    StatusCard(Icons.Rounded.Cancel, colors.danger, "Update stopped", st.message)
                    st.release?.let { ReleaseCard(it) }
                }
                is State.Available -> {
                    ReleaseCard(st.release)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { Updater.download(ctx, st.release) } }) {
                            Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                            Text("Download & verify" + if (st.release.apkSize > 0) " (${Files.formatSize(st.release.apkSize)})" else "")
                        }
                        TextButton(onClick = { app.settings.update { putString(Keys.UPDATE_SKIP, st.release.version) }; Updater.state.value = State.Idle }) { Text("Skip this version") }
                    }
                }
                is State.Downloading -> {
                    ReleaseCard(st.release)
                    Text("Downloading ${Files.formatSize(st.done)}" + if (st.total > 0) " of ${Files.formatSize(st.total)}" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                    if (st.total > 0) LinearProgressIndicator(progress = { (st.done.toFloat() / st.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is State.Verified -> {
                    ReleaseCard(st.release)
                    BlCard(Modifier.fillMaxWidth().padding(top = 10.dp), accent = colors.success) {
                        Text("Verified", style = MaterialTheme.typography.titleSmall)
                        Check(true, "SHA-256 matches the checksum published with the release")
                        Check(true, "Signed with the same key as the installed app")
                        Check(true, "Same app (${ctx.packageName}), newer build")
                        SelectionContainer { Text(st.checks.sha256, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
                    }
                    if (!canInstall) {
                        Text("Android needs your permission once before BlackLab can install its own updates.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                        }, modifier = Modifier.padding(top = 6.dp)) { Text("Allow installing updates") }
                    }
                    Button(onClick = { scope.launch { Updater.install(ctx, st) } }, enabled = canInstall, modifier = Modifier.padding(top = 10.dp)) {
                        Text("Install ${st.release.version}")
                    }
                    Text("BlackLab closes while Android installs the update. Your data stays where it is.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                is State.Installing -> {
                    ReleaseCard(st.release)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.size(12.dp)); Text("Installing… confirm on the next screen if Android asks.")
                    }
                }
                else -> {}
            }

            SectionHeader("Settings")
            LabeledSwitchRow("Check for updates weekly", "Asks GitHub once a week and notifies you. Nothing downloads without your OK.", s.updateAuto, icon = Icons.Rounded.Autorenew) { v ->
                app.settings.update { putBoolean(Keys.UPDATE_AUTO, v) }
            }
            if (s.updateSkip.isNotBlank()) TextButton(onClick = { app.settings.update { putString(Keys.UPDATE_SKIP, "") } }) { Text("Stop skipping version ${s.updateSkip}") }

            SectionHeader("How updates are checked")
            Text(
                "BlackLab asks GitHub (github.com/${Updater.REPO}) for the latest release. Only GitHub sees the request; no account or personal data is sent. " +
                    "Before anything installs, the download must match the SHA-256 checksum published with the release and be signed with the same key as the app on this phone. " +
                    "Android checks the signature again before replacing the app.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, text: String) {
    BlCard(Modifier.fillMaxWidth().padding(top = 10.dp), accent = tint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint); Spacer(Modifier.size(10.dp))
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ReleaseCard(r: Updater.Release) {
    val ctx = LocalContext.current
    BlCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { "BlackLab ${r.version}" }, style = MaterialTheme.typography.titleSmall)
                Text("Version ${r.version}" + (r.publishedAt.takeIf { it.length >= 10 }?.let { " · released ${it.take(10)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (Updater.isAllowedUrl(r.pageUrl)) androidx.compose.material3.IconButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open on GitHub")
            }
        }
        if (r.notes.isNotBlank()) Text(r.notes.take(4000), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Check(ok: Boolean, text: String) {
    val c = LocalBlColors.current
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel, null, Modifier.size(16.dp), tint = if (ok) c.success else c.danger)
        Spacer(Modifier.size(8.dp)); Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
