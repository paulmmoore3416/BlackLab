package com.blacklab.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.Markdown
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.files.FileKind
import com.blacklab.app.files.FileKinds
import com.blacklab.app.files.FileOps
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val EDIT_LIMIT = 1_500_000L

/** Text, code, Markdown, HTML, JSON, CSV-as-text, logs… — view and edit with real tools. */
@Composable
fun TextEditorScreen(file: File) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val kind = FileKinds.of(file)
    val ext = file.extension.lowercase()
    val large = remember(file) { file.length() > EDIT_LIMIT }
    if (large) { LargeTextViewer(file); return }

    val state = rememberTextFieldState()
    var saved by remember { mutableStateOf<String?>(null) }
    var crlf by remember { mutableStateOf(false) }
    var bom by remember { mutableStateOf(false) }
    LaunchedEffect(file) {
        val raw = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrDefault(ByteArray(0)) }
        bom = raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
        var text = String(raw, Charsets.UTF_8).removePrefix("\uFEFF")
        crlf = text.contains("\r\n")
        text = text.replace("\r\n", "\n")
        state.setTextAndPlaceCursorAtEnd(text)
        state.edit { selection = TextRange(0) }
        state.undoState.clearHistory()
        saved = text
    }
    val loaded = saved != null
    val dirty by remember { derivedStateOf { saved != null && state.text.toString() != saved } }

    var wrap by rememberSaveable { mutableStateOf(kind == FileKind.TEXT || kind == FileKind.MARKDOWN) }
    var mono by rememberSaveable { mutableStateOf(kind != FileKind.TEXT && kind != FileKind.MARKDOWN) }
    var size by rememberSaveable { mutableIntStateOf(15) }
    var preview by rememberSaveable { mutableStateOf(kind == FileKind.MARKDOWN || kind == FileKind.HTML) }
    var findOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val vScroll = rememberScrollState()

    fun current() = state.text.toString()
    fun replaceAll(newText: String) { state.edit { replace(0, length, newText) } }
    fun transformSelection(f: (String) -> String) {
        val sel = state.selection
        if (sel.collapsed) replaceAll(f(current()))
        else state.edit { val s = minOf(sel.start, sel.end); val e = maxOf(sel.start, sel.end); val r = f(asCharSequence().substring(s, e)); replace(s, e, r); selection = TextRange(s, s + r.length) }
    }
    fun scrollTo(offset: Int) {
        val l = layout ?: return
        val line = l.getLineForOffset(offset.coerceIn(0, l.layoutInput.text.length))
        scope.launch { vScroll.animateScrollTo((l.getLineTop(line) - 160).toInt().coerceAtLeast(0)) }
    }

    fun write(requested: File, then: () -> Unit = {}) {
        val text = current()
        // Files opened from other apps live in a temporary copy — saving puts the result in BlackLab › Imports
        val outside = requested == file && !app.storage.contains(file)
        val target = if (outside) Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), file.nameWithoutExtension, file.extension.ifBlank { "txt" }) else requested
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    FileOps.writeAtomically(target) { tmp ->
                        val body = if (crlf) text.replace("\n", "\r\n") else text
                        tmp.writeBytes((if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) else ByteArray(0)) + body.toByteArray(Charsets.UTF_8))
                    }
                    app.storage.scan(target)
                }.isSuccess
            }
            if (ok) {
                if (target == file) saved = text
                if (outside) { saved = text; shell.toast("Saved to BlackLab › Imports › ${target.name}", "Open") { shell.back(); openInApp(shell, app, target) } } else shell.toast("Saved ${target.name}")
                then()
            } else shell.toast("Couldn't save")
        }
    }

    fun bodyHtml(): String = when (kind) {
        FileKind.MARKDOWN -> Markdown.toHtml(current())
        FileKind.HTML -> Jsoup.parse(current()).let { d -> d.head().select("style").outerHtml() + d.body().html() }
        else -> "<pre>" + ContentStore.escape(current()) + "</pre>"
    }

    BackHandler(enabled = dirty) { dialog = "exit" }

    val words by remember { derivedStateOf { ContentStore.wordCount(state.text.toString()) } }
    val stats by remember {
        derivedStateOf {
            val t = state.text
            val sel = state.selection.start.coerceIn(0, t.length)
            var line = 1; var col = 1
            for (i in 0 until sel) { if (t[i] == '\n') { line++; col = 1 } else col++ }
            Triple(line, col, t.count { it == '\n' } + 1)
        }
    }

    Scaffold(topBar = {
        BlTopBar(file.name, subtitle = (if (dirty) "● Edited · " else "") + kind.label + " · " + Files.formatSize(file.length()), onBack = { if (dirty) dialog = "exit" else shell.back() }, actions = {
            if (!preview) {
                IconButton(enabled = state.undoState.canUndo, onClick = { state.undoState.undo() }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                IconButton(enabled = state.undoState.canRedo, onClick = { state.undoState.redo() }) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            }
            if (kind == FileKind.MARKDOWN || kind == FileKind.HTML) IconButton(onClick = { preview = !preview }) { Icon(if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility, if (preview) "Edit" else "Preview") }
            IconButton(enabled = dirty, onClick = { write(file) }) { Icon(Icons.Rounded.Save, "Save") }
            FileMenu(file, buildList {
                add(MenuItem("Find & replace", Icons.Rounded.FindReplace) { findOpen = true; preview = false })
                add(MenuItem("Go to line", Icons.Rounded.FormatListNumbered) { dialog = "goto" })
                add(MenuItem(if (wrap) "Turn off word wrap" else "Word wrap", Icons.Rounded.WrapText) { wrap = !wrap })
                add(MenuItem(if (mono) "Proportional font" else "Monospace font", Icons.Rounded.FontDownload) { mono = !mono })
                add(MenuItem("Text tools", Icons.Rounded.Build) { dialog = "tools" })
                if (ext == "json") {
                    add(MenuItem("Format JSON", Icons.Rounded.DataObject) { formatJson(current(), 2)?.let { replaceAll(it) } ?: shell.toast("That isn't valid JSON") })
                    add(MenuItem("Minify JSON", Icons.Rounded.Compress) { formatJson(current(), 0)?.let { replaceAll(it) } ?: shell.toast("That isn't valid JSON") })
                }
                add(MenuItem("Save as…", Icons.Rounded.SaveAs) { dialog = "saveas" })
                add(MenuItem("Convert to BlackLab document", Icons.Rounded.EditNote) {
                    app.io {
                        val id = runCatching { FileOps.importAsDocument(app, file, if (kind == FileKind.MARKDOWN) Markdown.toHtml(current()) else if (kind == FileKind.HTML) Jsoup.parse(current()).body().html() else ContentStore.textToHtml(current())) }.getOrNull()
                        withContext(Dispatchers.Main) { if (id != null) shell.go("doc/$id") else shell.toast("Couldn't convert") }
                    }
                })
                add(MenuItem("Export as PDF", Icons.Rounded.PictureAsPdf) {
                    val html = bodyHtml()
                    app.io {
                        val out = runCatching { DocExporter.export(html, file.nameWithoutExtension, PageSetup(), ExportFormat.PDF, app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS)) }.getOrNull()
                        withContext(Dispatchers.Main) { if (out != null) shell.toast("Saved ${out.name} to Exports", "Open") { openInApp(shell, app, out) } else shell.toast("Export failed") }
                    }
                })
                if (ext == "csv" || ext == "tsv") add(MenuItem("Open as spreadsheet", Icons.Rounded.TableChart) { shell.back(); shell.go("view?path=" + encodePath(app, file)) })
                if (dirty) add(MenuItem("Revert changes", Icons.Rounded.Restore) { saved?.let { replaceAll(it) } })
            })
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (findOpen && !preview) FindBar(
                onFind = { term, opts, dir -> findNext(current(), term, opts, state.selection, dir)?.let { r -> state.edit { selection = r }; scrollTo(r.start); true } ?: false },
                onReplace = { term, rep, opts ->
                    val sel = state.selection
                    val selected = current().substring(minOf(sel.start, sel.end), maxOf(sel.start, sel.end))
                    if (!sel.collapsed && matches(selected, term, opts)) state.edit { replace(minOf(sel.start, sel.end), maxOf(sel.start, sel.end), replacement(selected, term, rep, opts)) }
                    findNext(current(), term, opts, state.selection, 1)?.let { r -> state.edit { selection = r }; scrollTo(r.start) }
                },
                onReplaceAll = { term, rep, opts -> val (t, n) = replaceEvery(current(), term, rep, opts); if (n > 0) replaceAll(t); shell.toast("Replaced $n") },
                count = { term, opts -> countMatches(current(), term, opts) },
                onClose = { findOpen = false },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (preview) HtmlView(bodyHtml(), Modifier.fillMaxSize())
                else if (loaded) {
                    val style = TextStyle(fontFamily = if (mono) FontFamily.Monospace else com.blacklab.app.ui.theme.readingStyle(TextStyle(fontFamily = FontFamily.Default)).fontFamily, fontSize = size.sp, lineHeight = (size * 1.45f).sp, color = MaterialTheme.colorScheme.onSurface)
                    val screenW = LocalConfiguration.current.screenWidthDp.dp
                    Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                        if (!wrap) {
                            Text((1..stats.third).joinToString("\n"), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
                                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 200.dp))
                        }
                        val field = Modifier.padding(start = 10.dp, end = 12.dp, top = 12.dp, bottom = 200.dp)
                        Box(if (wrap) Modifier.weight(1f) else Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            BasicTextField(
                                state = state,
                                modifier = if (wrap) field.fillMaxWidth() else field.wrapContentWidth(Alignment.Start, unbounded = true).widthIn(min = screenW),
                                textStyle = style,
                                lineLimits = TextFieldLineLimits.MultiLine(),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(capitalization = if (kind == FileKind.TEXT || kind == FileKind.MARKDOWN) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                                    autoCorrectEnabled = kind == FileKind.TEXT || kind == FileKind.MARKDOWN, keyboardType = KeyboardType.Text),
                                onTextLayout = { get -> layout = get() },
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ln ${stats.first}, Col ${stats.second} · ${stats.third} lines · $words words · ${if (crlf) "CRLF" else "LF"} · UTF-8",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { size = (size - 1).coerceAtLeast(9) }) { Icon(Icons.Rounded.TextDecrease, "Smaller") }
                IconButton(onClick = { size = (size + 1).coerceAtMost(30) }) { Icon(Icons.Rounded.TextIncrease, "Larger") }
            }
        }
    }

    when (dialog) {
        "exit" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Save changes?") },
            text = { Text("“${file.name}” has unsaved changes.") },
            confirmButton = { TextButton(onClick = { dialog = null; write(file) { shell.back() } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dialog = null; shell.back() }) { Text("Discard") } },
        )
        "goto" -> TextInputDialog("Go to line", "", "Line (1–${stats.third})", confirmLabel = "Go", keyboardType = KeyboardType.Number, onDismiss = { dialog = null }) { v ->
            val n = v.toIntOrNull()?.coerceIn(1, stats.third) ?: return@TextInputDialog
            val t = current(); var off = 0; repeat(n - 1) { off = t.indexOf('\n', off).let { i -> if (i < 0) t.length else i + 1 } }
            state.edit { selection = TextRange(off) }; scrollTo(off)
        }
        "saveas" -> TextInputDialog("Save as", file.name, "File name", onDismiss = { dialog = null }) { n ->
            val name = Files.sanitize(n)
            val target = Files.unique(file.parentFile!!, name.substringBeforeLast('.'), name.substringAfterLast('.', file.extension))
            write(target) { shell.back(); openInApp(shell, app, target) }
        }
        "tools" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Text tools") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Applies to the selection, or the whole file when nothing is selected.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf<Pair<String, (String) -> String>>(
                        "UPPERCASE" to { it.uppercase() },
                        "lowercase" to { it.lowercase() },
                        "Title Case" to { s -> s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { w -> w.lowercase().replaceFirstChar { it.titlecase() } } },
                        "Sort lines A→Z" to { s -> s.lines().sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n") },
                        "Sort lines Z→A" to { s -> s.lines().sortedWith(String.CASE_INSENSITIVE_ORDER).reversed().joinToString("\n") },
                        "Remove duplicate lines" to { s -> s.lines().distinct().joinToString("\n") },
                        "Remove blank lines" to { s -> s.lines().filter { it.isNotBlank() }.joinToString("\n") },
                        "Trim trailing spaces" to { s -> s.lines().joinToString("\n") { it.trimEnd() } },
                        "Number the lines" to { s -> s.lines().mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n") },
                        "Bulleted list" to { s -> s.lines().joinToString("\n") { if (it.isBlank()) it else "• $it" } },
                        "Join lines" to { s -> s.lines().filter { it.isNotBlank() }.joinToString(" ") { it.trim() } },
                    ).forEach { (label, f) ->
                        TextButton(onClick = { transformSelection(f); dialog = null }, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.fillMaxWidth()) }
                    }
                    TextButton(onClick = {
                        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        state.edit { val s = minOf(selection.start, selection.end); val e = maxOf(selection.start, selection.end); replace(s, e, stamp); selection = TextRange(s + stamp.length) }
                        dialog = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Insert date & time", Modifier.fillMaxWidth()) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
    }
}

// ------------------------------------------------------------------ find & replace

data class FindOptions(val matchCase: Boolean = false, val regex: Boolean = false, val wholeWord: Boolean = false)

private fun pattern(term: String, o: FindOptions): Regex? = runCatching {
    var p = if (o.regex) term else Regex.escape(term)
    if (o.wholeWord) p = "\\b$p\\b"
    Regex(p, if (o.matchCase) setOf(RegexOption.MULTILINE) else setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
}.getOrNull()

private fun findNext(text: String, term: String, o: FindOptions, sel: TextRange, dir: Int): TextRange? {
    if (term.isEmpty()) return null
    val re = pattern(term, o) ?: return null
    val all = re.findAll(text).filter { it.value.isNotEmpty() }.toList()
    if (all.isEmpty()) return null
    val m = if (dir > 0) all.firstOrNull { it.range.first >= maxOf(sel.start, sel.end) && !(it.range.first == minOf(sel.start, sel.end) && !sel.collapsed) } ?: all.first()
    else all.lastOrNull { it.range.first < minOf(sel.start, sel.end) } ?: all.last()
    return TextRange(m.range.first, m.range.last + 1)
}

private fun matches(s: String, term: String, o: FindOptions) = pattern(term, o)?.matches(s) == true
private fun replacement(s: String, term: String, rep: String, o: FindOptions) = if (o.regex) pattern(term, o)?.replace(s, rep) ?: rep else rep
private fun countMatches(text: String, term: String, o: FindOptions) = if (term.isEmpty()) 0 else pattern(term, o)?.findAll(text)?.count() ?: 0
private fun replaceEvery(text: String, term: String, rep: String, o: FindOptions): Pair<String, Int> {
    val re = pattern(term, o) ?: return text to 0
    val n = re.findAll(text).count()
    return (if (o.regex) re.replace(text, rep) else re.replace(text, Regex.escapeReplacement(rep))) to n
}

@Composable
fun FindBar(
    onFind: (String, FindOptions, Int) -> Boolean,
    onReplace: ((String, String, FindOptions) -> Unit)?,
    onReplaceAll: ((String, String, FindOptions) -> Unit)?,
    count: (String, FindOptions) -> Int,
    onClose: () -> Unit,
) {
    var term by rememberSaveable { mutableStateOf("") }
    var rep by rememberSaveable { mutableStateOf("") }
    var opts by remember { mutableStateOf(FindOptions()) }
    var showReplace by rememberSaveable { mutableStateOf(false) }
    val n = remember(term, opts) { count(term, opts) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(term, { term = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Find") },
                supportingText = null, trailingIcon = { if (term.isNotEmpty()) Text(if (n == 0) "0" else "$n", style = MaterialTheme.typography.labelMedium) })
            IconButton(onClick = { if (!onFind(term, opts, -1)) Unit }) { Icon(Icons.Rounded.KeyboardArrowUp, "Previous") }
            IconButton(onClick = { onFind(term, opts, 1) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Next") }
            if (onReplace != null) IconButton(onClick = { showReplace = !showReplace }) { Icon(Icons.Rounded.FindReplace, "Replace") }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close") }
        }
        if (showReplace && onReplace != null) Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(rep, { rep = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Replace with") })
            TextButton(onClick = { onReplace(term, rep, opts) }) { Text("Replace") }
            TextButton(onClick = { onReplaceAll?.invoke(term, rep, opts) }) { Text("All") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(opts.matchCase, { opts = opts.copy(matchCase = !opts.matchCase) }, label = { Text("Match case") })
            Spacer(Modifier.width(6.dp))
            FilterChip(opts.wholeWord, { opts = opts.copy(wholeWord = !opts.wholeWord) }, label = { Text("Whole word") })
            Spacer(Modifier.width(6.dp))
            FilterChip(opts.regex, { opts = opts.copy(regex = !opts.regex) }, label = { Text("Regex") })
        }
    }
}

private fun formatJson(s: String, indent: Int): String? = runCatching {
    when (val v = JSONTokener(s.trim()).nextValue()) {
        is JSONObject -> if (indent > 0) v.toString(indent) else v.toString()
        is JSONArray -> if (indent > 0) v.toString(indent) else v.toString()
        else -> null
    }
}.getOrNull()

// ------------------------------------------------------------------ very large files

/** Read-only, line-virtualized viewer for logs and exports too big to edit comfortably. */
@Composable
private fun LargeTextViewer(file: File) {
    val shell = LocalShell.current
    var lines by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(file) { lines = withContext(Dispatchers.IO) { runCatching { file.bufferedReader().useLines { it.take(400_000).toList() } }.getOrDefault(emptyList()) } }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var findOpen by remember { mutableStateOf(false) }
    var hit by remember { mutableIntStateOf(-1) }
    Scaffold(topBar = {
        BlTopBar(file.name, subtitle = "Large file · read-only · ${Files.formatSize(file.length())}", onBack = { shell.back() }, actions = {
            IconButton(onClick = { findOpen = !findOpen }) { Icon(Icons.Rounded.Search, "Find") }
            FileMenu(file)
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (findOpen) FindBar(
                onFind = { term, o, dir ->
                    val re = pattern(term, o); val l = lines.orEmpty()
                    val idx = if (re == null) -1 else if (dir > 0) ((hit + 1 until l.size).firstOrNull { re.containsMatchIn(l[it]) } ?: (0..hit).firstOrNull { it >= 0 && re.containsMatchIn(l[it]) } ?: -1)
                    else ((hit - 1 downTo 0).firstOrNull { re.containsMatchIn(l[it]) } ?: -1)
                    if (idx >= 0) { hit = idx; scope.launch { list.scrollToItem(idx) } }
                    idx >= 0
                },
                onReplace = null, onReplaceAll = null,
                count = { term, o -> pattern(term, o)?.let { re -> lines.orEmpty().count { re.containsMatchIn(it) } } ?: 0 },
                onClose = { findOpen = false },
            )
            SelectionContainer {
                LazyColumn(state = list, modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    itemsIndexed(lines.orEmpty()) { i, l ->
                        Row(Modifier.background(if (i == hit) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text("${i + 1}".padStart(6), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(l, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
