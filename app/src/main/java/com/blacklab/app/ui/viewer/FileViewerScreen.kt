package com.blacklab.app.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.files.FileKind
import com.blacklab.app.files.FileKinds
import com.blacklab.app.files.FileOps
import com.blacklab.app.files.OfficeReader
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.library.MediaViewerScreen
import com.blacklab.app.ui.library.iconFor
import com.blacklab.app.ui.music.PlayFileScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Route "view?path=…": opens any file with the right BlackLab viewer/editor. */
@Composable
fun FileViewerScreen(path: String, forceText: Boolean = false, sign: Boolean = false) {
    val app = rememberApp()
    val shell = LocalShell.current
    val file = remember(path) { app.storage.resolve(path) }
    if (!file.exists()) {
        Scaffold(topBar = { BlTopBar("Not found", onBack = { shell.back() }) }) { pad ->
            EmptyState(Icons.Default.SearchOff, "File not found", "It may have been moved, renamed or deleted.", Modifier.padding(pad))
        }
        return
    }
    androidx.compose.runtime.key(path, forceText) { ViewerFor(file, path, forceText, sign) }
}

@Composable
private fun ViewerFor(file: File, path: String, forceText: Boolean, sign: Boolean = false) {
    val shell = LocalShell.current
    if (forceText) { TextEditorScreen(file); return }
    when (FileKinds.of(file)) {
        FileKind.PDF -> PdfViewerScreen(file, startSign = sign)
        FileKind.TEXT, FileKind.CODE, FileKind.MARKDOWN, FileKind.HTML -> TextEditorScreen(file)
        FileKind.CALENDAR -> CalendarFileScreen(file)
        FileKind.SHEET -> SheetScreen(file)
        FileKind.WORD, FileKind.RTF, FileKind.ODT, FileKind.SLIDES, FileKind.EPUB -> DocumentViewerScreen(file)
        FileKind.ARCHIVE -> ArchiveScreen(file)
        FileKind.IMAGE, FileKind.VIDEO -> MediaViewerScreen(path)
        FileKind.AUDIO -> PlayFileScreen(path)
        FileKind.FOLDER -> { LaunchedEffect(Unit) { shell.back() } }
        FileKind.OTHER -> UnknownFileScreen(file)
    }
}

// ------------------------------------------------------------------ Word / RTF / ODT / PowerPoint / EPUB

