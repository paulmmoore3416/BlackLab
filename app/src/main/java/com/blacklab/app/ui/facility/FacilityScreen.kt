package com.blacklab.app.ui.facility

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddTask
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.docs.HtmlToBlocks
import com.blacklab.app.docs.PageSetup
import com.blacklab.app.docs.PdfWriter
import com.blacklab.app.facility.Facility
import com.blacklab.app.facility.FacilityReminders
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberCodeScanner
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.readable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private fun statusColor(s: Facility.Status): Color = when (s) { Facility.Status.OVERDUE -> Danger; Facility.Status.DUE_SOON -> Amber; Facility.Status.OK -> Mint }
private fun day(d: Long) = Time.date(LocalDate.ofEpochDay(d))
private fun every(days: Int) = when (days) { 1 -> "Daily"; 7 -> "Weekly"; 14 -> "Every 2 weeks"; 30, 31 -> "Monthly"; 91, 90 -> "Quarterly"; 182, 183 -> "Every 6 months"; 365 -> "Yearly"; else -> "Every $days days" }

/** Facility home: what's due, the asset register and the inspection / work log. [tag] opens or creates the asset with that tag (from the code scanner). */
@Composable
fun FacilityScreen(tab: String = "", tag: String = "") {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    remember { Facility.load(ctx) }
    val st by Facility.state.collectAsStateWithLifecycle()
    val today = LocalDate.now().toEpochDay()
    val due = remember(st, today) { Facility.due(st, today) }
    var selected by rememberSaveable { mutableStateOf(when (tab) { "assets" -> 1; "log" -> 2; else -> 0 }) }
    var newAsset by remember { mutableStateOf<Facility.Asset?>(null) }
    var catalog by remember { mutableStateOf(false) }
    var binder by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf<Facility.Due?>(null) }
    var menu by remember { mutableStateOf(false) }
    var intake by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val scanner = rememberCodeScanner { code ->
        val v = code.rawValue ?: code.displayValue ?: ""
        val found = Facility.findByTag(Facility.load(ctx), v)
        if (found != null) shell.go("facility/asset/${found.id}") else newAsset = Facility.Asset(name = "", tag = v.trim())
    }
    LaunchedEffect(tag) {
        if (tag.isNotBlank()) {
            val found = Facility.findByTag(Facility.load(ctx), tag)
            if (found != null) shell.go("facility/asset/${found.id}") else newAsset = Facility.Asset(name = "", tag = tag.trim())
        }
    }

    val overdue = due.count { it.status == Facility.Status.OVERDUE }
    val soon = due.count { it.status == Facility.Status.DUE_SOON }
    Scaffold(
        topBar = {
            BlTopBar("Facility", subtitle = if (due.isEmpty()) "Assets, PMs and life-safety inspections" else "$overdue overdue · $soon due soon · ${st.assets.size} assets", onBack = { shell.back() }) {
                IconButton(onClick = { intake = true }) { Icon(Icons.Rounded.Build, "New work order") }
                IconButton(onClick = scanner) { Icon(Icons.Rounded.QrCodeScanner, "Scan an asset tag") }
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Add life-safety & PM program") }, leadingIcon = { Icon(Icons.Rounded.HealthAndSafety, null) }, onClick = { menu = false; catalog = true })
                        DropdownMenuItem(text = { Text("Survey binder (PDF)") }, leadingIcon = { Icon(Icons.Rounded.PictureAsPdf, null) }, onClick = { menu = false; binder = true })
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newAsset = Facility.Asset(name = "") }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Asset") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selected) {
                listOf("Due", "Assets", "Log").forEachIndexed { i, t -> Tab(selected == i, { selected = i }, text = { Text(t) }) }
            }
            when (selected) {
                0 -> DueTab(due, st, onComplete = { complete = it }, onCatalog = { catalog = true })
                1 -> AssetsTab(st, today, query, { query = it })
                else -> LogTab(st)
            }
        }
    }

    newAsset?.let { a -> AssetDialog(a, onDismiss = { newAsset = null }) { saved -> Facility.update(ctx) { Facility.upsertAsset(it, saved) }; newAsset = null; shell.go("facility/asset/${saved.id}") } }
    if (catalog) CatalogDialog(onDismiss = { catalog = false })
    if (binder) BinderDialog(st, onDismiss = { binder = false })
    complete?.let { d -> CompleteDialog(d, onDismiss = { complete = null }) }
    if (intake) IntakeDialog(st, onDismiss = { intake = false })
}

