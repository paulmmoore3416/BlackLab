package com.blacklab.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.OcrResultDialog
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.components.ScanDoneDialog
import com.blacklab.app.ui.components.ScanResult
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.components.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private val Tabs = listOf("PHOTOS" to "Photos", "VIDEOS" to "Videos", "SCANS" to "Scans", "RECORDINGS" to "Audio", "DICTATION" to "Dictation", "EXPORTS" to "Exports", "IMPORTS" to "Imports", "BROWSE" to "All files")
fun File.isImage() = extension.lowercase(Locale.US) in com.blacklab.app.files.FileKinds.image
fun File.isVideo() = com.blacklab.app.files.FileKinds.of(this) == com.blacklab.app.files.FileKind.VIDEO

@Composable
fun LibraryScreen(initialTab: String) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    var tab by rememberSaveable { mutableIntStateOf(Tabs.indexOfFirst { it.first == initialTab }.coerceAtLeast(0)) }
    var refresh by remember { mutableIntStateOf(0) }
    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    val scanner = rememberDocumentScanner(app, s.activeProfile) { scanResult = it; refresh++ }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) app.io {
            uris.forEach { uri ->
                val name = Files.displayName(ctx, uri)
                val f = Files.unique(app.storage.dir(s.activeProfile, Folder.IMPORTS), name.substringBeforeLast('.'), name.substringAfterLast('.', "bin"))
                Files.copyFromUri(ctx, uri, f)
            }
            withContext(Dispatchers.Main) { refresh++; shell.toast("Imported ${uris.size} file(s)") }
        }
    }

    Scaffold(topBar = {
        BlTopBar("Library", subtitle = if (app.storage.isPublic) "Internal storage › BlackLab › ${app.settings.profileName(s.activeProfile)}" else "App storage", actions = {
            IconButton(onClick = { shell.go("create") }) { Icon(Icons.Rounded.AddCircle, "Create & edit") }
            IconButton(onClick = { shell.go("music") }) { Icon(Icons.Rounded.LibraryMusic, "Music") }
            IconButton(onClick = { shell.go("camera") }) { Icon(Icons.Rounded.PhotoCamera, "Camera") }
            IconButton(onClick = { scanner() }) { Icon(Icons.Rounded.DocumentScanner, "Scan") }
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Upload, "Import") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                Tabs.forEachIndexed { i, (_, l) -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(l) }) }
            }
            when (Tabs[tab].first) {
                "PHOTOS" -> MediaGrid(app.storage.dir(s.activeProfile, Folder.PHOTOS), refresh) { refresh++ }
                "VIDEOS" -> MediaGrid(app.storage.dir(s.activeProfile, Folder.VIDEOS), refresh) { refresh++ }
                "RECORDINGS" -> {
                    Column(Modifier.padding(16.dp)) {
                        Text("Audio recordings live in the Recorder, with playback, markers and transcripts.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { shell.go("recorder") }) { Text("Open Recorder") }
                    }
                    FileList(app.storage.dir(s.activeProfile, Folder.RECORDINGS), refresh, browse = false) { refresh++ }
                }
                "DICTATION" -> {
                    Column(Modifier.padding(16.dp)) {
                        Text("Dictation sessions — each in its own dated folder with audio, transcript and location.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { shell.go("dictation") }) { Text("Open Dictation") }
                    }
                    FileList(app.storage.dir(s.activeProfile, Folder.DICTATION), refresh, browse = false) { refresh++ }
                }
                "SCANS" -> FileList(app.storage.dir(s.activeProfile, Folder.SCANS), refresh, browse = false) { refresh++ }
                "EXPORTS" -> FileList(app.storage.dir(s.activeProfile, Folder.EXPORTS), refresh, browse = false) { refresh++ }
                "IMPORTS" -> FileList(app.storage.dir(s.activeProfile, Folder.IMPORTS), refresh, browse = false) { refresh++ }
                else -> FileList(app.storage.root, refresh, browse = true) { refresh++ }
            }
        }
    }
    scanResult?.let { r -> ScanDoneDialog(r) { scanResult = null } }
}

