package com.blacklab.app.ui.journal

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.ContentStore
import com.blacklab.app.data.JournalEntity
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.docs.Printer
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DatePickerDialogBox
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.documents.DocActions
import com.blacklab.app.ui.documents.ExportSheet
import com.blacklab.app.ui.editor.EditorController
import com.blacklab.app.ui.editor.EditorRibbon
import com.blacklab.app.ui.editor.RichEditorView
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.LocalBlColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun JournalEditorScreen(id: Long, day: Long?, template: String?) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = settingsState()
    var entry by remember { mutableStateOf<JournalEntity?>(null) }
    var controller by remember { mutableStateOf<EditorController?>(null) }
    var dirtyMeta by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(PageSetup(s.defaultPageSize)) }
    val scope = rememberCoroutineScope()
    val lock = remember { Mutex() }

    LaunchedEffect(id) {
        val (e, body) = withContext(Dispatchers.IO) {
            if (id != 0L) {
                val j = app.db.journal().get(id)
                j to (j?.let { app.content.readBody(it.filePath) } ?: "")
            } else {
                val prompt = JournalDraft.prompt.also { JournalDraft.prompt = "" }
                val t = JournalTemplates.firstOrNull { it.id == template } ?: JournalTemplates.first()
                val html = (if (prompt.isNotBlank()) "<blockquote>${ContentStore.escape(prompt)}</blockquote>" else "") + t.html
                JournalEntity(profile = s.activeProfile.name, entryDay = day ?: Time.today().toEpochDay(), title = if (t.id == "blank") "" else t.name) to html
            }
        }
        entry = e
        controller = EditorController(body, "Write freely…")
    }

    suspend fun save(final: Boolean = false) = lock.withLock {
        val c = controller ?: return@withLock
        val e = entry ?: return@withLock
        val html = c.html()
        withContext(Dispatchers.IO) {
            val text = ContentStore.plainText(html)
            if (e.id == 0L && text.isBlank() && e.title.isBlank() && final) return@withContext
            val rel = app.content.writeJournal(Profile.from(e.profile), LocalDate.ofEpochDay(e.entryDay), e.title, e.filePath, html)
            val updated = e.copy(filePath = rel, preview = ContentStore.preview(text), wordCount = ContentStore.wordCount(text), updatedAt = Time.now())
            val newId = if (e.id == 0L) app.db.journal().insert(updated) else { app.db.journal().update(updated); e.id }
            withContext(Dispatchers.Main) { entry = updated.copy(id = newId); dirtyMeta = false }
        }
    }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        c.editTick.drop(1).collectLatest { delay(1500); save() }
    }
    LaunchedEffect(entry?.title, entry?.mood, entry?.favorite, entry?.tags, entry?.location, entry?.entryDay) {
        if (dirtyMeta) { delay(800); save() }
    }
    fun meta(update: (JournalEntity) -> JournalEntity) { entry?.let { entry = update(it); dirtyMeta = true } }

    val leave: () -> Unit = { scope.launch { withContext(NonCancellable) { runCatching { save(final = true) } }; shell.back() } }
    BackHandler { leave() }

    val e = entry
    val c = controller
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column(Modifier.clickable { dialog = "date" }) {
                    Text(e?.let { Time.longDate(LocalDate.ofEpochDay(it.entryDay)) } ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(app.settings.profileName(s.activeProfile) + " journal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (e != null) IconButton(onClick = { meta { it.copy(favorite = !it.favorite) } }) {
                    Icon(if (e.favorite) Icons.Default.Star else Icons.Default.StarOutline, "Favorite", tint = if (e.favorite) Amber else MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = { scope.launch { save(); dialog = "export" } }) { Icon(Icons.Default.IosShare, "Export") }
                var more by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Tags & location") }, leadingIcon = { Icon(Icons.Default.Sell, null) }, onClick = { more = false; dialog = "meta" })
                        DropdownMenuItem(text = { Text("Change date") }, leadingIcon = { Icon(Icons.Default.Event, null) }, onClick = { more = false; dialog = "date" })
                        DropdownMenuItem(text = { Text("Delete entry") }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { more = false; dialog = "delete" })
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        )
        if (e == null || c == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        TextField(
            e.title, { v -> meta { it.copy(title = v) } }, placeholder = { Text("Title (optional)") }, singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Mood", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Moods.drop(1).forEachIndexed { i, (emoji, label) ->
                val idx = i + 1
                Text(emoji, fontSize = 24.sp, modifier = Modifier.clip(CircleShape)
                    .background(if (e.mood == idx) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                    .clickable { meta { it.copy(mood = if (it.mood == idx) 0 else idx) } }.padding(6.dp))
            }
            if (e.mood > 0) Text(Moods[e.mood].second, style = MaterialTheme.typography.labelMedium)
        }
        RichEditorView(c, lightDesk = !LocalBlColors.current.isDark, modifier = Modifier.weight(1f).fillMaxWidth())
        EditorRibbon(c, page, { page = it }, Modifier.navigationBarsPadding())
    }

    val cur = entry ?: return
    when (dialog) {
        "date" -> DatePickerDialogBox(LocalDate.ofEpochDay(cur.entryDay), { dialog = null }) { d -> d?.let { nd -> meta { it.copy(entryDay = nd.toEpochDay()) } } }
        "meta" -> {
            var tags by remember { mutableStateOf(cur.tags) }
            var loc by remember { mutableStateOf(cur.location) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Tags & location") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true)
                        OutlinedTextField(loc, { loc = it }, label = { Text("Location") }, singleLine = true)
                    }
                },
                confirmButton = { TextButton(onClick = { meta { it.copy(tags = tags.trim(), location = loc.trim()) }; dialog = null }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "export" -> {
            val title = cur.title.ifBlank { "Journal " + Time.date(LocalDate.ofEpochDay(cur.entryDay)) }
            ExportSheet(title, { dialog = null }, onPrint = {
                scope.launch { val html = controller?.html() ?: return@launch; Printer.print(ctx, title, html, page) }
            }) { f, share ->
                scope.launch {
                    val html = controller?.html() ?: return@launch
                    DocActions.export(app, ctx, shell, Profile.from(cur.profile), title, html, page, f, share)
                }
            }
        }
        "delete" -> ConfirmDialog("Delete entry?", "This journal entry and its file will be permanently deleted.", onDismiss = { dialog = null }) {
            app.io { if (cur.filePath.isNotBlank()) app.content.delete(cur.filePath); if (cur.id != 0L) app.db.journal().delete(cur) }
            entry = null; controller = null
            shell.back()
        }
    }
}
