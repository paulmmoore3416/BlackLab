package com.blacklab.app.ui.documents

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.AppContainer
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.docs.DocExporter
import com.blacklab.app.docs.DocImporter
import com.blacklab.app.docs.ExportFormat
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.docs.Printer
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.ScanDoneDialog
import com.blacklab.app.ui.components.ScanResult
import com.blacklab.app.ui.components.Shell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared export/share action used by documents and journal entries. */
object DocActions {
    fun export(app: AppContainer, ctx: Context, shell: Shell, profile: Profile, title: String, bodyHtml: String, page: PageSetup, format: ExportFormat, share: Boolean) {
        app.io {
            val result = runCatching { DocExporter.export(bodyHtml, title, page, format, app.storage.dir(profile, Folder.EXPORTS)) }
            withContext(Dispatchers.Main) {
                result.onSuccess { f ->
                    app.storage.scan(f)
                    if (share) Files.share(ctx, listOf(f), title)
                    else shell.toast("Saved Exports/${f.name}", "Open") { Files.open(ctx, f) }
                }.onFailure { shell.toast("Export failed: ${it.message}") }
            }
        }
    }

    fun pageOf(d: DocumentEntity) = PageSetup(d.pageSize, d.landscape, d.marginMm)
}

@Composable
fun ExportSheet(title: String, onDismiss: () -> Unit, onPrint: (() -> Unit)? = null, onPick: (ExportFormat, Boolean) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Export “$title”", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Saved to BlackLab › Exports, or shared directly.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            ExportFormat.entries.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (f) { ExportFormat.PDF -> Icons.Default.PictureAsPdf; ExportFormat.DOCX -> Icons.Default.Description; ExportFormat.HTML -> Icons.Default.Language; ExportFormat.MD -> Icons.Default.Code; ExportFormat.TXT -> Icons.Default.TextSnippet },
                        null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(f.label, Modifier.weight(1f))
                    TextButton(onClick = { onPick(f, false); onDismiss() }) { Text("Save") }
                    TextButton(onClick = { onPick(f, true); onDismiss() }) { Text("Share") }
                }
            }
            if (onPrint != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Print, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Text("Print or Save as PDF (system)", Modifier.weight(1f))
                    TextButton(onClick = { onPrint(); onDismiss() }) { Text("Print") }
                }
            }
        }
    }
}

