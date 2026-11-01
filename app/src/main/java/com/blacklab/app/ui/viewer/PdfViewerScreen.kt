package com.blacklab.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.data.ContentStore
import com.blacklab.app.files.FileOps
import com.blacklab.app.files.FormField
import com.blacklab.app.files.PageRef
import com.blacklab.app.files.PdfTools
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Thread-safe PdfRenderer wrapper with a bitmap cache. */
class PdfDoc(val file: File) : Closeable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    val pageCount = renderer.pageCount
    /** Width/height in points of each page as displayed (rotation applied). */
    val sizes: List<Pair<Int, Int>> = (0 until pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } }
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(i: Int, w: Int): Bitmap? = cache.get("$i@$w")

    suspend fun render(i: Int, widthPx: Int): Bitmap? = cache.get("$i@$widthPx") ?: withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                renderer.openPage(i).use { p ->
                    val w = widthPx.coerceIn(16, 3000)
                    val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(android.graphics.Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put("$i@$widthPx", b)
                    }
                }
            }.getOrNull()
        }
    }

    override fun close() { runCatching { renderer.close() }; runCatching { pfd.close() }; cache.evictAll() }
}

private val NightFilter = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    -0.9f, 0f, 0f, 0f, 235f,
    0f, -0.9f, 0f, 0f, 235f,
    0f, 0f, -0.9f, 0f, 235f,
    0f, 0f, 0f, 1f, 0f,
)))

