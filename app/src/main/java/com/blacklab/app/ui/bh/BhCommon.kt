package com.blacklab.app.ui.bh

import com.blacklab.app.ui.theme.readable
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.MainActivity
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Forms
import com.blacklab.app.bh.Specs
import com.blacklab.app.bh.Writer
import com.blacklab.app.core.Profile
import com.blacklab.app.data.ContentStore
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.findActivity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.Danger

// ------------------------------------------------------------------ state + lock

/** Wall-clock millis that refresh every [periodMs] while the screen is shown (for countdowns). */
@Composable
fun rememberTicker(periodMs: Long = 1_000): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMs) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(periodMs) } }
    return now
}

@Composable
fun bhState(): Bh.State {
    val ctx = LocalContext.current
    remember { BhStore.load(ctx) }
    val s by BhStore.state.collectAsState()
    return s
}

/** Asks for the fingerprint / screen lock before showing the workspace, then stays open for a few minutes of use. */
object BhGate {
    @Volatile var unlockedUntil = 0L
    private const val GRACE_MS = 10 * 60_000L
    fun open(): Boolean = System.currentTimeMillis() < unlockedUntil
    fun touch() { if (open()) unlockedUntil = System.currentTimeMillis() + GRACE_MS }
    fun lock() { unlockedUntil = 0 }
    fun unlock(context: Context, onOk: () -> Unit) {
        val act = context.findActivity() as? MainActivity
        if (act == null || !act.canAuthenticate()) { unlockedUntil = System.currentTimeMillis() + GRACE_MS; onOk(); return }
        act.authenticate("Open Behavioral Health", "Confirm it's you") { ok -> if (ok) { unlockedUntil = System.currentTimeMillis() + GRACE_MS; onOk() } }
    }
}

/** Wraps every Behavioral Health screen: unlock first (when required), then the content. */
@Composable
fun BhGuarded(content: @Composable () -> Unit) {
    val s = bhState()
    val ctx = LocalContext.current
    val shell = LocalShell.current
    var open by remember { mutableStateOf(!s.prefs.requireUnlock || BhGate.open()) }
    LaunchedEffect(Unit) { if (!open) BhGate.unlock(ctx) { open = true } else BhGate.touch() }
    if (open) content() else Scaffold(topBar = { BlTopBar("Behavioral Health", onBack = { shell.back() }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.Lock, "Locked", "Your Behavioral Health workspace is encrypted on this phone. Confirm it's you to open it.") {
                Button(onClick = { BhGate.unlock(ctx) { open = true } }) { Text("Unlock") }
            }
        }
    }
}

// ------------------------------------------------------------------ output: copy / share / save

fun copySensitive(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    cm.setPrimaryClip(clip)
}

/** Shows a finished note with Copy (marked sensitive), Share and Save as document. */
@Composable
fun NoteOutputDialog(title: String, text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    var confirmSave by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { confirmSave = true }) { Text("Save doc") }
            TextButton(onClick = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title), "Share note"))
            }) { Text("Share") }
            Button(onClick = { copySensitive(ctx, title, text); shell.toast("Copied — paste it into the EHR") }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Copy") }
        }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (confirmSave) ConfirmDialog("Save as a document?",
        "Documents are ordinary files in BlackLab › Professional › Documents — they're not encrypted like this workspace and can sync to BlackLab Desktop. Save only notes without identifying details.",
        confirmLabel = "Save", destructive = false, onDismiss = { confirmSave = false }) {
        app.io {
            val html = ContentStore.textToHtml(text)
            val rel = app.content.writeDocument(Profile.PROFESSIONAL, title, "Behavioral Health", "", html)
            val plain = ContentStore.plainText(html)
            app.db.documents().insert(com.blacklab.app.data.DocumentEntity(profile = Profile.PROFESSIONAL.name, title = title, folder = "Behavioral Health", filePath = rel,
                preview = ContentStore.preview(plain), wordCount = ContentStore.wordCount(plain)))
        }
        shell.toast("Saved to Documents › Behavioral Health")
    }
}

// ------------------------------------------------------------------ chips

@Composable
fun ChipSelect(options: List<String>, selected: List<String>, onChange: (List<String>) -> Unit, single: Boolean = false, collapseAfter: Int = 14) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all || options.size <= collapseAfter) options else options.take(collapseAfter).let { base -> (base + selected.filter { it !in base }) }
    ChipFlow {
        shown.forEach { o ->
            val on = o in selected
            FilterChip(on, onClick = {
                onChange(if (single) (if (on) emptyList() else listOf(o)) else if (on) selected - o else selected + o)
            }, label = { Text(o) }, leadingIcon = if (on) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null,
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
        }
        if (options.size > collapseAfter) TextButton(onClick = { all = !all }) { Text(if (all) "Fewer" else "+${options.size - collapseAfter} more") }
    }
}