@Composable
fun DocumentsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    val profile = s.activeProfile
    val docs by remember(profile) { app.db.documents().observe(profile.name) }.collectAsState(emptyList())
    var folder by rememberSaveable { mutableStateOf("ALL") }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var newSheet by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<DocumentEntity?>(null) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.io {
            val r = runCatching { DocImporter.import(ctx, uri) }.getOrNull()
            if (r == null) { withContext(Dispatchers.Main) { shell.toast("Couldn't import that file") }; return@io }
            val rel = app.content.writeDocument(profile, r.title, "Imported", "", r.html)
            val text = ContentStore.plainText(r.html)
            val id = app.db.documents().insert(DocumentEntity(profile = profile.name, title = r.title, folder = "Imported", filePath = rel,
                preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), pageSize = s.defaultPageSize))
            withContext(Dispatchers.Main) { shell.go("doc/$id") }
        }
    }
    val scanner = rememberDocumentScanner(app, profile) { scanResult = it }

    val folders = remember(docs) { docs.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    val shown = remember(docs, folder, query, s.docSort) {
        docs.filter {
            (folder == "ALL" || (folder == "PINNED" && it.pinned) || it.folder == folder) &&
                (query.isBlank() || it.title.contains(query, true) || it.preview.contains(query, true) || it.tags.contains(query, true))
        }.let { list ->
            when (s.docSort) {
                "TITLE" -> list.sortedBy { it.title.lowercase() }
                "CREATED" -> list.sortedByDescending { it.createdAt }
                "WORDS" -> list.sortedByDescending { it.wordCount }
                else -> list.sortedWith(compareByDescending<DocumentEntity> { it.pinned }.thenByDescending { it.updatedAt })
            }
        }
    }

    Scaffold(
        topBar = {
            BlTopBar("Documents", subtitle = "${docs.size} documents · ${app.settings.profileName(profile)}", actions = {
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "Search") }
                IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.Default.ViewList else Icons.Default.GridView, "Layout") }
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("UPDATED" to "Last modified", "CREATED" to "Date created", "TITLE" to "Title", "WORDS" to "Length").forEach { (k, l) ->
                            DropdownMenuItem(text = { Text(l) }, onClick = { app.settings.update { putString(Keys.DOC_SORT, k) }; sortMenu = false },
                                trailingIcon = { if (s.docSort == k) Icon(Icons.Default.Check, null) })
                        }
                    }
                }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSheet = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searching) OutlinedTextField(query, { query = it }, placeholder = { Text("Search titles and text") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(folder == "ALL", { folder = "ALL" }, label = { Text("All") })
                FilterChip(folder == "PINNED", { folder = "PINNED" }, label = { Text("📌 Pinned") })
                folders.forEach { f -> FilterChip(folder == f, { folder = f }, label = { Text("📁 $f") }) }
                FilterChip(false, { importer.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/*", "text/html", "text/markdown", "application/rtf")) },
                    label = { Text("Import") }, leadingIcon = { Icon(Icons.Default.Upload, null, Modifier.size(16.dp)) })
                FilterChip(false, { scanner() }, label = { Text("Scan") }, leadingIcon = { Icon(Icons.Default.DocumentScanner, null, Modifier.size(16.dp)) })
            }
            if (shown.isEmpty()) {
                EmptyState(Icons.Default.Description, if (docs.isEmpty()) "No documents yet" else "No matches",
                    if (docs.isEmpty()) "Create one from a template — memos, minutes, work orders, SOPs, inspections — or import a Word file." else "Try a different search or folder.",
                    Modifier.padding(top = 40.dp))
            } else {
                LazyVerticalGrid(
                    columns = if (grid) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(shown, key = { it.id }) { d -> DocCard(d, grid, onClick = { shell.go("doc/${d.id}") }, onMenu = { menuFor = d }) }
                }
            }
        }
    }

    if (newSheet) TemplateSheet(professionalFirst = profile == Profile.PROFESSIONAL, onDismiss = { newSheet = false }) { t ->
        newSheet = false; shell.go("doc/0?template=${t.id}")
    }
    menuFor?.let { d -> DocMenu(d, folders) { menuFor = null } }
    scanResult?.let { r -> ScanDoneDialog(r) { scanResult = null } }
}

@Composable
private fun DocCard(d: DocumentEntity, grid: Boolean, onClick: () -> Unit, onMenu: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).pressable(onClick = onClick, onLongClick = onMenu)) {
        Column(Modifier.padding(12.dp)) {
            if (grid) {
                // mini "page" preview
                Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color(0xFFF7F7F8)).padding(8.dp)) {
                    Text(d.preview.ifBlank { " " }, fontSize = 8.sp, lineHeight = 10.sp, color = androidx.compose.ui.graphics.Color(0xFF333333), overflow = TextOverflow.Clip)
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (d.pinned) Icon(Icons.Default.PushPin, null, tint = Amber, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                        Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${Time.ago(d.updatedAt)} · ${d.wordCount} words", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!grid && d.preview.isNotBlank()) Text(d.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (d.folder.isNotBlank()) Pill(d.folder, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "More", Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
fun TemplateSheet(professionalFirst: Boolean, onDismiss: () -> Unit, onPick: (DocTemplate) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ordered = Templates.all.sortedBy { if (it.id == "blank") 0 else if (it.professional == professionalFirst) 1 else 2 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Text("New document", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.height(560.dp)) {
            items(ordered, key = { it.id }) { t ->
                Surface(onClick = { onPick(t) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(14.dp).height(96.dp)) {
                        Text(t.emoji, fontSize = 26.sp)
                        Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(t.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocMenu(d: DocumentEntity, folders: List<String>, onDismiss: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val profile = Profile.from(d.profile)
    var step by remember { mutableStateOf("menu") }
    when (step) {
        "menu" -> {
            val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
                Column(Modifier.padding(bottom = 24.dp)) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp), maxLines = 1)
                    @Composable fun item(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
                        DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = action, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    item(Icons.Default.Edit, "Open") { onDismiss(); shell.go("doc/${d.id}") }
                    item(Icons.Default.DriveFileRenameOutline, "Rename") { step = "rename" }
                    item(Icons.Default.DriveFileMove, "Move to folder") { step = "move" }
                    item(Icons.Default.PushPin, if (d.pinned) "Unpin" else "Pin") { app.io { app.db.documents().update(d.copy(pinned = !d.pinned)) }; onDismiss() }
                    item(Icons.Default.ContentCopy, "Duplicate") {
                        app.io {
                            val body = app.content.readBody(d.filePath)
                            val title = d.title + " copy"
                            val rel = app.content.writeDocument(profile, title, d.folder, "", body)
                            app.db.documents().insert(d.copy(id = 0, title = title, filePath = rel, pinned = false, createdAt = Time.now(), updatedAt = Time.now()))
                        }
                        onDismiss()
                    }
                    item(Icons.Default.IosShare, "Export / share") { step = "export" }
                    item(Icons.Default.Print, "Print") { app.io { val b = app.content.readBody(d.filePath); withContext(Dispatchers.Main) { Printer.print(ctx, d.title, b, DocActions.pageOf(d)) } }; onDismiss() }
                    item(Icons.Default.Html, "Open .html file") { Files.open(ctx, app.content.file(d.filePath)); onDismiss() }
                    item(Icons.Default.Delete, "Delete") { step = "delete" }
                }
            }
        }
        "rename" -> TextInputDialog("Rename", d.title, "Title", onDismiss = onDismiss) { t ->
            app.io {
                val body = app.content.readBody(d.filePath)
                val rel = app.content.writeDocument(profile, t, d.folder, d.filePath, body)
                app.db.documents().update(d.copy(title = t, filePath = rel, updatedAt = Time.now()))
            }
        }
        "move" -> {
            var name by remember { mutableStateOf(d.folder) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Move to folder") },
                text = {
                    Column {
                        OutlinedTextField(name, { name = it }, label = { Text("Folder name (blank = none)") }, singleLine = true)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (folders + listOf("Work Orders", "Meetings", "Policies & SOPs", "Reports", "Personal")).distinct().forEach { f -> FilterChip(name == f, { name = f }, label = { Text(f) }) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        app.io {
                            val body = app.content.readBody(d.filePath)
                            val rel = app.content.writeDocument(profile, d.title, name.trim(), d.filePath, body)
                            app.db.documents().update(d.copy(folder = name.trim(), filePath = rel))
                        }
                        onDismiss()
                    }) { Text("Move") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        "export" -> ExportSheet(d.title, onDismiss, onPrint = {
            app.io { val b = app.content.readBody(d.filePath); withContext(Dispatchers.Main) { Printer.print(ctx, d.title, b, DocActions.pageOf(d)) } }
        }) { f, share ->
            app.io {
                val body = app.content.readBody(d.filePath)
                withContext(Dispatchers.Main) { DocActions.export(app, ctx, shell, profile, d.title, body, DocActions.pageOf(d), f, share) }
            }
        }
        "delete" -> ConfirmDialog("Delete document?", "“${d.title}” and its file will be permanently deleted.", onDismiss = onDismiss) {
            app.io { app.content.delete(d.filePath); app.db.documents().delete(d) }
        }
    }
}
