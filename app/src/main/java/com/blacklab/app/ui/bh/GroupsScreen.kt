package com.blacklab.app.ui.bh

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Library
import com.blacklab.app.bh.Specs
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Violet

@Composable
fun BhGroupsScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var category by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var open by remember { mutableStateOf<Bh.GroupPlan?>(null) }
    var edit by remember { mutableStateOf<Bh.GroupPlan?>(null) }
    val plans = (s.plans + Library.GROUPS).filter { (category.isBlank() || it.category == category) &&
        (query.isBlank() || listOf(it.title, it.objective, it.activity, it.category).any { f -> f.contains(query, true) }) }
    val notes = s.records(Specs.GROUP_NOTE)
    val month = notes.filter { it.created >= System.currentTimeMillis() - 30 * 86_400_000L }

    Scaffold(topBar = {
        BlTopBar("Group Facilitation", subtitle = "${Library.GROUPS.size} built-in · ${s.plans.size} mine · ${month.size} groups in 30 days", onBack = { shell.back() }) {
            IconButton(onClick = { edit = blankPlan() }) { Icon(Icons.Rounded.Add, "New plan") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Plans") }); Tab(tab == 1, { tab = 1 }, text = { Text("Sessions") })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (tab == 0) {
                    item {
                        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search plans") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            singleLine = true, shape = RoundedCornerShape(28.dp))
                    }
                    item { OptionalDropdown("Category", Library.GROUP_CATEGORIES + s.plans.map { it.category }.distinct(), category, { category = it }) }
                    items(plans, key = { it.id }) { g ->
                        BlCard(Modifier.fillMaxWidth(), onClick = { open = g }, accent = if (!g.builtin) Violet else null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(g.title, style = MaterialTheme.typography.titleSmall)
                                    Text(g.objective, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                    Text("${g.category} · ${g.minutes} min" + (if (g.audience != "Any") " · ${g.audience}" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!g.builtin) Pill("Mine", Violet)
                            }
                        }
                    }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile("Groups · 30 d", month.size.toString(), Icons.Rounded.Groups, Amber, Modifier.weight(1f))
                            StatTile("Attendance", month.sumOf { it.int("attended") ?: 0 }.toString(), Icons.Rounded.People, Mint, Modifier.weight(1f))
                            StatTile("Minutes", month.sumOf { it.int("minutes") ?: 0 }.toString(), Icons.Rounded.Schedule, Cobalt, Modifier.weight(1f))
                        }
                    }
                    item { Button(onClick = { shell.go("bh/form/${Specs.GROUP_NOTE}") }, Modifier.fillMaxWidth()) { Text("New group note") } }
                    items(notes, key = { it.id }) { r -> RecordRow(r, s) { shell.go("bh/form/${Specs.GROUP_NOTE}?id=${r.id}") } }
                }
            }
        }
    }
    open?.let { g -> PlanDialog(g, onDismiss = { open = null }, onRun = { open = null; shell.go("bh/form/${Specs.GROUP_NOTE}?ref=${g.id}") },
        onEdit = { open = null; edit = if (g.builtin) g.copy(id = Bh.id(), title = g.title + " (my version)", builtin = false) else g },
        onDelete = { BhStore.deletePlan(ctx, g.id); open = null }) }
    edit?.let { g -> PlanEditor(g, onDismiss = { edit = null }) { BhStore.upsertPlan(ctx, it); shell.toast("Plan saved") } }
}

private fun blankPlan() = Bh.GroupPlan(title = "", category = Library.GROUP_CATEGORIES.first(), objective = "", opener = "", activity = "", discussion = emptyList(), closing = "")

private fun planText(g: Bh.GroupPlan) = buildString {
    appendLine("GROUP PLAN — ${g.title}")
    appendLine("${g.category} · ${g.minutes} minutes" + (if (g.audience != "Any") " · ${g.audience}" else ""))
    appendLine(); appendLine("Objective: ${g.objective}")
    appendLine(); appendLine("Opening (5 min): ${g.opener}")
    appendLine(); appendLine("Activity: ${g.activity}")
    if (g.discussion.isNotEmpty()) { appendLine(); appendLine("Discussion:"); g.discussion.forEach { appendLine("• $it") } }
    appendLine(); appendLine("Closing: ${g.closing}")
    if (g.materials.isNotBlank()) { appendLine(); appendLine("Materials: ${g.materials}") }
    if (g.adaptations.isNotBlank()) { appendLine(); appendLine("Adaptations: ${g.adaptations}") }
}.trim()