@Composable
fun PdfViewerScreen(original: File, startSign: Boolean = false) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PdfTools.init(ctx) } }

    var version by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    // An unlocked copy in cache when the PDF is password-protected
    var viewFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(original, version, password) {
        val locked = withContext(Dispatchers.IO) { PdfTools.needsPassword(original) }
        if (!locked) { viewFile = original; return@LaunchedEffect }
        val pw = password ?: run { askPassword = true; return@LaunchedEffect }
        val out = File(app.storage.openedDir, "unlocked_${original.nameWithoutExtension}.pdf")
        val ok = withContext(Dispatchers.IO) { runCatching { PdfTools.init(ctx); PdfTools.unlock(original, out, pw) }.isSuccess }
        if (ok) viewFile = out else { shell.toast("Wrong password"); password = null; askPassword = true }
    }
    val wasLocked = viewFile != null && viewFile != original

    val doc by produceState<PdfDoc?>(null, viewFile, version) {
        val f = viewFile ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { PdfDoc(f) }.getOrNull() }
    }
    DisposableEffect(doc) { val d = doc; onDispose { d?.close() } }

    var mode by rememberSaveable { mutableStateOf("read") } // read | grid | organize | annotate
    var night by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<File, String>?>(null) } // tool output awaiting "replace / copy"
    var annotatePage by remember { mutableIntStateOf(0) }
    // Opened from Signatures › Sign a document: go straight to the sign tool on page 1
    var signStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(doc, startSign) { if (startSign && !signStarted && doc != null) { signStarted = true; annotatePage = 0; mode = "annotate" } }
    val list = rememberLazyListState()
    val currentPage by remember { derivedStateOf { list.firstVisibleItemIndex } }
    val texts by produceState<List<String>?>(null, viewFile, version) { val f = viewFile ?: return@produceState; value = withContext(Dispatchers.IO) { runCatching { PdfTools.init(ctx); PdfTools.pageTexts(f) }.getOrNull() } }

    /** Runs a PdfBox tool into a temp file, then asks whether to replace the original or keep a copy. */
    fun runTool(label: String, suffix: String, block: (src: File, out: File) -> Unit) {
        val src = viewFile ?: return
        busy = "$label…"
        scope.launch {
            val out = File(app.storage.openedDir, "tool_${System.currentTimeMillis()}.pdf")
            val r = withContext(Dispatchers.IO) { runCatching { PdfTools.init(ctx); block(src, out) } }
            busy = null
            r.onSuccess { pending = out to suffix }.onFailure { shell.toast("$label failed: ${it.message}") }
        }
    }
    fun finish(replace: Boolean) {
        val (tmp, suffix) = pending ?: return
        pending = null
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                runCatching {
                    val inside = app.storage.contains(original)
                    val t = if (replace && inside) original else if (inside) FileOps.sibling(original, suffix, "pdf")
                        else Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.IMPORTS), original.nameWithoutExtension + suffix, "pdf")
                    FileOps.writeAtomically(t) { tmp.copyTo(it, true) }; tmp.delete(); app.storage.scan(t); t
                }.getOrNull()
            }
            if (target == null) { shell.toast("Couldn't save"); return@launch }
            mode = "read"
            if (replace && target == original) { password = null; version++; shell.toast("Saved") } else { shell.toast("Saved ${target.name}", "Open") { openInApp(shell, app, target) } }
        }
    }

    val mergePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runTool("Merging", "_merged") { src, out ->
            val extra = uris.mapIndexed { i, u ->
                val name = Files.displayName(ctx, u)
                File(app.storage.openedDir, "merge_${i}_${Files.sanitize(name)}").also { Files.copyFromUri(ctx, u, it) }
            }
            PdfTools.merge(listOf(src) + extra, out, app.storage.openedDir); extra.forEach { it.delete() }
        }
    }

    val d = doc
    when {
        mode == "organize" && d != null -> { PdfOrganizer(d, onCancel = { mode = "read" }) { refs, extractOnly ->
            if (extractOnly) runTool("Extracting pages", "_pages") { src, out -> PdfTools.organize(src, out, refs) }
            else runTool("Saving pages", "_organized") { src, out -> PdfTools.organize(src, out, refs) }
        }; pending?.let { SaveChoiceDialog(original, onCancel = { pending = null }, onChoice = ::finish) }; busy?.let { BusyOverlay(it) }; return }
        mode == "annotate" && d != null -> { PdfAnnotator(d, annotatePage, startSign = startSign && signStarted, onCancel = { mode = "read" }) { stamps -> runTool("Saving markup", "_marked") { src, out -> PdfTools.applyStamps(src, out, stamps) } }
            pending?.let { SaveChoiceDialog(original, onCancel = { pending = null }, onChoice = ::finish) }; busy?.let { BusyOverlay(it) }; return }
    }

    Scaffold(topBar = {
        BlTopBar(original.nameWithoutExtension, subtitle = d?.let { "Page ${currentPage + 1} of ${it.pageCount}" + if (wasLocked) " · 🔒 unlocked" else "" } ?: "PDF", onBack = { shell.back() }, actions = {
            IconButton(onClick = { dialog = "search" }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { mode = if (mode == "grid") "read" else "grid" }) { Icon(if (mode == "grid") Icons.Default.ViewAgenda else Icons.Default.GridView, "Pages") }
            IconButton(onClick = { annotatePage = currentPage; mode = "annotate" }) { Icon(Icons.Default.Draw, "Mark up & sign") }
            FileMenu(original, buildList {
                add(MenuItem("Organize pages", Icons.Default.Dashboard) { mode = "organize" })
                add(MenuItem("Merge with other PDFs or photos", Icons.Default.MergeType) { mergePicker.launch(arrayOf("application/pdf", "image/*")) })
                add(MenuItem("Split / extract pages", Icons.Default.CallSplit) { dialog = "split" })
                add(MenuItem("Fill in form", Icons.Default.EditNote) { dialog = "form" })
                add(MenuItem("Add watermark", Icons.Default.BrandingWatermark) { dialog = "watermark" })
                add(MenuItem("Add page numbers", Icons.Default.FormatListNumbered) { dialog = "numbers" })
                if (wasLocked) add(MenuItem("Remove password", Icons.Default.LockOpen) { runTool("Removing password", "_unlocked") { src, out -> src.copyTo(out, true) } })
                else add(MenuItem("Protect with password", Icons.Default.Lock) { dialog = "protect" })
                add(MenuItem("Save pages as pictures", Icons.Default.Image) {
                    val src = viewFile ?: return@MenuItem
                    busy = "Rendering pages…"
                    scope.launch {
                        val dir = FileOps.uniqueDir(app.storage.dir(app.settings.current.activeProfile, Folder.PHOTOS), original.nameWithoutExtension + " pages")
                        val r = withContext(Dispatchers.IO) { runCatching { dir.mkdirs(); PdfTools.toImages(src, dir, 150).onEach { app.storage.scan(it) } } }
                        busy = null
                        r.onSuccess { shell.toast("Saved ${it.size} picture(s) to Photos › ${dir.name}") }.onFailure { shell.toast("Couldn't render pages") }
                    }
                })
                add(MenuItem("Reduce file size", Icons.Default.Compress) { dialog = "compress" })
                add(MenuItem("Copy text / save as .txt", Icons.Default.TextSnippet) { dialog = "text" })
                add(MenuItem("Convert to BlackLab document", Icons.Default.Description) {
                    val t = texts ?: return@MenuItem
                    app.io {
                        val html = t.mapIndexed { i, p -> "<h3>Page ${i + 1}</h3>" + ContentStore.textToHtml(p.trim()) }.joinToString("")
                        val id = runCatching { FileOps.importAsDocument(app, original, html) }.getOrNull()
                        withContext(Dispatchers.Main) { if (id != null) shell.go("doc/$id") else shell.toast("Couldn't convert") }
                    }
                })
                add(MenuItem("Properties", Icons.Default.Tune) { dialog = "props" })
                add(MenuItem(if (night) "Normal colors" else "Night mode", Icons.Default.DarkMode) { night = !night })
            })
        })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
            if (d == null) { if (!askPassword) CircularProgressIndicator(Modifier.align(Alignment.Center)); return@Box }
            if (mode == "grid") {
                LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.pageCount) { i ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { mode = "read"; scope.launch { list.scrollToItem(i) } }) {
                            PageImage(d, i, 260, night, Modifier.fillMaxWidth().border(if (i == currentPage) 2.dp else 0.dp, MaterialTheme.colorScheme.primary))
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                return@Box
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val viewW = maxWidth
                val pageW = viewW * zoom
                val px = with(density) { (pageW - 16.dp).toPx() }.toInt()
                val bucket = ((px + 127) / 128) * 128 // re-render in steps, not on every pinch frame
                val h = rememberScrollState()
                Box(Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                            do {
                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                if (event.changes.size >= 2) {
                                    val z = event.calculateZoom()
                                    if (z != 1f) { zoom = (zoom * z).coerceIn(1f, 5f); event.changes.forEach { it.consume() } }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { zoom = if (zoom > 1.1f) 1f else 2.2f }) }
                    .horizontalScroll(h, enabled = zoom > 1.01f)) {
                    LazyColumn(state = list, modifier = Modifier.width(pageW).fillMaxSize(), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.pageCount, key = { it }) { i ->
                            val (w, hh) = d.sizes[i]
                            PageImage(d, i, bucket, night, Modifier.fillMaxWidth().aspectRatio(w.toFloat() / hh.coerceAtLeast(1)).clip(RoundedCornerShape(2.dp)))
                        }
                    }
                }
            }
            Text("${currentPage + 1} / ${d.pageCount}", color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).clickable { dialog = "goto" }.padding(horizontal = 14.dp, vertical = 6.dp))
            if (zoom > 1.01f) Text("${(zoom * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).clickable { zoom = 1f }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        busy?.let { BusyOverlay(it) }
    }

    pending?.let { SaveChoiceDialog(original, onCancel = { pending = null }, onChoice = ::finish) }
    if (askPassword) PasswordDialog(original.name, onDismiss = { askPassword = false; shell.back() }) { askPassword = false; password = it }

    val pages = d?.pageCount ?: 0
    when (dialog) {
        "goto" -> TextInputDialog("Go to page", "", "Page (1–$pages)", confirmLabel = "Go", keyboardType = KeyboardType.Number, onDismiss = { dialog = null }) { v ->
            v.toIntOrNull()?.let { p -> scope.launch { list.scrollToItem((p - 1).coerceIn(0, (pages - 1).coerceAtLeast(0))) } }
        }
        "search" -> PdfSearchDialog(texts, onDismiss = { dialog = null }) { p -> dialog = null; mode = "read"; scope.launch { list.scrollToItem(p) } }
        "text" -> {
            val t = texts
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Page ${currentPage + 1} text") },
                text = { SelectionContainer { Text(t?.getOrNull(currentPage)?.trim()?.ifBlank { "No selectable text on this page — it may be a scan. Use the Library's OCR on a page picture." } ?: "Reading…", Modifier.verticalScroll(rememberScrollState())) } },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val all = t ?: return@TextButton
                        app.io {
                            val out = Files.unique(app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS), original.nameWithoutExtension, "txt")
                            out.writeText(all.mapIndexed { i, p -> "— Page ${i + 1} —\n${p.trim()}" }.joinToString("\n\n"))
                            withContext(Dispatchers.Main) { shell.toast("Saved ${out.name} to Exports", "Open") { openInApp(shell, app, out) } }
                        }
                    }) { Text("Save all as .txt") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("PDF text", t?.joinToString("\n\n").orEmpty()))
                        shell.toast("Copied all text"); dialog = null
                    }) { Text("Copy all") }
                },
            )
        }
        "split" -> {
            var spec by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Split / extract pages") },
                text = {
                    Column {
                        Text("Enter page ranges, one file each — e.g. “1-3, 5, 8-10”. Leave blank to split every page into its own PDF.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(spec, { spec = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, placeholder = { Text("All pages") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        dialog = null
                        val src = viewFile ?: return@TextButton
                        busy = "Splitting…"
                        scope.launch {
                            val dir = FileOps.uniqueDir(original.parentFile!!.takeIf { app.storage.contains(original) } ?: app.storage.dir(app.settings.current.activeProfile, Folder.EXPORTS), original.nameWithoutExtension + " split")
                            val r = withContext(Dispatchers.IO) { runCatching { dir.mkdirs(); PdfTools.split(src, dir, PdfTools.parseRanges(spec, pages)).onEach { app.storage.scan(it) } } }
                            busy = null
                            r.onSuccess { shell.toast("Created ${it.size} PDF(s) in ${dir.name}") }.onFailure { shell.toast("Split failed: ${it.message}") }
                        }
                    }) { Text("Split") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "watermark" -> {
            var text by remember { mutableStateOf("CONFIDENTIAL") }
            var opacity by remember { mutableFloatStateOf(0.18f) }
            var red by remember { mutableStateOf(true) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Watermark every page") },
                text = {
                    Column {
                        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Text") })
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("CONFIDENTIAL", "DRAFT", "COPY", "APPROVED", "VOID").forEach { s -> FilledTonalButton(onClick = { text = s }) { Text(s) } }
                        }
                        Text("Opacity ${(opacity * 100).toInt()}%"); Slider(opacity, { opacity = it }, valueRange = 0.05f..0.6f)
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Red (off = gray)", Modifier.weight(1f)); Switch(red, { red = it }) }
                    }
                },
                confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { dialog = null; runTool("Adding watermark", "_watermarked") { s, o -> PdfTools.watermark(s, o, text, if (red) 0xFFD32F2F.toInt() else 0xFF606060.toInt(), opacity) } }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "numbers" -> {
            var format by remember { mutableStateOf("Page {n} of {total}") }
            var pos by remember { mutableStateOf("BC") }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Page numbers") },
                text = {
                    Column {
                        OutlinedTextField(format, { format = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Format ({n}, {total})") })
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("{n}", "Page {n}", "Page {n} of {total}", "{n} / {total}").forEach { s -> FilledTonalButton(onClick = { format = s }) { Text(s.replace("{n}", "3").replace("{total}", "9")) } }
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("BL" to "Bottom left", "BC" to "Bottom center", "BR" to "Bottom right", "TR" to "Top right").forEach { (k, l) ->
                                androidx.compose.material3.FilterChip(pos == k, { pos = k }, label = { Text(l) })
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; runTool("Numbering pages", "_numbered") { s, o -> PdfTools.pageNumbers(s, o, format, pos) } }) { Text("Apply") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "protect" -> {
            var pw by remember { mutableStateOf("") }
            var pw2 by remember { mutableStateOf("") }
            var print by remember { mutableStateOf(true) }
            var copy by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Protect with password") },
                text = {
                    Column {
                        Text("AES-256 encryption. Anyone opening the file needs this password — BlackLab can't recover it if it's lost.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
                        OutlinedTextField(pw2, { pw2 = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Confirm") }, visualTransformation = PasswordVisualTransformation(), isError = pw2.isNotEmpty() && pw != pw2)
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(print, { print = it }); Text("Allow printing") }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(copy, { copy = it }); Text("Allow copying text") }
                    }
                },
                confirmButton = { TextButton(enabled = pw.length >= 4 && pw == pw2, onClick = { dialog = null; runTool("Encrypting", "_protected") { s, o -> PdfTools.protect(s, o, pw, pw, print, copy) } }) { Text("Protect") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "compress" -> {
            var dpi by remember { mutableFloatStateOf(110f) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Reduce file size") },
                text = {
                    Column {
                        Text("Re-renders each page as a compressed picture. Great for scans and photo-heavy PDFs; text will no longer be selectable. Current size: ${Files.formatSize(original.length())}.", style = MaterialTheme.typography.bodyMedium)
                        Text("Quality: ${dpi.toInt()} dpi", Modifier.padding(top = 8.dp)); Slider(dpi, { dpi = it }, valueRange = 72f..200f)
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; runTool("Compressing", "_small") { s, o -> PdfTools.compress(s, o, dpi.toInt(), if (dpi < 100) 0.55f else 0.65f) } }) { Text("Compress") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "form" -> PdfFormDialog(viewFile, onDismiss = { dialog = null }) { values, flatten, images -> dialog = null; runTool("Filling form", "_filled") { s, o -> PdfTools.fillForm(s, o, values, flatten, images) } }
        "props" -> {
            val info by produceState<Map<String, String>?>(null, viewFile) { value = withContext(Dispatchers.IO) { viewFile?.let { PdfTools.init(ctx); PdfTools.info(it) } } }
            var title by remember(info) { mutableStateOf(info?.get("Title").orEmpty()) }
            var author by remember(info) { mutableStateOf(info?.get("Author").orEmpty()) }
            var subject by remember(info) { mutableStateOf(info?.get("Subject").orEmpty()) }
            var keywords by remember(info) { mutableStateOf(info?.get("Keywords").orEmpty()) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Properties") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        info?.filterKeys { it !in setOf("Title", "Author", "Subject", "Keywords") }?.forEach { (k, v) -> Row { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v) } }
                        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") })
                        OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Author") })
                        OutlinedTextField(subject, { subject = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Subject") })
                        OutlinedTextField(keywords, { keywords = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Keywords") })
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null; runTool("Saving properties", "") { s, o -> PdfTools.setInfo(s, o, title, author, subject, keywords) } }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
    }
}

@Composable
fun PageImage(d: PdfDoc, i: Int, widthPx: Int, night: Boolean, modifier: Modifier) {
    val bmp by produceState(d.cached(i, widthPx), d, i, widthPx) { value = d.render(i, widthPx) ?: value }
    Box(modifier.background(Color.White)) {
        bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), colorFilter = if (night) NightFilter else null) }
    }
}

@Composable
fun SaveChoiceDialog(original: File, onCancel: () -> Unit, onChoice: (replace: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Save changes") },
        text = { Text("Keep the original “${original.name}” and save a new copy, or replace it?") },
        confirmButton = { TextButton(onClick = { onChoice(false) }) { Text("Save as copy") } },
        dismissButton = { TextButton(onClick = { onChoice(true) }) { Text("Replace original") } },
    )
}

@Composable
private fun PasswordDialog(name: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null) },
        title = { Text("Password required") },
        text = {
            Column {
                Text("“$name” is protected.")
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onOk(pw) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PdfSearchDialog(texts: List<String>?, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var q by remember { mutableStateOf("") }
    val hits = remember(q, texts) {
        if (q.length < 2 || texts == null) emptyList()
        else texts.flatMapIndexed { p, t ->
            Regex(Regex.escape(q), RegexOption.IGNORE_CASE).findAll(t).take(20).map { m ->
                val s = (m.range.first - 40).coerceAtLeast(0); val e = (m.range.last + 40).coerceAtMost(t.length - 1)
                p to t.substring(s, e + 1).replace(Regex("\\s+"), " ")
            }.toList()
        }.take(200)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Find in document") }, leadingIcon = { Icon(Icons.Default.Search, null) })
                if (texts == null) Text("Reading text…", Modifier.padding(top = 8.dp))
                else if (q.length >= 2) Text("${hits.size} result(s)", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(hits) { (p, snip) ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onGo(p) }.padding(8.dp)) {
                            Text("Page ${p + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text("…$snip…", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PdfFormDialog(file: File?, onDismiss: () -> Unit, onFill: (Map<String, String>, Boolean, Map<String, android.graphics.Bitmap>) -> Unit) {
    val ctx = LocalContext.current
    val settings = com.blacklab.app.ui.components.settingsState()
    val fields by produceState<List<FormField>?>(null, file) { value = withContext(Dispatchers.IO) { file?.let { runCatching { PdfTools.init(ctx); PdfTools.formFields(it) }.getOrDefault(emptyList()) } } }
    val values = remember(fields) { androidx.compose.runtime.mutableStateMapOf<String, String>().apply { fields?.forEach { put(it.name, it.value) } } }
    val images = remember(fields) { androidx.compose.runtime.mutableStateMapOf<String, android.graphics.Bitmap>() }
    val slots = remember(fields) { fields.orEmpty().associate { it.name to com.blacklab.app.core.FormAutofill.classify(it.name, it.alt) } }
    var flatten by remember { mutableStateOf(false) }
    var profile by remember(settings) { mutableStateOf(com.blacklab.app.core.FormAutofill.profile(ctx, settings)) }
    var signFor by remember { mutableStateOf<String?>(null) }
    var editDetails by remember { mutableStateOf(false) }
    var filledNote by remember { mutableStateOf<String?>(null) }
    fun isSignature(f: FormField) = f.type == "signature" || (f.type == "text" && slots[f.name].let { it == com.blacklab.app.core.FormAutofill.Slot.SIGNATURE || it == com.blacklab.app.core.FormAutofill.Slot.INITIALS })
    fun label(f: FormField) = f.alt.ifBlank { f.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fill in form") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    fields == null -> CircularProgressIndicator()
                    fields!!.isEmpty() -> Text("This PDF has no fillable fields. Use Mark up & sign to type text anywhere on a page.")
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.FilledTonalButton(onClick = {
                                var n = 0
                                fields!!.filter { it.type == "text" && !isSignature(it) && values[it.name].isNullOrBlank() }.forEach { f ->
                                    val v = slots[f.name]?.let { com.blacklab.app.core.FormAutofill.valueFor(it, profile) }.orEmpty()
                                    if (v.isNotBlank()) { values[f.name] = v; n++ }
                                }
                                filledNote = if (n > 0) "Filled $n field(s) from your details. Check them before saving." else "Nothing to fill: add your details, or the field names weren't recognized."
                            }) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Auto-fill my details") }
                            TextButton(onClick = { editDetails = true }) { Text("My details") }
                        }
                        filledNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp)) }
                        fields!!.forEach { f ->
                            val slot = slots[f.name]
                            when {
                                isSignature(f) -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(label(f), style = MaterialTheme.typography.bodyMedium)
                                        Text(if (slot == com.blacklab.app.core.FormAutofill.Slot.INITIALS) "Initials" else "Signature", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    val img = images[f.name]
                                    if (img != null) androidx.compose.foundation.Image(img.asImageBitmap(), "Signature", Modifier.size(width = 110.dp, height = 40.dp).background(Color.White, RoundedCornerShape(8.dp)).clickable { signFor = f.name }.padding(4.dp))
                                    else androidx.compose.material3.OutlinedButton(onClick = { signFor = f.name }) { Icon(Icons.Default.Draw, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Sign here") }
                                }
                                f.type == "check" -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(values[f.name] == "Yes", { values[f.name] = if (it) "Yes" else "Off" }); Text(label(f)) }
                                f.type == "radio" || f.type == "choice" -> ChoiceField(label(f), f.options, values[f.name].orEmpty()) { values[f.name] = it }
                                else -> OutlinedTextField(values[f.name].orEmpty(), { values[f.name] = it }, Modifier.fillMaxWidth().padding(vertical = 2.dp), label = { Text(label(f)) },
                                    supportingText = slot?.let { sl -> { Text(sl.label, style = MaterialTheme.typography.labelSmall) } },
                                    trailingIcon = slot?.takeIf { values[f.name].isNullOrBlank() }?.let { sl -> {
                                        val v = com.blacklab.app.core.FormAutofill.valueFor(sl, profile)
                                        if (v.isNotBlank()) IconButton(onClick = { values[f.name] = v }) { Icon(Icons.Default.AutoAwesome, "Fill with ${sl.label.lowercase()}") }
                                    } })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(flatten, { flatten = it }); Text("Lock as finished copy (answers can't be changed)") }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !fields.isNullOrEmpty(), onClick = { onFill(values.toMap(), flatten, images.toMap()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    signFor?.let { name ->
        com.blacklab.app.ui.signature.SignatureChooserDialog(onDismiss = { signFor = null }) { _, bmp -> images[name] = bmp; signFor = null; flatten = true }
    }
    if (editDetails) FormDetailsDialog(profile, onDismiss = { editDetails = false }) { p -> com.blacklab.app.core.FormAutofill.saveContact(ctx, p); profile = com.blacklab.app.core.FormAutofill.profile(ctx, settings); editDetails = false }
}

/** Contact details used to fill forms (name, title and organization come from Settings › You). */
@Composable
fun FormDetailsDialog(p: com.blacklab.app.core.FormAutofill.Profile, onDismiss: () -> Unit, onSave: (com.blacklab.app.core.FormAutofill.Profile) -> Unit) {
    var email by remember { mutableStateOf(p.email) }
    var phone by remember { mutableStateOf(p.phone) }
    var address by remember { mutableStateOf(p.address) }
    var city by remember { mutableStateOf(p.city) }
    var state by remember { mutableStateOf(p.state) }
    var zip by remember { mutableStateOf(p.zip) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My details for forms") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Name: ${p.fullName.ifBlank { "—" }} · Title: ${p.title.ifBlank { "—" }} · Organization: ${p.organization.ifBlank { "—" }} (change these in Settings › You).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf<Triple<String, String, (String) -> Unit>>(
                    Triple("Email", email) { email = it }, Triple("Phone", phone) { phone = it }, Triple("Street address", address) { address = it },
                    Triple("City", city) { city = it }, Triple("State", state) { state = it }, Triple("ZIP / postal code", zip) { zip = it },
                ).forEach { (l, v, set) -> OutlinedTextField(v, set, Modifier.fillMaxWidth().padding(vertical = 2.dp), label = { Text(l) }, singleLine = true) }
                Text("Kept only on this phone; not synced to BlackLab Desktop.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p.copy(email = email, phone = phone, address = address, city = city, state = state, zip = zip)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChoiceField(label: String, options: List<String>, value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

// ------------------------------------------------------------------ page organizer

@Composable
private fun PdfOrganizer(d: PdfDoc, onCancel: () -> Unit, onSave: (List<PageRef>, extractOnly: Boolean) -> Unit) {
    val pages = remember { mutableStateListOf<PageRef>().apply { addAll((0 until d.pageCount).map { PageRef(it) }) } }
    var selected by remember { mutableStateOf(setOf<Int>()) } // positions in [pages]
    fun move(delta: Int) {
        val order = selected.sorted().let { if (delta > 0) it.reversed() else it }
        val newSel = mutableSetOf<Int>()
        order.forEach { i -> val j = i + delta; if (j in pages.indices && j !in newSel) { val t = pages[i]; pages[i] = pages[j]; pages[j] = t; newSel += j } else newSel += i }
        selected = newSel
    }
    Scaffold(topBar = {
        BlTopBar("Organize pages", subtitle = if (selected.isEmpty()) "${pages.size} pages · tap to select" else "${selected.size} selected", onBack = onCancel, actions = {
            TextButton(onClick = { selected = if (selected.size == pages.size) emptySet() else pages.indices.toSet() }) { Text(if (selected.size == pages.size) "None" else "All") }
            TextButton(enabled = pages.isNotEmpty(), onClick = { onSave(pages.toList(), false) }) { Text("Save", fontWeight = FontWeight.Bold) }
        })
    }, bottomBar = {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            val any = selected.isNotEmpty()
            OrgAction(Icons.AutoMirrored.Filled.RotateLeft, "Left", any) { selected.forEach { pages[it] = pages[it].copy(rotate = (pages[it].rotate + 270) % 360) } }
            OrgAction(Icons.AutoMirrored.Filled.RotateRight, "Right", any) { selected.forEach { pages[it] = pages[it].copy(rotate = (pages[it].rotate + 90) % 360) } }
            OrgAction(Icons.Default.ArrowBack, "Earlier", any) { move(-1) }
            OrgAction(Icons.Default.ArrowForward, "Later", any) { move(1) }
            OrgAction(Icons.Default.ContentCopy, "Duplicate", any) { selected.sortedDescending().forEach { pages.add(it + 1, pages[it]) }; selected = emptySet() }
            OrgAction(Icons.Default.Delete, "Delete", any && selected.size < pages.size) { selected.sortedDescending().forEach { pages.removeAt(it) }; selected = emptySet() }
            OrgAction(Icons.Default.Output, "Extract", any) { onSave(selected.sorted().map { pages[it] }, true) }
        }
    }) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.padding(pad), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(pages.size) { pos ->
                val ref = pages[pos]
                val sel = pos in selected
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { selected = if (sel) selected - pos else selected + pos }
                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent).padding(6.dp)) {
                    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                        val (w, h) = d.sizes[ref.index]
                        PageImage(d, ref.index, 220, false, Modifier.rotate(ref.rotate.toFloat()).aspectRatio(w.toFloat() / h).border(if (sel) 2.dp else 0.5.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray))
                        if (sel) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Color.White))
                    }
                    Text("${pos + 1}" + if (ref.index != pos) " (p${ref.index + 1})" else "", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun OrgAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Icon(icon, label, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}