/** "Describe the problem" → a filed work order, with a live preview of what BlackLab understood. */
@Composable
private fun IntakeDialog(st: Facility.State, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    val draft = remember(text, st) { if (text.isBlank()) null else com.blacklab.app.facility.Intake.parse(text, st) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Build, null) },
        title = { Text("New work order") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    label = { Text("What's wrong, and where?") }, placeholder = { Text("Leak under the sink in room 214, B-wing") })
                Text("Tip: tap the keyboard's mic to dictate.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft?.let { d ->
                    Text("${d.priority.name} · ${d.priority.label}", style = MaterialTheme.typography.titleSmall,
                        color = if (d.priority == com.blacklab.app.facility.Intake.Priority.P1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (d.reasons.isNotEmpty()) Text("Because: " + d.reasons.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    Text("Trade: ${d.trade}" + (if (d.location.isNotBlank()) " · ${d.location}" else ""), style = MaterialTheme.typography.bodySmall)
                    Text(d.asset?.let { "Asset: ${it.name}" + (it.tag.takeIf { t -> t.isNotBlank() }?.let { t -> " · $t" } ?: "") } ?: "No registered asset matched",
                        style = MaterialTheme.typography.bodySmall)
                    d.checklist.forEach { Text("☐ $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            Button(enabled = draft != null, onClick = {
                val d = draft ?: return@Button
                scope.launch {
                    val id = com.blacklab.app.facility.FacilityReminders.addIntake(ctx, d)
                    onDismiss()
                    shell.toast("Work order filed · ${d.priority.name}", "Open") { shell.go("task/$id") }
                }
            }) { Text("File it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DueTab(due: List<Facility.Due>, st: Facility.State, onComplete: (Facility.Due) -> Unit, onCatalog: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    if (due.isEmpty()) {
        EmptyState(Icons.Rounded.HealthAndSafety, "No schedules yet",
            "Add your life-safety program (extinguishers, generator, emergency lighting, eyewash…) or an asset with its own PM.") {
            Button(onClick = onCatalog) { Text("Add life-safety & PM program") }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Overdue", due.count { it.status == Facility.Status.OVERDUE }.toString(), Icons.Rounded.Warning, Danger, Modifier.weight(1f))
                StatTile("Due soon", due.count { it.status == Facility.Status.DUE_SOON }.toString(), Icons.Rounded.Schedule, Amber, Modifier.weight(1f))
                StatTile("Done · 30 days", st.log.count { it.kind == Facility.Kind.PM && it.day >= LocalDate.now().toEpochDay() - 30 }.toString(), Icons.Rounded.CheckCircle, Mint, Modifier.weight(1f))
            }
        }
        items(due, key = { it.pm.id }) { d ->
            BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("facility/asset/${d.asset.id}") }, accent = if (d.status == Facility.Status.OK) null else statusColor(d.status)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.pm.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(d.asset.name, d.asset.location).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Pill(d.describe(), statusColor(d.status))
                            if (d.pm.standard.isNotBlank()) Pill(d.pm.standard, Cobalt)
                        }
                    }
                    IconButton(onClick = { scope.launch { FacilityReminders.addTask(ctx, d); shell.toast("Added to To-Do › Inspections") } }) { Icon(Icons.Rounded.AddTask, "Add to To-Do") }
                    IconButton(onClick = { onComplete(d) }) { Icon(Icons.Rounded.CheckCircle, "Log it as done", tint = Mint.readable()) }
                }
            }
        }
        item {
            Text("Intervals are common starting points. Your authority having jurisdiction, accreditor and manufacturers may require more — every schedule is editable.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun AssetsTab(st: Facility.State, today: Long, query: String, onQuery: (String) -> Unit) {
    val shell = LocalShell.current
    val q = query.trim().lowercase()
    val list = st.assets.filter { q.isEmpty() || listOf(it.name, it.tag, it.location, it.kind, it.make, it.model, it.serial).any { f -> f.lowercase().contains(q) } }
        .sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search name, tag, location, model…") })
        }
        if (st.assets.isEmpty()) item { EmptyState(Icons.Rounded.Inventory2, "No assets yet", "Add equipment by hand, or scan its QR / asset tag to start a record.") }
        items(list, key = { it.id }) { a ->
            val worst = a.pms.map { Facility.status(it, today) }.minByOrNull { it.ordinal }
            BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("facility/asset/${a.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOf(a.kind, a.location, a.tag.ifBlank { null }?.let { "Tag $it" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (worst != null && worst != Facility.Status.OK) Pill(if (worst == Facility.Status.OVERDUE) "Overdue" else "Due soon", statusColor(worst))
                }
            }
        }
    }
}