@Composable
private fun MediaGrid(dir: File, refresh: Int, onChanged: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val files by produceState(emptyList<File>(), dir, refresh) {
        value = withContext(Dispatchers.IO) { dir.listFiles()?.filter { it.isFile && (it.isImage() || it.isVideo()) }?.sortedByDescending { it.lastModified() } ?: emptyList() }
    }
    var selected by remember { mutableStateOf(setOf<File>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuFile by remember { mutableStateOf<File?>(null) }
    FileActionHost(menuFile, onDismiss = { menuFile = null }, onChanged = onChanged, onSelect = { selected = selected + it })
    if (files.isEmpty()) { EmptyState(Icons.Rounded.PhotoLibrary, "Nothing here yet", "Capture with the BlackLab camera — everything is saved to ${dir.name}.") ; return }
    Column {
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Clear") }
            Text("${selected.size} selected", Modifier.weight(1f))
            IconButton(onClick = { selected = if (selected.size == files.size) emptySet() else files.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
            IconButton(onClick = { Files.share(ctx, selected.toList()) }) { Icon(Icons.Rounded.Share, "Share") }
            SelectionMore(selected.toList(), onDone = { selected = emptySet(); onChanged() })
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
        val grouped = files.groupBy { Time.monthYear(Time.toDate(it.lastModified())) }
        LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(4.dp, 4.dp, 4.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            grouped.forEach { (month, list) ->
                item(key = "h$month", span = { GridItemSpan(maxLineSpan) }) { Text(month, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp)) }
                items(list, key = { it.path }) { f ->
                    val sel = f in selected
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .combinedClickable(
                            onClick = { if (selected.isNotEmpty()) selected = if (sel) selected - f else selected + f else shell.go("media?path=" + Uri.encode(app.storage.relative(f))) },
                            onLongClick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); if (selected.isNotEmpty()) selected = if (sel) selected - f else selected + f else menuFile = f },
                        )
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else Modifier)) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(f).size(320).apply { if (f.isVideo()) decoderFactory(VideoFrameDecoder.Factory()) }.crossfade(true).build(),
                            contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                        if (f.isVideo()) Icon(Icons.Rounded.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                        if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                    }
                }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete ${selected.size} item(s)?", "Files will be permanently removed from BlackLab.", onDismiss = { confirmDelete = false }) {
        val list = selected.toList(); selected = emptySet()
        app.io { list.forEach { it.delete(); app.storage.scan(it) }; withContext(Dispatchers.Main) { onChanged() } }
    }
}

@Composable
private fun FileList(start: File, refresh: Int, browse: Boolean, onChanged: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var dir by remember(start) { mutableStateOf(start) }
    val entries by produceState(emptyList<File>(), dir, refresh) {
        value = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(compareBy<File>({ !it.isDirectory }, { -it.lastModified() })) ?: emptyList()
        }
    }
    val stats by produceState<Pair<Long, Int>?>(null, dir, refresh) {
        value = if (browse) withContext(Dispatchers.IO) { dir.walkTopDown().filter { it.isFile }.let { seq -> seq.sumOf { it.length() } to seq.count() } } else null
    }
    var menu by remember { mutableStateOf<File?>(null) }

    FileActionHost(menu, onDismiss = { menu = null }, onChanged = onChanged)
    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp)) {
        if (browse) item {
            BlCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (dir != start) IconButton(onClick = { dir.parentFile?.let { dir = it } }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Up") }
                    Column(Modifier.weight(1f)) {
                        Text(("BlackLab/" + dir.relativeTo(start).path).trimEnd('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        stats?.let { (bytes, count) -> Text("$count files · ${Files.formatSize(bytes)} · ${Files.formatSize(app.storage.freeBytes())} free on device", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (dir == start) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Folder location: ${start.absolutePath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (entries.isEmpty()) item { EmptyState(Icons.Rounded.FolderOpen, "Empty folder", "Files saved here will appear in this list.") }
        items(entries, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = {
                    when {
                        f.isDirectory -> dir = f
                        else -> com.blacklab.app.ui.viewer.openInApp(shell, app, f)
                    }
                }, onLongClick = { if (f.isFile) menu = f else dir = f }).padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (f.isImage()) AsyncImage(f, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (f.isDirectory) "${f.listFiles()?.size ?: 0} items" else "${Files.formatSize(f.length())} · ${Time.ago(f.lastModified())}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (f.isFile) IconButton(onClick = { menu = f }) { Icon(Icons.Rounded.MoreVert, "More") }
            }
        }
    }
}

fun iconFor(f: File) = when (com.blacklab.app.files.FileKinds.of(f)) {
    com.blacklab.app.files.FileKind.FOLDER -> Icons.Rounded.Folder
    com.blacklab.app.files.FileKind.IMAGE -> Icons.Rounded.Image
    com.blacklab.app.files.FileKind.VIDEO -> Icons.Rounded.Movie
    com.blacklab.app.files.FileKind.AUDIO -> Icons.Rounded.AudioFile
    com.blacklab.app.files.FileKind.PDF -> Icons.Rounded.PictureAsPdf
    com.blacklab.app.files.FileKind.WORD, com.blacklab.app.files.FileKind.RTF, com.blacklab.app.files.FileKind.ODT, com.blacklab.app.files.FileKind.TEXT -> Icons.Rounded.Description
    com.blacklab.app.files.FileKind.MARKDOWN, com.blacklab.app.files.FileKind.CODE, com.blacklab.app.files.FileKind.HTML -> Icons.Rounded.Code
    com.blacklab.app.files.FileKind.SHEET -> Icons.Rounded.TableChart
    com.blacklab.app.files.FileKind.SLIDES -> Icons.Rounded.Slideshow
    com.blacklab.app.files.FileKind.EPUB -> Icons.Rounded.MenuBook
    com.blacklab.app.files.FileKind.ARCHIVE -> Icons.Rounded.FolderZip
    com.blacklab.app.files.FileKind.CALENDAR -> Icons.Rounded.Event
    else -> Icons.Rounded.InsertDriveFile
}

/** Overflow for multi-selected photos/videos: one PDF, one video montage, or a ZIP. */
@Composable
private fun SelectionMore(files: List<File>, onDone: () -> Unit) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
    DropdownMenu(open, { open = false }) {
        if (files.all { it.isImage() }) DropdownMenuItem(text = { Text("Make a PDF") }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, onClick = {
            open = false
            app.io {
                val out = runCatching {
                    com.blacklab.app.files.PdfTools.init(ctx)
                    Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.SCANS), "Photos ${Time.date(System.currentTimeMillis())}", "pdf").also {
                        com.blacklab.app.files.PdfTools.imagesToPdf(files.sortedBy { f -> f.lastModified() }, it, "LETTER"); app.storage.scan(it)
                    }
                }.getOrNull()
                withContext(Dispatchers.Main) { onDone(); if (out != null) com.blacklab.app.ui.viewer.openInApp(shell, app, out) else shell.toast("Couldn't build the PDF") }
            }
        })
        DropdownMenuItem(text = { Text("Make a video") }, leadingIcon = { Icon(Icons.Rounded.Movie, null) }, onClick = {
            open = false
            val paths = files.sortedBy { it.lastModified() }.joinToString("|") { Uri.encode(app.storage.relative(it)) }
            onDone(); shell.go("videoedit?paths=" + Uri.encode(paths))
        })
        DropdownMenuItem(text = { Text("Compress to ZIP") }, leadingIcon = { Icon(Icons.Rounded.FolderZip, null) }, onClick = {
            open = false
            app.io {
                val out = Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS), "BlackLab ${files.size} items", "zip")
                val ok = runCatching { com.blacklab.app.files.FileOps.zip(files, out); app.storage.scan(out) }.isSuccess
                withContext(Dispatchers.Main) { onDone(); shell.toast(if (ok) "Saved ${out.name} to Exports" else "Couldn't create the ZIP") }
            }
        })
    }
}