@Composable
private fun PlanDialog(g: Bh.GroupPlan, onDismiss: () -> Unit, onRun: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var share by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val started = remember { mutableStateOf<Long?>(null) }
    val now = rememberTicker(1_000)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(g.title) }, text = {
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill(g.category, Amber); Pill("${g.minutes} min", Cobalt) }
            started.value?.let { t ->
                val m = (now - t) / 60_000; val left = g.minutes - m
                Text("Running · $m min in · ${if (left >= 0) "$left min left" else "${-left} min over"}", fontWeight = FontWeight.SemiBold, color = if (left < 5) Amber else Mint)
            }
            block("Objective", g.objective); block("Opening", g.opener); block("Activity", g.activity)
            if (g.discussion.isNotEmpty()) { Text("Discussion", style = MaterialTheme.typography.labelLarge); g.discussion.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) } }
            block("Closing", g.closing); block("Materials", g.materials); block("Adaptations", g.adaptations)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { started.value = if (started.value == null) System.currentTimeMillis() else null }, Modifier.weight(1f)) { Text(if (started.value == null) "Start timer" else "Stop timer") }
                OutlinedButton(onClick = { share = true }, Modifier.weight(1f)) { Text("Copy plan") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, Modifier.weight(1f)) { Text(if (g.builtin) "Make my version" else "Edit") }
                if (!g.builtin) OutlinedButton(onClick = { del = true }, Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }, confirmButton = { Button(onClick = onRun) { Icon(Icons.Rounded.EditNote, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Write group note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (share) NoteOutputDialog("Group plan", planText(g), onDismiss = { share = false })
    if (del) ConfirmDialog("Delete this plan?", g.title, onDismiss = { del = false }) { onDelete() }
}

@Composable
private fun block(label: String, text: String) {
    if (text.isBlank()) return
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PlanEditor(g: Bh.GroupPlan, onDismiss: () -> Unit, onSave: (Bh.GroupPlan) -> Unit) {
    var x by remember { mutableStateOf(g) }
    var q by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (g.title.isBlank()) "New group plan" else "Edit plan") }, text = {
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x.title, { x = x.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            DropdownField("Category", Library.GROUP_CATEGORIES.map { it to it }, x.category, { x = x.copy(category = it) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Length", listOf(20, 30, 40, 45, 50, 60, 75, 90).map { it to "$it min" }, x.minutes, { x = x.copy(minutes = it) }, Modifier.weight(1f))
                DropdownField("Audience", listOf("Any", "Adults", "Adolescents", "Older adults", "Substance use", "Co-occurring", "Women", "Men", "Veterans", "Family").map { it to it },
                    x.audience, { x = x.copy(audience = it) }, Modifier.weight(1f))
            }
            OutlinedTextField(x.objective, { x = x.copy(objective = it) }, Modifier.fillMaxWidth(), label = { Text("Objective") })
            OutlinedTextField(x.opener, { x = x.copy(opener = it) }, Modifier.fillMaxWidth(), label = { Text("Opening / check-in") })
            OutlinedTextField(x.activity, { x = x.copy(activity = it) }, Modifier.fillMaxWidth(), label = { Text("Activity") }, minLines = 3)
            Text("Discussion questions", style = MaterialTheme.typography.labelLarge)
            x.discussion.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $d", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { x = x.copy(discussion = x.discussion - d) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Add a question") }, singleLine = true)
                IconButton(enabled = q.isNotBlank(), onClick = { x = x.copy(discussion = x.discussion + q.trim()); q = "" }) { Icon(Icons.Rounded.Add, "Add") }
            }
            OutlinedTextField(x.closing, { x = x.copy(closing = it) }, Modifier.fillMaxWidth(), label = { Text("Closing") })
            OutlinedTextField(x.materials, { x = x.copy(materials = it) }, Modifier.fillMaxWidth(), label = { Text("Materials") })
            OutlinedTextField(x.adaptations, { x = x.copy(adaptations = it) }, Modifier.fillMaxWidth(), label = { Text("Adaptations / safety notes") })
        }
    }, confirmButton = {
        TextButton(enabled = x.title.isNotBlank(), onClick = { onSave(x.copy(builtin = false, discussion = x.discussion + listOfNotNull(q.takeIf { it.isNotBlank() }?.trim()))); onDismiss() }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
