package com.blacklab.app.ui.bh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Specs
import com.blacklab.app.bh.Writer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.ui.theme.Violet

val SETTINGS = listOf("Inpatient", "Residential", "Crisis unit", "PHP / IOP", "Outpatient", "Community", "Recovery center", "Post-discharge", "Justice-involved", "Shelter")
val PATHWAYS = listOf("12-step (AA / NA)", "SMART Recovery", "Medication for addiction treatment (MAT / MOUD)", "Faith-based", "Harm reduction", "Clinical treatment / therapy",
    "Recovery Dharma", "Natural / self-directed recovery", "Peer recovery community", "Family support", "Mental health recovery (WRAP)", "Cultural / traditional healing", "Wellness / fitness")
val GOAL_STATUS = listOf("Active", "Achieved", "Paused", "Revised", "Discontinued")
val CAPITAL = listOf("Housing", "Physical health", "Mental health", "Recovery support", "Family & relationships", "Friends & community", "Work, school or purpose",
    "Money & benefits", "Transportation", "Legal", "Spirituality & meaning", "Coping skills", "Hope & self-esteem", "Free time")

@Composable
fun BhPeerScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    var showAll by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    val list = s.participants.filter { showAll || it.active }.sortedBy { it.alias.lowercase() }
    val notes = s.records(Specs.PEER_NOTE)
    val month = notes.filter { it.created >= System.currentTimeMillis() - 30 * 86_400_000L }

    Scaffold(topBar = {
        BlTopBar("Peer Support", subtitle = "${s.participants.count { it.active }} active · ${month.size} contacts in 30 days", onBack = { shell.back() }) {
            IconButton(onClick = { add = true }) { Icon(Icons.Rounded.PersonAdd, "Add participant") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Contacts · 30 d", month.size.toString(), Icons.Rounded.Forum, Violet, Modifier.weight(1f)) { shell.go("bh/records/${Specs.PEER_NOTE}") }
                    StatTile("Minutes · 30 d", month.sumOf { it.int("minutes") ?: 0 }.toString(), Icons.Rounded.Schedule, Cobalt, Modifier.weight(1f))
                    StatTile("Goals met", s.participants.sumOf { p -> p.goals.count { it.status == "Achieved" } }.toString(), Icons.Rounded.EmojiEvents, Amber, Modifier.weight(1f))
                }
            }
            item {
                Button(onClick = { shell.go("bh/form/${Specs.PEER_NOTE}") }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.EditNote, null); Spacer(Modifier.size(6.dp)); Text("New session note") }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shell.go("bh/records/${Specs.PEER_NOTE}") }, Modifier.weight(1f)) { Text("Notes") }
                    OutlinedButton(onClick = { shell.go("bh/records/${Specs.SAFETY_PLAN}") }, Modifier.weight(1f)) { Text("Wellness plans") }
                    OutlinedButton(onClick = { shell.go("bh/records/${Specs.REFERRAL}") }, Modifier.weight(1f)) { Text("Referrals") }
                }
            }
            item {
                SectionHeader("Participants", action = if (showAll) "Active only" else "Show all") { showAll = !showAll }
            }
            if (list.isEmpty()) item {
                EmptyState(Icons.Rounded.Diversity3, "No participants yet", "Add the people you support by an alias you'll recognize — never a full name. Track recovery goals, capital and every contact.") {
                    Button(onClick = { add = true }) { Text("Add participant") }
                }
            }
            items(list, key = { it.id }) { p ->
                val last = notes.firstOrNull { it.ref == p.id }
                val active = p.goals.filter { it.status == "Active" }
                BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("bh/participant/${p.id}") }, accent = if (p.active) Violet else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.alias, style = MaterialTheme.typography.titleMedium)
                            Text(listOfNotNull(p.setting, p.stage.ifBlank { null }, "${active.size} active goal${if (active.size == 1) "" else "s"}").joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(last?.let { "Last contact ${Writer.dayLabel(it.created)}" } ?: "No contacts yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!p.active) Pill("Closed", MaterialTheme.colorScheme.onSurfaceVariant)
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        }
    }
    if (add) ParticipantDialog(Bh.Participant(alias = ""), onDismiss = { add = false }) { BhStore.upsertParticipant(ctx, it); shell.go("bh/participant/${it.id}") }
}