@Composable
private fun LogTab(st: Facility.State) {
    val shell = LocalShell.current
    val names = remember(st) { st.assets.associate { it.id to it.name } }
    val pms = remember(st) { st.assets.flatMap { a -> a.pms.map { it.id to it.title } }.toMap() }
    if (st.log.isEmpty()) { EmptyState(Icons.Rounded.History, "Nothing logged yet", "Inspections, work orders and findings show up here."); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(st.log.take(300), key = { it.id }) { e -> EntryCard(e, names[e.assetId] ?: "(deleted asset)", e.pmId?.let { pms[it] }) { if (e.assetId in names) shell.go("facility/asset/${e.assetId}") } }
    }
}

@Composable
private fun EntryCard(e: Facility.Entry, asset: String?, pm: String?, onClick: (() -> Unit)? = null) {
    BlCard(Modifier.fillMaxWidth(), onClick = onClick, accent = if (e.result == Facility.Result.FAIL) Danger else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pm ?: e.kind.label, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(day(e.day), asset, e.by.ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.text.isNotBlank()) Text(e.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (e.result != Facility.Result.NONE) Pill(e.result.label, when (e.result) { Facility.Result.FAIL -> Danger; Facility.Result.PASS -> Mint; else -> Cobalt })
        }
    }
}

