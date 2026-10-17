package com.blacklab.app.ui.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.DocumentEntity
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.docs.Printer
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.editor.EditorController
import com.blacklab.app.ui.editor.EditorRibbon
import com.blacklab.app.ui.editor.RichEditorView
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val UNTITLED = "Untitled document"

@Composable
fun DocumentEditorScreen(id: Long, template: String?, courseId: Long?) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    var doc by remember { mutableStateOf<DocumentEntity?>(null) }
    var controller by remember { mutableStateOf<EditorController?>(null) }
    var status by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val saveLock = remember { Mutex() }

    LaunchedEffect(id) {
        val (entity, body) = withContext(Dispatchers.IO) {
            if (id != 0L) {
                val d = app.db.documents().get(id)
                d to (d?.let { app.content.readBody(it.filePath) } ?: "")
            } else {
                val t = Templates.byId(template)
                val course = courseId?.let { app.db.education().course(it) }
                var html = t.build(Ctx.from(s))
                if (course != null) html = html.replaceFirst("<th style=\"width:32%\">Course</th><td><br></td>", "<th style=\"width:32%\">Course</th><td>${ContentStore.escape(course.code + " " + course.title)}</td>")
                DocumentEntity(
                    profile = s.activeProfile.name,
                    title = if (t.id == "blank") UNTITLED else t.name,
                    folder = course?.code?.ifBlank { course.title } ?: "",
                    courseId = courseId, pageSize = s.defaultPageSize,
                ) to html
            }
        }
        doc = entity
        controller = EditorController(body, "Start typing…")
    }

    suspend fun save(final: Boolean = false) = saveLock.withLock {
        val c = controller ?: return@withLock
        val d = doc ?: return@withLock
        val html = c.html()
        withContext(Dispatchers.IO) {
            val text = ContentStore.plainText(html)
            var title = d.title
            if (title == UNTITLED && text.isNotBlank()) title = text.lineSequence().first { it.isNotBlank() }.take(60).trim()
            if (d.id == 0L && text.isBlank() && final) return@withContext
            val rel = app.content.writeDocument(com.blacklab.app.core.Profile.from(d.profile), title, d.folder, d.filePath, html, DocActions.pageOf(d).css())
            val updated = d.copy(title = title, filePath = rel, preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), updatedAt = Time.now())
            val newId = if (d.id == 0L) app.db.documents().insert(updated) else { app.db.documents().update(updated); d.id }
            withContext(Dispatchers.Main) { doc = updated.copy(id = newId); status = "Saved" }
        }
    }

    // Autosave 1.5 s after the last edit
    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        c.editTick.drop(1).collectLatest {
            status = "Editing…"
            delay(1500)
            status = "Saving…"
            save()
        }
    }

    val leave: () -> Unit = {
        scope.launch {
            withContext(NonCancellable) { runCatching { save(final = true) } }
            shell.back()
        }
    }
    BackHandler { leave() }

    val d = doc
    val c = controller
    val words by (c?.words ?: kotlinx.coroutines.flow.MutableStateFlow(0)).collectAsState()

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column(Modifier.clickable { dialog = "rename" }) {
                    Text(d?.title ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(listOf(status, "$words words", d?.folder?.takeIf { it.isNotBlank() }?.let { "📁 $it" }).filter { !it.isNullOrBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(onClick = { c?.exec("undo") }) { Icon(Icons.Default.Undo, "Undo") }
                IconButton(onClick = { scope.launch { save(); dialog = "export" } }) { Icon(Icons.Default.IosShare, "Export") }
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Save now") }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { more = false; scope.launch { save(); shell.toast("Saved to BlackLab") } })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }, onClick = { more = false; dialog = "rename" })
                        DropdownMenuItem(text = { Text("Print / Save as PDF") }, leadingIcon = { Icon(Icons.Default.Print, null) }, onClick = {
                            more = false
                            scope.launch { val html = c?.html() ?: return@launch; d?.let { Printer.print(ctx, it.title, html, DocActions.pageOf(it)) } }
                        })
                        DropdownMenuItem(text = { Text(if (d?.pinned == true) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = {
                            more = false; d?.let { doc = it.copy(pinned = !it.pinned); scope.launch { save() } }
                        })
                        DropdownMenuItem(text = { Text("Document info") }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { more = false; dialog = "info" })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { more = false; dialog = "delete" })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
        if (d == null || c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        RichEditorView(c, lightDesk = !LocalBlColors.current.isDark, modifier = Modifier.weight(1f).fillMaxWidth())
        EditorRibbon(
            controller = c,
            page = DocActions.pageOf(d),
            onPageChange = { p: PageSetup -> doc = d.copy(pageSize = p.size, landscape = p.landscape, marginMm = p.marginMm); scope.launch { save() } },
            modifier = Modifier.navigationBarsPadding(),
        )
    }

    val cur = doc ?: return
    when (dialog) {
        "rename" -> TextInputDialog("Rename document", if (cur.title == UNTITLED) "" else cur.title, "Title", onDismiss = { dialog = null }) { t ->
            doc = cur.copy(title = t); scope.launch { save() }
        }
        "export" -> ExportSheet(cur.title, { dialog = null }, onPrint = {
            scope.launch { val html = controller?.html() ?: return@launch; Printer.print(ctx, cur.title, html, DocActions.pageOf(cur)) }
        }) { f, share ->
            scope.launch {
                val html = controller?.html() ?: return@launch
                DocActions.export(app, ctx, shell, com.blacklab.app.core.Profile.from(cur.profile), cur.title, html, DocActions.pageOf(cur), f, share)
            }
        }
        "info" -> {
            val file = app.content.file(cur.filePath)
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Document info") },
                text = {
                    Column {
                        Text("Words: ${controller?.words?.value ?: cur.wordCount}")
                        Text("Created: ${Time.dateTime(cur.createdAt)}")
                        Text("Modified: ${Time.dateTime(cur.updatedAt)}")
                        Text("Page: ${PageSetup.sizes.first { it.first == cur.pageSize }.second}${if (cur.landscape) ", landscape" else ""}, margins ${cur.marginMm} mm")
                        Text("File: BlackLab/${cur.filePath}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        if (file.exists()) Text("Size: ${Files.formatSize(file.length())}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
                dismissButton = { if (file.exists()) TextButton(onClick = { Files.open(ctx, file) }) { Text("Open file") } },
            )
        }
        "delete" -> ConfirmDialog("Delete document?", "“${cur.title}” and its file will be permanently deleted.", onDismiss = { dialog = null }) {
            app.io { if (cur.filePath.isNotBlank()) app.content.delete(cur.filePath); if (cur.id != 0L) app.db.documents().delete(cur) }
            doc = null; controller = null
            shell.back()
        }
    }
}
