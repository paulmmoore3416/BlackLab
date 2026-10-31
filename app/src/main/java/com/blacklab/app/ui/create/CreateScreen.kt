package com.blacklab.app.ui.create

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.files.FileKind
import com.blacklab.app.files.FileKinds
import com.blacklab.app.files.PdfTools
import com.blacklab.app.files.Sheet
import com.blacklab.app.files.Sheets
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.IconBadge
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Violet
import com.blacklab.app.ui.viewer.BusyOverlay
import com.blacklab.app.ui.viewer.openInApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private data class CreateItem(val title: String, val hint: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

/** One place to start anything new — or open & edit any file from the phone. */
@Composable
fun CreateScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val profile = app.settings.current.activeProfile
    fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm"))

    fun make(folder: Folder, base: String, ext: String, write: (File) -> Unit) {
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching { Files.unique(app.storage.dir(profile, folder), base, ext).also { write(it); app.storage.scan(it) } }.getOrNull() }
            if (f == null) shell.toast("Couldn't create the file") else if (ext == "png") shell.go("imgedit?path=" + Uri.encode(app.storage.relative(f)) + "&tab=draw") else openInApp(shell, app, f)
        }
    }

    /** Copies picked documents into BlackLab's "opened" area so every viewer/editor can use them. */
    suspend fun copyIn(uris: List<Uri>): List<File> = withContext(Dispatchers.IO) {
        uris.mapNotNull { u ->
            val name = Files.displayName(ctx, u).let { n -> if ('.' in n) n else n + "." + FileKinds.extFor(ctx.contentResolver.getType(u)) }
            val dest = File(app.storage.openedDir, Files.sanitize(name))
            if (Files.copyFromUri(ctx, u, dest)) dest else null
        }
    }

    val openAny = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch { copyIn(listOf(u)).firstOrNull()?.let { openInApp(shell, app, it) } ?: shell.toast("Couldn't open that file") }
    }
    val editPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch { copyIn(listOf(u)).firstOrNull()?.let { shell.go("imgedit?path=" + Uri.encode(it.absolutePath) + "&tab=crop") } }
    }
    val editAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch { copyIn(listOf(u)).firstOrNull()?.let { shell.go("audioedit?path=" + Uri.encode(it.absolutePath)) } }
    }
    val editVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? ->
        if (u != null) scope.launch { copyIn(listOf(u)).firstOrNull()?.let { shell.go("videoedit?path=" + Uri.encode(it.absolutePath)) } }
    }
    val montage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            busy = "Gathering media…"
            val files = copyIn(uris)
            busy = null
            if (files.isNotEmpty()) shell.go("videoedit?paths=" + Uri.encode(files.joinToString("|") { Uri.encode(it.absolutePath) }))
        }
    }
    val photosToPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            busy = "Building PDF…"
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val pics = copyIn(uris).filter { FileKinds.of(it) == FileKind.IMAGE }
                    PdfTools.init(ctx)
                    Files.unique(app.storage.dir(profile, Folder.SCANS), "Photos ${stamp()}", "pdf").also { PdfTools.imagesToPdf(pics, it, "LETTER"); app.storage.scan(it); pics.forEach { p -> p.delete() } }
                }
            }
            busy = null
            out.onSuccess { openInApp(shell, app, it) }.onFailure { shell.toast("Couldn't build the PDF: ${it.message}") }
        }
    }

    val create = listOf(
        CreateItem("Document", "Rich text, tables, pictures — exports to Word & PDF", Icons.Default.Description, Cobalt) { shell.go("doc/0") },
        CreateItem("Spreadsheet", "Excel-compatible .xlsx with formulas", Icons.Default.TableChart, Mint) {
            make(Folder.DOCUMENTS, "Spreadsheet ${stamp()}", "xlsx") { Sheets.writeXlsx(listOf(Sheet("Sheet1", mutableListOf(mutableListOf("Item", "Qty", "Cost", "Total"), mutableListOf("", "", "", "=B2*C2")))), it) }
        },
        CreateItem("Text note", "Plain .txt", Icons.Default.Notes, Amber) { make(Folder.DOCUMENTS, "Note ${stamp()}", "txt") { it.writeText("") } },
        CreateItem("Markdown note", "Headings, lists, links — with preview", Icons.Default.Code, Violet) { make(Folder.DOCUMENTS, "Note ${stamp()}", "md") { it.writeText("# Title\n\n- \n") } },
        CreateItem("CSV table", "Simple comma-separated data", Icons.Default.GridOn, Mint) { make(Folder.DOCUMENTS, "Table ${stamp()}", "csv") { it.writeText("Name,Value\r\n") } },
        CreateItem("Drawing", "Blank canvas with pens, shapes & text", Icons.Default.Brush, Coral) {
            make(Folder.PHOTOS, "Drawing ${stamp()}", "png") { f ->
                val b = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
                f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        },
        CreateItem("PDF from photos", "Pick pictures, get one PDF", Icons.Default.PictureAsPdf, Coral) { photosToPdf.launch(arrayOf("image/*")) },
        CreateItem("Blank PDF", "A page to write, sign or annotate", Icons.Default.NoteAdd, Cobalt) { make(Folder.DOCUMENTS, "Blank ${stamp()}", "pdf") { PdfTools.init(ctx); PdfTools.blank(it) } },
        CreateItem("Video montage", "Photos & clips → one video with music & titles", Icons.Default.Movie, Violet) { montage.launch(arrayOf("image/*", "video/*")) },
        CreateItem("Audio recording", "Studio recorder with markers & transcript", Icons.Default.Mic, Coral) { shell.go("recorder") },
        CreateItem("Photo or video", "BlackLab camera", Icons.Default.PhotoCamera, Amber) { shell.go("camera") },
    )
    val edit = listOf(
        CreateItem("Open any file", "PDF, Word, Excel, PowerPoint, EPUB, ZIP, code…", Icons.Default.FileOpen, Cobalt) { openAny.launch(arrayOf("*/*")) },
        CreateItem("Edit a photo", "Crop, adjust, looks, markup, remove background", Icons.Default.AutoFixHigh, Coral) { editPhoto.launch(arrayOf("image/*")) },
        CreateItem("Edit a video", "Trim, split, filters, titles, music, speed", Icons.Default.VideoSettings, Violet) { editVideo.launch(arrayOf("video/*")) },
        CreateItem("Edit audio", "Waveform editor: cut, fade, normalize, effects", Icons.Default.GraphicEq, Mint) { editAudio.launch(arrayOf("audio/*", "video/*")) },
        CreateItem("Music", "Play songs on this phone", Icons.Default.LibraryMusic, Amber) { shell.go("music") },
    )

    Scaffold(topBar = { BlTopBar("Create & Edit", subtitle = "Start something new, or open any file") }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Create") }
                items(create) { Tile(it) }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader("Open & edit") }
                items(edit) { Tile(it) }
            }
            busy?.let { BusyOverlay(it) }
        }
    }
}

@Composable
private fun Tile(i: CreateItem) {
    BlCard(Modifier.fillMaxWidth().height(132.dp), onClick = i.onClick) {
        IconBadge(i.icon, i.color)
        Spacer(Modifier.height(10.dp))
        Text(i.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(i.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
