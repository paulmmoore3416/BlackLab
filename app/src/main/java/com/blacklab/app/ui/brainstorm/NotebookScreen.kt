package com.blacklab.app.ui.brainstorm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.SectionKeys
import com.blacklab.app.core.Files
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.PageEntity
import com.blacklab.app.data.SectionEntity
import com.blacklab.app.ui.components.ActionSheet
import com.blacklab.app.ui.components.ColorPickerRow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SheetAction
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookScreen(notebookId: Long, startSection: Long = 0) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = app.db.brainstorm()
    val prefs = remember { ctx.getSharedPreferences("blacklab_brainstorm", android.content.Context.MODE_PRIVATE) }
    val notebook by dao.observeNotebook(notebookId).collectAsState(initial = null)
    val sections by dao.observeSections(notebookId).collectAsState(initial = emptyList())
    var selected by remember { mutableLongStateOf(if (startSection > 0) startSection else prefs.getLong("nb_${notebookId}_sec", 0L)) }
    val current = sections.firstOrNull { it.id == selected } ?: sections.firstOrNull()
    LaunchedEffect(current?.id) { current?.let { prefs.edit().putLong("nb_${notebookId}_sec", it.id).apply() } }
    val pages by remember(current?.id) { dao.observePages(current?.id ?: -1) }.collectAsState(initial = emptyList())
    var unlockTick by remember { mutableIntStateOf(0) }
    val unlocked = remember(current?.id, unlockTick) { current?.let { !it.locked || SectionKeys.get(it.id) != null } ?: true }

    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var sectionSheet by remember { mutableStateOf<SectionEntity?>(null) }
    var pageSheet by remember { mutableStateOf<PageEntity?>(null) }
    var editSection by remember { mutableStateOf<SectionEntity?>(null) }
    var pagePick by remember { mutableStateOf<PageEntity?>(null) }

    fun newPage(template: String = "blank", level: Int = 0, after: PageEntity? = null) {
        val s = current ?: return
        if (!unlocked) { dialog = "unlock"; return }
        scope.launch {
            runCatching { Brainstorm.createPage(app, Profile.from(notebook?.profile ?: app.settings.current.activeProfile.name), s.id, template, "", level, after) }
                .onSuccess { shell.go("page/$it") }.onFailure { shell.toast("Couldn't create the page: ${it.message}") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp, 22.dp).clip(RoundedCornerShape(3.dp)).background(Color(notebook?.color ?: 0xFF7C5CFF.toInt())))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(notebook?.name ?: "Notebook", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${sections.size} section" + if (sections.size == 1) "" else "s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = { shell.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { shell.go("brainstorm?tab=search") }) { Icon(Icons.Rounded.Search, "Search") }
                    IconButton(onClick = { editSection = SectionEntity(notebookId = notebookId, name = "New Section ${sections.size + 1}", color = Brainstorm.SectionColors[(sections.size + 1) % Brainstorm.SectionColors.size]); dialog = "section" }) {
                        Icon(Icons.Rounded.PostAdd, "New section")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename notebook") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; dialog = "rename" })
                            DropdownMenuItem(text = { Text("Notebook color") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = { menu = false; dialog = "color" })
                            DropdownMenuItem(text = { Text("Export notebook (.blnotebook)") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = {
                                menu = false
                                notebook?.let { nb -> scope.launch { runCatching { Brainstorm.exportNotebook(app, nb) }.onSuccess { f -> Files.share(ctx, listOf(f), nb.name); shell.toast("Saved to Exports › ${f.name}") }.onFailure { shell.toast("Export failed: ${it.message}") } } }
                            })
                            if (SectionKeys.anyUnlocked) DropdownMenuItem(text = { Text("Lock all sections") }, leadingIcon = { Icon(Icons.Rounded.Lock, null) }, onClick = { menu = false; SectionKeys.lockAll(); unlockTick++ })
                            DropdownMenuItem(text = { Text("Delete notebook", color = LocalBlColors.current.danger) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = LocalBlColors.current.danger) }, onClick = { menu = false; dialog = "delete" })
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (current != null) {
                Column(horizontalAlignment = Alignment.End) {
                    SmallFloatingActionButton(onClick = { dialog = "template" }, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) { Icon(Icons.Rounded.Dashboard, "From template") }
                    Spacer(Modifier.height(10.dp))
                    ExtendedFloatingActionButton(onClick = { newPage() }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Page") })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // binder tabs
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                var lastGroup = ""
                sections.forEach { s ->
                    if (s.groupName.isNotBlank() && s.groupName != lastGroup) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.FolderOpen, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(s.groupName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                    lastGroup = s.groupName
                    val sel = s.id == current?.id
                    val c = Color(s.color)
                    Surface(
                        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 4.dp, bottomEnd = 4.dp),
                        color = if (sel) c.copy(alpha = 0.28f) else c.copy(alpha = 0.10f),
                        border = BorderStroke(if (sel) 2.dp else 1.dp, c.copy(alpha = if (sel) 0.9f else 0.4f)),
                        modifier = Modifier.pressable(onClick = { selected = s.id }, onLongClick = { sectionSheet = s }),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (s.locked) { Icon(if (SectionKeys.get(s.id) != null) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                            Text(s.name, style = MaterialTheme.typography.labelLarge, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
            HorizontalDivider(color = current?.let { Color(it.color).copy(alpha = 0.6f) } ?: MaterialTheme.colorScheme.outlineVariant, thickness = 2.dp)
            when {
                current == null -> EmptyState(Icons.Rounded.Folder, "No sections", "Add a section to start writing pages.") {
                    Button(onClick = { editSection = SectionEntity(notebookId = notebookId, name = "New Section 1", color = Brainstorm.SectionColors[1]); dialog = "section" }) { Text("New section") }
                }
                !unlocked -> EmptyState(Icons.Rounded.Lock, "“${current.name}” is password protected", "Its pages are encrypted on this phone." + if (current.lockHint.isNotBlank()) "\nHint: ${current.lockHint}" else "") {
                    Button(onClick = { dialog = "unlock" }) { Text("Unlock") }
                }
                pages.isEmpty() -> EmptyState(Icons.AutoMirrored.Rounded.NoteAdd, "No pages yet", "Tap + Page, or start from a template — meeting notes, Cornell lecture notes, mind map and more.")
                else -> LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 140.dp)) {
                    itemsIndexed(pages, key = { _, p -> p.id }) { _, p ->
                        Surface(
                            shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(start = (p.level * 22).dp, top = 3.dp, bottom = 3.dp).pressable(onClick = { shell.go("page/${p.id}") }, onLongClick = { pageSheet = p }),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp, 34.dp).clip(RoundedCornerShape(2.dp)).background(Color(current.color).copy(alpha = if (p.level == 0) 0.9f else 0.45f)))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(p.title.ifBlank { "Untitled page" }, style = if (p.level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOf(Time.ago(p.updatedAt), p.preview.take(80)).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { if (current.locked) "Encrypted" else "" },
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (p.reminderAt != null) Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                if (p.tagIndex.isNotBlank()) { Spacer(Modifier.width(6.dp)); Icon(Icons.Rounded.Sell, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------------- sheets & dialogs
    sectionSheet?.let { s ->
        ActionSheet(
            title = s.name, subtitle = if (s.locked) "Password protected" else if (s.groupName.isNotBlank()) "Group: ${s.groupName}" else null, onDismiss = { sectionSheet = null },
            actions = listOfNotNull(
                SheetAction("Rename, color & group", Icons.Rounded.Edit) { editSection = s; dialog = "section" },
                SheetAction("Move left", Icons.Rounded.ChevronLeft) { scope.launch { moveSection(app, sections, s, -1) } },
                SheetAction("Move right", Icons.Rounded.ChevronRight) { scope.launch { moveSection(app, sections, s, 1) } },
                if (!s.locked) SheetAction("Password protect", Icons.Rounded.Lock, hint = "Encrypts every page in this section") { selected = s.id; dialog = "lock" } else null,
                if (s.locked && SectionKeys.get(s.id) != null) SheetAction("Lock now", Icons.Rounded.Lock) { SectionKeys.lock(s.id); unlockTick++ } else null,
                if (s.locked) SheetAction("Remove password", Icons.Rounded.LockOpen) { selected = s.id; dialog = "unlockRemove" } else null,
                SheetAction("Merge into another section", Icons.AutoMirrored.Rounded.CallMerge) { selected = s.id; dialog = "merge" },
                SheetAction("Delete section", Icons.Rounded.Delete, destructive = true, hint = "Pages go to the recycle bin") { selected = s.id; dialog = "deleteSection" },
            ),
        )
    }
    pageSheet?.let { p ->
        val idx = pages.indexOfFirst { it.id == p.id }
        ActionSheet(
            title = p.title.ifBlank { "Untitled page" }, subtitle = "Edited " + Time.ago(p.updatedAt), onDismiss = { pageSheet = null },
            quick = listOf(
                SheetAction("Open", Icons.AutoMirrored.Rounded.OpenInNew) { shell.go("page/${p.id}") },
                SheetAction("Subpage", Icons.Rounded.SubdirectoryArrowRight) { newPage("blank", (p.level + 1).coerceAtMost(2), p) },
                SheetAction("Duplicate", Icons.Rounded.ContentCopy) { scope.launch { runCatching { Brainstorm.duplicatePage(app, p) }.onFailure { shell.toast("Couldn't copy: ${it.message}") } } },
            ),
            actions = listOfNotNull(
                if (p.level < 2) SheetAction("Make subpage", Icons.AutoMirrored.Rounded.FormatIndentIncrease) { app.io { dao.updatePage(p.copy(level = p.level + 1)) } } else null,
                if (p.level > 0) SheetAction("Promote", Icons.AutoMirrored.Rounded.FormatIndentDecrease) { app.io { dao.updatePage(p.copy(level = p.level - 1)) } } else null,
                if (idx > 0) SheetAction("Move up", Icons.Rounded.ArrowUpward) { scope.launch { Brainstorm.reorder(app, pages.toMutableList().apply { add(idx - 1, removeAt(idx)) }) } } else null,
                if (idx in 0 until pages.lastIndex) SheetAction("Move down", Icons.Rounded.ArrowDownward) { scope.launch { Brainstorm.reorder(app, pages.toMutableList().apply { add(idx + 1, removeAt(idx)) }) } } else null,
                SheetAction("Move to…", Icons.AutoMirrored.Rounded.DriveFileMove) { pagePick = p; dialog = "movePage" },
                SheetAction("Copy to…", Icons.Rounded.FileCopy) { pagePick = p; dialog = "copyPage" },
                SheetAction(if (p.reminderAt != null) "Reminder · ${Time.dateTime(p.reminderAt)}" else "Remind me", Icons.Rounded.NotificationsActive) { pagePick = p; dialog = "pageReminder" },
                SheetAction("Delete", Icons.Rounded.Delete, destructive = true) { scope.launch { Brainstorm.trashPage(app, p); shell.toast("Moved to recycle bin", "Undo") { app.io { Brainstorm.restorePage(app, p) } } } },
            ),
        )
    }

    when (dialog) {
        "template" -> TemplatePickerDialog({ dialog = null }) { newPage(it.id) }
        "rename" -> TextInputDialog("Rename notebook", notebook?.name.orEmpty(), "Name", onDismiss = { dialog = null }) { n -> notebook?.let { nb -> app.io { dao.updateNotebook(nb.copy(name = n)) } } }
        "color" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("Notebook color") },
            text = { ColorPickerRow(notebook?.color, { c -> notebook?.let { nb -> app.io { dao.updateNotebook(nb.copy(color = c)) } }; dialog = null }, Brainstorm.SectionColors) },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } })
        "delete" -> ConfirmDialog("Delete “${notebook?.name}”?", "All its sections are removed and its pages go to the recycle bin for ${Brainstorm.TRASH_DAYS} days.", onDismiss = { dialog = null }) {
            notebook?.let { nb -> scope.launch { Brainstorm.deleteNotebook(app, nb); shell.back() } }
        }
        "section" -> editSection?.let { s -> SectionDialog(s, sections.map { it.groupName }.filter { it.isNotBlank() }.distinct(), onDismiss = { dialog = null; editSection = null }) { name, color, group ->
            scope.launch {
                if (s.id == 0L) selected = Brainstorm.createSection(app, notebookId, name, color, group)
                else dao.updateSection(s.copy(name = name, color = color, groupName = group))
            }
        } }
        "unlock" -> current?.let { s -> PasswordDialog("Unlock “${s.name}”", "Enter the section password.", hint = s.lockHint, onDismiss = { dialog = null }) { pw, _ ->
            scope.launch { if (Brainstorm.unlockSection(s, pw)) { dialog = null; unlockTick++ } else shell.toast("Wrong password") }
        } }
        "lock" -> current?.let { s -> PasswordDialog("Protect “${s.name}”", "Pages, pictures, recordings and history in this section are encrypted (AES-256). There is no way to recover a forgotten password.", confirm = true, withHint = true, onDismiss = { dialog = null }) { pw, hint ->
            dialog = null
            scope.launch { runCatching { Brainstorm.lockSection(app, s, pw, hint) }.onSuccess { shell.toast("Section protected"); unlockTick++ }.onFailure { shell.toast("Couldn't protect: ${it.message}") } }
        } }
        "unlockRemove" -> current?.let { s -> PasswordDialog("Remove password", "Pages in “${s.name}” will be decrypted.", hint = s.lockHint, onDismiss = { dialog = null }) { pw, _ ->
            scope.launch { if (Brainstorm.removeLock(app, s, pw)) { dialog = null; unlockTick++; shell.toast("Password removed") } else shell.toast("Wrong password") }
        } }
        "merge" -> current?.let { s -> SectionPickerDialog("Merge “${s.name}” into", Profile.from(notebook?.profile ?: "PERSONAL"), excludeSection = s.id, onDismiss = { dialog = null }) { target ->
            scope.launch {
                runCatching { dao.pages(s.id).forEach { Brainstorm.movePage(app, it, target.id) }; Brainstorm.deleteSection(app, s); selected = target.id }
                    .onSuccess { shell.toast("Merged into ${target.name}") }.onFailure { shell.toast("Unlock both sections first") }
            }
        } }
        "deleteSection" -> current?.let { s -> ConfirmDialog("Delete “${s.name}”?", if (s.locked) "This section is password protected — its pages can't be restored after it's deleted." else "Its pages go to the recycle bin for ${Brainstorm.TRASH_DAYS} days.", onDismiss = { dialog = null }) {
            scope.launch { Brainstorm.deleteSection(app, s) }
        } }
        "movePage", "copyPage" -> pagePick?.let { p -> SectionPickerDialog(if (dialog == "movePage") "Move page to" else "Copy page to", Profile.from(p.profile), excludeSection = if (dialog == "movePage") p.sectionId else null, onDismiss = { dialog = null }) { target ->
            val move = dialog == "movePage"
            scope.launch {
                runCatching { if (move) Brainstorm.movePage(app, p, target.id) else Brainstorm.copyPageTo(app, p, target.id) }
                    .onSuccess { shell.toast((if (move) "Moved to " else "Copied to ") + target.name) }
                    .onFailure { shell.toast("Unlock the section first") }
            }
        } }
        "pageReminder" -> pagePick?.let { p -> ReminderDialog(p.reminderAt, null, null, allowPlace = false, onDismiss = { dialog = null }) { r ->
            app.io { dao.updatePage(p.copy(reminderAt = r.at)); app.requestReminderSync() }
        } }
    }
}

private suspend fun moveSection(app: com.blacklab.app.AppContainer, sections: List<SectionEntity>, s: SectionEntity, dir: Int) {
    val list = sections.toMutableList()
    val i = list.indexOfFirst { it.id == s.id }
    val j = i + dir
    if (i < 0 || j !in list.indices) return
    list.add(j, list.removeAt(i))
    list.forEachIndexed { k, x -> if (x.sortOrder != k) app.db.brainstorm().updateSection(x.copy(sortOrder = k)) }
}

@Composable
private fun SectionDialog(s: SectionEntity, groups: List<String>, onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    var name by remember { mutableStateOf(s.name) }
    var color by remember { mutableIntStateOf(s.color) }
    var group by remember { mutableStateOf(s.groupName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (s.id == 0L) "New section" else "Edit section") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ColorPickerRow(color, { color = it }, Brainstorm.SectionColors)
                OutlinedTextField(group, { group = it }, label = { Text("Section group (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g -> AssistChip({ group = g }, { Text(g) }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) }) }
                    AssistChip({ group = "" }, { Text("No group") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim().ifBlank { "Section" }, color, group.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