/** One asset: details, its schedules and its history. */
@Composable
fun FacilityAssetScreen(id: String) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    remember { Facility.load(ctx) }
    val st by Facility.state.collectAsStateWithLifecycle()
    val a = st.assets.firstOrNull { it.id == id }
    if (a == null) { Scaffold(topBar = { BlTopBar("Asset", onBack = { shell.back() }) }) { p -> EmptyState(Icons.Rounded.Inventory2, "Asset not found", "It may have been deleted.", Modifier.padding(p)) }; return }
    val today = LocalDate.now().toEpochDay()
    var edit by remember { mutableStateOf(false) }
    var pmEdit by remember { mutableStateOf<Facility.Pm?>(null) }
    var complete by remember { mutableStateOf<Facility.Due?>(null) }
    var note by remember { mutableStateOf<Facility.Kind?>(null) }
    var delete by remember { mutableStateOf(false) }
    val history = remember(st, id) { Facility.history(st, id) }
    val pmTitles = a.pms.associate { it.id to it.title }

    Scaffold(topBar = {
        BlTopBar(a.name.ifBlank { "Asset" }, subtitle = listOf(a.kind, a.location).filter { it.isNotBlank() }.joinToString(" · "), onBack = { shell.back() }) {
            IconButton(onClick = { edit = true }) { Icon(Icons.Rounded.Edit, "Edit asset") }
            IconButton(onClick = { delete = true }) { Icon(Icons.Rounded.Delete, "Delete asset") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    listOf("Tag" to a.tag, "Make" to a.make, "Model" to a.model, "Serial" to a.serial, "Installed" to (a.installedDay?.let(::day) ?: ""), "Notes" to a.notes)
                        .filter { it.second.isNotBlank() }.ifEmpty { listOf("Details" to "Tap edit to add tag, make, model and serial") }
                        .forEach { (k, v) -> Row { Text("$k  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { note = Facility.Kind.WORK_ORDER }, Modifier.weight(1f)) { Icon(Icons.Rounded.Build, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Work order") }
                    OutlinedButton(onClick = { note = Facility.Kind.FINDING }, Modifier.weight(1f)) { Icon(Icons.Rounded.Warning, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Finding") }
                    OutlinedButton(onClick = { note = Facility.Kind.NOTE }, Modifier.weight(1f)) { Text("Note") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Schedules", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pmEdit = Facility.Pm(title = "", everyDays = 30) }) { Icon(Icons.Rounded.Add, null); Text("Add") }
                }
            }
            if (a.pms.isEmpty()) item { Text("No inspections or PMs yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(a.pms, key = { it.id }) { p ->
                val s = Facility.status(p, today)
                val d = Facility.Due(a, p, p.dueDay, p.dueDay - today, s)
                BlCard(Modifier.fillMaxWidth(), onClick = { pmEdit = p }, accent = if (s == Facility.Status.OK) null else statusColor(s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(every(p.everyDays), p.standard.ifBlank { null }, "last ${p.lastDay?.let(::day) ?: "never"}").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Pill("${d.describe()} · ${day(p.dueDay)}", statusColor(s), Modifier.padding(top = 4.dp))
                        }
                        IconButton(onClick = { scope.launch { FacilityReminders.addTask(ctx, d); shell.toast("Added to To-Do › Inspections") } }) { Icon(Icons.Rounded.AddTask, "Add to To-Do") }
                        IconButton(onClick = { complete = d }) { Icon(Icons.Rounded.CheckCircle, "Log it as done", tint = Mint.readable()) }
                    }
                }
            }
            item { Text("History", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            if (history.isEmpty()) item { Text("Nothing logged yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(history.take(200), key = { it.id }) { e -> EntryCard(e, null, e.pmId?.let { pmTitles[it] }) }
        }
    }

    if (edit) AssetDialog(a, onDismiss = { edit = false }) { saved -> Facility.update(ctx) { Facility.upsertAsset(it, saved) }; edit = false }
    pmEdit?.let { p ->
        PmDialog(p, isNew = a.pms.none { it.id == p.id }, onDismiss = { pmEdit = null },
            onDelete = { Facility.update(ctx) { s -> Facility.upsertAsset(s, a.copy(pms = a.pms.filterNot { it.id == p.id })) }; pmEdit = null }) { saved ->
            Facility.update(ctx) { s -> Facility.upsertAsset(s, a.copy(pms = if (a.pms.any { it.id == saved.id }) a.pms.map { if (it.id == saved.id) saved else it } else a.pms + saved)) }
            pmEdit = null
        }
    }
    complete?.let { d -> CompleteDialog(d, onDismiss = { complete = null }) }
    note?.let { k -> NoteDialog(a, k, onDismiss = { note = null }) }
    if (delete) ConfirmDialog("Delete ${a.name}?", "Removes the asset, its schedules and its history. This can't be undone (except from a backup).", onDismiss = { delete = false }) {
        Facility.update(ctx) { Facility.deleteAsset(it, a.id) }; shell.back()
    }
}

@Composable
private fun AssetDialog(a: Facility.Asset, onDismiss: () -> Unit, onSave: (Facility.Asset) -> Unit) {
    var name by remember { mutableStateOf(a.name) }
    var kind by remember { mutableStateOf(a.kind) }
    var tag by remember { mutableStateOf(a.tag) }
    var location by remember { mutableStateOf(a.location) }
    var make by remember { mutableStateOf(a.make) }
    var model by remember { mutableStateOf(a.model) }
    var serial by remember { mutableStateOf(a.serial) }
    var notes by remember { mutableStateOf(a.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (a.name.isBlank()) "New asset" else "Edit asset") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. RTU-4, Generator 1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                DropdownField("Type", Facility.KINDS.map { it to it }, kind, { kind = it }, Modifier.fillMaxWidth())
                OutlinedTextField(location, { location = it }, label = { Text("Location (building, floor, room)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(tag, { tag = it }, label = { Text("QR / asset tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(make, { make = it }, label = { Text("Make") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(serial, { serial = it }, label = { Text("Serial") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(a.copy(name = name.trim(), kind = kind, tag = tag.trim(), location = location.trim(), make = make.trim(), model = model.trim(), serial = serial.trim(), notes = notes.trim()))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PmDialog(p: Facility.Pm, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Facility.Pm) -> Unit) {
    var title by remember { mutableStateOf(p.title) }
    var days by remember { mutableStateOf(p.everyDays.toString()) }
    var standard by remember { mutableStateOf(p.standard) }
    var checklist by remember { mutableStateOf(p.checklist.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New inspection / PM" else "Edit schedule") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("What (e.g. Monthly inspection)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(7 to "Week", 30 to "Month", 91 to "Quarter", 365 to "Year").forEach { (n, l) -> FilterChip(days == n.toString(), { days = n.toString() }, label = { Text(l) }) }
                }
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(4) }, label = { Text("Every … days") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(standard, { standard = it }, label = { Text("Standard / code (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(checklist, { checklist = it }, label = { Text("Checklist — one item per line") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete this schedule", color = Danger.readable()) }
            }
        },
        confirmButton = {
            val n = days.toIntOrNull()
            TextButton(enabled = title.isNotBlank() && n != null && n in 1..3650, onClick = {
                onSave(p.copy(title = title.trim(), everyDays = n!!, standard = standard.trim(), checklist = checklist.lines().map { it.trim() }.filter { it.isNotEmpty() }))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Logs a completed inspection or PM; a failure can become a work order. */
@Composable
private fun CompleteDialog(d: Facility.Due, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    val checks = remember { mutableStateListOf<Boolean>().apply { addAll(d.pm.checklist.map { false }) } }
    var result by remember { mutableStateOf(Facility.Result.PASS) }
    var notes by remember { mutableStateOf("") }
    var by by remember { mutableStateOf(s.userName) }
    var wo by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf(LocalDate.now().toEpochDay()) }
    var pickDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.pm.title) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(listOf(d.asset.name, d.asset.location, d.pm.standard).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.pm.checklist.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checks[i], { checks[i] = it }); Text(item, style = MaterialTheme.typography.bodyMedium) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Facility.Result.PASS, Facility.Result.FAIL, Facility.Result.NA).forEach { r -> FilterChip(result == r, { result = r }, label = { Text(r.label) }) }
                }
                OutlinedTextField(notes, { notes = it }, label = { Text(if (result == Facility.Result.FAIL) "What failed?" else "Notes / readings") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(by, { by = it }, label = { Text("Done by") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { pickDate = true }) { Text("Date: ${day(picked)}") }
                if (result == Facility.Result.FAIL) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wo, { wo = it }); Text("Create a work order in To-Do", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = result != Facility.Result.FAIL || notes.isNotBlank(), onClick = {
                val missed = d.pm.checklist.filterIndexed { i, _ -> !checks[i] }
                val text = notes.trim() + if (missed.isNotEmpty() && missed.size < d.pm.checklist.size) "\nNot checked: " + missed.joinToString("; ") else ""
                scope.launch {
                    val task = if (result == Facility.Result.FAIL && wo) FacilityReminders.addWorkOrder(ctx, d.asset, "${d.pm.title} failed: ${notes.trim()}") else null
                    Facility.update(ctx) { Facility.complete(it, d.asset.id, d.pm.id, picked, result, text.trim(), by, task) }
                    shell.toast(if (task != null) "Logged · work order created" else "Logged · next due ${day(picked + d.pm.everyDays)}")
                }
                onDismiss()
            }) { Text("Log it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickDate) com.blacklab.app.ui.components.DatePickerDialogBox(LocalDate.ofEpochDay(picked), { pickDate = false }) { it?.let { d2 -> picked = d2.toEpochDay().coerceAtMost(LocalDate.now().toEpochDay()) } }
}

@Composable
private fun NoteDialog(a: Facility.Asset, kind: Facility.Kind, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val s = settingsState()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var task by remember { mutableStateOf(kind != Facility.Kind.NOTE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kind.label}: ${a.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(if (kind == Facility.Kind.WORK_ORDER) "What needs doing?" else "What did you find?") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                if (kind != Facility.Kind.NOTE) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(task, { task = it }); Text("Add to To-Do › Work Orders") }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = {
                scope.launch {
                    val id = if (task) FacilityReminders.addWorkOrder(ctx, a, text.trim()) else null
                    Facility.update(ctx) { Facility.addEntry(it, Facility.Entry(assetId = a.id, day = LocalDate.now().toEpochDay(), kind = kind,
                        result = if (kind == Facility.Kind.FINDING) Facility.Result.FAIL else Facility.Result.NONE, text = text.trim(), by = s.userName, taskId = id)) }
                    shell.toast(if (id != null) "Logged · added to Work Orders" else "Logged")
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CatalogDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val chosen = remember { mutableStateListOf<String>() }
    var location by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Life-safety & PM program") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                Text("Pick the inspections you're responsible for. Each one becomes a schedule you can edit; confirm intervals with your AHJ, accreditor and manufacturers.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(location, { location = it }, label = { Text("Building or area (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Facility.STANDARDS.forEach { st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(st.id in chosen, { if (it) chosen += st.id else chosen -= st.id })
                        Column {
                            Text("${st.asset}: ${st.title}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("${every(st.everyDays)} · ${st.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                Facility.update(ctx) { s -> Facility.STANDARDS.filter { it.id in chosen }.fold(s) { acc, st -> Facility.addStandard(acc, st, location) } }
                shell.toast("Added ${chosen.size} schedule${if (chosen.size == 1) "" else "s"} — all due today until you log them")
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Exports the survey binder: schedules, the log for a period, deficiencies and the asset register, as a landscape PDF. */
@Composable
private fun BinderDialog(st: Facility.State, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val app = rememberApp()
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(365) }
    var title by remember { mutableStateOf(st.site.ifBlank { "Facility inspection & maintenance binder" }) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Survey binder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A PDF with every schedule and its status, the inspection and work log for the period, deficiencies and the asset register.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it }, label = { Text("Title (facility name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30 to "30 days", 90 to "90 days", 365 to "12 months", 1095 to "3 years").forEach { (n, l) -> FilterChip(range == n, { range = n }, label = { Text(l) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = runCatching {
                        withContext(Dispatchers.IO) {
                            Facility.update(ctx) { it.copy(site = title.trim()) }
                            val today = LocalDate.now().toEpochDay()
                            val html = Facility.binderHtml(Facility.load(ctx), today - range, today, title.trim().ifBlank { "Facility binder" }, today)
                            val dir = app.storage.dir(Profile.PROFESSIONAL, Folder.EXPORTS)
                            val out = Files.unique(dir, "Facility binder ${BlackLabStorage.timestamp()}", "pdf")
                            PdfWriter.write(HtmlToBlocks.parse(html), out, title.trim(), PageSetup(landscape = true, marginMm = 15))
                            out
                        }
                    }
                    busy = false
                    r.onSuccess { f -> onDismiss(); shell.toast("Saved to Professional › Exports", "Share") { Files.share(ctx, listOf(f), f.nameWithoutExtension) } }
                        .onFailure { shell.toast("Couldn't make the binder: ${it.message}") }
                }
            }) { Text(if (busy) "Making…" else "Make PDF") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
