package com.blacklab.app.ui.bh

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Library
import com.blacklab.app.bh.Writer
import com.blacklab.app.core.Profile
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Success

val EOC_KINDS = listOf("Ligature risk & environmental safety", "Environment of care (EOC) round", "Life safety / fire", "Infection prevention walk-through",
    "Post-incident environment review", "Contraband / room search sweep", "New construction / renovation turnover", "Survey readiness (mock tracer)")

@Composable
fun BhEocScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    var start by remember { mutableStateOf(false) }
    val recent = s.eoc.filter { it.time >= System.currentTimeMillis() - 90 * 86_400_000L }
    // Recurring issues across rounds in the last 90 days — where to put capital and PM effort
    val recurring = remember(s.eoc) {
        recent.flatMap { e -> e.areas.flatMap { a -> a.results.filter { it.value == "ISSUE" }.map { a.area to it.key } } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
            .map { (k, n) -> Triple(k.first, Library.EOC_ITEMS[k.first]?.firstOrNull { it.id == k.second }?.text ?: k.second, n) }
    }
    Scaffold(topBar = {
        BlTopBar("Safety Rounds", subtitle = "Ligature risk & environment of care", onBack = { shell.back() }) {
            IconButton(onClick = { start = true }) { Icon(Icons.Rounded.Add, "New round") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Rounds · 90 d", recent.size.toString(), Icons.Rounded.HealthAndSafety, Coral, Modifier.weight(1f))
                    StatTile("Issues · 90 d", recent.sumOf { e -> e.areas.sumOf { it.issueCount } }.toString(), Icons.Rounded.ReportProblem, Amber, Modifier.weight(1f))
                    StatTile("High risk open", s.eoc.filter { !it.closed }.sumOf { e -> e.areas.count { it.risk.startsWith("High") || it.risk.startsWith("Immediate") } }.toString(),
                        Icons.Rounded.Warning, Danger, Modifier.weight(1f))
                }
            }
            item { Button(onClick = { start = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Start a safety round") } }
            if (recurring.isNotEmpty()) {
                item { SectionHeader("Recurring findings · 90 days") }
                items(recurring) { (area, text, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(area, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(8.dp)); Pill("×$n", if (n >= 3) Danger else Amber)
                    }
                }
            }
            item { SectionHeader("Rounds") }
            if (s.eoc.isEmpty()) item {
                EmptyState(Icons.Rounded.HealthAndSafety, "No safety rounds yet", "Walk a unit area by area: door hardware, fixtures, ceilings, furniture, electrical and contraband — every finding can become a work order.")
            }
            items(s.eoc, key = { it.id }) { e ->
                val issues = e.areas.sumOf { it.issueCount }
                BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("bh/eoc/${e.id}") }, accent = if (!e.closed) Coral else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.kind, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(e.unit.ifBlank { null }, Writer.when_(e.time), "${e.areas.size} areas", "$issues issues").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(if (e.closed) "Closed" else "Open", if (e.closed) Success else Coral)
                    }
                }
            }
            item {
                Text("A field aid for your environmental risk assessment and EOC program — record findings in your facility's official system and follow your organization's risk-assessment and mitigation process.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (start) NewRoundDialog(s, onDismiss = { start = false }) { e -> BhStore.upsertEoc(ctx, e); shell.go("bh/eoc/${e.id}") }
}

@Composable
private fun NewRoundDialog(s: Bh.State, onDismiss: () -> Unit, onStart: (Bh.EocRound) -> Unit) {
    var kind by remember { mutableStateOf(EOC_KINDS.first()) }
    var unit by remember { mutableStateOf(s.prefs.unit) }
    var who by remember { mutableStateOf(s.prefs.signature) }
    var areas by remember { mutableStateOf(listOf("Patient room", "Bathroom", "Day room / common area", "Hallway / corridor")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("New safety round") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Round type", EOC_KINDS.map { it to it }, kind, { kind = it })
            DropdownField("Unit / area", listOf("" to "—") + s.prefs.units.map { it to it } + listOf("Whole facility" to "Whole facility", "Grounds" to "Grounds"), unit, { unit = it })
            OutlinedTextField(who, { who = it }, Modifier.fillMaxWidth(), label = { Text("Inspector") }, singleLine = true)
            Text("Start with these areas", style = MaterialTheme.typography.labelLarge)
            ChipSelect(Library.EOC_AREAS, areas, { areas = it }, collapseAfter = 12)
        }
    }, confirmButton = {
        TextButton(onClick = { onStart(Bh.EocRound(unit = unit, inspector = who, kind = kind, areas = areas.map { Bh.AreaCheck(area = it) })); onDismiss() }) { Text("Start") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
fun BhEocRoundScreen(id: String) = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val app = rememberApp()
    val s = bhState()
    val e = s.eoc.firstOrNull { it.id == id } ?: run { Box(Modifier.fillMaxSize()); return@BhGuarded }
    var report by remember { mutableStateOf(false) }
    var addArea by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    var notes by remember(e.id) { mutableStateOf(e.notes) }
    val save: (Bh.EocRound) -> Unit = { BhStore.upsertEoc(ctx, it) }
    val setArea: (Bh.AreaCheck) -> Unit = { a -> save(e.copy(areas = e.areas.map { if (it.id == a.id) a else it })) }

    Scaffold(topBar = {
        BlTopBar(e.kind, subtitle = listOfNotNull(e.unit.ifBlank { null }, Writer.when_(e.time)).joinToString(" · "), onBack = { if (notes != e.notes) save(e.copy(notes = notes)); shell.back() }) {
            IconButton(onClick = { save(e.copy(notes = notes)); report = true }) { Icon(Icons.Rounded.Description, "Report") }
            IconButton(onClick = { del = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val total = e.areas.sumOf { Library.EOC_ITEMS[it.area]?.size ?: 0 }
                val checked = e.areas.sumOf { it.results.size }
                Text("$checked of $total items checked · ${e.areas.sumOf { it.issueCount }} issues", style = MaterialTheme.typography.labelLarge)
            }
            items(e.areas, key = { it.id }) { a -> AreaCard(a, setArea, onRemove = { save(e.copy(areas = e.areas.filterNot { it.id == a.id })) },
                onWorkOrder = { item, text ->
                    app.io {
                        val pr = when { a.risk.startsWith("Immediate") -> 4; a.risk.startsWith("High") -> 3; a.risk.startsWith("Medium") -> 2; else -> 1 }
                        val taskId = TaskOps.save(app, TaskEntity(profile = Profile.PROFESSIONAL.name, title = "Work order: ${item.text} — ${a.area}${a.label.ifBlank { null }?.let { " $it" } ?: ""}",
                            notes = listOfNotNull("Found on ${e.kind.lowercase()} ${Writer.when_(e.time)}", e.unit.ifBlank { null }?.let { "Unit: $it" }, text.ifBlank { null }?.let { "Finding: $it" },
                                a.risk.ifBlank { null }?.let { "Risk: $it" }).joinToString("\n"), priority = pr, important = pr >= 3, tags = "workorder,eoc,ligature",
                            dueDay = java.time.LocalDate.now().plusDays(if (pr >= 3) 0 else 7).toEpochDay()))
                        BhStore.update(ctx) { st -> st.copy(eoc = st.eoc.map { r -> if (r.id != e.id) r else r.copy(areas = r.areas.map { x -> if (x.id == a.id) x.copy(taskId = taskId, mitigation = x.mitigation.ifBlank { "Work order entered" }) else x }) }) }
                    }
                    shell.toast("Work order added to To-Do (Professional)", "Open") { shell.go("tasks") }
                }) }
            item { OutlinedButton(onClick = { addArea = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.size(6.dp)); Text("Add an area") } }
            item { OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Round notes") }, minLines = 2) }
            item {
                LabeledSwitchRow("Round complete", "Close it when findings are mitigated or handed off", e.closed) { save(e.copy(closed = it, notes = notes)) }
            }
        }
    }
    if (report) NoteOutputDialog("Safety round report", Writer.eocReport(e), onDismiss = { report = false })
    if (addArea) AddAreaDialog(onDismiss = { addArea = false }) { area, label -> save(e.copy(areas = e.areas + Bh.AreaCheck(area = area, label = label))) }
    if (del) ConfirmDialog("Delete this round?", "${e.kind} · ${Writer.when_(e.time)}", onDismiss = { del = false }) { BhStore.deleteEoc(ctx, e.id); shell.back() }
}

@Composable
private fun AreaCard(a: Bh.AreaCheck, onChange: (Bh.AreaCheck) -> Unit, onRemove: () -> Unit, onWorkOrder: (Library.EocItem, String) -> Unit) {
    val items = Library.EOC_ITEMS[a.area].orEmpty()
    var open by remember { mutableStateOf(a.results.size < items.size) }
    var label by remember(a.id) { mutableStateOf(a.label) }
    BlCard(Modifier.fillMaxWidth(), accent = if (a.issueCount > 0) (if (a.risk.startsWith("High") || a.risk.startsWith("Immediate")) Danger else Amber) else if (a.results.size == items.size) Success else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.area + (a.label.ifBlank { null }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.titleSmall)
                Text("${a.results.size}/${items.size} checked · ${a.issueCount} issue${if (a.issueCount == 1) "" else "s"}" + (a.taskId?.let { " · work order entered" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { open = !open }) { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand") }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Room / location label") }, singleLine = true,
                    trailingIcon = { if (label != a.label) IconButton(onClick = { onChange(a.copy(label = label.trim())) }) { Icon(Icons.Rounded.Check, "Save") } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onChange(a.copy(results = items.associate { it.id to (a.results[it.id] ?: "OK") })) }, Modifier.weight(1f)) { Text("Rest OK") }
                    OutlinedButton(onClick = onRemove, Modifier.weight(1f)) { Text("Remove area") }
                }
                items.groupBy { it.group }.forEach { (group, list) ->
                    Text(group.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    list.forEach { item -> ItemRow(item, a, onChange, onWorkOrder) }
                }
                if (a.issueCount > 0) {
                    OptionalDropdown("Risk level", Library.RISK_LEVELS, a.risk, { onChange(a.copy(risk = it)) })
                    OptionalDropdown("Mitigation", Library.MITIGATIONS, a.mitigation, { onChange(a.copy(mitigation = it)) })
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Library.EocItem, a: Bh.AreaCheck, onChange: (Bh.AreaCheck) -> Unit, onWorkOrder: (Library.EocItem, String) -> Unit) {
    val r = a.results[item.id]
    var issue by remember(a.id, item.id) { mutableStateOf(a.issues[item.id].orEmpty()) }
    Column {
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
        ChipFlow {
            listOf("OK" to Success, "ISSUE" to Danger, "NA" to Cobalt).forEach { (v, c) ->
                FilterChip(r == v, onClick = { onChange(a.copy(results = if (r == v) a.results - item.id else a.results + (item.id to v))) },
                    label = { Text(when (v) { "OK" -> "OK"; "ISSUE" -> "Issue"; else -> "N/A" }) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.copy(alpha = 0.22f)))
            }
        }
        if (r == "ISSUE") {
            OutlinedTextField(issue, { issue = it }, Modifier.fillMaxWidth(), placeholder = { Text("What's wrong?") }, singleLine = true,
                trailingIcon = { if (issue != a.issues[item.id].orEmpty()) IconButton(onClick = { onChange(a.copy(issues = a.issues + (item.id to issue.trim()))) }) { Icon(Icons.Rounded.Check, "Save") } })
            TextButton(onClick = { onChange(a.copy(issues = a.issues + (item.id to issue.trim()))); onWorkOrder(item, issue) }) {
                Icon(Icons.Rounded.Build, null, Modifier.size(16.dp)); Spacer(Modifier.size(6.dp)); Text("Create work order")
            }
        }
    }
}

@Composable
private fun AddAreaDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var area by remember { mutableStateOf(Library.EOC_AREAS.first()) }
    var label by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add an area") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Area type", Library.EOC_AREAS.map { it to it }, area, { area = it })
            OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), label = { Text("Label (e.g. Room 214)") }, singleLine = true)
        }
    }, confirmButton = { TextButton(onClick = { onAdd(area, label.trim()); onDismiss() }) { Text("Add") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

