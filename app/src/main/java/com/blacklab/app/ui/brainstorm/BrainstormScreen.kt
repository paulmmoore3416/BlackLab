package com.blacklab.app.ui.brainstorm

import com.blacklab.app.ui.components.sharedCard
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.LiveShare
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.brainstorm.NoteStyle
import com.blacklab.app.brainstorm.SectionKeys
import com.blacklab.app.brainstorm.Tags
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.NotebookEntity
import com.blacklab.app.data.PageEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.ColorPickerRow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BTab(val label: String) { NOTEBOOKS("Notebooks"), NOTES("Notes"), TAGS("Tags") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainstormScreen(startTab: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = settingsState().activeProfile
    val prefs = remember { ctx.getSharedPreferences("blacklab_brainstorm", android.content.Context.MODE_PRIVATE) }
    var tab by remember { mutableStateOf(when (startTab) { "notes" -> BTab.NOTES; "tags" -> BTab.TAGS; "notebooks" -> BTab.NOTEBOOKS; else -> BTab.entries[prefs.getInt("tab", 0).coerceIn(0, 2)] }) }
    LaunchedEffect(tab) { prefs.edit().putInt("tab", tab.ordinal).apply() }
    var searching by remember { mutableStateOf(startTab == "search") }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(if (startTab == "mindmap") "mindmap" else null) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", true)) }
    val selected = remember { mutableStateListOf<Long>() }

    LaunchedEffect(profile) { withContext(Dispatchers.IO) { Brainstorm.ensureStarter(app, profile) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch { runCatching { Brainstorm.importNotebook(app, profile, u) }.onSuccess { shell.toast("Notebook imported"); shell.go("notebook/$it") }.onFailure { shell.toast("Couldn't import: ${it.message}") } }
    }
    BackHandler(searching || selected.isNotEmpty()) { if (selected.isNotEmpty()) selected.clear() else { searching = false; query = "" } }

    Scaffold(
        topBar = {
            when {
                selected.isNotEmpty() -> SelectionBar(selected, profile) { selected.clear() }
                searching -> TopAppBar(
                    navigationIcon = { IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                    title = {
                        TextField(query, { query = it }, placeholder = { Text("Search notebooks & notes") }, singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
                    },
                    actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                )
                else -> TopAppBar(
                    navigationIcon = { IconButton(onClick = shell.openDrawer) { Icon(Icons.Rounded.Menu, "Menu") } },
                    title = { Column { Text("Brainstorm"); Text("Notebooks, notes & ideas", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                        if (tab == BTab.NOTES) IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() }) { Icon(if (grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Recycle bin") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; sheet = "trash" })
                                DropdownMenuItem(text = { Text("Live Share links") }, leadingIcon = { Icon(Icons.Rounded.Wifi, null) }, onClick = { menu = false; sheet = "shares" })
                                DropdownMenuItem(text = { Text("Import notebook") }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) }, onClick = { menu = false; import.launch(arrayOf("*/*")) })
                                DropdownMenuItem(text = { Text("Manage labels") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) }, onClick = { menu = false; sheet = "labels" })
                                if (SectionKeys.anyUnlocked) DropdownMenuItem(text = { Text("Lock all sections") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; SectionKeys.lockAll(); shell.toast("Locked") })
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!searching && selected.isEmpty() && tab == BTab.NOTEBOOKS) ExtendedFloatingActionButton(onClick = { sheet = "newNotebook" }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Notebook") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (searching) { SearchResults(profile, query); return@Column }
            PrimaryTabRow(tab.ordinal, containerColor = Color.Transparent) {
                BTab.entries.forEach { t -> Tab(t == tab, { tab = t; selected.clear() }, text = { Text(t.label) }) }
            }
            when (tab) {
                BTab.NOTEBOOKS -> NotebooksTab(profile)
                BTab.NOTES -> NotesBoard(profile, grid, selected)
                BTab.TAGS -> TagsTab(profile)
            }
        }
    }

    when (sheet) {
        "newNotebook" -> NewNotebookDialog(onDismiss = { sheet = null }) { name, color -> scope.launch { val id = Brainstorm.createNotebook(app, profile, name, color); shell.go("notebook/$id") } }
        "trash" -> RecycleBinSheet(profile) { sheet = null }
        "shares" -> SharesSheet { sheet = null }
        "labels" -> ManageLabelsDialog(profile) { sheet = null }
        "mindmap" -> TextInputDialog("Mind map", "", "Central idea or question", "Create", onDismiss = { sheet = null }) { topic ->
            scope.launch {
                val dao = app.db.brainstorm()
                Brainstorm.ensureStarter(app, profile)
                val sec = dao.notebooks(profile.name).flatMap { dao.sections(it.id) }.firstOrNull { !it.locked } ?: return@launch
                val (ideas, source) = Brainstorm.ideas(app, topic)
                val id = Brainstorm.createPage(app, profile, sec.id, "blank", topic, json = com.blacklab.app.brainstorm.PageTemplates.mindMap(topic, ideas))
                shell.toast("Mind map ready · $source")
                shell.go("page/$id")
            }
        }
    }
}

// ====================================================================== notebooks

@Composable
private fun NotebooksTab(profile: Profile) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val dao = app.db.brainstorm()
    val notebooks by dao.observeNotebooks(profile.name).collectAsState(initial = emptyList())
    val counts by dao.observeCounts(profile.name).collectAsState(initial = emptyList())
    val recent by dao.observeRecent(profile.name, 8).collectAsState(initial = emptyList())
    var sheetFor by remember { mutableStateOf<NotebookEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<NotebookEntity?>(null) }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    scope.launch {
                        val nb = dao.notebooks(profile.name).firstOrNull() ?: return@launch
                        val sec = dao.sections(nb.id).firstOrNull { !it.locked } ?: return@launch
                        shell.go("page/" + Brainstorm.createPage(app, profile, sec.id, "blank", ""))
                    }
                }) { Icon(Icons.AutoMirrored.Rounded.NoteAdd, null); Spacer(Modifier.width(6.dp)); Text("Quick page") }
                FilledTonalButton(onClick = { shell.go("note/0?kind=") }) { Icon(Icons.AutoMirrored.Rounded.StickyNote2, null); Spacer(Modifier.width(6.dp)); Text("Quick note") }
            }
        }
        if (recent.isNotEmpty()) {
            item { SectionHeader("Recent pages") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(recent, key = { it.id }) { p ->
                        Surface(onClick = { shell.go("page/${p.id}") }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(180.dp).height(112.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(p.title.ifBlank { "Untitled page" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.preview.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(Time.ago(p.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeader("Notebooks") }
        if (notebooks.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.MenuBook, "No notebooks", "Create one for each area of life or work — sections and pages live inside.") }
        items(notebooks, key = { it.id }) { nb ->
            val c = counts.firstOrNull { it.notebookId == nb.id }
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).pressable(onClick = { shell.go("notebook/${nb.id}") }, onLongClick = { sheetFor = nb }),
            ) {
                Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(14.dp).fillMaxHeight().background(Color(nb.color)))
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, null, tint = Color(nb.color), modifier = Modifier.padding(start = 14.dp).size(30.dp))
                    Column(Modifier.weight(1f).padding(14.dp)) {
                        Text(nb.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${plural(c?.sections ?: 0, "section")} · ${plural(c?.pages ?: 0, "page")} · edited ${Time.ago(nb.updatedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 10.dp))
                }
            }
        }
    }
    val ctx = LocalContext.current
    sheetFor?.let { nb ->
        ActionSheet(title = nb.name, onDismiss = { sheetFor = null }, actions = listOf(
            SheetAction("Open", Icons.AutoMirrored.Rounded.OpenInNew) { shell.go("notebook/${nb.id}") },
            SheetAction("Move up", Icons.Rounded.ArrowUpward) { scope.launch { reorderNotebooks(app, notebooks, nb, -1) } },
            SheetAction("Move down", Icons.Rounded.ArrowDownward) { scope.launch { reorderNotebooks(app, notebooks, nb, 1) } },
            SheetAction("Export as .blnotebook", Icons.Rounded.Archive, hint = "Portable package — import it on another phone") {
                scope.launch { runCatching { Brainstorm.exportNotebook(app, nb) }.onSuccess { com.blacklab.app.core.Files.share(ctx, listOf(it), nb.name) }.onFailure { shell.toast("Export failed") } }
            },
            SheetAction("Delete notebook", Icons.Rounded.Delete, destructive = true) { confirmDelete = nb },
        ))
    }
    confirmDelete?.let { nb -> ConfirmDialog("Delete “${nb.name}”?", "Sections are removed and pages go to the recycle bin.", onDismiss = { confirmDelete = null }) { scope.launch { Brainstorm.deleteNotebook(app, nb) } } }
}

private suspend fun reorderNotebooks(app: com.blacklab.app.AppContainer, list: List<NotebookEntity>, nb: NotebookEntity, dir: Int) {
    val l = list.toMutableList(); val i = l.indexOfFirst { it.id == nb.id }; val j = i + dir
    if (i < 0 || j !in l.indices) return
    l.add(j, l.removeAt(i))
    l.forEachIndexed { k, x -> if (x.sortOrder != k) app.db.brainstorm().updateNotebook(x.copy(sortOrder = k)) }
}

@Composable
private fun NewNotebookDialog(onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(Brainstorm.SectionColors[0]) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New notebook") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ColorPickerRow(color, { color = it }, Brainstorm.SectionColors)
        }
    }, confirmButton = { TextButton(onClick = { onCreate(name.trim().ifBlank { "Notebook" }, color); onDismiss() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ====================================================================== notes board (Keep-style)

private sealed interface NoteFilter { data object All : NoteFilter; data object Reminders : NoteFilter; data object Archive : NoteFilter; data object Trash : NoteFilter; data class Label(val name: String) : NoteFilter }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesBoard(profile: Profile, grid: Boolean, selected: MutableList<Long>) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val notes by app.db.notes().observe(profile.name).collectAsState(initial = emptyList())
    val labels by app.db.notes().observeLabels(profile.name).collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<NoteFilter>(NoteFilter.All) }
    var trashSheet by remember { mutableStateOf<NoteEntity?>(null) }

    val shown = when (val f = filter) {
        NoteFilter.All -> notes.filter { it.trashedAt == null && !it.archived }
        NoteFilter.Reminders -> notes.filter { it.trashedAt == null && (it.reminderAt != null || it.placeLat != null) }.sortedBy { it.reminderAt ?: Long.MAX_VALUE }
        NoteFilter.Archive -> notes.filter { it.trashedAt == null && it.archived }
        NoteFilter.Trash -> notes.filter { it.trashedAt != null }
        is NoteFilter.Label -> notes.filter { it.trashedAt == null && f.name in NoteOps.labels(it) }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(filter == NoteFilter.All, { filter = NoteFilter.All }, label = { Text("Notes") }, leadingIcon = { Icon(Icons.Rounded.Lightbulb, null, Modifier.size(18.dp)) })
                FilterChip(filter == NoteFilter.Reminders, { filter = NoteFilter.Reminders }, label = { Text("Reminders") }, leadingIcon = { Icon(Icons.Rounded.NotificationsNone, null, Modifier.size(18.dp)) })
                labels.forEach { l -> FilterChip(filter == NoteFilter.Label(l.name), { filter = NoteFilter.Label(l.name) }, label = { Text(l.name) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(18.dp)) }) }
                FilterChip(filter == NoteFilter.Archive, { filter = NoteFilter.Archive }, label = { Text("Archive") }, leadingIcon = { Icon(Icons.Rounded.Archive, null, Modifier.size(18.dp)) })
                FilterChip(filter == NoteFilter.Trash, { filter = NoteFilter.Trash }, label = { Text("Trash") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)) })
            }
            if (filter == NoteFilter.Trash && shown.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notes in Trash are deleted after ${NoteOps.TRASH_DAYS} days.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { shown.forEach { NoteOps.deleteForever(app, it) } } }) { Text("Empty Trash") }
                }
            }
            if (shown.isEmpty()) {
                EmptyState(when (filter) { NoteFilter.Trash -> Icons.Rounded.DeleteOutline; NoteFilter.Archive -> Icons.Rounded.Archive; NoteFilter.Reminders -> Icons.Rounded.NotificationsNone; else -> Icons.Rounded.Lightbulb },
                    when (filter) { NoteFilter.Trash -> "No notes in Trash"; NoteFilter.Archive -> "Your archived notes appear here"; NoteFilter.Reminders -> "Notes with upcoming reminders appear here"; else -> "Notes you add appear here" },
                    "Lists, voice notes, photos and sketches — pin, color, label and set reminders by time or place.")
            } else {
                val pinned = if (filter == NoteFilter.All || filter is NoteFilter.Label) shown.filter { it.pinned } else emptyList()
                val others = shown - pinned.toSet()
                LazyVerticalStaggeredGrid(
                    StaggeredGridCells.Fixed(if (grid) 2 else 1), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp, modifier = Modifier.weight(1f),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) { SectionHeader("Pinned") }
                        items(pinned, key = { it.id }) { n -> NoteCard(n, n.id in selected, onClick = { if (selected.isNotEmpty()) toggle(selected, n.id) else shell.go("note/${n.id}") }, onLong = { toggle(selected, n.id) }) }
                        if (others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) { SectionHeader("Others") }
                    }
                    items(others, key = { it.id }) { n ->
                        NoteCard(n, n.id in selected, onClick = {
                            when { filter == NoteFilter.Trash -> trashSheet = n; selected.isNotEmpty() -> toggle(selected, n.id); else -> shell.go("note/${n.id}") }
                        }, onLong = { if (filter != NoteFilter.Trash) toggle(selected, n.id) })
                    }
                }
            }
        }
        if (filter != NoteFilter.Trash) TakeNoteBar(Modifier.align(Alignment.BottomCenter))
    }
    trashSheet?.let { n ->
        ActionSheet(title = n.title.ifBlank { NoteOps.summary(n).take(50).ifBlank { "Note" } }, subtitle = "In Trash", onDismiss = { trashSheet = null }, actions = listOf(
            SheetAction("Restore", Icons.Rounded.RestoreFromTrash) { app.io { NoteOps.restore(app, n) } },
            SheetAction("Delete forever", Icons.Rounded.DeleteForever, destructive = true) { app.io { NoteOps.deleteForever(app, n) } },
        ))
    }
}

