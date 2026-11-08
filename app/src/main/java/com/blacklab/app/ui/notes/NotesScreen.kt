package com.blacklab.app.ui.notes

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.brainstorm.NoteStyle
import com.blacklab.app.core.Files
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Settings
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.NoteLabelEntity
import com.blacklab.app.ui.brainstorm.LabelsDialog
import com.blacklab.app.ui.brainstorm.NoteCard
import com.blacklab.app.ui.brainstorm.PaletteSheet
import com.blacklab.app.ui.brainstorm.ReminderDialog
import com.blacklab.app.ui.brainstorm.SectionPickerDialog
import com.blacklab.app.ui.brainstorm.noteColor
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.profileAccent
import kotlinx.coroutines.launch

/** Which notes the board shows: the side menu's destinations, like Google Keep. */
sealed interface NoteView {
    data object All : NoteView
    data object Reminders : NoteView
    data object Archive : NoteView
    data object Trash : NoteView
    data class Label(val name: String) : NoteView

    companion object {
        fun parse(s: String): NoteView = when {
            s == "reminders" -> Reminders
            s == "archive" -> Archive
            s == "trash" || s == "deleted" -> Trash
            s.startsWith("label:") -> Label(s.removePrefix("label:"))
            else -> All
        }
    }
}

/** What a note holds, for the search screen's type filters. */
enum class NoteType(val label: String, val icon: ImageVector) {
    REMINDERS("Reminders", Icons.Rounded.NotificationsNone),
    LISTS("Lists", Icons.Rounded.CheckBox),
    IMAGES("Images", Icons.Rounded.Image),
    VOICE("Voice", Icons.Rounded.Mic),
    DRAWINGS("Drawings", Icons.Rounded.Brush),
    URLS("URLs", Icons.Rounded.Link);

    fun matches(n: NoteEntity): Boolean = when (this) {
        REMINDERS -> n.reminderAt != null || n.placeLat != null
        LISTS -> n.checklist
        IMAGES -> NoteOps.attachments(n).any { it.kind == "image" }
        VOICE -> NoteOps.attachments(n).any { it.kind == "audio" }
        DRAWINGS -> NoteOps.attachments(n).any { it.kind == "drawing" }
        URLS -> URL.containsMatchIn(NoteOps.plainText(n))
    }

    companion object { private val URL = Regex("(https?://|www\\.)\\S+", RegexOption.IGNORE_CASE) }
}