@Composable
fun BhParticipantScreen(id: String) = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    val p = s.participants.firstOrNull { it.id == id } ?: run { Box(Modifier.fillMaxSize()); return@BhGuarded }
    var edit by remember { mutableStateOf(false) }
    var goal by remember { mutableStateOf<Bh.Goal?>(null) }
    var capital by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val recs = s.records.filter { it.ref == p.id }.sortedByDescending { it.created }
    val notes = recs.filter { it.kind == Specs.PEER_NOTE }

    Scaffold(topBar = {
        BlTopBar(p.alias, subtitle = listOfNotNull(p.setting, p.stage.ifBlank { null }, "since ${Writer.dayLabel(p.started)}").joinToString(" · "), onBack = { shell.back() }) {
            IconButton(onClick = { edit = true }) { Icon(Icons.Rounded.Edit, "Edit") }
            IconButton(onClick = { del = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Contacts", notes.size.toString(), Icons.Rounded.Forum, Violet, Modifier.weight(1f))
                    StatTile("Minutes", notes.sumOf { it.int("minutes") ?: 0 }.toString(), Icons.Rounded.Schedule, Cobalt, Modifier.weight(1f))
                    StatTile("Goals", "${p.goals.count { it.status == "Achieved" }}/${p.goals.size}", Icons.Rounded.Flag, Amber, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shell.go("bh/form/${Specs.PEER_NOTE}?ref=${p.id}") }, Modifier.weight(1f)) { Text("Session note") }
                    OutlinedButton(onClick = { shell.go("bh/form/${Specs.SAFETY_PLAN}?ref=${p.id}") }, Modifier.weight(1f)) { Text("Wellness plan") }
                    OutlinedButton(onClick = { shell.go("bh/form/${Specs.REFERRAL}?ref=${p.id}") }, Modifier.weight(1f)) { Text("Referral") }
                }
            }
            if (p.strengths.isNotBlank() || p.supports.isNotBlank() || p.pathways.isNotEmpty() || p.preferences.isNotBlank()) item {
                BlCard(Modifier.fillMaxWidth()) {
                    if (p.pathways.isNotEmpty()) { Text("Pathways", style = MaterialTheme.typography.labelLarge); Text(p.pathways.joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                    if (p.strengths.isNotBlank()) { Text("Strengths", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)); Text(p.strengths, style = MaterialTheme.typography.bodySmall) }
                    if (p.supports.isNotBlank()) { Text("Supports", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)); Text(p.supports, style = MaterialTheme.typography.bodySmall) }
                    if (p.preferences.isNotBlank()) { Text("What helps / preferences", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp)); Text(p.preferences, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(if (p.wrap) "Wellness plan on file" else "No wellness plan", if (p.wrap) Success else Amber)
                        Pill(if (p.crisisPlan) "Crisis plan on file" else "No crisis plan", if (p.crisisPlan) Success else Amber)
                    }
                }
            }
            item { SectionHeader("Recovery goals", action = "Add") { goal = Bh.Goal(domain = Specs.RECOVERY_DOMAINS.first(), text = "") } }
            if (p.goals.isEmpty()) item { Text("Goals are theirs, in their words. Break each into small steps you can check off together.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(p.goals.sortedBy { GOAL_STATUS.indexOf(it.status) }, key = { it.id }) { g ->
                val done = g.steps.count { it.done }
                BlCard(Modifier.fillMaxWidth(), onClick = { goal = g }, accent = if (g.status == "Achieved") Success else if (g.status == "Active") Mint else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.text, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(g.domain, g.target?.let { "by ${Writer.dayLabel(it)}" }).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(g.status, if (g.status == "Achieved") Success else Cobalt)
                    }
                    if (g.steps.isNotEmpty()) {
                        LinearProgressIndicator(progress = { done / g.steps.size.toFloat() }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        g.steps.forEach { st ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(st.done, { c ->
                                    BhStore.upsertParticipant(ctx, p.copy(goals = p.goals.map { x -> if (x.id == g.id) x.copy(steps = x.steps.map { y -> if (y.id == st.id) y.copy(done = c) else y }) else x }))
                                })
                                Text(st.text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item { SectionHeader("Recovery capital", action = if (p.capital.isEmpty()) "Rate" else "Re-rate") { capital = true } }
            if (p.capital.isEmpty()) item {
                Text("A quick 0–5 self-rating across life areas shows where strengths are and where support would help. Rate it together every few weeks.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else item {
                BlCard(Modifier.fillMaxWidth()) {
                    val total = p.capital.values.sum(); val max = CAPITAL.size * 5
                    Text("$total of $max · rated ${p.capitalAt?.let { Writer.dayLabel(it) } ?: ""}", style = MaterialTheme.typography.labelLarge)
                    CAPITAL.forEach { d ->
                        val v = p.capital[d] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Text(d, Modifier.width(150.dp), style = MaterialTheme.typography.bodySmall)
                            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))) {
                                LinearProgressIndicator(progress = { v / 5f }, Modifier.fillMaxSize(), color = if (v <= 1) Amber else if (v >= 4) Success else Mint)
                            }
                            Text(" $v", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            item { SectionHeader("Contacts & documents") }
            if (recs.isEmpty()) item { Text("No notes yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(recs, key = { it.id }) { r -> RecordRow(r, s) { shell.go("bh/form/${r.kind}?id=${r.id}") } }
            if (p.notes.isNotBlank()) item { Text("Notes: ${p.notes}", style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (edit) ParticipantDialog(p, onDismiss = { edit = false }) { BhStore.upsertParticipant(ctx, it) }
    goal?.let { g -> GoalDialog(g, isNew = p.goals.none { it.id == g.id }, onDismiss = { goal = null },
        onDelete = { BhStore.upsertParticipant(ctx, p.copy(goals = p.goals.filterNot { it.id == g.id })) }) { ng ->
        BhStore.upsertParticipant(ctx, p.copy(goals = if (p.goals.any { it.id == ng.id }) p.goals.map { if (it.id == ng.id) ng else it } else p.goals + ng))
    } }
    if (capital) CapitalDialog(p.capital, onDismiss = { capital = false }) { m -> BhStore.upsertParticipant(ctx, p.copy(capital = m, capitalAt = System.currentTimeMillis())) }
    if (del) ConfirmDialog("Delete ${p.alias}?", "Their goals and the notes linked to them are removed from this phone.", onDismiss = { del = false }) {
        BhStore.deleteParticipant(ctx, p.id); shell.back()
    }
}

@Composable
private fun ParticipantDialog(p: Bh.Participant, onDismiss: () -> Unit, onSave: (Bh.Participant) -> Unit) {
    var x by remember { mutableStateOf(p) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (p.alias.isBlank()) "New participant" else "Edit ${p.alias}") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x.alias, { x = x.copy(alias = it.take(24)) }, Modifier.fillMaxWidth(), label = { Text("Alias") }, placeholder = { Text("e.g. J.R. or “Blue Jay”") }, singleLine = true)
            Text("An alias or initials only — never a full name.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownField("Setting", SETTINGS.map { it to it }, x.setting, { x = x.copy(setting = it) })
            OptionalDropdown("Readiness for change", Specs.STAGES, x.stage, { x = x.copy(stage = it) })
            Text("Recovery pathways", style = MaterialTheme.typography.labelLarge)
            ChipSelect(PATHWAYS, x.pathways, { x = x.copy(pathways = it) }, collapseAfter = 6)
            OutlinedTextField(x.strengths, { x = x.copy(strengths = it) }, Modifier.fillMaxWidth(), label = { Text("Strengths") })
            OutlinedTextField(x.supports, { x = x.copy(supports = it) }, Modifier.fillMaxWidth(), label = { Text("Natural supports") })
            OutlinedTextField(x.preferences, { x = x.copy(preferences = it) }, Modifier.fillMaxWidth(), label = { Text("What helps / preferences") })
            LabeledSwitchRow("Wellness plan on file", null, x.wrap) { x = x.copy(wrap = it) }
            LabeledSwitchRow("Crisis plan on file", null, x.crisisPlan) { x = x.copy(crisisPlan = it) }
            LabeledSwitchRow("Active", "Turn off when services end", x.active) { x = x.copy(active = it) }
            OutlinedTextField(x.notes, { x = x.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Notes") })
        }
    }, confirmButton = { TextButton(enabled = x.alias.isNotBlank(), onClick = { onSave(x.copy(alias = x.alias.trim())); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun GoalDialog(g: Bh.Goal, isNew: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Bh.Goal) -> Unit) {
    var x by remember { mutableStateOf(g) }
    var step by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "New recovery goal" else "Goal") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Life area", Specs.RECOVERY_DOMAINS.map { it to it }, x.domain, { x = x.copy(domain = it) })
            OutlinedTextField(x.text, { x = x.copy(text = it) }, Modifier.fillMaxWidth(), label = { Text("Goal, in their words") })
            OutlinedTextField(x.why, { x = x.copy(why = it) }, Modifier.fillMaxWidth(), label = { Text("Why it matters to them") })
            DropdownField("Target", listOf(null to "No date", 7 to "1 week", 14 to "2 weeks", 30 to "1 month", 60 to "2 months", 90 to "3 months", 180 to "6 months"),
                x.target?.let { t -> listOf(7, 14, 30, 60, 90, 180).minByOrNull { kotlin.math.abs((t - x.created) / 86_400_000L - it) } }, { d -> x = x.copy(target = d?.let { x.created + it * 86_400_000L }) })
            DropdownField("Status", GOAL_STATUS.map { it to it }, x.status, { x = x.copy(status = it) })
            Text("Steps", style = MaterialTheme.typography.labelLarge)
            x.steps.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(st.done, { c -> x = x.copy(steps = x.steps.map { if (it.id == st.id) it.copy(done = c) else it }) })
                    Text(st.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { x = x.copy(steps = x.steps - st) }) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(step, { step = it }, Modifier.weight(1f), placeholder = { Text("Add a small step") }, singleLine = true)
                IconButton(enabled = step.isNotBlank(), onClick = { x = x.copy(steps = x.steps + Bh.Step(text = step.trim())); step = "" }) { Icon(Icons.Rounded.Add, "Add step") }
            }
            if (!isNew) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete goal", color = com.blacklab.app.ui.theme.Danger) }
        }
    }, confirmButton = { TextButton(enabled = x.text.isNotBlank(), onClick = { onSave(x.copy(steps = x.steps + listOfNotNull(step.takeIf { it.isNotBlank() }?.let { Bh.Step(text = it.trim()) }))); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CapitalDialog(current: Map<String, Int>, onDismiss: () -> Unit, onSave: (Map<String, Int>) -> Unit) {
    var m by remember { mutableStateOf(CAPITAL.associateWith { current[it] ?: 3 }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Recovery capital (0–5)") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("0 = a big struggle right now · 5 = a real strength", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CAPITAL.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${m[d]}", fontWeight = FontWeight.SemiBold)
                }
                Slider((m[d] ?: 3).toFloat(), { v -> m = m + (d to v.toInt()) }, valueRange = 0f..5f, steps = 4)
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(m); onDismiss() }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
