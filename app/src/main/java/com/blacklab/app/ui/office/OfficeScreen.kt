package com.blacklab.app.ui.office

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Time
import com.blacklab.app.office.MicrosoftApps
import com.blacklab.app.office.MicrosoftApps.App
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Microsoft's brand colours for each app, used for its file badges. */
internal fun officeColor(app: App?): Color = when (app) {
    App.POWERPOINT -> Color(0xFFC43E1C)
    App.EXCEL -> Color(0xFF107C41)
    App.WORD -> Color(0xFF185ABD)
    App.TEAMS -> Color(0xFF5B5FC7)
    App.OUTLOOK, App.ONEDRIVE, App.M365 -> Color(0xFF0F6CBD)
    null -> Color(0xFF616161)
}

internal fun officeIcon(app: App?): ImageVector = when (app) {
    App.POWERPOINT -> Icons.Rounded.Slideshow
    App.EXCEL -> Icons.Rounded.TableChart
    App.WORD -> Icons.Rounded.Description
    App.TEAMS -> Icons.Rounded.Groups
    App.OUTLOOK -> Icons.Rounded.Mail
    App.ONEDRIVE -> Icons.Rounded.Cloud
    App.M365, null -> Icons.Rounded.BusinessCenter
}

/**
 * Office: your PowerPoint, Excel and Word files (opened in BlackLab's viewers or handed to Microsoft's apps with
 * write access), Teams meetings from your calendars with one-tap Join, and the Microsoft 365 connection.
 */
@Composable
fun OfficeScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var files by remember { mutableStateOf<List<File>?>(null) }
    var meetings by remember { mutableStateOf<List<MicrosoftApps.Meeting>>(emptyList()) }
    var filter by remember { mutableStateOf<App?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var calOk by remember { mutableStateOf(com.blacklab.app.ui.schedule.DeviceCalendar.hasPermission(ctx)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calOk = it; refresh++ }

    LaunchedEffect(refresh) {
        files = withContext(Dispatchers.IO) { recentOfficeFiles(app.storage.root) }
        meetings = withContext(Dispatchers.IO) { MicrosoftApps.meetings(ctx) }
    }

    Scaffold(topBar = { BlTopBar("Office", subtitle = "PowerPoint · Excel · Word · Teams", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { MicrosoftAccountCard() }
            item { SectionHeader("Microsoft apps") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(App.POWERPOINT, App.EXCEL, App.WORD, App.TEAMS, App.OUTLOOK, App.ONEDRIVE).forEach { a ->
                        AssistChip(onClick = { MicrosoftApps.launch(ctx, a) }, label = { Text(a.label) },
                            leadingIcon = { Icon(officeIcon(a), null, tint = officeColor(a), modifier = Modifier.size(18.dp)) })
                    }
                }
            }

            item { SectionHeader("Teams meetings", action = if (calOk) "Refresh" else null) { refresh++ } }
            if (!calOk) item {
                OfficeNote("Allow calendar access and BlackLab finds the Teams links in your Outlook and work calendar invites, so you can join in one tap.") {
                    FilledTonalButton(onClick = { calPerm.launch(Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar") }
                }
            } else if (meetings.isEmpty()) item {
                OfficeNote("No Teams meetings in the next 7 days. Meetings show up here when your work calendar syncs to this phone (Outlook › Settings › your account › Sync calendars).")
            } else items(meetings, key = { "${it.eventId}@${it.start}" }) { m -> MeetingRow(m) { MicrosoftApps.join(ctx, m.link) } }

            item {
                SectionHeader("Recent files")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, label = { Text("All") })
                    listOf(App.POWERPOINT to "Presentations", App.EXCEL to "Workbooks", App.WORD to "Documents").forEach { (a, label) ->
                        FilterChip(filter == a, { filter = if (filter == a) null else a }, label = { Text(label) },
                            leadingIcon = { Icon(officeIcon(a), null, tint = officeColor(a), modifier = Modifier.size(16.dp)) })
                    }
                }
            }
            val shown = files?.filter { filter == null || MicrosoftApps.appFor(it) == filter }
            when {
                shown == null -> item { Text("Looking for Office files…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                shown.isEmpty() -> item { OfficeNote("No ${filter?.let { "${it.label} " } ?: "Office "}files in your BlackLab folder yet. Share a file to BlackLab, or save it into BlackLab from Microsoft's apps.") }
                else -> items(shown, key = { it.absolutePath }) { f ->
                    val fromOneDrive = remember(f, refresh) { com.blacklab.app.office.Microsoft365.linkOf(ctx, f) != null }
                    val changed = remember(f, refresh) { com.blacklab.app.office.Microsoft365.changedSinceDownload(ctx, f) }
                    FileRow(f, onOpen = { shell.go("view?path=" + Uri.encode(app.storage.relative(f))) },
                        onMicrosoft = { shell.toast(MicrosoftApps.openInMicrosoft(ctx, f)) },
                        oneDrive = if (!fromOneDrive) null else changed,
                        onUpload = { scope.launch { shell.toast(runCatching { com.blacklab.app.office.Microsoft365.upload(app, f) }.getOrElse { it.message ?: "Upload failed" }); refresh++ } })
                }
            }
        }
    }
}

/** Office files anywhere in the BlackLab folder, newest first (hidden folders skipped). */
internal fun recentOfficeFiles(root: File, limit: Int = 60): List<File> =
    root.walkTopDown().maxDepth(8).onEnter { !it.name.startsWith(".") }
        .filter { it.isFile && !it.name.startsWith("~$") && it.extension.lowercase() in MicrosoftApps.OFFICE_EXTS }
        .sortedByDescending { it.lastModified() }.take(limit).toList()

@Composable
private fun MeetingRow(m: MicrosoftApps.Meeting, onJoin: () -> Unit) {
    val now = java.time.LocalDateTime.now()
    val live = now >= m.start.minusMinutes(10) && now <= m.end
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Groups, null, tint = officeColor(App.TEAMS))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${Time.relativeDay(m.start.toLocalDate())} · ${Time.time(m.start)}–${Time.time(m.end)}" + if (m.calendar.isNotBlank()) " · ${m.calendar}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            FilledTonalButton(onClick = onJoin) { Icon(Icons.Rounded.Videocam, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (live) "Join now" else "Join") }
        }
    }
}

@Composable
private fun FileRow(f: File, onOpen: () -> Unit, onMicrosoft: () -> Unit, oneDrive: Boolean? = null, onUpload: () -> Unit = {}) {
    val a = MicrosoftApps.appFor(f)
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = officeColor(a), modifier = Modifier.size(36.dp)) {
                Icon(officeIcon(a), null, tint = Color.White, modifier = Modifier.padding(8.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val day = java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                Text("${f.extension.uppercase()} · ${if (day == LocalDate.now()) "today " + Time.time(Time.toLdt(f.lastModified())) else Time.date(day)} · ${f.parentFile?.name.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // from OneDrive: a cloud that turns into "upload" once the copy here has changes
            if (oneDrive != null) IconButton(onClick = onUpload, enabled = oneDrive) {
                Icon(if (oneDrive) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDone, if (oneDrive) "Save changes to OneDrive" else "Same as OneDrive",
                    tint = if (oneDrive) officeColor(App.ONEDRIVE) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onMicrosoft) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in ${a?.label ?: "Microsoft 365"}", tint = officeColor(a)) }
        }
    }
}

@Composable
internal fun OfficeNote(text: String, action: (@Composable () -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action?.let { Spacer(Modifier.size(8.dp)); it() }
        }
    }
}
