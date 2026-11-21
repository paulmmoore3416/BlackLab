package com.blacklab.app.ui.office

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Folder
import com.blacklab.app.office.Microsoft365
import com.blacklab.app.office.MicrosoftApps
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.launch

/** Office › Microsoft 365: set up and connect your personal Microsoft account, then OneDrive, Outlook and the week ahead. */
@Composable
internal fun MicrosoftAccountCard() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val st by Microsoft365.state.collectAsState()
    val scope = rememberCoroutineScope()
    var idText by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<Microsoft365.DriveItem>?>(null) }
    var mail by remember { mutableStateOf<List<Microsoft365.Mail>?>(null) }
    var events by remember { mutableStateOf<List<Microsoft365.Event>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(st.account) {
        if (st.account == null) return@LaunchedEffect
        runCatching { files = Microsoft365.recent(app); mail = Microsoft365.inbox(app, 5); events = Microsoft365.events(app, 7) }.onFailure { loadError = it.message }
    }

    BlCard(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.BusinessCenter, null, tint = officeColor(MicrosoftApps.App.M365))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Microsoft 365", style = MaterialTheme.typography.titleSmall)
                Text(st.account?.let { a -> "${a.name.ifBlank { "Connected" }}${if (a.email.isNotBlank()) " · ${a.email}" else ""}" }
                    ?: if (st.configured) "Personal account · not connected" else "Personal account · set up once",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (st.account != null) TextButton(onClick = { Microsoft365.signOut(app); files = null; mail = null; events = null }) { Text("Sign out") }
        }
        st.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

        when {
            !st.configured -> {
                Text("Paste the Application (client) ID of the \"BlackLab\" app you registered in Microsoft Entra (personal Microsoft accounts, Android platform with this phone's signature hash). It isn't a secret.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Text("Signature hash: ${remember { Microsoft365.signatureHash(ctx) }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(idText, { idText = it.trim() }, Modifier.weight(1f), singleLine = true, label = { Text("Application (client) ID") },
                        isError = idText.isNotEmpty() && !Microsoft365.validClientId(idText))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { if (Microsoft365.setClientId(app, idText)) shell.toast("Saved — now tap Connect") else shell.toast("That isn't an Application ID (it looks like 1a2b3c4d-…)") },
                        enabled = Microsoft365.validClientId(idText)) { Text("Save") }
                }
            }
            st.account == null -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { Microsoft365.signIn(app, ctx)?.let { shell.toast(it) } }) { Icon(Icons.Rounded.Login, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Connect") }
                OutlinedButton(onClick = { app.secrets.put(com.blacklab.app.assistant.Secrets.MS_CLIENT_ID, null); Microsoft365.refresh(app) }) { Text("Change ID") }
            }
            else -> {
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                busy?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp)) }
                events?.takeIf { it.isNotEmpty() }?.let { ev ->
                    Text("This week", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    ev.take(4).forEach { e ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(e.start.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(112.dp))
                            Text(e.subject, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            e.joinUrl?.let { u -> TextButton(onClick = { MicrosoftApps.join(ctx, u) }) { Text("Join") } }
                        }
                    }
                }
                mail?.let { m ->
                    Text("Outlook · ${m.count { it.unread }} unread of the latest ${m.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    m.take(3).forEach { x ->
                        Surface(onClick = { com.blacklab.app.core.Files.openUrl(ctx, x.webLink) }, shape = RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
                            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text("${x.from} · ${x.subject}", style = MaterialTheme.typography.bodySmall, fontWeight = if (x.unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(x.preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                files?.let { fl ->
                    Text("OneDrive · recent", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    if (fl.isEmpty()) Text("Nothing recent in OneDrive.", style = MaterialTheme.typography.bodySmall)
                    fl.take(8).forEach { item ->
                        val kind = MicrosoftApps.appFor(java.io.File(item.name))
                        Surface(onClick = {
                            busy = "Opening ${item.name}…"
                            scope.launch {
                                runCatching { Microsoft365.download(app, item, java.io.File(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), "OneDrive")) }
                                    .onSuccess { f -> busy = null; shell.go("view?path=" + Uri.encode(app.storage.relative(f))) }
                                    .onFailure { busy = null; shell.toast(it.message ?: "Couldn't open it") }
                            }
                        }, shape = RoundedCornerShape(8.dp), color = androidx.compose.ui.graphics.Color.Transparent) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(officeIcon(kind), null, tint = officeColor(kind), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.modified.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
