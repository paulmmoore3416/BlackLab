package com.blacklab.app.ui.settings

import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.BuildConfig
import com.blacklab.app.core.CrashLog
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.theme.LocalBlColors

/** The private problem-report log: crashes, ANRs and native crashes, kept only on this phone. */
@Composable
fun DiagnosticsScreen() {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var entries by remember { mutableStateOf(CrashLog.entries(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { CrashLog.markSeen(ctx) }

    val titleScroll = com.blacklab.app.ui.components.rememberCollapsingTitle()
    Scaffold(Modifier.nestedScroll(titleScroll.nestedScrollConnection), topBar = {
        BlTopBar("Problem reports", onBack = { shell.back() }, showMenu = false, scrollBehavior = titleScroll, large = true)
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Kept only on this phone", style = MaterialTheme.typography.titleSmall)
                    Text("When BlackLab crashes or freezes, a report is saved here: the error and the code it came from, the app version and the phone model. " +
                        "Nothing is sent anywhere. Use Share if you want to pass a report on.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("BlackLab ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    if (entries.isNotEmpty()) Row(Modifier.padding(top = 6.dp)) {
                        TextButton(onClick = { Files.share(ctx, listOf(CrashLog.exportAll(ctx)), "BlackLab problem reports") }) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Share all")
                        }
                        TextButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Delete all")
                        }
                    }
                }
            }
            item {
                // Smoothness meter (this session, in memory only)
                var snap by remember { mutableStateOf(com.blacklab.app.core.FrameStats.snapshot()) }
                var all by remember { mutableStateOf(com.blacklab.app.core.FrameStats.overall()) }
                BlCard(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Smoothness this session", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { snap = com.blacklab.app.core.FrameStats.snapshot(); all = com.blacklab.app.core.FrameStats.overall() }) { Text("Refresh") }
                    }
                    if (all.frames < 30) Text("Use the app for a bit and come back.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Text("%.1f%% of %,d frames on time".format(all.smoothPct, all.frames), style = MaterialTheme.typography.bodyMedium)
                        snap.take(3).filter { it.smoothPct < 99.5 }.forEach { sc ->
                            Text("${sc.route}: %.1f%% smooth (%,d frames)".format(sc.smoothPct, sc.frames), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Counted on this phone only; a frame is late when it takes over 1.5× the screen's refresh time.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (entries.isEmpty()) item {
                Text("No problems recorded. 🎉", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(entries, key = { it.file.name }) { e ->
                val expanded = open == e.file.name
                BlCard(Modifier.fillMaxWidth().padding(top = 10.dp).animateContentSize(), onClick = { open = if (expanded) null else e.file.name }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when (e.kind) { "ANR" -> Icons.Rounded.HourglassBottom; "Native crash" -> Icons.Rounded.Memory; else -> Icons.Rounded.BugReport }, null,
                            tint = LocalBlColors.current.danger)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.kind + " · " + Time.dateTime(e.time), style = MaterialTheme.typography.titleSmall)
                            Text(e.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 4 else 2)
                        }
                        if (expanded) {
                            IconButton(onClick = { clipboard.setText(AnnotatedString(e.text())); shell.toast("Report copied") }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                            IconButton(onClick = { Files.share(ctx, listOf(e.file), "BlackLab problem report") }) { Icon(Icons.Rounded.Share, "Share") }
                        }
                    }
                    if (expanded) {
                        SelectionContainer {
                            Text(e.text(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 8.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(32.dp)) }
        }
    }
    if (confirmClear) ConfirmDialog("Delete all problem reports?", "They are removed from this phone.", "Delete", onDismiss = { confirmClear = false }) {
        CrashLog.clear(ctx); entries = emptyList()
    }
}