@Composable
private fun DocumentViewerScreen(file: File) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val kind = FileKinds.of(file)
    var zoom by rememberSaveable { mutableIntStateOf(100) }
    var busy by remember { mutableStateOf<String?>(null) }
    var chapter by rememberSaveable { mutableIntStateOf(0) }
    var toc by remember { mutableStateOf(false) }
    var theme by rememberSaveable { mutableStateOf("auto") }

    // EPUB → chapters; everything else → one page of HTML
    val chapters by produceState<List<Pair<String, String>>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when (kind) {
                    FileKind.EPUB -> OfficeReader.epubChapters(file)
                    FileKind.SLIDES -> listOf(file.nameWithoutExtension to OfficeReader.slidesHtml(OfficeReader.pptxSlides(file)))
                    else -> listOf(file.nameWithoutExtension to FileOps.toDocumentHtml(file))
                }
            }.getOrElse { listOf("Error" to "<p>Couldn't read this file: ${it.message}</p>") }
        }
    }
    val title = remember(file) { if (kind == FileKind.EPUB) OfficeReader.epubTitle(file) ?: file.nameWithoutExtension else file.nameWithoutExtension }

    fun editInDocuments() {
        busy = "Importing…"
        scope.launch {
            val id = withContext(Dispatchers.IO) { runCatching { FileOps.importAsDocument(app, file, chapters?.joinToString("<hr>") { it.second } ?: FileOps.toDocumentHtml(file)) }.getOrNull() }
            busy = null
            if (id != null) shell.go("doc/$id") else shell.toast("Couldn't import this file")
        }
    }
    fun export(format: ExportFormat) {
        busy = "Exporting…"
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val body = chapters?.joinToString("<hr>") { it.second } ?: ""
                    DocExporter.export(body, file.nameWithoutExtension, PageSetup(), format, app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS))
                }.getOrNull()
            }
            busy = null
            if (out != null) { app.storage.scan(out); shell.toast("Saved ${out.name} to Exports", "Open") { openInApp(shell, app, out) } } else shell.toast("Export failed")
        }
    }

    val list = chapters
    val dark = when (theme) { "light", "sepia" -> false; "dark" -> true; else -> com.blacklab.app.ui.theme.LocalBlColors.current.isDark }
    val css = if (theme == "sepia") "html,body{background:#F4ECD8!important;color:#3B2F1E!important}" else ""
    Scaffold(topBar = {
        BlTopBar(title, subtitle = if (kind == FileKind.EPUB && list != null) "${list.getOrNull(chapter)?.first ?: ""} · ${chapter + 1}/${list.size}" else kind.label + " · " + Files.formatSize(file.length()),
            onBack = { shell.back() }, actions = {
                if (kind == FileKind.EPUB) IconButton(onClick = { toc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Contents") }
                IconButton(onClick = { zoom = (zoom - 10).coerceAtLeast(60) }) { Icon(Icons.Default.TextDecrease, "Smaller text") }
                IconButton(onClick = { zoom = (zoom + 10).coerceAtMost(220) }) { Icon(Icons.Default.TextIncrease, "Larger text") }
                FileMenu(file, buildList {
                    add(MenuItem("Edit in Documents", Icons.Default.EditNote) { editInDocuments() })
                    add(MenuItem("Export as PDF", Icons.Default.PictureAsPdf) { export(ExportFormat.PDF) })
                    if (kind != FileKind.WORD) add(MenuItem("Export as Word", Icons.Default.Description) { export(ExportFormat.DOCX) })
                    add(MenuItem("Export as text", Icons.Default.TextSnippet) { export(ExportFormat.TXT) })
                    add(MenuItem("Theme: " + when (theme) { "auto" -> "Auto"; "light" -> "Light"; "sepia" -> "Sepia"; else -> "Dark" }, Icons.Default.Contrast) {
                        theme = when (theme) { "auto" -> "light"; "light" -> "sepia"; "sepia" -> "dark"; else -> "auto" }
                    })
                })
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (list == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else {
                Column(Modifier.fillMaxSize()) {
                    HtmlView(list.getOrNull(chapter)?.second.orEmpty(), Modifier.weight(1f).fillMaxWidth(), dark = dark, zoom = zoom, extraCss = css)
                    if (list.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(enabled = chapter > 0, onClick = { chapter-- }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous chapter") }
                        Text(list[chapter].first, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                        IconButton(enabled = chapter < list.lastIndex, onClick = { chapter++ }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next chapter") }
                    }
                }
            }
            busy?.let { BusyOverlay(it) }
        }
    }
    if (toc && list != null) AlertDialog(
        onDismissRequest = { toc = false },
        title = { Text("Contents") },
        text = {
            LazyColumn {
                items(list.size) { i ->
                    Text("${i + 1}. ${list[i].first}", Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { chapter = i; toc = false }.padding(10.dp),
                        color = if (i == chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
            }
        },
        confirmButton = { TextButton(onClick = { toc = false }) { Text("Close") } },
    )
}

// ------------------------------------------------------------------ ZIP archives

@Composable
private fun ArchiveScreen(file: File) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var dir by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val entries by produceState<List<FileOps.Entry>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { FileOps.listZip(file) }.getOrElse { emptyList() } }
    }
    // Synthesize folders for archives that don't store directory entries
    val children = remember(entries, dir) {
        val all = entries.orEmpty()
        val folders = sortedSetOf<String>()
        val files = mutableListOf<FileOps.Entry>()
        all.forEach { e ->
            if (!e.name.startsWith(dir)) return@forEach
            val rest = e.name.removePrefix(dir)
            if (rest.isEmpty()) return@forEach
            val slash = rest.indexOf('/')
            if (slash >= 0) folders += dir + rest.substring(0, slash + 1) else if (!e.dir) files += e
        }
        folders.map { FileOps.Entry(it, 0, 0, 0, true) } + files.sortedBy { it.name.lowercase() }
    }
    val totalSize = remember(entries) { entries.orEmpty().sumOf { it.size.coerceAtLeast(0) } }

    fun extract(only: Set<String>?) {
        busy = "Extracting…"
        scope.launch {
            val dest = FileOps.uniqueDir(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), file.nameWithoutExtension)
            val n = withContext(Dispatchers.IO) { runCatching { FileOps.unzip(file, dest, only) }.getOrDefault(-1) }
            busy = null
            if (n >= 0) { app.storage.scan(dest); shell.toast("Extracted $n file(s) to Imports › ${dest.name}") } else shell.toast("Couldn't extract this archive")
            selected = emptySet()
        }
    }

    Scaffold(topBar = {
        BlTopBar(file.name, subtitle = entries?.let { "${it.count { e -> !e.dir }} files · ${Files.formatSize(totalSize)} uncompressed" } ?: "Reading…", onBack = { if (dir.isNotEmpty()) dir = dir.dropLast(1).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } else shell.back() }, actions = {
            if (selected.isNotEmpty()) IconButton(onClick = { extract(selected) }) { Icon(Icons.Default.Unarchive, "Extract selected") }
            FileMenu(file, listOf(MenuItem("Extract all", Icons.Default.Unarchive) { extract(null) }))
        })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 96.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("/" + dir, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { extract(null) }) { Icon(Icons.Default.Unarchive, null); Spacer(Modifier.width(6.dp)); Text("Extract all") }
                    }
                }
                items(children, key = { it.name }) { e ->
                    val name = e.name.removePrefix(dir).trimEnd('/')
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                        if (e.dir) dir = e.name
                        else scope.launch {
                            val out = withContext(Dispatchers.IO) { runCatching { FileOps.extractEntry(file, e.name, app.storage.openedDir) }.getOrNull() }
                            if (out != null) openInApp(shell, app, out) else shell.toast("Couldn't open this entry")
                        }
                    }.padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(e.name in selected, { selected = if (it) selected + e.name else selected - e.name })
                        Icon(if (e.dir) Icons.Default.Folder else iconFor(File(name)), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!e.dir) Text("${Files.formatSize(e.size)}${if (e.compressed > 0 && e.size > 0) " · ${100 - e.compressed * 100 / e.size}% smaller" else ""}${if (e.time > 0) " · " + Time.date(e.time) else ""}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (entries == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            busy?.let { BusyOverlay(it) }
        }
    }
}

// ------------------------------------------------------------------ calendar (.ics)

@Composable
private fun CalendarFileScreen(file: File) {
    val shell = LocalShell.current
    val app = rememberApp()
    var asText by rememberSaveable { mutableStateOf(false) }
    if (asText) { TextEditorScreen(file); return }
    val events by produceState(emptyList<Map<String, String>>(), file) { value = withContext(Dispatchers.IO) { runCatching { FileOps.icsEvents(file.readText()) }.getOrDefault(emptyList()) } }
    Scaffold(topBar = {
        BlTopBar(file.nameWithoutExtension, subtitle = "${events.size} event(s)", onBack = { shell.back() }, actions = {
            FileMenu(file, listOf(MenuItem("Edit as text", Icons.Default.Code) { asText = true }))
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (events.isEmpty()) item { EmptyState(Icons.Default.Event, "No events", "This calendar file doesn't contain any events.") }
            items(events) { e ->
                com.blacklab.app.ui.components.BlCard(Modifier.fillMaxWidth()) {
                    Text(e["SUMMARY"] ?: "(No title)", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(e["DTSTART"]?.let { icsDate(it) }, e["DTEND"]?.let { "→ " + icsDate(it) }).joinToString(" "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e["LOCATION"]?.takeIf { it.isNotBlank() }?.let { Text("📍 $it", style = MaterialTheme.typography.bodyMedium) }
                    e["DESCRIPTION"]?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

private fun icsDate(v: String): String = runCatching {
    val d = v.take(8); val t = v.drop(9).take(4)
    "${d.substring(0, 4)}-${d.substring(4, 6)}-${d.substring(6, 8)}" + if (t.length == 4) " ${t.take(2)}:${t.drop(2)}" else ""
}.getOrDefault(v)

// ------------------------------------------------------------------ anything else

@Composable
private fun UnknownFileScreen(file: File) {
    val shell = LocalShell.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by rememberSaveable { mutableStateOf(false) }
    if (text) { TextEditorScreen(file); return }
    val hex by produceState("", file) {
        value = withContext(Dispatchers.IO) {
            val bytes = file.inputStream().use { s -> ByteArray(4096).let { b -> val n = s.read(b); if (n > 0) b.copyOf(n) else ByteArray(0) } }
            bytes.toList().chunked(16).mapIndexed { i, row ->
                "%08X  ".format(i * 16) + row.joinToString(" ") { "%02X".format(it) }.padEnd(48) + "  " + row.map { val c = it.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")
            }.joinToString("\n")
        }
    }
    Scaffold(topBar = { BlTopBar(file.name, subtitle = Files.formatSize(file.length()), onBack = { shell.back() }, actions = { FileMenu(file) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            Text("BlackLab doesn't have a dedicated viewer for .${file.extension.ifBlank { "?" }} files. You can view it as text, inspect the raw bytes below, or hand it to another app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { text = true }) { Icon(Icons.Default.TextSnippet, null); Spacer(Modifier.width(6.dp)); Text("Open as text") }
                FilledTonalButton(onClick = { Files.open(ctx, file) }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Open with…") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
                SelectionContainer {
                    Text(hex, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(10.dp))
                }
            }
        }
    }
}
