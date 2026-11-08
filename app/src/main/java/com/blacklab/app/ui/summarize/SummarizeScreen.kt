package com.blacklab.app.ui.summarize

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blacklab.app.assistant.Nano
import com.blacklab.app.assistant.VoiceInput
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.docs.Markdown
import com.blacklab.app.summarize.Summarizer
import com.blacklab.app.summarize.SummarySources
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private fun kindIcon(k: SummarySources.Kind): ImageVector = when (k) {
    SummarySources.Kind.DOCUMENT -> Icons.Rounded.Description
    SummarySources.Kind.NOTE -> Icons.Rounded.StickyNote2
    SummarySources.Kind.PAGE -> Icons.Rounded.Lightbulb
    SummarySources.Kind.JOURNAL -> Icons.Rounded.AutoStories
    SummarySources.Kind.EVENT -> Icons.Rounded.Event
    SummarySources.Kind.TASK -> Icons.Rounded.TaskAlt
    SummarySources.Kind.RECORDING -> Icons.Rounded.Mic
    SummarySources.Kind.DICTATION -> Icons.Rounded.RecordVoiceOver
    SummarySources.Kind.FILE -> Icons.Rounded.InsertDriveFile
}

/**
 * Pick any mix of items — documents, notes, notebook pages, journal entries, events, tasks,
 * recordings, dictations, files — and get the summary you ask for.
 */
