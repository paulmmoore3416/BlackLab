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
            val prof = s.activeProfile
            val tabKey = Tabs[tab].first
            when (tabKey) {
                "PHOTOS" -> FolderBrowser(app.storage.dir(prof, Folder.PHOTOS), "photos", media = true, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "Capture with the BlackLab camera — everything is saved to Photos. Make folders from ⋮ › New folder.")
                "VIDEOS" -> FolderBrowser(app.storage.dir(prof, Folder.VIDEOS), "videos", media = true, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "Videos you record in BlackLab are saved here.")
                "SCANS" -> FolderBrowser(app.storage.dir(prof, Folder.SCANS), "scans", media = false, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "Scan a document with the scan button above — PDFs land here.")
                "RECORDINGS" -> Column {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Playback, markers and transcripts live in the Recorder.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { shell.go("recorder") }) { Text("Recorder") }
                    }
                    FolderBrowser(app.storage.dir(prof, Folder.RECORDINGS), "recordings", media = false, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                        emptyHint = "Recordings and their transcripts are saved here.")
                }
                "DICTATION" -> Column {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Each session has its own dated folder with audio, transcript and location.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { shell.go("dictation") }) { Text("Dictation") }
                    }
                    FolderBrowser(app.storage.dir(prof, Folder.DICTATION), "dictation", media = false, canOrganize = false, refresh = refresh, onChanged = { refresh++ },
                        emptyHint = "Dictation sessions appear here.")
                }
                "EXPORTS" -> FolderBrowser(app.storage.dir(prof, Folder.EXPORTS), "exports", media = false, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "Exports (DOCX, PDF, ZIP, ICS…) are saved here.")
                "IMPORTS" -> FolderBrowser(app.storage.dir(prof, Folder.IMPORTS), "imports", media = false, canOrganize = true, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "Files you import or share into BlackLab are saved here.")
                else -> FolderBrowser(app.storage.root, "browse", media = false, canOrganize = false, refresh = refresh, onChanged = { refresh++ },
                    emptyHint = "The BlackLab folder is empty.")
            }
        }
    }
    scanResult?.let { r -> ScanDoneDialog(r) { scanResult = null } }
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
internal fun SelectionMore(files: List<File>, onDone: () -> Unit) {
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
