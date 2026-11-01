package com.blacklab.app.capture

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.blacklab.app.app
import com.blacklab.app.brainstorm.Brainstorm
import com.blacklab.app.brainstorm.NoteAttachment
import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.brainstorm.VoiceRecorderSheet
import com.blacklab.app.ui.components.Ocr
import com.blacklab.app.ui.theme.BlackLabTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Write-only capture opened from the Quick capture and Checklist widgets: new note, checklist,
 * voice note, photo note, task, or items added to a pinned checklist. It never lists or shows
 * existing content, so it does not ask for the BlackLab app lock.
 */
class QuickCaptureActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_NOTE = "noteId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE) ?: "note"
        val noteId = intent.getLongExtra(EXTRA_NOTE, 0)
        val app = app
        setContent {
            val s by app.settings.state.collectAsState()
            var profile by remember { mutableStateOf(s.activeProfile) }
            BlackLabTheme(com.blacklab.app.ui.theme.rememberThemeMode(s), s.dynamicColor, profile, com.blacklab.app.ui.theme.ThemeChoice.from(s)) {
                when (mode) {
                    "voice" -> VoiceRecorderSheet(onDismiss = { finish() }) { f, transcript, ms -> saveVoice(profile, f, transcript, ms) }
                    "photo" -> PhotoCapture(profile, onProfile = { profile = it })
                    else -> TextCapture(mode, noteId, profile, onProfile = { profile = it })
                }
            }
        }
    }

    // ------------------------------------------------------------------ UI

    @Composable
    private fun Sheet(onOutside: () -> Unit, content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOutside() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                Modifier.fillMaxWidth().imePadding().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { Column(Modifier.navigationBarsPadding().padding(20.dp)) { content() } }
        }
    }

    @Composable
    private fun AccountChip(profile: Profile, onProfile: (Profile) -> Unit) {
        val app = app
        AssistChip(onClick = { onProfile(if (profile == Profile.PERSONAL) Profile.PROFESSIONAL else Profile.PERSONAL) },
            label = { Text("Save to " + app.settings.profileName(profile)) })
    }

    @Composable
    private fun TextCapture(mode: String, noteId: Long, profile: Profile, onProfile: (Profile) -> Unit) {
        var title by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        var due by remember { mutableStateOf("TODAY") }
        var important by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        val heading = when (mode) { "checklist" -> "New checklist"; "task" -> "New task"; "additem" -> "Add to checklist"; else -> "New note" }
        fun save() = saveText(mode, noteId, profile, title, text, due, important)
        Sheet(onOutside = { if (text.isBlank() && title.isBlank()) finish() else save() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (mode != "additem") AccountChip(profile, onProfile)
            }
            Spacer(Modifier.size(8.dp))
            val colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent)
            if (mode == "note" || mode == "checklist") {
                TextField(title, { title = it }, placeholder = { Text("Title") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.titleMedium, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            }
            TextField(
                text, { text = it }, colors = colors,
                placeholder = { Text(when (mode) { "task" -> "What needs doing?"; "note" -> "Take a note…"; else -> "One item per line" }) },
                singleLine = mode == "task",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = if (mode == "task") 56.dp else 120.dp, max = 320.dp).focusRequester(focus),
            )
            if (mode == "task") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("TODAY" to "Today", "TOMORROW" to "Tomorrow", "NONE" to "No date").forEach { (k, l) ->
                        FilterChip(due == k, { due = k }, label = { Text(l) })
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { important = !important }) {
                        Icon(if (important) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Important", tint = if (important) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { finish() }) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = { save() }, enabled = text.isNotBlank() || title.isNotBlank()) { Text("Save") }
            }
        }
    }

    @Composable
    private fun PhotoCapture(profile: Profile, onProfile: (Profile) -> Unit) {
        val camFile = remember { File(cacheDir, "capture_${System.currentTimeMillis()}.jpg") }
        val camUri = remember { FileProvider.getUriForFile(this, "$packageName.fileprovider", camFile) }
        var taken by remember { mutableStateOf(false) }
        var caption by remember { mutableStateOf("") }
        val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) taken = true else finish() }
        val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) take.launch(camUri) else { toast("Camera permission is needed for photo notes"); finish() } }
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(this@QuickCaptureActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) take.launch(camUri)
            else perm.launch(Manifest.permission.CAMERA)
        }
        if (!taken) return
        val preview = remember { runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(camFile)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sc = 900f / maxOf(info.size.width, info.size.height); if (sc < 1f) d.setTargetSize((info.size.width * sc).toInt(), (info.size.height * sc).toInt())
        } }.getOrNull() }
        Sheet(onOutside = { savePhoto(profile, camFile, caption) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Photo note", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AccountChip(profile, onProfile)
            }
            preview?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(vertical = 10.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
            }
            TextField(caption, { caption = it }, placeholder = { Text("Add a caption (optional)") }, modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent))
            Text("Text in the photo becomes searchable after saving.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { camFile.delete(); finish() }) { Text("Discard") }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = { savePhoto(profile, camFile, caption) }) { Text("Save") }
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private var saving = false

    private fun saveText(mode: String, noteId: Long, profile: Profile, title: String, text: String, due: String, important: Boolean) {
        if (saving) return
        saving = true
        val app = app
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        lifecycleScope.launch {
            val msg = runCatching {
                when (mode) {
                    "task" -> {
                        val today = Time.today().toEpochDay()
                        TaskOps.save(app, TaskEntity(profile = profile.name, title = text.trim(), important = important,
                            dueDay = when (due) { "TODAY" -> today; "TOMORROW" -> today + 1; else -> null }))
                        "Task added"
                    }
                    "checklist" -> {
                        NoteOps.save(app, NoteEntity(profile = profile.name, title = title.trim(), checklist = true, items = NoteOps.itemsJson(lines.map { NoteItem(it) })))
                        "Checklist saved"
                    }
                    "additem" -> {
                        val n = app.db.notes().get(noteId) ?: error("That note no longer exists")
                        if (n.checklist) NoteOps.save(app, n.copy(items = NoteOps.itemsJson(NoteOps.items(n) + lines.map { NoteItem(it) })))
                        else NoteOps.save(app, n.copy(body = (n.body.trimEnd() + "\n" + lines.joinToString("\n")).trim()))
                        if (lines.size == 1) "Item added" else "${lines.size} items added"
                    }
                    else -> {
                        NoteOps.save(app, NoteEntity(profile = profile.name, title = title.trim(), body = text.trim()))
                        "Note saved"
                    }
                }
            }.getOrElse { "Couldn't save: ${it.message}" }
            toast(msg)
            finish()
        }
    }

    private fun saveVoice(profile: Profile, file: File, transcript: String, ms: Long) {
        val app = app
        lifecycleScope.launch {
            runCatching {
                val id = NoteOps.save(app, NoteEntity(profile = profile.name, title = "Voice note", body = transcript))
                val dst = File(Brainstorm.noteDir(app, profile, id), "voice_${System.currentTimeMillis()}.m4a")
                withContext(Dispatchers.IO) { file.copyTo(dst, overwrite = true); file.delete() }
                val n = app.db.notes().get(id)!!
                NoteOps.save(app, n.copy(attachments = NoteOps.attachmentsJson(listOf(NoteAttachment("audio", app.storage.relative(dst), transcript = transcript, durationMs = ms)))))
            }.onSuccess { toast("Voice note saved") }.onFailure { toast("Couldn't save the recording") }
            finish()
        }
    }

    private fun savePhoto(profile: Profile, camFile: File, caption: String) {
        if (saving) return
        saving = true
        val app = app
        val appContext = applicationContext
        lifecycleScope.launch {
            val ok = runCatching {
                val id = NoteOps.save(app, NoteEntity(profile = profile.name, body = caption.trim()))
                val dst = File(Brainstorm.noteDir(app, profile, id), "img_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(camFile)) { d, info, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val sc = 2000f / maxOf(info.size.width, info.size.height); if (sc < 1f) d.setTargetSize((info.size.width * sc).toInt(), (info.size.height * sc).toInt())
                    }
                    dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                    camFile.delete()
                }
                val n = app.db.notes().get(id)!!
                NoteOps.save(app, n.copy(attachments = NoteOps.attachmentsJson(listOf(NoteAttachment("image", app.storage.relative(dst))))))
                // Read the text in the picture after the sheet closes, so saving feels instant
                app.appScope.launch {
                    val ocr = runCatching { Ocr.recognize(appContext, Uri.fromFile(dst)) }.getOrDefault("")
                    if (ocr.isNotBlank()) app.db.notes().get(id)?.let { cur ->
                        val atts = NoteOps.attachments(cur).map { if (it.path == app.storage.relative(dst)) it.copy(ocr = ocr) else it }
                        NoteOps.save(app, cur.copy(attachments = NoteOps.attachmentsJson(atts)))
                    }
                }
            }.isSuccess
            toast(if (ok) "Photo note saved" else "Couldn't save the photo")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
}
