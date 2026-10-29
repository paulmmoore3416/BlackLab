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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
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
private val imageExt = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "dng")
private val videoExt = setOf("mp4", "mov", "3gp", "mkv", "webm")

fun File.isImage() = extension.lowercase(Locale.US) in imageExt
fun File.isVideo() = extension.lowercase(Locale.US) in videoExt

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
            IconButton(onClick = { shell.go("camera") }) { Icon(Icons.Default.PhotoCamera, "Camera") }
            IconButton(onClick = { scanner() }) { Icon(Icons.Default.DocumentScanner, "Scan") }
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Upload, "Import") }
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
    if (files.isEmpty()) { EmptyState(Icons.Default.PhotoLibrary, "Nothing here yet", "Capture with the BlackLab camera — everything is saved to ${dir.name}.") ; return }
    Column {
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, "Clear") }
            Text("${selected.size} selected", Modifier.weight(1f))
            IconButton(onClick = { selected = if (selected.size == files.size) emptySet() else files.toSet() }) { Icon(Icons.Default.SelectAll, "Select all") }
            IconButton(onClick = { Files.share(ctx, selected.toList()) }) { Icon(Icons.Default.Share, "Share") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
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
                        if (f.isVideo()) Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                        if (sel) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
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
                    if (dir != start) IconButton(onClick = { dir.parentFile?.let { dir = it } }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
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
        if (entries.isEmpty()) item { EmptyState(Icons.Default.FolderOpen, "Empty folder", "Files saved here will appear in this list.") }
        items(entries, key = { it.path }) { f ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = {
                    when {
                        f.isDirectory -> dir = f
                        f.isImage() || f.isVideo() -> shell.go("media?path=" + Uri.encode(app.storage.relative(f)))
                        else -> Files.open(ctx, f)
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
                if (f.isFile) IconButton(onClick = { menu = f }) { Icon(Icons.Default.MoreVert, "More") }
            }
        }
    }
}

fun iconFor(f: File) = when {
    f.isDirectory -> Icons.Default.Folder
    f.isImage() -> Icons.Default.Image
    f.isVideo() -> Icons.Default.Movie
    f.extension.lowercase(Locale.US) in setOf("m4a", "wav", "mp3", "aac", "ogg", "flac", "opus") -> Icons.Default.AudioFile
    f.extension.equals("pdf", true) -> Icons.Default.PictureAsPdf
    f.extension.lowercase(Locale.US) in setOf("doc", "docx", "html", "htm", "md", "txt", "rtf") -> Icons.Default.Description
    f.extension.lowercase(Locale.US) in setOf("xls", "xlsx", "csv") -> Icons.Default.TableChart
    f.extension.equals("zip", true) -> Icons.Default.FolderZip
    f.extension.equals("ics", true) -> Icons.Default.Event
    else -> Icons.Default.InsertDriveFile
}
