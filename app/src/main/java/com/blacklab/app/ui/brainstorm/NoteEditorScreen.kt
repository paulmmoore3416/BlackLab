package com.blacklab.app.ui.brainstorm

import com.blacklab.app.ui.components.sharedCard
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.rounded.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.NoteAttachment
import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.brainstorm.NoteStyle
import com.blacklab.app.core.Files
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.NoteLabelEntity
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.viewer.openInApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One undo step: the note's text and list as they were. */
private data class NoteSnap(val title: String, val body: String, val items: List<NoteItem>, val checklist: Boolean)

fun noteColor(index: Int, dark: Boolean): Color? = NoteStyle.colors.getOrNull(index)?.let { c -> if (c.light == 0L) null else Color(if (dark) c.dark else c.light) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(noteId: Long, kind: String = "", label: String = "") {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = LocalBlColors.current.isDark
    val settings = settingsState()
    val profile = settings.activeProfile
    val dao = app.db.notes()

    var note by remember { mutableStateOf<NoteEntity?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<NoteItem>() }
    var lastSavedAt by remember { mutableLongStateOf(0L) }
    // what this editor last wrote; database changes that differ came from someone else (Live Share guests)
    var lastWritten by remember { mutableStateOf("" to "") }
    var edits by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var showChecked by remember { mutableStateOf(true) }
    var focusItem by remember { mutableIntStateOf(-1) }
    var editDrawing by remember { mutableStateOf<NoteAttachment?>(null) }
    var bigImage by remember { mutableStateOf<NoteAttachment?>(null) }
    val bodyFocus = remember { FocusRequester() }
    // Undo / redo, like Keep: text and list snapshots, committed after each pause in typing
    val undoStack = remember { mutableStateListOf<NoteSnap>() }
    val redoStack = remember { mutableStateListOf<NoteSnap>() }
    var lastSnap by remember { mutableStateOf<NoteSnap?>(null) }
    fun snap() = NoteSnap(title, body, items.toList(), note?.checklist == true)

    // ---- load or create
    LaunchedEffect(noteId) {
        val n = if (noteId > 0) dao.get(noteId) else NoteEntity(profile = profile.name, checklist = kind == "checklist", labels = if (label.isNotBlank()) NoteOps.joinLabels(listOf(label)) else "")
        if (n == null) { shell.back(); return@LaunchedEffect }
        note = n; title = n.title; body = n.body; items.clear(); items.addAll(NoteOps.items(n)); lastSavedAt = n.updatedAt; lastWritten = n.items to n.body
        if (kind == "checklist" && items.isEmpty()) { items.add(NoteItem("")); focusItem = 0 }
        lastSnap = snap()
        when (kind) { "drawing" -> dialog = "drawing"; "voice" -> dialog = "voice"; "image" -> dialog = "pickImage"; "camera" -> dialog = "camera" }
    }

    fun current(): NoteEntity? = note?.copy(title = title, body = body, items = NoteOps.itemsJson(items.toList()))
    fun isEmpty(n: NoteEntity) = n.title.isBlank() && n.body.isBlank() && NoteOps.items(n).all { it.text.isBlank() } && NoteOps.attachments(n).isEmpty() && n.reminderAt == null && n.placeLat == null
    suspend fun saveNow() {
        val n = current() ?: return
        if (n.id == 0L && isEmpty(n)) return
        lastWritten = n.items to n.body
        val id = NoteOps.save(app, n)
        val saved = dao.get(id)
        if (saved != null) { note = saved; lastSavedAt = saved.updatedAt }
    }
    fun update(block: (NoteEntity) -> NoteEntity) { note = note?.let(block); edits++ }
    fun commitSnap() {
        val cur = snap(); val prev = lastSnap
        if (prev != null && prev != cur) { undoStack.add(prev); if (undoStack.size > 100) undoStack.removeAt(0); redoStack.clear() }
        lastSnap = cur
    }
    fun restore(sn: NoteSnap) {
        title = sn.title; body = sn.body; items.clear(); items.addAll(sn.items); note = note?.copy(checklist = sn.checklist)
        lastSnap = sn; focusItem = -1; edits++
    }
    fun undo() { commitSnap(); if (undoStack.isEmpty()) return; lastSnap?.let { redoStack.add(it) }; restore(undoStack.removeAt(undoStack.lastIndex)) }
    fun redo() { commitSnap(); if (redoStack.isEmpty()) return; lastSnap?.let { undoStack.add(it) }; restore(redoStack.removeAt(redoStack.lastIndex)) }
    LaunchedEffect(edits) { if (edits > 0) { delay(500); commitSnap(); saveNow() } }

    // guests (Live Share) can tick items; adopt their changes when we aren't mid-edit
    val live by remember(note?.id) { dao.observeNote(note?.id ?: -1) }.collectAsState(initial = null)
    LaunchedEffect(live?.updatedAt) {
        val l = live ?: return@LaunchedEffect
        if (note?.id == l.id && (l.items to l.body) != lastWritten) {
            items.clear(); items.addAll(NoteOps.items(l)); body = l.body; note = l; lastSavedAt = l.updatedAt; lastWritten = l.items to l.body
            lastSnap = snap()
        }
    }

    fun leave() {
        scope.launch {
            val n = current()
            if (n != null && n.id != 0L && isEmpty(n)) { NoteOps.deleteForever(app, n); shell.toast("Empty note discarded") }
            else saveNow()
            shell.back()
        }
    }
    BackHandler { leave() }

    // ---- attachments
    suspend fun ensureId(): Long { if ((note?.id ?: 0L) == 0L) { val n = current()!!; val id = NoteOps.save(app, n); note = dao.get(id); lastSavedAt = note?.updatedAt ?: 0 }; return note!!.id }
    suspend fun addAttachment(a: NoteAttachment) { ensureId(); update { it.copy(attachments = NoteOps.attachmentsJson(NoteOps.attachments(it) + a)) } }
    suspend fun addImage(uri: Uri) {
        val id = ensureId()
        val f = withContext(Dispatchers.IO) {
            runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val s = 2000f / maxOf(info.size.width, info.size.height); if (s < 1f) d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
                }
                File(Brainstorm.noteDir(app, Profile.from(note!!.profile), id), "img_${System.currentTimeMillis()}.jpg").also { out -> out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            }.getOrNull()
        } ?: run { shell.toast("Couldn't add that picture"); return }
        val ocr = runCatching { Ocr.recognize(ctx, f) }.getOrDefault("")
        addAttachment(NoteAttachment("image", app.storage.relative(f), ocr = ocr))
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> scope.launch { uris.forEach { addImage(it) } } }
    val camFile = remember { File(ctx.cacheDir, "note_cam.jpg") }
    val camUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", camFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) scope.launch { addImage(camUri) } }
    LaunchedEffect(dialog) {
        if (dialog == "pickImage") { dialog = null; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        if (dialog == "camera") { dialog = null; takePhoto.launch(camUri) }
    }

    val n = note
    val bg = n?.let { noteColor(it.color, dark) } ?: MaterialTheme.colorScheme.background
    val onBg = MaterialTheme.colorScheme.onSurface
    val attachments = n?.let { NoteOps.attachments(it) } ?: emptyList()

    Scaffold(
        modifier = Modifier.sharedCard("note-$noteId"),
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { update { it.copy(pinned = !it.pinned) } }) { Icon(if (n?.pinned == true) Icons.Rounded.PushPin else Icons.Outlined.PushPin, if (n?.pinned == true) "Unpin" else "Pin") }
                    IconButton(onClick = { dialog = "reminder" }) { Icon(if (n?.reminderAt != null || n?.placeLat != null) Icons.Rounded.NotificationsActive else Icons.Rounded.AddAlert, "Reminder") }
                    IconButton(onClick = {
                        val archived = !(n?.archived ?: false)
                        update { it.copy(archived = archived, pinned = false) }
                        scope.launch { saveNow(); shell.toast(if (archived) "Note archived" else "Note unarchived"); if (archived) shell.back() }
                    }) { Icon(if (n?.archived == true) Icons.Rounded.Unarchive else Icons.Rounded.Archive, "Archive") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
            )
        },
        bottomBar = {
            Surface(color = bg, tonalElevation = 0.dp) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Rounded.AddBox, "Add") }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem(text = { Text("Take photo") }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) }, onClick = { addMenu = false; takePhoto.launch(camUri) })
                            DropdownMenuItem(text = { Text("Add image") }, leadingIcon = { Icon(Icons.Rounded.Image, null) }, onClick = { addMenu = false; pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            DropdownMenuItem(text = { Text("Drawing") }, leadingIcon = { Icon(Icons.Rounded.Brush, null) }, onClick = { addMenu = false; dialog = "drawing" })
                            DropdownMenuItem(text = { Text("Recording") }, leadingIcon = { Icon(Icons.Rounded.Mic, null) }, onClick = { addMenu = false; dialog = "voice" })
                            DropdownMenuItem(text = { Text(if (n?.checklist == true) "Hide checkboxes" else "Checkboxes") }, leadingIcon = { Icon(Icons.Rounded.CheckBox, null) }, onClick = {
                                addMenu = false
                                if (n?.checklist == true) { body = items.joinToString("\n") { it.text }; items.clear(); update { it.copy(checklist = false) } }
                                else { items.clear(); items.addAll(body.lines().filter { it.isNotBlank() }.map { NoteItem(it.trim()) }.ifEmpty { listOf(NoteItem("")) }); body = ""; update { it.copy(checklist = true) } }
                            })
                        }
                    }
                    IconButton(onClick = { dialog = "palette" }) { Icon(Icons.Rounded.Palette, "Background options") }
                    val canUndo = undoStack.isNotEmpty() || (lastSnap != null && lastSnap != snap())
                    IconButton(onClick = { undo() }, enabled = canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                    IconButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Icon(Icons.AutoMirrored.Rounded.Redo, "Redo") }
                    Text(if (n != null && n.id != 0L) "Edited " + Time.time(n.updatedAt) else "", style = MaterialTheme.typography.labelSmall, color = onBg.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                                menu = false; scope.launch { saveNow(); note?.takeIf { it.id != 0L }?.let { NoteOps.trash(app, it) }; shell.toast("Note moved to trash"); shell.back() }
                            })
                            DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                                menu = false; scope.launch { saveNow(); note?.takeIf { it.id != 0L }?.let { val id = NoteOps.copy(app, it); shell.toast("Copied"); shell.nav.navigate("note/$id") { popUpTo("note/{id}?kind={kind}&label={label}") { inclusive = true } } } }
                            })
                            DropdownMenuItem(text = { Text("Send") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Send, null) }, onClick = {
                                menu = false; current()?.let { c ->
                                    val imgs = NoteOps.attachments(c).filter { it.kind != "audio" }.map { app.storage.resolve(it.path) }.filter { it.exists() }
                                    if (imgs.isEmpty()) Files.shareText(ctx, NoteOps.plainText(c), c.title.ifBlank { null }) else Files.share(ctx, imgs, NoteOps.plainText(c).take(200))
                                }
                            })
                            DropdownMenuItem(text = { Text("Live Share") }, leadingIcon = { Icon(Icons.Rounded.Wifi, null) }, onClick = { menu = false; scope.launch { ensureId(); saveNow(); dialog = "live" } })
                            DropdownMenuItem(text = { Text("Comments") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Comment, null) }, onClick = { menu = false; scope.launch { ensureId(); dialog = "comments" } })
                            DropdownMenuItem(text = { Text("Labels") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Label, null) }, onClick = { menu = false; dialog = "labels" })
                            DropdownMenuItem(text = { Text("Send to notebook") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.MenuBook, null) }, onClick = { menu = false; scope.launch { saveNow(); dialog = "toPage" } })
                            if (n?.checklist == true) {
                                DropdownMenuItem(text = { Text("Uncheck all items") }, leadingIcon = { Icon(Icons.Rounded.CheckBoxOutlineBlank, null) }, onClick = { menu = false; for (i in items.indices) items[i] = items[i].copy(done = false); edits++ })
                                DropdownMenuItem(text = { Text("Delete checked items") }, leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) }, onClick = { menu = false; items.removeAll { it.done }; edits++ })
                            }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            n?.bg?.let { NoteStyle.bgEmoji(it) }?.takeIf { it.isNotEmpty() }?.let { e ->
                Text(e, fontSize = 150.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).alpha(0.13f))
            }
            if (n == null) return@Box
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                val pics = attachments.filter { it.kind != "audio" }
                if (pics.isNotEmpty()) item(key = "pics") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = if (pics.size == 1) 1 else 2) {
                        pics.forEach { a ->
                            AsyncImage(model = app.storage.resolve(a.path), contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.weight(1f).heightIn(min = 120.dp, max = if (pics.size == 1) 360.dp else 180.dp).clip(RoundedCornerShape(12.dp))
                                    .clickable { if (a.kind == "drawing") editDrawing = a else bigImage = a })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                attachments.filter { it.kind == "audio" }.forEach { a ->
                    item(key = "a" + a.path) {
                        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(14.dp), color = onBg.copy(alpha = 0.06f)) {
                            Column(Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { openInApp(shell, app, app.storage.resolve(a.path)) }) { Icon(Icons.Rounded.PlayCircle, "Play", Modifier.size(32.dp)) }
                                    Text("Recording · " + Time.duration(a.durationMs), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { update { it.copy(attachments = NoteOps.attachmentsJson(NoteOps.attachments(it) - a)) }; app.storage.resolve(a.path).delete() }) { Icon(Icons.Rounded.Close, "Remove") }
                                }
                                if (a.transcript.isNotBlank()) Text(a.transcript, style = MaterialTheme.typography.bodySmall, color = onBg.copy(alpha = 0.8f), modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                            }
                        }
                    }
                }
                item(key = "title") {
                    BasicTextField(title, { title = it; edits++ }, textStyle = TextStyle(fontSize = 22.sp, color = onBg, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { if (n.checklist) { if (items.isEmpty()) items.add(NoteItem("")); focusItem = 0 } else runCatching { bodyFocus.requestFocus() } }),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        decorationBox = { inner -> Box { if (title.isEmpty()) Text("Title", fontSize = 22.sp, color = onBg.copy(alpha = 0.45f)); inner() } })
                }
                if (!n.checklist) item(key = "body") {
                    BasicTextField(body, { body = it; edits++ }, textStyle = TextStyle(fontSize = 16.sp, color = onBg, lineHeight = 23.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).focusRequester(bodyFocus),
                        decorationBox = { inner -> Box { if (body.isEmpty()) Text("Note", fontSize = 16.sp, color = onBg.copy(alpha = 0.45f)); inner() } })
                } else {
                    val checkedBottom = settings.notesCheckedBottom
                    val open = items.withIndex().filter { !checkedBottom || !it.value.done }
                    val done = if (checkedBottom) items.withIndex().filter { it.value.done } else emptyList()
                    val addAt = if (settings.notesNewBottom) open.lastOrNull()?.index?.plus(1) ?: items.size else 0
                    val addRow: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                        item(key = "addItem") {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { items.add(addAt, NoteItem("")); focusItem = addAt; edits++ }.padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Add, null, tint = onBg.copy(alpha = 0.6f)); Spacer(Modifier.width(12.dp)); Text("List item", color = onBg.copy(alpha = 0.6f))
                            }
                        }
                    }
                    if (!settings.notesNewBottom) addRow()
                    items(open, key = { "o${it.index}" }) { (i, it) ->
                        ChecklistRow(it, onBg, focus = focusItem == i, onFocused = { focusItem = i },
                            onText = { t ->
                                if (t.contains('\n')) {
                                    val parts = t.split('\n')
                                    items[i] = it.copy(text = parts.first())
                                    parts.drop(1).forEachIndexed { k, p -> items.add(i + 1 + k, NoteItem(p, indent = it.indent)) }
                                    focusItem = i + parts.size - 1
                                } else items[i] = it.copy(text = t)
                                edits++
                            },
                            onCheck = { d -> items[i] = it.copy(done = d); edits++ },
                            onIndent = { d -> items[i] = it.copy(indent = (it.indent + d).coerceIn(0, 1)); edits++ },
                            onDelete = { items.removeAt(i); focusItem = (i - 1).coerceAtLeast(-1); edits++ },
                            onUp = { if (i > 0) { items.add(i - 1, items.removeAt(i)); focusItem = i - 1; edits++ } },
                            onDown = { if (i < items.lastIndex) { items.add(i + 1, items.removeAt(i)); focusItem = i + 1; edits++ } })
                    }
                    if (settings.notesNewBottom) addRow()
                    if (done.isNotEmpty()) {
                        item(key = "doneHeader") {
                            HorizontalDivider(color = onBg.copy(alpha = 0.15f))
                            Row(Modifier.fillMaxWidth().clickable { showChecked = !showChecked }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (showChecked) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = onBg.copy(alpha = 0.7f))
                                Spacer(Modifier.width(8.dp))
                                Text("${done.size} checked item${if (done.size > 1) "s" else ""}", color = onBg.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        if (showChecked) items(done, key = { "d${it.index}" }) { (i, item) ->
                            Row(Modifier.fillMaxWidth().padding(start = (item.indent * 24).dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(true, { items[i] = item.copy(done = false); edits++ })
                                Text(item.text, color = onBg.copy(alpha = 0.55f), textDecoration = TextDecoration.LineThrough, modifier = Modifier.weight(1f))
                                IconButton(onClick = { items.removeAt(i); edits++ }) { Icon(Icons.Rounded.Close, "Delete", tint = onBg.copy(alpha = 0.5f)) }
                            }
                        }
                    }
                }
                item(key = "chips") {
                    FlowRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        n.reminderAt?.let { at ->
                            AssistChip({ dialog = "reminder" }, { Text(Time.relativeDay(Time.toDate(at)) + ", " + Time.time(at) + (Recurrence.parse(n.repeatRule)?.let { " · " + it.describe() } ?: ""),
                                textDecoration = if (at < Time.now()) TextDecoration.LineThrough else null) }, leadingIcon = { Icon(Icons.Rounded.Alarm, null) })
                        }
                        if (n.placeLat != null) AssistChip({ dialog = "reminder" }, { Text((if (n.placeOnLeave) "Leaving " else "") + n.placeName.ifBlank { "Place" }) }, leadingIcon = { Icon(Icons.Rounded.Place, null) })
                        NoteOps.labels(n).forEach { l -> AssistChip({ dialog = "labels" }, { Text(l) }) }
                    }
                }
            }
        }
    }

    // ---------------- dialogs
    when (dialog) {
        "reminder" -> ReminderDialog(n?.reminderAt, n?.repeatRule, n?.placeLat?.let { PlacePick(n.placeName, it, n.placeLon ?: 0.0, n.placeRadius, n.placeOnLeave) },
            onDismiss = { dialog = null }) { r ->
            update {
                when {
                    r.place != null -> it.copy(reminderAt = null, repeatRule = null, placeName = r.place.name, placeLat = r.place.lat, placeLon = r.place.lon, placeRadius = r.place.radius, placeOnLeave = r.place.onLeave)
                    r.at != null -> it.copy(reminderAt = r.at, repeatRule = r.repeat, placeLat = null, placeLon = null, placeName = "")
                    else -> it.copy(reminderAt = null, repeatRule = null, placeLat = null, placeLon = null, placeName = "")
                }
            }
        }
        "palette" -> PaletteSheet(n?.color ?: 0, n?.bg ?: "", dark, onDismiss = { dialog = null }, onColor = { c -> update { it.copy(color = c) } }, onBg = { b -> update { it.copy(bg = b) } })
        "labels" -> LabelsDialog(profile, n?.let { NoteOps.labels(it) } ?: emptyList(), onDismiss = { dialog = null }) { l -> update { it.copy(labels = NoteOps.joinLabels(l)) } }
        "drawing" -> DrawingPadDialog(onDismiss = { dialog = null }) { f ->
            dialog = null
            scope.launch {
                val id = ensureId()
                val dst = File(Brainstorm.noteDir(app, profile, id), "drawing_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { f.copyTo(dst, overwrite = true); f.delete() }
                addAttachment(NoteAttachment("drawing", app.storage.relative(dst)))
            }
        }
        "voice" -> VoiceRecorderSheet(onDismiss = { dialog = null }) { f, transcript, ms ->
            dialog = null
            scope.launch {
                val id = ensureId()
                val dst = File(Brainstorm.noteDir(app, profile, id), "voice_${System.currentTimeMillis()}.m4a")
                withContext(Dispatchers.IO) { f.copyTo(dst, overwrite = true); f.delete() }
                if (transcript.isNotBlank()) { if (note?.checklist == true) items.add(NoteItem(transcript)) else body = (body.trimEnd() + "\n" + transcript).trim() }
                addAttachment(NoteAttachment("audio", app.storage.relative(dst), transcript = transcript, durationMs = ms))
            }
        }
        "live" -> note?.let { LiveShareSheet("NOTE", it.id, title.ifBlank { (NoteOps.items(it).firstOrNull { i -> i.text.isNotBlank() }?.text ?: body).take(40).ifBlank { "Note" } }) { dialog = null } }
        "comments" -> note?.let { CommentsSheet(null, it.id) { dialog = null } }
        "toPage" -> SectionPickerDialog("Send to notebook section", profile, onDismiss = { dialog = null }) { s ->
            scope.launch {
                val c = current() ?: return@launch
                runCatching { NoteOps.toPage(app, c, s.id) }.onSuccess { id -> shell.toast("Sent to ${s.name}", "Open") { shell.go("page/$id") } }.onFailure { shell.toast("Unlock “${s.name}” first") }
            }
        }
    }
    editDrawing?.let { a ->
        DrawingPadDialog(base = app.storage.resolve(a.path), onDismiss = { editDrawing = null }) { f ->
            editDrawing = null
            scope.launch { withContext(Dispatchers.IO) { f.copyTo(app.storage.resolve(a.path), overwrite = true); f.delete() }; edits++ ; note = note?.copy(updatedAt = Time.now()) }
        }
    }
    bigImage?.let { a ->
        AlertDialog(onDismissRequest = { bigImage = null }, confirmButton = { TextButton(onClick = { bigImage = null }) { Text("Close") } },
            dismissButton = {
                Row {
                    if (a.ocr.isNotBlank()) TextButton(onClick = { if (note?.checklist == true) a.ocr.lines().filter { it.isNotBlank() }.forEach { items.add(NoteItem(it.trim())) } else body = (body.trimEnd() + "\n" + a.ocr).trim(); edits++; bigImage = null }) { Text("Grab text") }
                    TextButton(onClick = { update { it.copy(attachments = NoteOps.attachmentsJson(NoteOps.attachments(it) - a)) }; app.storage.resolve(a.path).delete(); bigImage = null }) { Text("Delete", color = LocalBlColors.current.danger) }
                }
            },
            text = {
                Column {
                    AsyncImage(model = app.storage.resolve(a.path), contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    if (a.ocr.isNotBlank()) Text("Text found: " + a.ocr.take(160) + if (a.ocr.length > 160) "…" else "", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
                }
            })
    }
}

@Composable
private fun ChecklistRow(it: NoteItem, onBg: Color, focus: Boolean, onFocused: () -> Unit, onText: (String) -> Unit, onCheck: (Boolean) -> Unit, onIndent: (Int) -> Unit, onDelete: () -> Unit, onUp: () -> Unit, onDown: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(focus) { if (focus) runCatching { fr.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = (it.indent * 24).dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(it.done, { c -> onCheck(c) })
        BasicTextField(it.text, onText, textStyle = TextStyle(fontSize = 16.sp, color = if (it.done) onBg.copy(alpha = 0.55f) else onBg, textDecoration = if (it.done) TextDecoration.LineThrough else null), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).focusRequester(fr).onFocusChanged { f -> if (f.isFocused) onFocused() }.padding(vertical = 8.dp))
        if (focus) {
            IconButton(onClick = onUp, Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = onBg.copy(alpha = 0.6f)) }
            IconButton(onClick = onDown, Modifier.size(34.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = onBg.copy(alpha = 0.6f)) }
            IconButton(onClick = { onIndent(if (it.indent == 0) 1 else -1) }, Modifier.size(34.dp)) {
                Icon(if (it.indent == 0) Icons.AutoMirrored.Rounded.FormatIndentIncrease else Icons.AutoMirrored.Rounded.FormatIndentDecrease, "Indent", tint = onBg.copy(alpha = 0.6f))
            }
            IconButton(onClick = onDelete, Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "Delete item", tint = onBg.copy(alpha = 0.6f)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaletteSheet(color: Int, bg: String, dark: Boolean, onDismiss: () -> Unit, onColor: (Int) -> Unit, onBg: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Color", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                itemsIndexed(NoteStyle.colors) { i, c ->
                    val fill = noteColor(i, dark)
                    Box(Modifier.size(44.dp).clip(CircleShape).background(fill ?: MaterialTheme.colorScheme.surface)
                        .border(if (i == color) 3.dp else 1.dp, if (i == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { onColor(i) }, contentAlignment = Alignment.Center) {
                        if (i == 0) Icon(Icons.Rounded.FormatColorReset, c.name, Modifier.size(20.dp)) else if (i == color) Icon(Icons.Rounded.Check, null)
                    }
                }
            }
            Text("Background", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                items(NoteStyle.backgrounds) { (id, label, emoji) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(if (id == bg) 3.dp else 1.dp, if (id == bg) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onBg(id) }, contentAlignment = Alignment.Center) { if (emoji.isEmpty()) Icon(Icons.Rounded.HideImage, null) else Text(emoji, fontSize = 24.sp) }
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun LabelsDialog(profile: Profile, selected: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val app = rememberApp()
    val labels by app.db.notes().observeLabels(profile.name).collectAsState(initial = emptyList())
    val chosen = remember { mutableStateListOf<String>().apply { addAll(selected) } }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onSave(chosen.toList()); onDismiss() },
        title = { Text("Label note") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Enter label name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val name = q.trim()
                if (name.isNotEmpty() && labels.none { it.name.equals(name, true) }) {
                    TextButton(onClick = { app.io { app.db.notes().insertLabel(NoteLabelEntity(profile = profile.name, name = name)) }; chosen += name; q = "" }) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Create “$name”")
                    }
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(labels.filter { q.isBlank() || it.name.contains(q, true) }, key = { it.id }) { l ->
                        Row(Modifier.fillMaxWidth().clickable { if (l.name in chosen) chosen.remove(l.name) else chosen.add(l.name) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.Label, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(l.name, Modifier.weight(1f))
                            Checkbox(l.name in chosen, { c -> if (c) chosen.add(l.name) else chosen.remove(l.name) })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen.toList()); onDismiss() }) { Text("Done") } },
    )
}
