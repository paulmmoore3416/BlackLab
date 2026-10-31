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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
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
import com.blacklab.app.files.Evaluator
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

/** CSV / TSV / XLSX viewer & editor with formulas, sorting, filtering, multiple sheets and stats. */
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
    val undo = remember { ArrayDeque<List<Sheet>>() }
    val redo = remember { ArrayDeque<List<Sheet>>() }

    LaunchedEffect(file) {
        val r = withContext(Dispatchers.IO) { runCatching { Sheets.read(file) } }
        r.onSuccess { list -> list.forEach { it.normalize(minRows = 40, minCols = 8) }; sheets.clear(); sheets += list }.onFailure { error = it.message }
        loaded = true
    }
    val sheet = sheets.getOrNull(tab)
    val eval = remember(version, tab, sheets.size) { sheet?.let { Evaluator(it) } }
    LaunchedEffect(sel, tab, version, loaded) { editText = sheet?.get(sel.first, sel.second) ?: "" }
    val dirty = version != savedVersion
    val isCsv = file.extension.lowercase() in setOf("csv", "tsv")

    fun snapshot() { undo.addLast(sheets.map { it.copy() }); if (undo.size > 40) undo.removeFirst(); redo.clear() }
    fun change(block: (Sheet) -> Unit) { val s = sheet ?: return; snapshot(); block(s); version++ }
    fun restore(from: ArrayDeque<List<Sheet>>, to: ArrayDeque<List<Sheet>>) {
        val prev = from.removeLastOrNull() ?: return
        to.addLast(sheets.map { it.copy() }); sheets.clear(); sheets += prev; tab = tab.coerceIn(0, sheets.lastIndex); version++
    }
    fun commit() { val (r, c) = sel; if (sheet != null && sheet[r, c] != editText) change { it[r, c] = editText } }

    fun save(requested: File, then: () -> Unit = {}) {
        commit()
        val outside = requested == file && !app.storage.contains(file)
        val target = if (outside) Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), file.nameWithoutExtension, file.extension) else requested
        val copy = sheets.map { s -> s.copy().also { trim(it) } }
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { FileOps.writeAtomically(target) { Sheets.write(copy, it, target.extension) }; app.storage.scan(target) }.isSuccess }
            if (ok) { if (target == file || outside) savedVersion = version; shell.toast("Saved ${target.name}" + if (isCsv && sheets.size > 1 && target.extension.lowercase() != "xlsx") " (first sheet only — save as .xlsx to keep all)" else ""); then() }
            else shell.toast("Couldn't save")
        }
    }

    BackHandler(enabled = dirty) { dialog = "exit" }

    Scaffold(topBar = {
        BlTopBar(file.name, subtitle = (if (dirty) "● Edited · " else "") + (sheet?.let { s -> val rows = s.cells.count { r -> r.any { c -> c.isNotEmpty() } }; val cols = (s.cells.maxOfOrNull { r -> r.indexOfLast { c -> c.isNotEmpty() } } ?: -1) + 1; "$rows rows × $cols columns" + if (sheets.size > 1) " · ${sheets.size} sheets" else "" } ?: "Spreadsheet"), onBack = { if (dirty) dialog = "exit" else shell.back() }, actions = {
            IconButton(enabled = undo.isNotEmpty(), onClick = { restore(undo, redo) }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(enabled = redo.isNotEmpty(), onClick = { restore(redo, undo) }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconButton(enabled = dirty, onClick = { save(file) }) { Icon(Icons.Default.Save, "Save") }
            FileMenu(file, listOf(
                MenuItem("Find / filter rows", Icons.Default.FilterAlt) { filterOpen = true },
                MenuItem(if (wide) "Narrow columns" else "Wide columns", Icons.Default.ViewColumn) { wide = !wide },
                MenuItem(if (headerRow) "First row is data" else "First row is a header", Icons.Default.TableRows) { headerRow = !headerRow },
                MenuItem("Add sheet", Icons.Default.AddBox) { snapshot(); sheets += Sheet("Sheet${sheets.size + 1}").also { it.normalize(40, 8) }; tab = sheets.lastIndex; version++ },
                MenuItem("Rename sheet", Icons.Default.DriveFileRenameOutline) { dialog = "rename" },
                MenuItem("Delete sheet", Icons.Default.Delete) { if (sheets.size > 1) { snapshot(); sheets.removeAt(tab); tab = tab.coerceAtMost(sheets.lastIndex); version++ } else shell.toast("A workbook needs at least one sheet") },
                MenuItem("Save as Excel (.xlsx)", Icons.Default.GridOn) { save(FileOps.sibling(file, "", "xlsx")) { } },
                MenuItem("Save as CSV", Icons.Default.Notes) { save(FileOps.sibling(file, "", "csv")) { } },
                MenuItem("Export as PDF", Icons.Default.PictureAsPdf) {
                    val s = sheet ?: return@MenuItem
                    app.io {
                        val out = runCatching { DocExporter.export("<h1>${s.name}</h1>" + Sheets.html(s.copy().also { trim(it) }), file.nameWithoutExtension, PageSetup(landscape = true), ExportFormat.PDF, app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS)) }.getOrNull()
                        withContext(Dispatchers.Main) { if (out != null) shell.toast("Saved ${out.name} to Exports", "Open") { openInApp(shell, app, out) } else shell.toast("Export failed") }
                    }
                },
                MenuItem("Edit as text", Icons.Default.Code) { if (isCsv) shell.go("view?path=" + encodePath(app, file) + "&text=true") else shell.toast("Only CSV/TSV files can be edited as text") },
            ))
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Column }
            if (error != null || sheet == null) { Text("Couldn't open this spreadsheet: ${error ?: "empty"}", Modifier.padding(16.dp)); return@Column }
            if (sheets.size > 1) ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, divider = {}) {
                sheets.forEachIndexed { i, s -> Tab(selected = i == tab, onClick = { commit(); tab = i; sel = 0 to 0 }, text = { Text(s.name) }) }
            }
            // formula bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Sheets.colName(sel.second) + (sel.first + 1), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
                OutlinedTextField(editText, { editText = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Value or =formula") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit(); sel = (sel.first + 1) to sel.second }))
                IconButton(onClick = { commit() }) { Icon(Icons.Default.Check, "Apply") }
            }
            if (filterOpen) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Show rows containing…") }, leadingIcon = { Icon(Icons.Default.FilterAlt, null) })
                IconButton(onClick = { filter = ""; filterOpen = false }) { Icon(Icons.Default.Close, "Clear filter") }
            }
            // quick actions
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip({ change { s -> s.cells.add(minOf(sel.first + 1, s.cells.size), MutableList(s.colCount) { "" }) } }, { Text("+ Row") }, Modifier.padding(end = 6.dp))
                AssistChip({ change { s -> s.cells.forEach { r -> r.add(minOf(sel.second + 1, r.size), "") } } }, { Text("+ Column") }, Modifier.padding(end = 6.dp))
                AssistChip({ snapshot(); sortBy(sheet, sel.second, true, headerRow, eval); version++ }, { Text("Sort A→Z") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) })
                AssistChip({ snapshot(); sortBy(sheet, sel.second, false, headerRow, eval); version++ }, { Text("Z→A") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)) })
                AssistChip({ dialog = "stats" }, { Text("Column stats") }, Modifier.padding(end = 6.dp), leadingIcon = { Icon(Icons.Default.Functions, null, Modifier.size(16.dp)) })
                AssistChip({
                    val c = Sheets.colName(sel.second); val last = sheet.rowCount
                    val start = if (headerRow) 2 else 1
                    change { s -> s[last, sel.second] = "=SUM($c$start:$c$last)" }; sel = last to sel.second
                }, { Text("Σ Sum column") }, Modifier.padding(end = 6.dp))
            }
            HorizontalDivider()
            val hScroll = rememberScrollState()
            val cellW = if (wide) 170.dp else 104.dp
            val visibleRows = remember(version, tab, filter, eval) {
                val all = (0 until sheet.rowCount).toList()
                if (filter.isBlank()) all else all.filter { r -> (headerRow && r == 0) || (0 until sheet.colCount).any { c -> eval?.display(r, c)?.contains(filter, true) == true } }
            }
            LazyColumn(Modifier.weight(1f)) {
                stickyHeader {
                    Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        HeaderCell("", 44.dp)
                        Row(Modifier.horizontalScroll(hScroll)) { (0 until sheet.colCount).forEach { c -> HeaderCell(Sheets.colName(c), cellW, selected = c == sel.second) } }
                    }
                }
                items(visibleRows.size, key = { visibleRows[it] }) { i ->
                    val r = visibleRows[i]
                    Row {
                        HeaderCell("${r + 1}", 44.dp, selected = r == sel.first)
                        GridRow(sheet, r, eval, cellW, hScroll, sel, bold = headerRow && r == 0, version = version,
                            onTap = { c -> commit(); sel = r to c },
                            onLong = { c -> commit(); sel = r to c; cellMenu = true })
                    }
                }
            }
            Box {
                DropdownMenu(cellMenu, { cellMenu = false }) {
                    val (r, c) = sel
                    DropdownMenuItem(text = { Text("Insert row above") }, onClick = { cellMenu = false; change { s -> s.cells.add(r, MutableList(s.colCount) { "" }) } })
                    DropdownMenuItem(text = { Text("Insert row below") }, onClick = { cellMenu = false; change { s -> s.cells.add(minOf(r + 1, s.cells.size), MutableList(s.colCount) { "" }) } })
                    DropdownMenuItem(text = { Text("Delete row ${r + 1}") }, onClick = { cellMenu = false; change { s -> if (s.cells.size > 1) s.cells.removeAt(r) } })
                    DropdownMenuItem(text = { Text("Insert column left") }, onClick = { cellMenu = false; change { s -> s.cells.forEach { row -> row.add(minOf(c, row.size), "") } } })
                    DropdownMenuItem(text = { Text("Insert column right") }, onClick = { cellMenu = false; change { s -> s.cells.forEach { row -> row.add(minOf(c + 1, row.size), "") } } })
                    DropdownMenuItem(text = { Text("Delete column ${Sheets.colName(c)}") }, onClick = { cellMenu = false; change { s -> s.cells.forEach { row -> if (c < row.size && s.colCount > 1) row.removeAt(c) } } })
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
        "rename" -> TextInputDialog("Rename sheet", sheet?.name ?: "", "Name", onDismiss = { dialog = null }) { n -> sheet?.let { snapshot(); it.name = n.take(31); version++ } }
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
private fun GridRow(sheet: Sheet, r: Int, eval: Evaluator?, cellW: Dp, h: ScrollState, sel: Pair<Int, Int>, bold: Boolean, version: Int, onTap: (Int) -> Unit, onLong: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(h)) {
        for (c in 0 until sheet.colCount) {
            val raw = sheet[r, c]
            val shown = if (raw.startsWith("=")) eval?.display(r, c) ?: raw else raw
            val numeric = shown.toDoubleOrNull() != null
            val isSel = sel.first == r && sel.second == c
            Box(Modifier.width(cellW).height(36.dp)
                .background(if (bold) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
                .border(if (isSel) 2.dp else 0.5.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                .combinedClickable(onClick = { onTap(c) }, onLongClick = { onLong(c) })
                .padding(horizontal = 6.dp), contentAlignment = if (numeric && !bold) Alignment.CenterEnd else Alignment.CenterStart) {
                Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                    color = if (shown.startsWith("#") && raw.startsWith("=")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    textAlign = if (numeric) TextAlign.End else TextAlign.Start)
            }
        }
    }
}

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
