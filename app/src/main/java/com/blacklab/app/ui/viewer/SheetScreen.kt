package com.blacklab.app.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.files.BookEvaluator
import com.blacklab.app.files.Evaluator
import com.blacklab.app.files.XlsxBook
import com.blacklab.app.files.FileOps
import com.blacklab.app.files.Sheet
import com.blacklab.app.files.Sheets
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a cell shows: its text in the cell's number format, its style, and whether the value is Excel's last one (stale). */
private data class Shown(val text: String, val style: XlsxBook.Style, val stale: Boolean, val error: Boolean, val numeric: Boolean)

/**
 * CSV / TSV / XLSX viewer & editor. Excel workbooks open as Excel shows them — number formats, bold, fills, font colors,
 * alignment, column widths and frozen header rows — with every formula live (other sheets and names included).
 * Saving an .xlsx rewrites only the cells you changed; everything else in the file (charts, formatting, names,
 * validation) is kept byte for byte, and Excel recalculates on open.
 */
@Composable
fun SheetScreen(file: File) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val sheets = remember { mutableStateListOf<Sheet>() }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var savedVersion by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var sel by remember { mutableStateOf(0 to 0) }
    var editText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var wide by remember { mutableStateOf(false) }
    var headerRow by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var cellMenu by remember { mutableStateOf(false) }
    /** The workbook as it is on disk (Excel files only): styles, cached results, and the baseline to diff edits against. */
    var book by remember { mutableStateOf<XlsxBook.Book?>(null) }
    /** Rows/columns inserted or removed, sorting, sheets added/removed/renamed: can't be saved in place. */
    var structural by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val undo = remember { ArrayDeque<List<Sheet>>() }
    val redo = remember { ArrayDeque<List<Sheet>>() }

    val isExcel = file.extension.lowercase() in setOf("xlsx", "xlsm")
    suspend fun reload() {
        val r = withContext(Dispatchers.IO) { runCatching { if (isExcel) XlsxBook.load(file).let { b -> book = b; b.sheets.map { it.copy() } } else Sheets.read(file) } }
        r.onSuccess { list -> list.forEach { it.normalize(minRows = 40, minCols = 8) }; sheets.clear(); sheets += list; structural = false }.onFailure { error = it.message }
        loaded = true
    }
    LaunchedEffect(file) { reload() }
    val sheet = sheets.getOrNull(tab)
    val bookEval = remember(version, sheets.size) { BookEvaluator(sheets.toList(), book?.names ?: emptyMap()) }
    val eval = remember(bookEval, tab) { if (tab in sheets.indices) bookEval[tab] else null }
    /** The original sheet behind tab [i] (by name, so added or reordered sheets still find their styles). */
    fun origIndex(i: Int): Int = sheets.getOrNull(i)?.name?.let { n -> book?.sheets?.indexOfFirst { it.name == n } } ?: -1
    /** Cell edits against the file on disk, per original sheet index (only when nothing structural happened). */
    fun diffs(): Map<Int, Map<Long, String>> {
        val b = book ?: return emptyMap()
        return sheets.indices.associate { i ->
            val o = origIndex(i); val now = sheets[i]; val was = b.sheets.getOrNull(o)
            o to buildMap {
                if (was != null) for (r in 0 until maxOf(now.rowCount, was.rowCount)) for (c in 0 until maxOf(now.colCount, was.colCount)) {
                    if (now[r, c] != was[r, c]) put(XlsxBook.key(r, c), now[r, c])
                }
            }
        }.filterKeys { it >= 0 }.filterValues { it.isNotEmpty() }
    }
    val changed = remember(version, book) { book != null && (structural || diffs().isNotEmpty()) }
    fun shown(r: Int, c: Int): Shown {
        val s0 = sheet ?: return Shown("", XlsxBook.Style(), false, false, false)
        val raw = s0[r, c]
        val o = origIndex(tab)
        val st = book?.style(o, r, c) ?: XlsxBook.Style()
        val cached = book?.cached(o, r, c)
        var stale = false
        val value: String = if (raw.startsWith("=") && raw.length > 1) {
            if (!changed && cached != null) cached
            else {
                val v = eval?.value(r, c)
                if (Evaluator.isUnsupported(v) && cached != null) { stale = true; cached }
                else when (v) { is Double -> Sheets.fmt(v); is Boolean -> if (v) "TRUE" else "FALSE"; null -> ""; else -> v.toString() }
            }
        } else raw
        val text = XlsxBook.format(value, st.numFmt)
        return Shown(text, st, stale, raw.startsWith("=") && value.startsWith("#"), value.toDoubleOrNull() != null)
    }
    LaunchedEffect(sel, tab, version, loaded) { editText = sheet?.get(sel.first, sel.second) ?: "" }
    val dirty = version != savedVersion
    val isCsv = file.extension.lowercase() in setOf("csv", "tsv")

    fun snapshot() { undo.addLast(sheets.map { it.copy() }); if (undo.size > 40) undo.removeFirst(); redo.clear() }
    fun change(block: (Sheet) -> Unit) { val s = sheet ?: return; snapshot(); block(s); version++ }
    /** A change that moves cells around (insert/delete rows or columns, sort, sheets): saved as a copy, never in place. */
    fun restructure(block: (Sheet) -> Unit) { structural = true; change(block) }
    fun restore(from: ArrayDeque<List<Sheet>>, to: ArrayDeque<List<Sheet>>) {
        val prev = from.removeLastOrNull() ?: return
        to.addLast(sheets.map { it.copy() }); sheets.clear(); sheets += prev; tab = tab.coerceIn(0, sheets.lastIndex); version++
    }
    fun commit() { val (r, c) = sel; if (sheet != null && sheet[r, c] != editText) change { it[r, c] = editText } }

    fun save(requested: File, then: () -> Unit = {}) {
        commit()
        val inPlace = isExcel && book != null && requested == file
        if (inPlace && structural) { dialog = "structural"; return }
        val outside = requested == file && !app.storage.contains(file)
        val target = if (outside) Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), file.nameWithoutExtension, file.extension) else requested
        val copy = sheets.map { s -> s.copy().also { trim(it) } }
        val edits = if (inPlace) diffs() else emptyMap()
        val b = book
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    FileOps.writeAtomically(target) { if (inPlace && b != null) XlsxBook.patch(file, it, b, edits) else Sheets.write(copy, it, target.extension) }
                    app.storage.scan(target)
                }.isSuccess
            }
            if (ok) {
                if (target == file || outside) savedVersion = version
                if (inPlace && target == file) { val keepTab = tab; val keepSel = sel; reload(); tab = keepTab.coerceIn(0, maxOf(0, sheets.lastIndex)); sel = keepSel; savedVersion = version }
                shell.toast(when {
                    inPlace -> "Saved ${target.name} · ${edits.values.sumOf { it.size }} cell(s) changed, formatting kept"
                    isCsv && sheets.size > 1 && target.extension.lowercase() != "xlsx" -> "Saved ${target.name} (first sheet only — save as .xlsx to keep all)"
                    else -> "Saved ${target.name}"
                })
                then()
            } else shell.toast("Couldn't save")
        }
    }

    BackHandler(enabled = dirty) { dialog = "exit" }

    Scaffold(topBar = {
        BlTopBar(file.name, subtitle = (if (dirty) "● Edited · " else "") + (sheet?.let { s -> val rows = s.cells.count { r -> r.any { c -> c.isNotEmpty() } }; val cols = (s.cells.maxOfOrNull { r -> r.indexOfLast { c -> c.isNotEmpty() } } ?: -1) + 1; "$rows rows × $cols columns" + if (sheets.size > 1) " · ${sheets.size} sheets" else "" } ?: "Spreadsheet"), onBack = { if (dirty) dialog = "exit" else shell.back() }, actions = {
            IconButton(enabled = undo.isNotEmpty(), onClick = { restore(undo, redo) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
            IconButton(enabled = redo.isNotEmpty(), onClick = { restore(redo, undo) }) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
            IconButton(enabled = dirty, onClick = { save(file) }) { Icon(Icons.Rounded.Save, "Save") }
            FileMenu(file, listOf(
                MenuItem("Find / filter rows", Icons.Rounded.FilterAlt) { filterOpen = true },
                MenuItem(if (wide) "Narrow columns" else "Wide columns", Icons.Rounded.ViewColumn) { wide = !wide },
                MenuItem(if (headerRow) "First row is data" else "First row is a header", Icons.Rounded.TableRows) { headerRow = !headerRow },
                MenuItem("Open in Excel", Icons.AutoMirrored.Rounded.OpenInNew) { if (dirty) shell.toast("Save first, then open in Excel") else shell.toast(com.blacklab.app.office.MicrosoftApps.openInMicrosoft(ctx, file)) },
                MenuItem("Add sheet", Icons.Rounded.AddBox) { structural = true; snapshot(); sheets += Sheet("Sheet${sheets.size + 1}").also { it.normalize(40, 8) }; tab = sheets.lastIndex; version++ },
                MenuItem("Rename sheet", Icons.Rounded.DriveFileRenameOutline) { dialog = "rename" },
                MenuItem("Delete sheet", Icons.Rounded.Delete) { if (sheets.size > 1) { structural = true; snapshot(); sheets.removeAt(tab); tab = tab.coerceAtMost(sheets.lastIndex); version++ } else shell.toast("A workbook needs at least one sheet") },
                MenuItem(if (isExcel) "Save a plain copy (.xlsx)" else "Save as Excel (.xlsx)", Icons.Rounded.GridOn) { save(Files.unique(file.parentFile ?: file, file.nameWithoutExtension + " (copy)", "xlsx")) { } },
                MenuItem("Save as CSV", Icons.Rounded.Notes) { save(FileOps.sibling(file, "", "csv")) { } },
                MenuItem("Export as PDF", Icons.Rounded.PictureAsPdf) {
                    val s = sheet ?: return@MenuItem
                    app.io {
                        val out = runCatching { DocExporter.export("<h1>${s.name}</h1>" + Sheets.html(s.copy().also { trim(it) }), file.nameWithoutExtension, PageSetup(landscape = true), ExportFormat.PDF, app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS)) }.getOrNull()
                        withContext(Dispatchers.Main) { if (out != null) shell.toast("Saved ${out.name} to Exports", "Open") { openInApp(shell, app, out) } else shell.toast("Export failed") }
                    }
                },
                MenuItem("Edit as text", Icons.Rounded.Code) { if (isCsv) shell.go("view?path=" + encodePath(app, file) + "&text=true") else shell.toast("Only CSV/TSV files can be edited as text") },
            ))
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!loaded) { com.blacklab.app.ui.components.SkeletonPage(); return@Column }
            if (error != null || sheet == null) { Text("Couldn't open this spreadsheet: ${error ?: "empty"}", Modifier.padding(16.dp)); return@Column }
            if (sheets.size > 1) ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, divider = {}) {
                sheets.forEachIndexed { i, s -> Tab(selected = i == tab, onClick = { commit(); tab = i; sel = 0 to 0 }, text = { Text(s.name + if (book?.info?.getOrNull(origIndex(i))?.hidden == true) " (hidden)" else "") }) }
            }
            // formula bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Sheets.colName(sel.second) + (sel.first + 1), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                OutlinedTextField(editText, { editText = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Value or =formula") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit(); sel = (sel.first + 1) to sel.second }))
                IconButton(onClick = { commit() }) { Icon(Icons.Rounded.Check, "Apply") }
            }
            if (filterOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Show rows containing…") }, leadingIcon = { Icon(Icons.Rounded.FilterAlt, null) })
                IconButton(onClick = { filter = ""; filterOpen = false }) { Icon(Icons.Rounded.Close, "Clear filter") }
            }
            // quick actions
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip({ restructure { s -> s.cells.add(minOf(sel.first + 1, s.cells.size), MutableList(s.colCount) { "" }) } }, { Text("+ Row") }, Modifier.padding(end = 6.dp))
                AssistChip({ restructure { s -> s.cells.forEach { r -> r.add(minOf(sel.second + 1, r.size), "") } } }, { Text("+ Column") }, Modifier.padding(end = 6.dp))
                AssistChip({ structural = true; snapshot(); sortBy(sheet, sel.second, true, headerRow, eval); version++ }, { Text("Sort A→Z") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null, Modifier.size(16.dp)) })
                AssistChip({ structural = true; snapshot(); sortBy(sheet, sel.second, false, headerRow, eval); version++ }, { Text("Z→A") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null, Modifier.size(16.dp)) })
                AssistChip({ dialog = "stats" }, { Text("Column stats") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Rounded.Functions, null, Modifier.size(16.dp)) })
                AssistChip({
                    val c = Sheets.colName(sel.second); val last = sheet.rowCount
                    val start = if (headerRow) 2 else 1
                    change { s -> s[last, sel.second] = "=SUM($c$start:$c$last)" }; sel = last to sel.second
                }, { Text("Σ Sum column") }, Modifier.padding(end = 6.dp))
            }
            HorizontalDivider()
            val hScroll = rememberScrollState()
            val cellW = if (wide) 170.dp else 104.dp
            val info = book?.info?.getOrNull(origIndex(tab))
            // phone-sized by default (wide label columns capped so the numbers stay on screen); "Wide columns" shows Excel's widths
            val widthOf: (Int) -> Dp = { c -> info?.widths?.get(c)?.let { w -> if (w <= 0.0) 6.dp else if (wide) (w * 7.0).coerceIn(40.0, 480.0).dp else (w * 5.2).coerceIn(40.0, 220.0).dp } ?: cellW }
            val frozen = (info?.frozenRows ?: 0).takeIf { it in 1..4 && !changedRowsAbove(structural) } ?: 0
            val visibleRows = remember(version, tab, filter, eval) {
                val all = (0 until sheet.rowCount).toList()
                if (filter.isBlank()) all else all.filter { r -> (headerRow && r == 0) || (0 until sheet.colCount).any { c -> eval?.display(r, c)?.contains(filter, true) == true } }
            }
            LazyColumn(Modifier.weight(1f)) {
                stickyHeader {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        HeaderCell("", 44.dp)
                        Row(Modifier.horizontalScroll(hScroll)) { (0 until sheet.colCount).forEach { c -> HeaderCell(Sheets.colName(c), widthOf(c), selected = c == sel.second) } }
                    }
                    // Frozen header rows from Excel stay on screen while you scroll
                    (0 until frozen).forEach { r ->
                        Row(Modifier.background(MaterialTheme.colorScheme.surface)) {
                            HeaderCell("${r + 1}", 44.dp, selected = r == sel.first)
                            GridRow(sheet, r, ::shown, widthOf, hScroll, sel, bold = headerRow && r == 0 && book == null, version = version,
                                onTap = { c -> commit(); sel = r to c }, onLong = { c -> commit(); sel = r to c; cellMenu = true })
                        }
                    }
                }
                val bodyRows = visibleRows.filter { it >= frozen }
                items(bodyRows.size, key = { bodyRows[it] }) { i ->
                    val r = bodyRows[i]
                    Row {
                        HeaderCell("${r + 1}", 44.dp, selected = r == sel.first)
                        GridRow(sheet, r, ::shown, widthOf, hScroll, sel, bold = headerRow && r == 0 && book == null, version = version,
                            onTap = { c -> commit(); sel = r to c },
                            onLong = { c -> commit(); sel = r to c; cellMenu = true })
                    }
                }
            }
            Box {
                DropdownMenu(cellMenu, { cellMenu = false }) {
                    val (r, c) = sel
                    DropdownMenuItem(text = { Text("Insert row above") }, onClick = { cellMenu = false; restructure { s -> s.cells.add(r, MutableList(s.colCount) { "" }) } })
                    DropdownMenuItem(text = { Text("Insert row below") }, onClick = { cellMenu = false; restructure { s -> s.cells.add(minOf(r + 1, s.cells.size), MutableList(s.colCount) { "" }) } })
                    DropdownMenuItem(text = { Text("Delete row ${r + 1}") }, onClick = { cellMenu = false; restructure { s -> if (s.cells.size > 1) s.cells.removeAt(r) } })
                    DropdownMenuItem(text = { Text("Insert column left") }, onClick = { cellMenu = false; restructure { s -> s.cells.forEach { row -> row.add(minOf(c, row.size), "") } } })
                    DropdownMenuItem(text = { Text("Insert column right") }, onClick = { cellMenu = false; restructure { s -> s.cells.forEach { row -> row.add(minOf(c + 1, row.size), "") } } })
                    DropdownMenuItem(text = { Text("Delete column ${Sheets.colName(c)}") }, onClick = { cellMenu = false; restructure { s -> s.cells.forEach { row -> if (c < row.size && s.colCount > 1) row.removeAt(c) } } })
                    DropdownMenuItem(text = { Text("Clear cell") }, onClick = { cellMenu = false; change { s -> s[r, c] = "" } })
                    DropdownMenuItem(text = { Text("Clear row") }, onClick = { cellMenu = false; change { s -> s.cells.getOrNull(r)?.let { row -> row.indices.forEach { row[it] = "" } } } })
                }
            }
        }
    }

    when (dialog) {
        "exit" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Save changes?") },
            text = { Text("“${file.name}” has unsaved changes.") },
            confirmButton = { TextButton(onClick = { dialog = null; save(file) { shell.back() } }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dialog = null; shell.back() }) { Text("Discard") } },
        )
        "rename" -> TextInputDialog("Rename sheet", sheet?.name ?: "", "Name", onDismiss = { dialog = null }) { n -> sheet?.let { structural = true; snapshot(); it.name = n.take(31); version++ } }
        "structural" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Save as a new workbook?") },
            text = { Text("You inserted, removed or sorted rows or columns, or changed sheets. Saving that over “${file.name}” would lose its Excel formatting, charts and named ranges, so BlackLab saves a new workbook next to it and leaves the original untouched.") },
            confirmButton = { TextButton(onClick = { dialog = null; save(Files.unique(file.parentFile ?: file, file.nameWithoutExtension + " (edited)", "xlsx")) { } }) { Text("Save new workbook") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        "stats" -> {
            val s = sheet
            val c = sel.second
            val nums = remember(version, c) { s?.let { ((if (headerRow) 1 else 0) until it.rowCount).mapNotNull { r -> (eval?.value(r, c) as? Double) } } ?: emptyList() }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Column ${Sheets.colName(c)}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (nums.isEmpty()) Text("No numbers in this column.")
                        else listOf(
                            "Count" to nums.size.toString(), "Sum" to Sheets.fmt(nums.sum()), "Average" to Sheets.fmt(nums.average()),
                            "Median" to Sheets.fmt(nums.sorted().let { l -> if (l.size % 2 == 1) l[l.size / 2] else (l[l.size / 2 - 1] + l[l.size / 2]) / 2 }),
                            "Min" to Sheets.fmt(nums.min()), "Max" to Sheets.fmt(nums.max()),
                        ).forEach { (k, v) -> Row { Text(k, Modifier.weight(1f)); Text(v, fontWeight = FontWeight.SemiBold) } }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp, selected: Boolean = false) {
    Box(Modifier.width(width).height(32.dp).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GridRow(sheet: Sheet, r: Int, shown: (Int, Int) -> Shown, widthOf: (Int) -> Dp, h: ScrollState, sel: Pair<Int, Int>, bold: Boolean, version: Int, onTap: (Int) -> Unit, onLong: (Int) -> Unit) {
    val dark = com.blacklab.app.ui.theme.LocalBlColors.current.isDark
    Row(Modifier.horizontalScroll(h)) {
        for (c in 0 until sheet.colCount) {
            val cell = shown(r, c)
            val st = cell.style
            val isSel = sel.first == r && sel.second == c
            // Excel fills are made for white paper: in dark mode they're shown as a soft tint so text stays readable
            val fill = st.fill?.let { Color(it.toInt()) }?.let { if (dark) it.copy(alpha = 0.28f) else it }
            val ink = st.color?.let { Color(it.toInt()) }?.let { with(com.blacklab.app.ui.theme.LocalBlColors.current) { it.let { c -> if (dark) Color(com.blacklab.app.ui.theme.Contrast.ensure(c.toArgb(), page.toArgb(), com.blacklab.app.ui.theme.Contrast.AA)) else c } } }
            val align = when (st.align) { "center", "centerContinuous" -> Alignment.Center; "right" -> Alignment.CenterEnd; "left" -> Alignment.CenterStart; else -> if (cell.numeric && !bold) Alignment.CenterEnd else Alignment.CenterStart }
            Box(Modifier.width(widthOf(c)).height(36.dp)
                .background(fill ?: if (bold) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .border(if (isSel) 2.dp else 0.5.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                .combinedClickable(onClick = { onTap(c) }, onLongClick = { onLong(c) })
                .padding(horizontal = 6.dp), contentAlignment = align) {
                Text(cell.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp,
                    fontWeight = if (bold || st.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (st.italic || cell.stale) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    color = when { cell.error -> MaterialTheme.colorScheme.error; cell.stale -> MaterialTheme.colorScheme.onSurfaceVariant; ink != null -> ink; fill != null && !dark -> Color(0xFF1B1B1B); else -> MaterialTheme.colorScheme.onSurface },
                    textAlign = when (align) { Alignment.CenterEnd -> TextAlign.End; Alignment.Center -> TextAlign.Center; else -> TextAlign.Start })
            }
        }
    }
}

/** Frozen rows stop making sense once rows were inserted or removed above them. */
private fun changedRowsAbove(structural: Boolean) = structural

private fun sortBy(s: Sheet, col: Int, asc: Boolean, header: Boolean, eval: Evaluator?) {
    val head = if (header && s.cells.isNotEmpty()) listOf(s.cells.first()) else emptyList()
    val body = s.cells.drop(head.size).filter { row -> row.any { it.isNotEmpty() } }
    val blanks = s.cells.drop(head.size).filter { row -> row.all { it.isEmpty() } }
    val cmp = Comparator<MutableList<String>> { a, b ->
        val x = a.getOrElse(col) { "" }; val y = b.getOrElse(col) { "" }
        val nx = x.toDoubleOrNull(); val ny = y.toDoubleOrNull()
        when { nx != null && ny != null -> nx.compareTo(ny); x.isEmpty() -> 1; y.isEmpty() -> -1; else -> x.compareTo(y, ignoreCase = true) }
    }
    val sorted = if (asc) body.sortedWith(cmp) else body.sortedWith(cmp.reversed())
    s.cells.clear(); s.cells += head; s.cells += sorted; s.cells += blanks
}

/** Drops empty trailing rows and columns before saving. */
private fun trim(s: Sheet) {
    while (s.cells.isNotEmpty() && s.cells.last().all { it.isEmpty() }) s.cells.removeAt(s.cells.lastIndex)
    val lastCol = s.cells.maxOfOrNull { row -> row.indexOfLast { it.isNotEmpty() } } ?: -1
    s.cells.forEach { row -> while (row.size > lastCol + 1) row.removeAt(row.lastIndex) }
}