@Composable
fun SummarizeScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var catalog by remember { mutableStateOf<List<SummarySources.Item>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Summarizer.Result?>(null) }
    var style by rememberSaveable { mutableStateOf(Summarizer.Style.KEY_POINTS) }
    var length by rememberSaveable { mutableStateOf(Summarizer.Length.MEDIUM) }
    var custom by rememberSaveable { mutableStateOf("") }
    val speaking by app.speaker.speaking.collectAsState()

    LaunchedEffect(s.activeProfile) { catalog = SummarySources.catalog(app, s.activeProfile); selected.clear() }
    DisposableEffect(Unit) { onDispose { app.speaker.stop() } }

    val all = catalog.orEmpty()
    val shown = all.filter { (kind == null || it.kind.name == kind) && (query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true)) }
    val zone = ZoneId.systemDefault()
    fun selectRange(from: LocalDate, to: LocalDate) {
        all.filter { val d = java.time.Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate(); d in from..to && it.kind != SummarySources.Kind.FILE }
            .forEach { if (it.key !in selected) selected += it.key }
    }

    fun run() {
        val items = all.filter { it.key in selected }
        showOptions = false
        result = null
        progress = "Reading ${items.size} item${if (items.size == 1) "" else "s"}…"
        scope.launch {
            val sources = items.mapNotNull { SummarySources.load(app, it) }
            // A typed or spoken request decides the style; Nano follows it word for word
            val wanted = custom.trim()
            val st = when {
                wanted.isBlank() -> style
                Nano.ready && s.asstUseNano -> Summarizer.Style.CUSTOM
                else -> Summarizer.styleFrom(wanted) ?: style
            }
            val len = Summarizer.lengthFrom(wanted) ?: length
            val r = Summarizer.run(Summarizer.Request(sources, st, len, wanted), s.asstUseNano) { p -> progress = p }
            progress = null
            result = r
        }
    }

    Scaffold(
        topBar = {
            BlTopBar("Summarize", subtitle = if (result != null) result!!.engine else "${selected.size} selected", onBack = { if (result != null || progress != null) { result = null; progress = null } else shell.back() }) {
                if (result == null && selected.isNotEmpty()) TextButton(onClick = { selected.clear() }) { Text("Clear") }
            }
        },
        bottomBar = {
            if (result == null && progress == null && selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(onClick = { showOptions = true }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp)) {
                        Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.size(8.dp)); Text("Summarize ${selected.size} item${if (selected.size == 1) "" else "s"}")
                    }
                }
            }
        },
    ) { pad ->
        val r = result
        when {
            progress != null -> Column(Modifier.padding(pad).fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text(progress!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r != null -> ResultView(Modifier.padding(pad), r, speaking,
                onSpeak = { if (speaking) app.speaker.stop() else app.speaker.speak(r.text) },
                onCopy = { clipboard.setText(AnnotatedString(r.text)); shell.toast("Copied") },
                onShare = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, r.title).putExtra(Intent.EXTRA_TEXT, r.text), "Share summary"))
                },
                onSaveDoc = {
                    app.io {
                        val html = "<h1 class=\"title\">${ContentStore.escape(r.title)}</h1><p><em>${ContentStore.escape(Time.dateTime(Time.now()))} · ${r.sourceCount} items · ${ContentStore.escape(r.engine)}</em></p>" +
                            Markdown.toHtml(toMarkdown(r.text))
                        val rel = app.content.writeDocument(s.activeProfile, r.title, "Summaries", "", html)
                        val text = ContentStore.plainText(html)
                        val id = app.db.documents().insert(DocumentEntity(profile = s.activeProfile.name, title = r.title, folder = "Summaries", filePath = rel,
                            preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), tags = "summary"))
                        shell.toast("Saved to Documents › Summaries", "Open") { shell.go("doc/$id") }
                    }
                },
                onSaveNote = {
                    app.io {
                        val id = NoteOps.save(app, NoteEntity(profile = s.activeProfile.name, title = r.title.take(120), body = r.text, labels = ",summary,"))
                        shell.toast("Saved as a note", "Open") { shell.go("note/$id") }
                    }
                },
                onTasks = if (r.actions.isEmpty()) null else {
                    {
                        app.io {
                            r.actions.take(20).forEach { a -> TaskOps.save(app, TaskEntity(profile = s.activeProfile.name, title = a.take(140), notes = "From: ${r.title}", tags = "summary")) }
                            shell.toast("${minOf(20, r.actions.size)} task(s) added", "To-Do") { shell.go("tasks") }
                        }
                    }
                },
                onAgain = { showOptions = true },
            )
            catalog == null -> Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(Modifier.padding(32.dp).fillMaxWidth())
            }
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), placeholder = { Text("Search your items") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp),
                        trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
                }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(kind == null, { kind = null }, label = { Text("All") }) }
                        items(SummarySources.Kind.entries.filter { k -> all.any { it.kind == k } }) { k ->
                            FilterChip(kind == k.name, { kind = if (kind == k.name) null else k.name }, label = { Text(k.label) }, leadingIcon = { Icon(kindIcon(k), null, Modifier.size(16.dp)) })
                        }
                    }
                }
                item {
                    LazyRow(Modifier.padding(top = 4.dp), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { TextButton(onClick = { selectRange(Time.today(), Time.today()) }) { Text("+ Today") } }
                        item { TextButton(onClick = { selectRange(Time.today().minusDays(6), Time.today()) }) { Text("+ Past 7 days") } }
                        item { TextButton(onClick = { selectRange(Time.today(), Time.today().plusDays(6)) }) { Text("+ Next 7 days") } }
                        item { TextButton(onClick = { shown.forEach { if (it.key !in selected) selected += it.key } }) { Text("Select shown") } }
                    }
                }
                if (shown.isEmpty()) item { EmptyState(Icons.Rounded.Inbox, "Nothing here", "Documents, notes, pages, journal entries, events, tasks, recordings, dictations and files show up here.") }
                items(shown, key = { it.key }) { item ->
                    val on = item.key in selected
                    Row(Modifier.fillMaxWidth().clickable { if (on) selected.remove(item.key) else selected += item.key }.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, { c -> if (c) selected += item.key else selected.remove(item.key) })
                        Icon(kindIcon(item.kind), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.kind.label, item.subtitle).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Time.relativeDay(java.time.Instant.ofEpochMilli(item.time).atZone(zone).toLocalDate()), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp))
                    }
                }
            }
        }
    }

    if (showOptions) OptionsSheet(style, length, custom, onStyle = { style = it }, onLength = { length = it }, onCustom = { custom = it },
        onDismiss = { showOptions = false }, onRun = { run() })
}