private fun toggle(list: MutableList<Long>, id: Long) { if (id in list) list.remove(id) else list.add(id) }

@Composable
private fun TakeNoteBar(modifier: Modifier) {
    val shell = LocalShell.current
    Surface(modifier.fillMaxWidth().padding(12.dp).navigationBarsPadding(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 20.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Take a note…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).clickable { shell.go("note/0?kind=") }.padding(vertical = 16.dp))
            IconButton(onClick = { shell.go("note/0?kind=checklist") }) { Icon(Icons.Rounded.CheckBox, "New list") }
            IconButton(onClick = { shell.go("note/0?kind=drawing") }) { Icon(Icons.Rounded.Brush, "New drawing") }
            IconButton(onClick = { shell.go("note/0?kind=voice") }) { Icon(Icons.Rounded.Mic, "New voice note") }
            IconButton(onClick = { shell.go("note/0?kind=image") }) { Icon(Icons.Rounded.Image, "New note with image") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteCard(n: NoteEntity, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val app = rememberApp()
    val dark = LocalBlColors.current.isDark
    val bg = noteColor(n.color, dark)
    val attachments = remember(n.attachments) { NoteOps.attachments(n) }
    val pic = attachments.firstOrNull { it.kind != "audio" }
    Surface(
        shape = RoundedCornerShape(16.dp), color = bg ?: MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else if (bg == null) MaterialTheme.colorScheme.outlineVariant else Color.Transparent),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).sharedCard("note-${n.id}").pressable(onClick = onClick, onLongClick = onLong),
    ) {
        Box {
            NoteStyle.bgEmoji(n.bg).takeIf { it.isNotEmpty() }?.let { Text(it, fontSize = 64.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).alpha(0.14f)) }
            Column {
                pic?.let { AsyncImage(model = app.storage.resolve(it.path), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                Column(Modifier.padding(12.dp)) {
                    if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (n.checklist) {
                        val items = NoteOps.items(n)
                        items.filter { !it.done }.take(8).forEach { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = (it.indent * 14).dp, top = 2.dp)) {
                            Icon(Icons.Rounded.CheckBoxOutlineBlank, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(6.dp))
                            Text(it.text, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                        val doneCount = items.count { it.done }; val more = items.count { !it.done } - 8
                        if (more > 0) Text("+ $more more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (doneCount > 0) Text("+ $doneCount checked item${if (doneCount > 1) "s" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodySmall, maxLines = 10, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    if (attachments.any { it.kind == "audio" }) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.GraphicEq, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Recording", style = MaterialTheme.typography.labelSmall)
                    }
                    val chips = buildList {
                        n.reminderAt?.let { add((if (it < Time.now()) "⏰ " else "⏰ ") + Time.relativeDay(Time.toDate(it)) + ", " + Time.time(it)) }
                        if (n.placeLat != null) add("📍 " + n.placeName.ifBlank { "Place" })
                        addAll(NoteOps.labels(n))
                    }
                    if (chips.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chips.forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, textDecoration = if (it.startsWith("⏰") && (n.reminderAt ?: Long.MAX_VALUE) < Time.now()) TextDecoration.LineThrough else null,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 6.dp, vertical = 2.dp)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(selected: MutableList<Long>, profile: Profile, onClose: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    suspend fun each(block: suspend (NoteEntity) -> Unit) { selected.toList().forEach { id -> app.db.notes().get(id)?.let { block(it) } } }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Done") } },
        title = { Text("${selected.size}") },
        actions = {
            IconButton(onClick = { scope.launch { val allPinned = selected.all { app.db.notes().get(it)?.pinned == true }; each { NoteOps.save(app, it.copy(pinned = !allPinned)) }; onClose() } }) { Icon(Icons.Outlined.PushPin, "Pin") }
            IconButton(onClick = { dialog = "color" }) { Icon(Icons.Rounded.Palette, "Color") }
            IconButton(onClick = { dialog = "labels" }) { Icon(Icons.AutoMirrored.Rounded.Label, "Labels") }
            IconButton(onClick = { scope.launch { each { NoteOps.save(app, it.copy(archived = !it.archived, pinned = false)) }; shell.toast("Archived"); onClose() } }) { Icon(Icons.Rounded.Archive, "Archive") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { more = false; scope.launch { each { NoteOps.trash(app, it) }; shell.toast("Moved to Trash"); onClose() } })
                    DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { more = false; scope.launch { each { NoteOps.copy(app, it) }; onClose() } })
                    DropdownMenuItem(text = { Text("Send to notebook") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, onClick = { more = false; dialog = "toPage" })
                    DropdownMenuItem(text = { Text("Move to top") }, leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, null) }, onClick = { more = false; scope.launch { each { n -> NoteOps.save(app, n.copy(sortOrder = (app.db.notes().maxOrder(n.profile) ?: 0) + 1)) }; onClose() } })
                }
            }
        },
    )
    when (dialog) {
        "color" -> PaletteSheet(-1, "-", LocalBlColors.current.isDark, onDismiss = { dialog = null }, onColor = { c -> scope.launch { each { NoteOps.save(app, it.copy(color = c)) } } }, onBg = { b -> scope.launch { each { NoteOps.save(app, it.copy(bg = b)) } } })
        "labels" -> LabelsDialog(profile, emptyList(), onDismiss = { dialog = null }) { add -> scope.launch { each { NoteOps.save(app, it.copy(labels = NoteOps.joinLabels(NoteOps.labels(it) + add))) }; onClose() } }
        "toPage" -> SectionPickerDialog("Send to notebook section", profile, onDismiss = { dialog = null }) { s ->
            scope.launch { runCatching { each { NoteOps.toPage(app, it, s.id) } }.onSuccess { shell.toast("Sent to ${s.name}") }.onFailure { shell.toast("Unlock “${s.name}” first") }; onClose() }
        }
    }
}

@Composable
private fun ManageLabelsDialog(profile: Profile, onDismiss: () -> Unit) {
    val app = rememberApp()
    val labels by app.db.notes().observeLabels(profile.name).collectAsState(initial = emptyList())
    var rename by remember { mutableStateOf<com.blacklab.app.data.NoteLabelEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit labels") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newName, { newName = it }, placeholder = { Text("Create new label") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { val n = newName.trim(); if (n.isNotEmpty()) app.io { app.db.notes().insertLabel(com.blacklab.app.data.NoteLabelEntity(profile = profile.name, name = n)) }; newName = "" }) { Icon(Icons.Rounded.Check, "Add") }
            }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(labels, key = { it.id }) { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
                        Text(l.name, Modifier.weight(1f))
                        IconButton(onClick = { rename = l }) { Icon(Icons.Rounded.Edit, "Rename") }
                        IconButton(onClick = { app.io { NoteOps.deleteLabel(app, profile, l) } }) { Icon(Icons.Rounded.Delete, "Delete") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    rename?.let { l -> TextInputDialog("Rename label", l.name, "Label", onDismiss = { rename = null }) { n -> app.io { NoteOps.renameLabel(app, profile, l, n) } } }
}

// ====================================================================== tags summary

private data class Places(val sections: Map<Long, SectionEntity>, val notebooks: Map<Long, NotebookEntity>) {
    fun where(p: PageEntity): String = sections[p.sectionId]?.let { s -> (notebooks[s.notebookId]?.name?.let { "$it › " } ?: "") + s.name } ?: ""
}

@Composable
private fun rememberPlaces(profile: Profile, key: Any?): Places {
    val app = rememberApp()
    var places by remember { mutableStateOf(Places(emptyMap(), emptyMap())) }
    LaunchedEffect(profile, key) {
        places = withContext(Dispatchers.IO) {
            val nbs = app.db.brainstorm().notebooks(profile.name)
            Places(nbs.flatMap { app.db.brainstorm().sections(it.id) }.associateBy { it.id }, nbs.associateBy { it.id })
        }
    }
    return places
}

@Composable
private fun TagsTab(profile: Profile) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val pages by app.db.brainstorm().observeTagged(profile.name).collectAsState(initial = emptyList())
    val places = rememberPlaces(profile, pages.size)
    var openOnly by remember { mutableStateOf(false) }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    val lines = pages.flatMap { p -> Tags.parseIndex(p.tagIndex).map { p to it } }
        .filter { (_, l) -> (!openOnly || (l.tag == "todo" && !l.done)) && (tagFilter == null || l.tag == tagFilter) }
    val grouped = Tags.all.mapNotNull { t -> lines.filter { it.second.tag == t.id }.takeIf { it.isNotEmpty() }?.let { t to it } } +
        lines.filter { l -> Tags.all.none { it.id == l.second.tag } }.groupBy { it.second.tag }.map { (k, v) -> Tags.of(k) to v }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 100.dp)) {
        item {
            Text("Every tagged note across your notebooks — like OneNote's Tags Summary.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(openOnly, { openOnly = !openOnly; if (openOnly) tagFilter = null }, label = { Text("Unchecked To Do") })
                Tags.all.filter { t -> pages.any { p -> p.tagIndex.lineSequence().any { it.startsWith(t.id + "|") } } }.forEach { t ->
                    FilterChip(tagFilter == t.id, { tagFilter = if (tagFilter == t.id) null else t.id; openOnly = false }, label = { Text(t.symbol + " " + t.label) })
                }
            }
            val open = pages.flatMap { p -> Tags.parseIndex(p.tagIndex).filter { it.tag == "todo" && !it.done }.map { p to it } }
            if (open.isNotEmpty()) OutlinedButton(onClick = {
                scope.launch { val n = Brainstorm.createTasks(app, profile, open.map { it.second.text }, "Tags summary"); shell.toast("$n task(s) added to To-Do", "Open") { shell.go("tasks") } }
            }) { Icon(Icons.Rounded.AddTask, null); Spacer(Modifier.width(6.dp)); Text("Send ${open.size} open To Do(s) to Tasks") }
        }
        if (grouped.isEmpty()) item { EmptyState(Icons.Rounded.Sell, "No tags yet", "On a page, put the cursor in a line and choose Home › Tag (To Do, Important, Question, Idea…).") }
        grouped.forEach { (tag, list) ->
            item(key = "h" + tag.id) { SectionHeader("${tag.symbol}  ${tag.label} (${list.size})") }
            items(list, key = { (p, l) -> "${tag.id}|${p.id}|${l.text}|${l.done}" }) { (p, l) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { shell.go("page/${p.id}") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (l.tag == "todo") Checkbox(l.done, { d -> scope.launch { Brainstorm.setTodo(app, p, l.text, d) } })
                    else Text(tag.symbol, fontSize = 18.sp, modifier = Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        Text(l.text, style = MaterialTheme.typography.bodyMedium, textDecoration = if (l.done) TextDecoration.LineThrough else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(p.title.ifBlank { "Untitled page" }, places.where(p)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ====================================================================== search

@Composable
private fun SearchResults(profile: Profile, query: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val notes by app.db.notes().observe(profile.name).collectAsState(initial = emptyList())
    var pages by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    LaunchedEffect(query) { pages = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { app.db.brainstorm().search(profile.name, "%${query.trim()}%") } }
    val places = rememberPlaces(profile, pages.size)
    val noteHits = if (query.isBlank()) emptyList() else notes.filter { it.trashedAt == null && NoteOps.matches(it, query.trim()) }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        if (query.isBlank()) item { Text("Searches page text, handwriting-free text boxes, words inside pictures, recording transcripts, notes, checklists and labels.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (pages.isNotEmpty()) item { SectionHeader("Pages (${pages.size})") }
        items(pages, key = { "p${it.id}" }) { p ->
            val snippet = snippet(p.searchText, query)
            Surface(onClick = { shell.go("page/${p.id}") }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(p.title.ifBlank { "Untitled page" }, style = MaterialTheme.typography.titleSmall)
                    Text(places.where(p), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (snippet.isNotBlank()) Text(snippet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                }
            }
        }
        if (noteHits.isNotEmpty()) item { SectionHeader("Notes (${noteHits.size})") }
        items(noteHits, key = { "n${it.id}" }) { n -> Box(Modifier.padding(vertical = 4.dp)) { NoteCard(n, false, onClick = { shell.go("note/${n.id}") }, onLong = {}) } }
        if (query.isNotBlank() && pages.isEmpty() && noteHits.isEmpty()) item { EmptyState(Icons.Rounded.SearchOff, "No matches", "Locked sections aren't searched until you remove their password.") }
    }
}

private fun snippet(text: String, q: String): String {
    val i = text.indexOf(q.trim(), ignoreCase = true)
    if (i < 0) return text.take(140)
    val s = (i - 50).coerceAtLeast(0)
    return (if (s > 0) "…" else "") + text.substring(s, (i + q.length + 90).coerceAtMost(text.length)).replace('\n', ' ') + "…"
}

// ====================================================================== recycle bin & live share links

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecycleBinSheet(profile: Profile, onDismiss: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val pages by app.db.brainstorm().observeDeleted(profile.name).collectAsState(initial = emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Recycle bin", style = MaterialTheme.typography.titleLarge)
                    Text("Deleted pages are kept ${Brainstorm.TRASH_DAYS} days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pages.isNotEmpty()) TextButton(onClick = { scope.launch { pages.forEach { Brainstorm.purgePage(app, it) } } }) { Text("Empty", color = LocalBlColors.current.danger) }
            }
            LazyColumn(Modifier.heightIn(max = 480.dp).padding(vertical = 8.dp)) {
                if (pages.isEmpty()) item { Text("Nothing here.", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(pages, key = { it.id }) { p ->
                    ListItem(headlineContent = { Text(p.title.ifBlank { "Untitled page" }) }, supportingContent = { Text("Deleted ${Time.ago(p.deletedAt ?: 0)}") },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { scope.launch { if (Brainstorm.restorePage(app, p)) shell.toast("Restored") else shell.toast("This page was encrypted in a section that no longer exists") } }) { Icon(Icons.Rounded.RestoreFromTrash, "Restore") }
                                IconButton(onClick = { scope.launch { Brainstorm.purgePage(app, p) } }) { Icon(Icons.Rounded.DeleteForever, "Delete forever", tint = LocalBlColors.current.danger) }
                            }
                        }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharesSheet(onDismiss: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val shares by app.db.brainstorm().observeShares().collectAsState(initial = emptyList())
    val guestComments by app.db.brainstorm().observeGuestComments().collectAsState(initial = emptyList())
    val active = shares.filter { !it.revoked && it.expiresAt > Time.now() }
    var qr by remember { mutableStateOf<com.blacklab.app.data.ShareLinkEntity?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Live Share", style = MaterialTheme.typography.titleLarge)
            Text(if (LiveShare.serverReady(app)) "Wi-Fi sharing is on" else "Wi-Fi sharing is off — links open again when it's turned on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.heightIn(max = 520.dp).padding(vertical = 8.dp)) {
                if (active.isEmpty()) item { Text("No active links. Open a page or note › More › Live Share.", modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(active, key = { it.token }) { s -> ShareRow(s) { qr = s } }
                if (guestComments.isNotEmpty()) {
                    item { SectionHeader("New from guests (${guestComments.size})") }
                    items(guestComments, key = { "c${it.id}" }) { c ->
                        ListItem(headlineContent = { Text(c.text, maxLines = 2) }, supportingContent = { Text(c.author + " · " + Time.ago(c.createdAt)) },
                            leadingContent = { Icon(Icons.AutoMirrored.Rounded.Comment, null) },
                            modifier = Modifier.clickable { onDismiss(); c.pageId?.let { shell.go("page/$it") } ?: c.noteId?.let { shell.go("note/$it") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
        }
    }
    qr?.let { QrDialog(it) { qr = null } }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