@Composable
fun OptionalDropdown(label: String, options: List<String>, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) =
    DropdownField(label, listOf("" to "—") + options.map { it to it }, value, onChange, modifier)

// ------------------------------------------------------------------ the form editor

@Composable
fun FormEditor(spec: Forms.Spec, rec: Forms.Rec, onChange: (Forms.Rec) -> Unit, extraOptions: Map<String, List<String>> = emptyMap()) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (spec.help.isNotBlank()) Text(spec.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        spec.sections.forEach { section ->
            val fields = section.fields.filter { Forms.visible(it, rec) }
            if (fields.isEmpty()) return@forEach
            var open by remember(section.title) { mutableStateOf(true) }
            SectionHeader(section.title, action = if (open) "Hide" else "Show") { open = !open }
            AnimatedVisibility(open) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (section.help.isNotBlank()) Text(section.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    fields.forEach { f -> FieldEditor(f, rec, onChange, extraOptions[f.key].orEmpty()) }
                }
            }
        }
    }
}

@Composable
private fun FieldEditor(f: Forms.Field, rec: Forms.Rec, onChange: (Forms.Rec) -> Unit, extra: List<String>) {
    val value = rec.v(f.key)
    val placeholder: (@Composable () -> Unit)? = f.hint.takeIf { it.isNotBlank() }?.let { { Text(it) } }
    when (f.type) {
        Forms.Type.TEXT -> OutlinedTextField(value, { onChange(rec.with(f.key, it)) }, Modifier.fillMaxWidth(), label = { Text(f.label) }, placeholder = placeholder, singleLine = true)
        Forms.Type.LONG -> OutlinedTextField(value, { onChange(rec.with(f.key, it)) }, Modifier.fillMaxWidth().heightIn(min = 110.dp), label = { Text(f.label) }, placeholder = placeholder, minLines = 3)
        Forms.Type.NUMBER -> OutlinedTextField(value, { t -> onChange(rec.with(f.key, t.filter { it.isDigit() || it == '.' })) }, Modifier.fillMaxWidth(), label = { Text(f.label) },
            placeholder = placeholder, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Forms.Type.TIME -> Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { onChange(rec.with(f.key, it)) }, Modifier.weight(1f), label = { Text(f.label) }, singleLine = true, placeholder = { Text(Writer.when_(System.currentTimeMillis())) })
            TextButton(onClick = { onChange(rec.with(f.key, Writer.when_(System.currentTimeMillis()))) }) { Text("Now") }
        }
        Forms.Type.DROPDOWN -> OptionalDropdown(f.label, (f.options + extra).distinct(), value, { onChange(rec.with(f.key, it)) })
        Forms.Type.MULTI -> Column {
            Text(f.label, style = MaterialTheme.typography.labelLarge)
            if (f.hint.isNotBlank()) Text(f.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipSelect((f.options + extra).distinct(), rec.m(f.key), { onChange(rec.withMulti(f.key, it)) })
        }
        Forms.Type.SLIDER -> Column {
            Row { Text(f.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(value.ifBlank { "—" } + "/${f.max}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) }
            Slider(value.toFloatOrNull() ?: (f.max / 2f), { onChange(rec.with(f.key, it.toInt().toString())) }, valueRange = f.min.toFloat()..f.max.toFloat(), steps = (f.max - f.min - 1).coerceAtLeast(0))
        }
        Forms.Type.SWITCH -> LabeledSwitchRow(f.label, f.hint.ifBlank { null }, value == "true") { onChange(rec.with(f.key, it.toString())) }
    }
}

// ------------------------------------------------------------------ generic form screen  (bh/form/{kind}?id=&ref=)

@Composable
fun BhFormScreen(kind: String, id: String, ref: String) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val s = bhState()
    val spec = Specs.spec(kind) ?: run { shell.back(); return }
    val existing = remember(id) { s.records.firstOrNull { it.id == id } }
    var rec by remember(id) { mutableStateOf(existing ?: prefill(spec, ref, s)) }
    var preview by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val refName = s.participants.firstOrNull { it.id == rec.ref }?.alias ?: s.patients.firstOrNull { it.id == rec.ref }?.display
    val (done, total) = Forms.progress(spec, rec)
    val save = { BhStore.saveRecord(ctx, rec); dirty = false }

    Scaffold(topBar = {
        BlTopBar(spec.title, subtitle = listOfNotNull(refName, "$done of $total answered").joinToString(" · "), onBack = { if (dirty) save(); shell.back() }) {
            IconButton(onClick = { save(); preview = true }) { Icon(Icons.Rounded.Visibility, "Preview note") }
            if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth()) }
            if (kind in BhStore.PEOPLE_KINDS) item {
                Text("Use initials or an alias only. Drafts are deleted after ${s.prefs.noteDays.takeIf { it > 0 }?.let { "$it days" } ?: "you remove them"}; the EHR is the record of care.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (kind == Specs.PEER_NOTE || kind == Specs.REFERRAL || kind == Specs.SAFETY_PLAN) item {
                val people = s.participants.filter { it.active }
                if (people.isNotEmpty()) OptionalDropdown("Participant", people.map { it.alias }, people.firstOrNull { it.id == rec.ref }?.alias.orEmpty(), { a ->
                    rec = rec.copy(ref = people.firstOrNull { it.alias == a }?.id.orEmpty()); dirty = true
                })
            }
            if (kind == Specs.BEHAVIOR) item {
                val census = s.census
                if (census.isNotEmpty()) OptionalDropdown("Person (census)", census.map { it.display }, census.firstOrNull { it.id == rec.ref }?.display.orEmpty(), { d ->
                    rec = rec.copy(ref = census.firstOrNull { it.display == d }?.id.orEmpty()); dirty = true
                })
            }
            item { FormEditor(spec, rec, { rec = it; dirty = true }, extraOptions(kind, s)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { save(); shell.toast("Saved") }, Modifier.weight(1f)) { Text("Save draft") }
                    Button(onClick = { save(); preview = true }, Modifier.weight(1f)) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Write note") }
                }
            }
        }
    }
    if (preview) NoteOutputDialog(spec.title, Writer.render(rec, s), onDismiss = { preview = false })
    if (confirmDelete) ConfirmDialog("Delete this ${spec.title.lowercase()}?", "It's removed from the encrypted workspace.", onDismiss = { confirmDelete = false }) {
        BhStore.deleteRecord(ctx, rec.id); shell.back()
    }
}

/** Starts new forms with what BlackLab already knows (unit, census size, shift…). */
private fun prefill(spec: Forms.Spec, ref: String, s: Bh.State): Forms.Rec {
    var r = Forms.blank(spec, ref)
    val now = Writer.when_(System.currentTimeMillis())
    if (spec.field("when") != null) r = r.with("when", now)
    when (spec.id) {
        Specs.HANDOFF -> {
            s.activeShift?.let { r = r.with("shift", it.type) }
            r = r.with("unit", s.prefs.unit).let { if (s.census.isNotEmpty()) it.with("census", s.census.size.toString()) else it }
        }
        Specs.BEHAVIOR -> s.patients.firstOrNull { it.id == ref }?.let { p -> if (p.precautions.isNotEmpty()) r = r.withMulti("precautions", p.precautions) }
        Specs.GROUP_NOTE -> s.plans.firstOrNull { it.id == ref }?.let { g -> r = r.with("title", g.title).with("minutes", g.minutes.toString()) }
            ?: com.blacklab.app.bh.Library.GROUPS.firstOrNull { it.id == ref }?.let { g -> r = r.with("title", g.title).with("minutes", g.minutes.toString()) }
    }
    return r
}

/** Adds your own entries (saved resources, group plans) to a form's dropdowns. */
private fun extraOptions(kind: String, s: Bh.State): Map<String, List<String>> = when (kind) {
    Specs.GROUP_NOTE -> mapOf("type" to s.plans.map { it.category })
    else -> emptyMap()
}

// ------------------------------------------------------------------ saved records list  (bh/records/{kind})

@Composable
fun BhRecordsScreen(kind: String) {
    val shell = LocalShell.current
    val s = bhState()
    val spec = Specs.spec(kind) ?: run { shell.back(); return }
    val recs = s.records(kind)
    Scaffold(topBar = {
        BlTopBar(spec.title, subtitle = "${recs.size} saved", onBack = { shell.back() }) {
            IconButton(onClick = { shell.go("bh/form/$kind") }) { Icon(Icons.Rounded.Add, "New") }
        }
    }) { pad ->
        if (recs.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.EditNote, "Nothing saved yet", "Start a new ${spec.title.lowercase()} — every option is a tap away.") {
                Button(onClick = { shell.go("bh/form/$kind") }) { Text("New") }
            }
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recs, key = { it.id }) { r -> RecordRow(r, s) { shell.go("bh/form/$kind?id=${r.id}") } }
        }
    }
}

@Composable
fun RecordRow(r: Forms.Rec, s: Bh.State, onClick: () -> Unit) {
    val spec = Specs.spec(r.kind)
    val who = s.participants.firstOrNull { it.id == r.ref }?.alias ?: s.patients.firstOrNull { it.id == r.ref }?.display
    val headline = listOf("type", "title", "resource", "shift", "kind", "format").firstNotNullOfOrNull { k -> r.v(k).ifBlank { null } }
    BlCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Text(listOfNotNull(spec?.title, who).joinToString(" — "), style = MaterialTheme.typography.titleSmall)
        Text(listOfNotNull(headline, Writer.when_(r.updated)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.kind == Specs.INCIDENT && r.on("restrictive")) Text("Restrictive intervention", style = MaterialTheme.typography.labelSmall, color = Danger.readable())
    }
}