@Composable
private fun OptionsSheet(
    style: Summarizer.Style, length: Summarizer.Length, custom: String,
    onStyle: (Summarizer.Style) -> Unit, onLength: (Summarizer.Length) -> Unit, onCustom: (String) -> Unit,
    onDismiss: () -> Unit, onRun: () -> Unit,
) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var listening by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceInput(ctx, onPartial = { onCustom(it) }, onFinal = { listening = false; onCustom(it) }, onError = { listening = false; shell.toast(it) })
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { listening = true; voice.start() } }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    val nano = Nano.ready
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("What kind of summary?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            ChipFlow {
                Summarizer.Style.entries.filter { it != Summarizer.Style.CUSTOM }.forEach { st ->
                    FilterChip(style == st && custom.isBlank(), { onStyle(st); onCustom("") }, label = { Text(st.label) })
                }
            }
            Text(if (custom.isNotBlank()) "Using your request below" else style.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            Text("Length", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Summarizer.Length.entries.forEach { l -> FilterChip(length == l, { onLength(l) }, label = { Text(l.label) }) }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(custom, onCustom, Modifier.fillMaxWidth(), label = { Text("Or say exactly what you want") },
                placeholder = { Text("e.g. a status update for my VP with risks first") }, minLines = 2, maxLines = 4,
                trailingIcon = {
                    IconButton(onClick = {
                        if (listening) { voice.stop(); listening = false }
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPerm.launch(Manifest.permission.RECORD_AUDIO)
                        else { listening = true; voice.start() }
                    }) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, "Speak") }
                })
            Text(if (nano) "Gemini Nano follows your request word for word, on the phone." else "Without Gemini Nano, your request picks the closest built-in style.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.size(8.dp)); Text("Summarize") }
        }
    }
}

@Composable
private fun ResultView(
    modifier: Modifier, r: Summarizer.Result, speaking: Boolean,
    onSpeak: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onSaveDoc: () -> Unit, onSaveNote: () -> Unit, onTasks: (() -> Unit)?, onAgain: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(r.title, style = MaterialTheme.typography.headlineSmall)
            Text("${r.sourceCount} item${if (r.sourceCount == 1) "" else "s"} · ${r.style.label} · ${r.engine}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ChipFlow {
                OutlinedButton(onClick = onSpeak) { Icon(if (speaking) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(if (speaking) "Stop" else "Read aloud") }
                OutlinedButton(onClick = onCopy) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Copy") }
                OutlinedButton(onClick = onShare) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Share") }
            }
        }
        item {
            BlCard(Modifier.fillMaxWidth()) { SelectionContainer { SummaryText(r.text) } }
        }
        item {
            ChipFlow {
                Button(onClick = onSaveDoc) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Save as document") }
                OutlinedButton(onClick = onSaveNote) { Icon(Icons.Rounded.StickyNote2, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Save as note") }
                if (onTasks != null) OutlinedButton(onClick = onTasks) { Icon(Icons.Rounded.AddTask, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Make tasks") }
                TextButton(onClick = onAgain) { Text("Try another style") }
            }
        }
    }
}

/** Headings ("Something:" on its own line) in bold, "- " bullets as bullets. */
@Composable
private fun SummaryText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { raw ->
            val l = raw.trimEnd()
            when {
                l.isBlank() -> Spacer(Modifier.height(6.dp))
                l.startsWith("- ") || l.startsWith("• ") || l.startsWith("* ") -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(l.drop(2).replace("**", ""), style = com.blacklab.app.ui.theme.readingStyle(MaterialTheme.typography.bodyMedium))
                }
                l.endsWith(":") && l.length < 60 || l.startsWith("#") -> Text(l.trimStart('#', ' ').replace("**", ""), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp))
                else -> Text(l.replace("**", ""), style = com.blacklab.app.ui.theme.readingStyle(MaterialTheme.typography.bodyMedium))
            }
        }
    }
}

/** Summary text → Markdown for the document editor (headings become ##, bullets stay bullets). */
internal fun toMarkdown(text: String): String = text.lines().joinToString("\n") { l ->
    val t = l.trimEnd()
    when {
        t.endsWith(":") && t.length < 60 && !t.startsWith("-") -> "## " + t.removeSuffix(":")
        t.startsWith("• ") -> "- " + t.drop(2)
        else -> t
    }
}
