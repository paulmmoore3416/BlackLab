package com.blacklab.app.ui.library

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.files.LibraryOps
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.pressable
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Per-tab sort, grouping and view, remembered on this phone. */
private class BrowserPrefs(context: Context, private val tab: String) {
    private val p = context.getSharedPreferences("blacklab_library", Context.MODE_PRIVATE)
    var sort: LibraryOps.Sort get() = LibraryOps.Sort.of(p.getString("sort_$tab", null)); set(v) { p.edit().putString("sort_$tab", v.name).apply() }
    var group: LibraryOps.Group get() = LibraryOps.Group.of(p.getString("group_$tab", null)); set(v) { p.edit().putString("group_$tab", v.name).apply() }
    var grid: Boolean get() = p.getBoolean("grid_$tab", true); set(v) { p.edit().putBoolean("grid_$tab", v).apply() }
}

/**
 * A folder-aware browser for one Library tab: subfolders with cover thumbnails, breadcrumbs,
 * sort / group / grid-or-list / search, multi-select with Move, Copy, Delete, Share and the
 * photo tools, New folder, and "Sort into month folders". [canOrganize] = false keeps it read-only
 * for folders other screens track by location (Dictation sessions, the whole BlackLab tree).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderBrowser(root: File, tab: String, media: Boolean, canOrganize: Boolean, refresh: Int, onChanged: () -> Unit, emptyHint: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val prefs = remember(tab) { BrowserPrefs(ctx, tab) }
    var dir by remember(root) { mutableStateOf(root) }
    var sort by remember(tab) { mutableStateOf(prefs.sort) }
    var group by remember(tab) { mutableStateOf(prefs.group) }
    var grid by remember(tab) { mutableStateOf(prefs.grid && media) }
    var query by remember(tab) { mutableStateOf("") }
    var searching by remember(tab) { mutableStateOf(false) }
    var selected by remember(dir) { mutableStateOf(setOf<File>()) }
    var menuFile by remember { mutableStateOf<File?>(null) }
    var folderMenu by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<File?>(null) }
    var moveItems by remember { mutableStateOf<Pair<List<File>, Boolean>?>(null) } // items, copy?
    var confirmDelete by remember { mutableStateOf<List<File>?>(null) }
    var confirmAuto by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var bump by remember { mutableStateOf(0) }
    val changed = { bump++; onChanged() }
    var smartPlan by remember { mutableStateOf<Map<String, List<File>>?>(null) }
    var smartBusy by remember { mutableStateOf<String?>(null) }

    val entries by produceState(emptyList<File>(), dir, refresh, bump, sort, query) {
        value = withContext(Dispatchers.IO) {
            val all = dir.listFiles()?.filter { !it.name.startsWith(".") && (it.isDirectory || !media || it.isImage() || it.isVideo()) }.orEmpty()
            LibraryOps.sorted(all.filter { LibraryOps.matches(it, query) }, sort)
        }
    }
    val folders = entries.filter { it.isDirectory }
    val files = entries.filter { it.isFile }

    // After a move, keep the Recorder's database pointing at the right files
    suspend fun fixRecordings(moves: List<LibraryOps.Moved>) {
        val dao = app.db.recordings()
        moves.forEach { m ->
            if (m.to.isDirectory) m.to.walkTopDown().filter { it.isFile }.forEach { nf ->
                val old = File(m.from, nf.relativeTo(m.to).path)
                dao.byPath(app.storage.relative(old))?.let { dao.upsert(it.copy(filePath = app.storage.relative(nf))) }
            } else dao.byPath(app.storage.relative(m.from))?.let { dao.upsert(it.copy(filePath = app.storage.relative(m.to))) }
            app.storage.scan(m.from); app.storage.scan(m.to)
        }
    }

    FileActionHost(menuFile, onDismiss = { menuFile = null }, onChanged = changed, onSelect = { selected = selected + it },
        onMove = if (canOrganize) ({ f -> moveItems = listOf(f) to false }) else null)

    Column(Modifier.fillMaxSize()) {
        // ---- location + tools
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dir != root) IconButton(onClick = { dir.parentFile?.let { dir = it }; selected = emptySet() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up a folder") }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = if (dir == root) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                val crumbs = generateSequence(dir) { if (it == root) null else it.parentFile }.toList().reversed()
                crumbs.forEachIndexed { i, c ->
                    if (i > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (c == root) root.name else c.name, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        color = if (c == dir) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable(onClick = { dir = c; selected = emptySet() }, onLongClick = {}).padding(horizontal = 6.dp, vertical = 8.dp))
                }
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Rounded.SearchOff else Icons.Rounded.Search, "Search") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort and group") }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    Text("SORT", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                    LibraryOps.Sort.entries.forEach { o ->
                        DropdownMenuItem(text = { Text(o.label) }, trailingIcon = { if (o == sort) Icon(Icons.Rounded.Check, null) }, onClick = { sort = o; prefs.sort = o; sortMenu = false })
                    }
                    if (media) {
                        HorizontalDivider()
                        Text("GROUP", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
                        LibraryOps.Group.entries.forEach { o ->
                            DropdownMenuItem(text = { Text(o.label) }, trailingIcon = { if (o == group) Icon(Icons.Rounded.Check, null) }, onClick = { group = o; prefs.group = o; sortMenu = false })
                        }
                    }
                }
            }
            if (media) IconButton(onClick = { grid = !grid; prefs.grid = grid }) { Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "List view" else "Grid view") }
            if (canOrganize) Box {
                IconButton(onClick = { overflow = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(overflow, { overflow = false }) {
                    DropdownMenuItem(text = { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, onClick = { overflow = false; newFolder = true })
                    DropdownMenuItem(text = { Text("Sort into month folders") }, leadingIcon = { Icon(Icons.Rounded.CalendarMonth, null) }, onClick = { overflow = false; confirmAuto = true })
                    if (media && com.blacklab.app.assistant.Gemini.visionEnabled) DropdownMenuItem(text = { Text("Smart sort with Gemini") }, leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) }, onClick = {
                        overflow = false
                        val photos = files.filter { it.isImage() }.take(40)
                        if (photos.isEmpty()) shell.toast("No loose photos here to sort") else {
                            smartBusy = "Looking at ${photos.size} photo${if (photos.size == 1) "" else "s"}…"
                            app.io {
                                val existing = folders.map { it.name }.toMutableList()
                                val plan = LinkedHashMap<String, MutableList<File>>()
                                photos.forEachIndexed { i, f ->
                                    withContext(Dispatchers.Main) { smartBusy = "Looking at photo ${i + 1} of ${photos.size}…" }
                                    val name = com.blacklab.app.assistant.Gemini.suggestFolder(f, existing)?.let { LibraryOps.cleanName(it) }?.ifBlank { null } ?: return@forEachIndexed
                                    plan.getOrPut(name) { mutableListOf() } += f
                                    if (name !in existing) existing += name
                                }
                                withContext(Dispatchers.Main) { smartBusy = null; if (plan.isEmpty()) shell.toast("Gemini couldn't suggest folders right now") else smartPlan = plan }
                            }
                        }
                    })
                    DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Rounded.SelectAll, null) }, onClick = { overflow = false; selected = entries.toSet() })
                }
            }
        }
        if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
            placeholder = { Text("Search ${dir.name}") }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(28.dp))

        // ---- selection bar
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear selection") }
            Text("${selected.size} selected", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
            IconButton(onClick = { selected = if (selected.size == entries.size) emptySet() else entries.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
            if (canOrganize) {
                IconButton(onClick = { moveItems = selected.toList() to false }) { Icon(Icons.Rounded.DriveFileMove, "Move to folder") }
                IconButton(onClick = { moveItems = selected.toList() to true }) { Icon(Icons.Rounded.FileCopy, "Copy to folder") }
            }
            val selFiles = selected.filter { it.isFile }
            if (selFiles.isNotEmpty()) IconButton(onClick = { Files.share(ctx, selFiles) }) { Icon(Icons.Rounded.Share, "Share") }
            if (media && selFiles.isNotEmpty()) SelectionMore(selFiles, onDone = { selected = emptySet(); changed() })
            if (canOrganize) IconButton(onClick = { confirmDelete = selected.toList() }) { Icon(Icons.Rounded.Delete, "Delete") }
        }

        if (entries.isEmpty()) {
            EmptyState(if (query.isNotBlank()) Icons.Rounded.SearchOff else if (dir == root) Icons.Rounded.PhotoLibrary else Icons.Rounded.FolderOpen,
                if (query.isNotBlank()) "No matches" else if (dir == root) "Nothing here yet" else "Empty folder",
                if (query.isNotBlank()) "Try another word." else if (dir == root) emptyHint else "Move items here from the selection bar, or long-press an item and choose Move to folder.")
            return@Column
        }

        val tap: (File) -> Unit = { f ->
            when {
                selected.isNotEmpty() -> selected = if (f in selected) selected - f else selected + f
                f.isDirectory -> { dir = f; query = "" }
                media -> shell.go("media?path=" + Uri.encode(app.storage.relative(f)))
                else -> com.blacklab.app.ui.viewer.openInApp(shell, app, f)
            }
        }
        val hold: (File) -> Unit = { f ->
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            when {
                selected.isNotEmpty() -> selected = if (f in selected) selected - f else selected + f
                f.isDirectory -> folderMenu = f
                else -> menuFile = f
            }
        }

        LazyVerticalGrid(
            if (grid) GridCells.Adaptive(108.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(if (grid) 4.dp else 0.dp),
        ) {
            if (folders.isNotEmpty()) {
                item(key = "fh", span = { GridItemSpan(maxLineSpan) }) { Text("Folders · ${folders.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp)) }
                items(folders, key = { "d" + it.path }) { d -> FolderTile(d, grid, media, d in selected, onClick = { tap(d) }, onLong = { hold(d) }) }
            }
            val groups = if (media && group != LibraryOps.Group.NONE) files.groupBy { LibraryOps.groupKey(it, group) } else mapOf("" to files)
            groups.forEach { (label, list) ->
                if (label.isNotEmpty() || folders.isNotEmpty()) item(key = "g$label", span = { GridItemSpan(maxLineSpan) }) {
                    Text(label.ifEmpty { "Files · ${files.size}" } + if (label.isNotEmpty()) " · ${list.size}" else "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                }
                items(list, key = { it.path }) { f ->
                    val sel = f in selected
                    if (grid) Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(onClick = { tap(f) }, onLongClick = { hold(f) })
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)) else Modifier)) {
                        AsyncImage(ImageRequest.Builder(ctx).data(f).size(320).apply { if (f.isVideo()) decoderFactory(VideoFrameDecoder.Factory()) }.crossfade(true).build(),
                            contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        if (f.isVideo()) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                        if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    } else FileRow(f, sel, onClick = { tap(f) }, onLong = { hold(f) }, onMore = { menuFile = f })
                }
            }
        }
    }

    // ---- dialogs
    smartBusy?.let { msg ->
        AlertDialog(onDismissRequest = {}, title = { Text("Smart sort") }, text = {
            Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(14.dp)); Text(msg) }
        }, confirmButton = {})
    }
    smartPlan?.let { plan ->
        AlertDialog(onDismissRequest = { smartPlan = null }, title = { Text("Sort into these folders?") }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Gemini looked at each photo (sent to Google, downscaled). Nothing moves until you confirm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan.forEach { (folder, list) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp))
                        Text(folder, Modifier.weight(1f)); Text("${list.size}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = {
                smartPlan = null
                app.io {
                    var n = 0
                    plan.forEach { (folder, list) -> n += LibraryOps.move(list, File(dir, folder)).count { it.from in list } }
                    withContext(Dispatchers.Main) { changed(); shell.toast("Sorted $n photo${if (n == 1) "" else "s"} into ${plan.size} folder${if (plan.size == 1) "" else "s"}") }
                }
            }) { Text("Sort") }
        }, dismissButton = { TextButton(onClick = { smartPlan = null }) { Text("Cancel") } })
    }
    if (newFolder) TextInputDialog("New folder in ${dir.name}", label = "Folder name", confirmLabel = "Create", onDismiss = { newFolder = false }) { name ->
        app.io {
            val f = LibraryOps.newFolder(dir, name)
            withContext(Dispatchers.Main) { if (f == null) shell.toast("Couldn't create that folder") else { changed(); shell.toast("Created ${f.name}") } }
        }
    }
    folderMenu?.let { d ->
        AlertDialog(onDismissRequest = { folderMenu = null }, title = { Text(d.name) }, text = {
            Column {
                Text("${d.listFiles()?.size ?: 0} items · ${Files.formatSize(LibraryOps.size(d))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOfNotNull(
                    Triple("Open", Icons.Rounded.FolderOpen) { dir = d },
                    if (canOrganize) Triple("Rename", Icons.Rounded.DriveFileRenameOutline) { renameFolder = d } else null,
                    if (canOrganize) Triple("Move to…", Icons.Rounded.DriveFileMove) { moveItems = listOf(d) to false } else null,
                    Triple("Select", Icons.Rounded.CheckCircle) { selected = selected + d },
                    if (canOrganize) Triple("Delete folder", Icons.Rounded.Delete) { confirmDelete = listOf(d) } else null,
                ).forEach { (label, icon, run) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(onClick = { folderMenu = null; run() }, onLongClick = {}).padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Text(label)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { folderMenu = null }) { Text("Close") } })
    }
    renameFolder?.let { d ->
        TextInputDialog("Rename folder", initial = d.name, label = "Name", onDismiss = { renameFolder = null }) { name ->
            app.io {
                val out = LibraryOps.rename(d, name)
                if (out != null && out != d) fixRecordings(listOf(LibraryOps.Moved(d, out)))
                withContext(Dispatchers.Main) { changed(); if (out == null) shell.toast("Couldn't rename") }
            }
        }
    }
    moveItems?.let { (items, copy) ->
        MoveDialog(root, items, copy, onDismiss = { moveItems = null }) { dest ->
            app.io {
                val n = if (copy) LibraryOps.copy(items, dest).size.also { _ -> app.storage.scan(dest) }
                else LibraryOps.move(items, dest).also { fixRecordings(it) }.count { it.from in items }
                withContext(Dispatchers.Main) {
                    selected = emptySet(); changed()
                    shell.toast("${if (copy) "Copied" else "Moved"} $n item${if (n == 1) "" else "s"} to ${dest.name}", "Open") { dir = dest }
                }
            }
        }
    }
    confirmDelete?.let { list ->
        val hasFolders = list.any { it.isDirectory }
        ConfirmDialog("Delete ${list.size} item${if (list.size == 1) "" else "s"}?",
            if (hasFolders) "Folders are deleted with everything inside them. This can't be undone." else "Files will be permanently removed from BlackLab.",
            onDismiss = { confirmDelete = null }) {
            selected = emptySet()
            app.io {
                list.forEach { f ->
                    if (f.isFile) app.db.recordings().byPath(app.storage.relative(f))?.let { r -> app.db.recordings().delete(r) }
                    f.deleteRecursively(); app.storage.scan(f)
                }
                withContext(Dispatchers.Main) { changed() }
            }
        }
    }
    if (confirmAuto) ConfirmDialog("Sort into month folders?", "Files directly in ${dir.name} move into folders like “${LibraryOps.monthFolderName(System.currentTimeMillis())}”, by the date they were taken or saved. Existing folders stay as they are.",
        confirmLabel = "Sort", destructive = false, onDismiss = { confirmAuto = false }) {
        app.io {
            val moved = LibraryOps.autoSortByMonth(dir).also { fixRecordings(it) }
            withContext(Dispatchers.Main) { changed(); shell.toast("Sorted ${moved.size} item${if (moved.size == 1) "" else "s"} into month folders") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(d: File, grid: Boolean, media: Boolean, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val info by produceState<Pair<Int, File?>>(0 to null, d) {
        value = withContext(Dispatchers.IO) {
            val kids = d.listFiles()?.filter { !it.name.startsWith(".") }.orEmpty()
            kids.size to if (media) kids.filter { it.isFile && (it.isImage() || it.isVideo()) }.maxByOrNull { it.lastModified() } else null
        }
    }
    val border = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier
    if (grid) Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .combinedClickable(onClick = onClick, onLongClick = onLong).then(border)) {
        info.second?.let { cover ->
            AsyncImage(ImageRequest.Builder(ctx).data(cover).size(320).apply { if (cover.isVideo()) decoderFactory(VideoFrameDecoder.Factory()) }.build(), null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.55f)
        }
        Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(22.dp))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
            Text(d.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("${info.first} items", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    } else Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLong).then(border).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${info.first} items · ${Time.ago(d.lastModified())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(f: File, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit, onMore: () -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLong)
        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (f.isImage() || f.isVideo()) AsyncImage(ImageRequest.Builder(ctx).data(f).size(120).apply { if (f.isVideo()) decoderFactory(VideoFrameDecoder.Factory()) }.build(), null,
            contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
        else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Files.formatSize(f.length())} · ${Time.ago(f.lastModified())}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        else IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "More") }
    }
}

/** Pick a destination folder under [root] (or make a new one) for moving or copying [items]. */
@Composable
private fun MoveDialog(root: File, items: List<File>, copy: Boolean, onDismiss: () -> Unit, onPick: (File) -> Unit) {
    var bump by remember { mutableStateOf(0) }
    var newIn by remember { mutableStateOf<File?>(null) }
    val tree by produceState(emptyList<Pair<File, Int>>(), bump) { value = withContext(Dispatchers.IO) { LibraryOps.folderTree(root) } }
    // A folder can't go inside itself
    val blocked = items.filter { it.isDirectory }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${if (copy) "Copy" else "Move"} ${items.size} item${if (items.size == 1) "" else "s"} to…") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            tree.forEach { (d, depth) ->
                val disabled = blocked.any { LibraryOps.inside(d, it) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).then(if (disabled) Modifier else Modifier.pressable(onClick = { onPick(d); onDismiss() }, onLongClick = {}))
                    .padding(start = (8 + depth * 18).dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (depth == 0) Icons.Rounded.Home else Icons.Rounded.Folder, null,
                        tint = if (disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (depth == 0) "${root.name} (top level)" else d.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    if (!disabled) IconButton(onClick = { newIn = d }, Modifier.size(32.dp)) { Icon(Icons.Rounded.CreateNewFolder, "New folder inside ${d.name}", Modifier.size(18.dp)) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { newIn = root }) { Text("New folder") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    newIn?.let { parent ->
        TextInputDialog("New folder in ${parent.name}", label = "Folder name", confirmLabel = "Create", onDismiss = { newIn = null }) { name ->
            LibraryOps.newFolder(parent, name); bump++
        }
    }
}