/** Route for a new note, carrying the label of the view it was started from. */
fun newNoteRoute(kind: String = "", label: String? = null) = "note/0?kind=$kind" + (label?.let { "&label=" + Uri.encode(it) } ?: "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(start: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = settingsState().activeProfile
    val prefs = remember { ctx.getSharedPreferences("blacklab_notes", android.content.Context.MODE_PRIVATE) }
    val legacyGrid = remember { ctx.getSharedPreferences("blacklab_brainstorm", android.content.Context.MODE_PRIVATE).getBoolean("grid", true) }
    var grid by remember { mutableStateOf(prefs.getBoolean("grid", legacyGrid)) }
    var view by remember { mutableStateOf(NoteView.parse(start)) }
    var searching by remember { mutableStateOf(start == "search") }
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<NoteType?>(null) }
    var colorFilter by remember { mutableStateOf<Int?>(null) }
    var labelFilter by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateListOf<Long>() }
    var dialog by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val notes by remember(profile) { app.db.notes().observe(profile.name) }.collectAsStateWithLifecycle(initialValue = null)
    val labels by remember(profile) { app.db.notes().observeLabels(profile.name) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val all = notes.orEmpty()

    LaunchedEffect(Unit) { app.io { NoteOps.purgeTrash(app) } }
    fun closeSearch() { searching = false; query = ""; typeFilter = null; colorFilter = null; labelFilter = null }
    BackHandler(drawer.isOpen || searching || selected.isNotEmpty() || view != NoteView.All) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            selected.isNotEmpty() -> selected.clear()
            searching -> closeSearch()
            else -> view = NoteView.All
        }
    }
    val labelOf = (view as? NoteView.Label)?.name

    ModalNavigationDrawer(
        drawerState = drawer, gesturesEnabled = drawer.isOpen,
        drawerContent = {
            NotesDrawer(view, all, labels,
                onPick = { v -> view = v; selected.clear(); closeSearch(); scope.launch { drawer.close() } },
                onEditLabels = { dialog = "labels"; scope.launch { drawer.close() } },
                onSettings = { scope.launch { drawer.close() }; shell.go("settings/dashboard") },
                onAppMenu = { scope.launch { drawer.close(); shell.openDrawer() } })
        },
    ) {
        Scaffold(
            topBar = {
                when {
                    selected.isNotEmpty() -> NotesSelectionBar(selected, profile, view) { selected.clear() }
                    searching -> TopAppBar(
                        navigationIcon = { IconButton(onClick = { closeSearch() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") } },
                        title = {
                            TextField(query, { query = it }, placeholder = { Text("Search your notes") }, singleLine = true,
                                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
                        },
                        actions = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                    )
                    view == NoteView.All -> KeepSearchBar(profile, grid, onMenu = { scope.launch { drawer.open() } }, onSearch = { searching = true },
                        onGrid = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() })
                    else -> {
                        var more by remember { mutableStateOf(false) }
                        TopAppBar(
                            navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Menu") } },
                            title = { Text(when (val v = view) { NoteView.Reminders -> "Reminders"; NoteView.Archive -> "Archive"; NoteView.Trash -> "Deleted"; is NoteView.Label -> v.name; else -> "Notes" }) },
                            actions = {
                                IconButton(onClick = { searching = true; labelFilter = labelOf }) { Icon(Icons.Rounded.Search, "Search") }
                                if (view != NoteView.Trash) IconButton(onClick = { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() }) { Icon(if (grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
                                if (view == NoteView.Trash || view is NoteView.Label) Box {
                                    IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                                    DropdownMenu(more, { more = false }) {
                                        if (view == NoteView.Trash) DropdownMenuItem(text = { Text("Empty Trash") }, leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) }, onClick = { more = false; dialog = "emptyTrash" })
                                        else {
                                            DropdownMenuItem(text = { Text("Rename label") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { more = false; dialog = "renameLabel" })
                                            DropdownMenuItem(text = { Text("Delete label") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { more = false; dialog = "deleteLabel" })
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            },
            bottomBar = {
                if (!searching && selected.isEmpty() && view != NoteView.Trash) BottomAppBar(
                    actions = {
                        IconButton(onClick = { shell.go(newNoteRoute("checklist", labelOf)) }) { Icon(Icons.Rounded.CheckBox, "New list") }
                        IconButton(onClick = { shell.go(newNoteRoute("drawing", labelOf)) }) { Icon(Icons.Rounded.Brush, "New drawing") }
                        IconButton(onClick = { shell.go(newNoteRoute("voice", labelOf)) }) { Icon(Icons.Rounded.Mic, "New voice note") }
                        IconButton(onClick = { shell.go(newNoteRoute("image", labelOf)) }) { Icon(Icons.Rounded.Image, "New note with image") }
                        IconButton(onClick = { shell.go(newNoteRoute("camera", labelOf)) }) { Icon(Icons.Rounded.PhotoCamera, "New note with photo") }
                    },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { shell.go(newNoteRoute("", labelOf)) }, containerColor = BottomAppBarDefaults.bottomAppBarFabColor,
                            elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation()) { Icon(Icons.Rounded.Add, "New note") }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when {
                    notes == null -> Unit
                    searching -> SearchContent(all, labels, query, typeFilter, colorFilter, labelFilter, grid,
                        onType = { typeFilter = it }, onColor = { colorFilter = it }, onLabel = { labelFilter = it })
                    else -> Board(all, view, grid, selected)
                }
            }
        }
    }

    when (dialog) {
        "labels" -> EditLabelsDialog(profile) { dialog = null }
        "emptyTrash" -> ConfirmDialog("Empty Trash?", "All notes in Trash will be permanently deleted.", "Empty Trash", onDismiss = { dialog = null }) {
            scope.launch { all.filter { it.trashedAt != null }.forEach { NoteOps.deleteForever(app, it) } }
        }
        "renameLabel" -> labels.firstOrNull { it.name == labelOf }?.let { l ->
            TextInputDialog("Rename label", l.name, "Label", onDismiss = { dialog = null }) { n ->
                val name = n.trim(); if (name.isNotEmpty() && name != l.name) { app.io { NoteOps.renameLabel(app, profile, l, name) }; view = NoteView.Label(name) }
            }
        } ?: run { dialog = null }
        "deleteLabel" -> labels.firstOrNull { it.name == labelOf }?.let { l ->
            ConfirmDialog("Delete label “${l.name}”?", "The label is removed from all notes. Your notes aren't deleted.", "Delete", onDismiss = { dialog = null }) {
                app.io { NoteOps.deleteLabel(app, profile, l) }; view = NoteView.All
            }
        } ?: run { dialog = null }
    }
}

// ====================================================================== top bar & side menu

@Composable
private fun KeepSearchBar(profile: Profile, grid: Boolean, onMenu: () -> Unit, onSearch: () -> Unit, onGrid: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(54.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "Notes menu") }
            Text("Search your notes", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.weight(1f).fillMaxHeight().clickable(onClick = onSearch).wrapContentHeight(Alignment.CenterVertically))
            IconButton(onClick = onGrid) { Icon(if (grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
            // Account bubble: notes are per account, tap to switch (as Keep switches Google accounts)
            val name = app.settings.profileName(profile)
            Box(Modifier.padding(end = 10.dp).size(34.dp).clip(CircleShape).background(profileAccent(profile)).clickable { shell.switchProfile(profile.other) },
                contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NotesDrawer(view: NoteView, notes: List<NoteEntity>, labels: List<NoteLabelEntity>, onPick: (NoteView) -> Unit, onEditLabels: () -> Unit, onSettings: () -> Unit, onAppMenu: () -> Unit) {
    val live = notes.filter { it.trashedAt == null }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text("Notes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp))
            @Composable fun item(label: String, icon: ImageVector, v: NoteView, count: Int? = null) = NavigationDrawerItem(
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }, icon = { Icon(icon, null) }, selected = view == v, onClick = { onPick(v) },
                badge = { if (count != null && count > 0) Text("$count", style = MaterialTheme.typography.labelMedium) })
            item("Notes", Icons.Rounded.Lightbulb, NoteView.All, live.count { !it.archived })
            item("Reminders", Icons.Rounded.NotificationsNone, NoteView.Reminders, live.count { it.reminderAt != null || it.placeLat != null })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Labels", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = onEditLabels) { Text("Edit") }
            }
            labels.forEach { l -> item(l.name, Icons.AutoMirrored.Outlined.Label, NoteView.Label(l.name), live.count { l.name in NoteOps.labels(it) }) }
            NavigationDrawerItem(label = { Text(if (labels.isEmpty()) "Create new label" else "Edit labels") }, icon = { Icon(if (labels.isEmpty()) Icons.Rounded.Add else Icons.Rounded.Edit, null) }, selected = false, onClick = onEditLabels)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            item("Archive", Icons.Rounded.Archive, NoteView.Archive)
            item("Deleted", Icons.Rounded.DeleteOutline, NoteView.Trash, notes.count { it.trashedAt != null })
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(label = { Text("Notes & Home settings") }, icon = { Icon(Icons.Rounded.Settings, null) }, selected = false, onClick = onSettings)
            NavigationDrawerItem(label = { Text("BlackLab menu") }, icon = { Icon(Icons.Rounded.Apps, null) }, selected = false, onClick = onAppMenu)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ====================================================================== the board

@Composable
private fun Board(all: List<NoteEntity>, view: NoteView, grid: Boolean, selected: MutableList<Long>) {
    val app = rememberApp()
    val shell = LocalShell.current
    var trashSheet by remember { mutableStateOf<NoteEntity?>(null) }
    val now = Time.now()
    val shown = when (view) {
        NoteView.All -> all.filter { it.trashedAt == null && !it.archived }
        NoteView.Reminders -> all.filter { it.trashedAt == null && (it.reminderAt != null || it.placeLat != null) }.sortedBy { it.reminderAt ?: Long.MAX_VALUE }
        NoteView.Archive -> all.filter { it.trashedAt == null && it.archived }
        NoteView.Trash -> all.filter { it.trashedAt != null }.sortedByDescending { it.trashedAt }
        is NoteView.Label -> all.filter { it.trashedAt == null && view.name in NoteOps.labels(it) }
    }
    if (shown.isEmpty()) {
        val (icon, title) = when (view) {
            NoteView.Trash -> Icons.Rounded.DeleteOutline to "No notes in Trash"
            NoteView.Archive -> Icons.Rounded.Archive to "Your archived notes appear here"
            NoteView.Reminders -> Icons.Rounded.NotificationsNone to "Notes with upcoming reminders appear here"
            is NoteView.Label -> Icons.AutoMirrored.Outlined.Label to "No notes with this label yet"
            else -> Icons.Rounded.Lightbulb to "Notes you add appear here"
        }
        EmptyState(icon, title, "Lists, voice notes, photos and sketches. Pin, color and label them, and set reminders by time or place.")
        return
    }
    // Sections, like Keep: Pinned/Others on the board, Upcoming/Sent under Reminders
    val groups: List<Pair<String?, List<NoteEntity>>> = when (view) {
        NoteView.All, is NoteView.Label -> shown.partition { it.pinned }.let { (p, o) -> if (p.isEmpty()) listOf(null to o) else listOf("Pinned" to p, "Others" to o) }
        NoteView.Reminders -> shown.partition { it.placeLat != null || (it.reminderAt ?: 0) > now }.let { (up, sent) -> listOf("Upcoming" to up, "Sent" to sent.sortedByDescending { it.reminderAt }) }
        else -> listOf(null to shown)
    }.filter { it.second.isNotEmpty() }
    LazyVerticalStaggeredGrid(
        StaggeredGridCells.Fixed(if (grid && view != NoteView.Trash) 2 else 1), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp, modifier = Modifier.fillMaxSize(),
    ) {
        if (view == NoteView.Trash) item(span = StaggeredGridItemSpan.FullLine) {
            Text("Notes in Trash are deleted after ${NoteOps.TRASH_DAYS} days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        }
        groups.forEach { (header, list) ->
            if (header != null) item(span = StaggeredGridItemSpan.FullLine, key = "h-$header") { SectionHeader(header) }
            items(list, key = { it.id }) { n ->
                val onClick: () -> Unit = { when { view == NoteView.Trash -> trashSheet = n; selected.isNotEmpty() -> toggle(selected, n.id); else -> shell.go("note/${n.id}") } }
                val onLong: () -> Unit = { if (view != NoteView.Trash) toggle(selected, n.id) }
                if (view == NoteView.Trash || selected.isNotEmpty()) NoteCard(n, n.id in selected, onClick = onClick, onLong = onLong)
                else SwipeToArchive(n) { NoteCard(n, n.id in selected, onClick = onClick, onLong = onLong) }
            }
        }
    }
    trashSheet?.let { n ->
        ActionSheet(title = n.title.ifBlank { NoteOps.summary(n).take(50).ifBlank { "Note" } }, subtitle = "In Trash · can't be edited", onDismiss = { trashSheet = null }, actions = listOf(
            SheetAction("Restore", Icons.Rounded.RestoreFromTrash) { app.io { NoteOps.restore(app, n) }; shell.toast("Note restored") },
            SheetAction("Delete forever", Icons.Rounded.DeleteForever, destructive = true) { app.io { NoteOps.deleteForever(app, n) } },
        ))
    }
}

/** Swipe a note sideways to archive it (or unarchive it in Archive), with Undo. */
@Composable
private fun SwipeToArchive(n: NoteEntity, content: @Composable () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val haptics = com.blacklab.app.ui.components.rememberHaptics()
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.Settled) return@rememberSwipeToDismissBoxState false
        haptics.confirm()
        val archive = !n.archived
        app.io { app.db.notes().get(n.id)?.let { NoteOps.save(app, it.copy(archived = archive, pinned = if (archive) false else it.pinned)) } }
        shell.toast(if (archive) "Note archived" else "Note unarchived", "Undo") {
            app.io { app.db.notes().get(n.id)?.let { NoteOps.save(app, it.copy(archived = n.archived, pinned = n.pinned)) } }
        }
        true
    }, positionalThreshold = { it * 0.4f })
    SwipeToDismissBox(state = state, backgroundContent = {
        Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Alignment.CenterEnd else Alignment.CenterStart) {
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled)
                Icon(if (n.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }) { content() }
}

private fun toggle(list: MutableList<Long>, id: Long) { if (id in list) list.remove(id) else list.add(id) }

// ====================================================================== search

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchContent(
    all: List<NoteEntity>, labels: List<NoteLabelEntity>, query: String, type: NoteType?, color: Int?, label: String?, grid: Boolean,
    onType: (NoteType?) -> Unit, onColor: (Int?) -> Unit, onLabel: (String?) -> Unit,
) {
    val shell = LocalShell.current
    val dark = LocalBlColors.current.isDark
    val live = all.filter { it.trashedAt == null }
    val q = query.trim()
    val filtering = q.isNotEmpty() || type != null || color != null || label != null
    if (!filtering) {
        // Keep's search start page: browse by type, label or color
        val types = NoteType.entries.filter { t -> live.any { t.matches(it) } }
        val usedColors = live.map { it.color }.filter { it > 0 }.distinct().sorted()
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            if (live.isEmpty()) item { Text("No notes to search yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (types.isNotEmpty()) {
                item { SectionHeader("Types") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                        types.forEach { t ->
                            Surface(onClick = { onType(t) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.weight(1f).height(96.dp)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(t.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp))
                                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                        repeat((3 - types.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (labels.isNotEmpty()) {
                item { SectionHeader("Labels") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                        labels.forEach { l ->
                            Surface(onClick = { onLabel(l.name) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.weight(1f).height(84.dp)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(l.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                        repeat((3 - labels.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (usedColors.isNotEmpty()) {
                item { SectionHeader("Colors") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        usedColors.forEach { c ->
                            Box(Modifier.size(46.dp).clip(CircleShape).background(noteColor(c, dark) ?: MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { onColor(c) })
                        }
                    }
                }
            }
        }
        return
    }
    val hits = live.filter { n ->
        (q.isEmpty() || NoteOps.matches(n, q)) && (type == null || type.matches(n)) && (color == null || n.color == color) && (label == null || label in NoteOps.labels(n))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            type?.let { InputChip(true, { onType(null) }, label = { Text(it.label) }, leadingIcon = { Icon(it.icon, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }) }
            label?.let { InputChip(true, { onLabel(null) }, label = { Text(it) }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(18.dp)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }) }
            color?.let { c -> InputChip(true, { onColor(null) }, label = { Text(NoteStyle.colors.getOrNull(c)?.name ?: "Color") },
                leadingIcon = { Box(Modifier.size(16.dp).clip(CircleShape).background(noteColor(c, dark) ?: Color.Transparent)) }, trailingIcon = { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }) }
        }
        if (hits.isEmpty()) EmptyState(Icons.Rounded.SearchOff, "No matching results", "Search looks through titles, text, list items, labels, words in pictures and voice transcripts.")
        else LazyVerticalStaggeredGrid(
            StaggeredGridCells.Fixed(if (grid) 2 else 1), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp, modifier = Modifier.weight(1f),
        ) {
            items(hits, key = { it.id }) { n -> NoteCard(n, false, onClick = { shell.go("note/${n.id}") }, onLong = {}) }
        }
    }
}

// ====================================================================== multi-select

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSelectionBar(selected: MutableList<Long>, profile: Profile, view: NoteView, onClose: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    suspend fun picked(): List<NoteEntity> = selected.toList().mapNotNull { app.db.notes().get(it) }
    suspend fun each(block: suspend (NoteEntity) -> Unit) = picked().forEach { block(it) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Done") } },
        title = { Text("${selected.size}") },
        actions = {
            IconButton(onClick = { scope.launch { val allPinned = picked().all { it.pinned }; each { NoteOps.save(app, it.copy(pinned = !allPinned, archived = if (!allPinned) false else it.archived)) }; onClose() } }) { Icon(Icons.Outlined.PushPin, "Pin") }
            IconButton(onClick = { dialog = "reminder" }) { Icon(Icons.Rounded.AddAlert, "Remind me") }
            IconButton(onClick = { dialog = "color" }) { Icon(Icons.Rounded.Palette, "Color") }
            IconButton(onClick = { dialog = "labels" }) { Icon(Icons.AutoMirrored.Rounded.Label, "Labels") }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(more, { more = false }) {
                    val unarchive = view == NoteView.Archive
                    DropdownMenuItem(text = { Text(if (unarchive) "Unarchive" else "Archive") }, leadingIcon = { Icon(if (unarchive) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) }, onClick = {
                        more = false
                        scope.launch {
                            val list = picked(); list.forEach { NoteOps.save(app, it.copy(archived = !unarchive, pinned = false)) }
                            shell.toast(if (unarchive) "Unarchived" else "${list.size} archived", "Undo") { app.io { list.forEach { NoteOps.save(app, it) } } }
                            onClose()
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                        more = false
                        scope.launch {
                            val list = picked(); list.forEach { NoteOps.trash(app, it) }
                            shell.toast("${list.size} moved to Trash", "Undo") { app.io { list.forEach { n -> app.db.notes().get(n.id)?.let { NoteOps.save(app, it.copy(trashedAt = null, pinned = n.pinned)) } } } }
                            onClose()
                        }
                    })
                    DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = { more = false; scope.launch { each { NoteOps.copy(app, it) }; onClose() } })
                    DropdownMenuItem(text = { Text("Send") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) }, onClick = {
                        more = false; scope.launch { val list = picked(); Files.shareText(ctx, list.joinToString("\n\n———\n\n") { NoteOps.plainText(it) }, if (list.size == 1) list[0].title.ifBlank { null } else "Notes"); onClose() }
                    })
                    DropdownMenuItem(text = { Text("Send to notebook") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, onClick = { more = false; dialog = "toPage" })
                    DropdownMenuItem(text = { Text("Move to top") }, leadingIcon = { Icon(Icons.Rounded.VerticalAlignTop, null) }, onClick = {
                        more = false; scope.launch { each { n -> NoteOps.save(app, n.copy(sortOrder = (app.db.notes().maxOrder(n.profile) ?: 0) + 1)) }; onClose() }
                    })
                }
            }
        },
    )
    when (dialog) {
        "color" -> PaletteSheet(-1, "-", LocalBlColors.current.isDark, onDismiss = { dialog = null }, onColor = { c -> scope.launch { each { NoteOps.save(app, it.copy(color = c)) } } }, onBg = { b -> scope.launch { each { NoteOps.save(app, it.copy(bg = b)) } } })
        "labels" -> LabelsDialog(profile, emptyList(), onDismiss = { dialog = null }) { add -> scope.launch { if (add.isNotEmpty()) each { NoteOps.save(app, it.copy(labels = NoteOps.joinLabels(NoteOps.labels(it) + add))) }; onClose() } }
        "reminder" -> ReminderDialog(null, null, null, onDismiss = { dialog = null }) { r ->
            scope.launch {
                each {
                    NoteOps.save(app, when {
                        r.place != null -> it.copy(reminderAt = null, repeatRule = null, placeName = r.place.name, placeLat = r.place.lat, placeLon = r.place.lon, placeRadius = r.place.radius, placeOnLeave = r.place.onLeave)
                        r.at != null -> it.copy(reminderAt = r.at, repeatRule = r.repeat, placeLat = null, placeLon = null, placeName = "")
                        else -> it.copy(reminderAt = null, repeatRule = null, placeLat = null, placeLon = null, placeName = "")
                    })
                }
                onClose()
            }
        }
        "toPage" -> SectionPickerDialog("Send to notebook section", profile, onDismiss = { dialog = null }) { s ->
            scope.launch { runCatching { each { NoteOps.toPage(app, it, s.id) } }.onSuccess { shell.toast("Sent to ${s.name}") }.onFailure { shell.toast("Unlock “${s.name}” first") }; onClose() }
        }
    }
}

// ====================================================================== labels

@Composable
fun EditLabelsDialog(profile: Profile, onDismiss: () -> Unit) {
    val app = rememberApp()
    val labels by remember(profile) { app.db.notes().observeLabels(profile.name) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var rename by remember { mutableStateOf<NoteLabelEntity?>(null) }
    var remove by remember { mutableStateOf<NoteLabelEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    fun add() { val n = newName.trim(); if (n.isNotEmpty() && labels.none { it.name.equals(n, true) }) app.io { app.db.notes().insertLabel(NoteLabelEntity(profile = profile.name, name = n)) }; newName = "" }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Edit labels") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newName, { newName = it }, placeholder = { Text("Create new label") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
                IconButton(onClick = { add() }) { Icon(Icons.Rounded.Check, "Add") }
            }
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(labels, key = { it.id }) { l ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(10.dp))
                        Text(l.name, Modifier.weight(1f))
                        IconButton(onClick = { rename = l }) { Icon(Icons.Rounded.Edit, "Rename") }
                        IconButton(onClick = { remove = l }) { Icon(Icons.Rounded.Delete, "Delete") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
    rename?.let { l -> TextInputDialog("Rename label", l.name, "Label", onDismiss = { rename = null }) { n -> n.trim().takeIf { it.isNotEmpty() }?.let { app.io { NoteOps.renameLabel(app, profile, l, it) } } } }
    remove?.let { l -> ConfirmDialog("Delete label “${l.name}”?", "The label is removed from all notes. Your notes aren't deleted.", "Delete", onDismiss = { remove = null }) { app.io { NoteOps.deleteLabel(app, profile, l) } } }
}

// ====================================================================== Home dashboard card

/** Which notes the Home card shows, from the Notes › on Home settings. */
fun dashboardNotes(notes: List<NoteEntity>, s: Settings): List<NoteEntity> {
    val live = notes.filter { it.trashedAt == null && !it.archived }
    val pinned = live.filter { it.pinned }
    val recent = live.sortedByDescending { it.updatedAt }
    return when (s.notesHomeMode) {
        "PINNED" -> pinned
        "RECENT" -> recent
        else -> pinned + recent.filter { !it.pinned }
    }.take(s.notesHomeCount)
}

/** The Notes card on Home: a Take a note bar and your pinned / latest notes, two across. */
@Composable
fun NotesDashboardCard(notes: List<NoteEntity>, s: Settings) {
    val shell = LocalShell.current
    val shown = dashboardNotes(notes, s)
    Column {
        if (s.notesHomeBar) Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Take a note…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).clickable { shell.go(newNoteRoute()) }.padding(vertical = 14.dp))
                IconButton(onClick = { shell.go(newNoteRoute("checklist")) }) { Icon(Icons.Rounded.CheckBox, "New list") }
                IconButton(onClick = { shell.go(newNoteRoute("drawing")) }) { Icon(Icons.Rounded.Brush, "New drawing") }
                IconButton(onClick = { shell.go(newNoteRoute("voice")) }) { Icon(Icons.Rounded.Mic, "New voice note") }
                IconButton(onClick = { shell.go(newNoteRoute("image")) }) { Icon(Icons.Rounded.Image, "New note with image") }
            }
        }
        if (shown.isEmpty()) {
            Text(if (s.notesHomeMode == "PINNED") "Pin a note in Notes to keep it here." else "Notes you add appear here.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
        } else {
            // Two staggered columns, like the board (the Home list can't nest a lazy grid)
            val cols = listOf(shown.filterIndexed { i, _ -> i % 2 == 0 }, shown.filterIndexed { i, _ -> i % 2 == 1 })
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cols.forEach { col ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        col.forEach { n -> NoteCard(n, false, onClick = { shell.go("note/${n.id}") }, onLong = { shell.go("notes") }, maxBodyLines = 5, maxItems = 4) }
                    }
                }
            }
        }
    }
}
