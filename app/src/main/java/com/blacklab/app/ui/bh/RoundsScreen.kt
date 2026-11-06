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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Library
import com.blacklab.app.bh.Rounds
import com.blacklab.app.bh.Writer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.rememberHaptics
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success

@Composable
fun BhRoundsScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    val now = rememberTicker(1_000)
    val due = remember(s, now / 1_000) { Rounds.due(s, now) }
    val overdue = due.filter { it.dueAt != null && now > it.dueAt + s.prefs.graceMinutes * 60_000L }
    val next = Rounds.nextDue(s, now)
    var bulk by remember { mutableStateOf(false) }
    var single by remember { mutableStateOf<Bh.Patient?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<Bh.Check?>(null) }
    var add by remember { mutableStateOf(false) }
    val (ok, late) = remember(s.checks) { Rounds.compliance(s) }

    Scaffold(topBar = {
        BlTopBar("Observation Rounds", subtitle = "${s.census.size} on census · ${s.prefs.unit.ifBlank { "unit not set" }}", onBack = { shell.back() }) {
            IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.Description, "Round sheet") }
            IconButton(onClick = { add = true }) { Icon(Icons.Rounded.PersonAdd, "Add to census") }
        }
    }) { pad ->
        if (s.census.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                EmptyState(Icons.Rounded.Timer, "No one on the census", "Add people by initials or room — each gets their own observation level and timer.") {
                    Button(onClick = { add = true }) { Text("Add to census") }
                }
            }
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                val c = when { !s.roundsOn -> MaterialTheme.colorScheme.onSurfaceVariant; overdue.isNotEmpty() -> Danger; else -> Mint }
                BlCard(Modifier.fillMaxWidth(), accent = c) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(when {
                                !s.roundsOn -> "Rounds are off"
                                overdue.isNotEmpty() -> "${overdue.size} OVERDUE"
                                next == null -> "Continuous observation only"
                                else -> "Next check in " + mmss(next - now)
                            }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = c)
                            Text(if (s.roundsOn) "Alerts ${if (s.prefs.roundAlerts) "on" else "off"} · overdue after ${s.prefs.graceMinutes} min grace" else "Turn on to start the timers and alerts",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(s.roundsOn, { BhStore.setRounds(ctx, it) })
                    }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { bulk = true }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FactCheck, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Log a round") }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Due now", due.count { it.dueAt != null && it.dueAt <= now }.toString(), Icons.Rounded.Alarm, Amber, Modifier.weight(1f))
                    StatTile("Continuous", due.count { Rounds.continuous(it.patient.obs) }.toString(), Icons.Rounded.Visibility, Danger, Modifier.weight(1f))
                    StatTile("On time", if (ok + late == 0) "—" else "${ok * 100 / (ok + late)}%", Icons.Rounded.Verified, Success, Modifier.weight(1f))
                }
            }
            item { SectionHeader("People") }
            items(due, key = { it.patient.id }) { d ->
                val p = d.patient
                val (label, color) = when {
                    Rounds.continuous(p.obs) -> "Continuous" to Danger
                    d.dueAt == null -> "Routine" to MaterialTheme.colorScheme.onSurfaceVariant
                    !s.roundsOn -> "Paused" to MaterialTheme.colorScheme.onSurfaceVariant
                    now > d.dueAt + s.prefs.graceMinutes * 60_000L -> "Late ${mmss(now - d.dueAt)}" to Danger
                    now >= d.dueAt -> "Due now" to Amber
                    else -> "in ${mmss(d.dueAt - now)}" to Mint
                }
                BlCard(Modifier.fillMaxWidth(), onClick = { single = p }, accent = if (color == Danger || color == Amber) color else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.display, style = MaterialTheme.typography.titleSmall)
                            Text(d.last?.let { "${Writer.clock(it.time)} · ${it.location} · ${it.behavior}" } ?: "Not checked yet",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Pill(p.obs, Cobalt)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item { SectionHeader("Recent checks") }
            items(s.checks.take(40), key = { it.id }) { c ->
                val p = s.patients.firstOrNull { it.id == c.patientId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Writer.clock(c.time)}  ${p?.display ?: "—"} · ${c.location} · ${c.behavior}" + (c.note.ifBlank { null }?.let { " — $it" } ?: ""),
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { del = c }) { Icon(Icons.Rounded.Close, "Remove check", Modifier.size(16.dp)) }
                }
            }
            item {
                Text("BlackLab's round log is a personal aid and backup. Document observation checks wherever your facility requires (paper sheet, tablet or EHR).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (bulk) BulkRoundDialog(s, due.map { it.patient }, now, onDismiss = { bulk = false })
    single?.let { p -> CheckDialog(p, s.checks.filter { it.patientId == p.id }.maxByOrNull { it.time }, s, onDismiss = { single = null }) }
    if (sheet) NoteOutputDialog("Round sheet", Writer.roundSheet(s), onDismiss = { sheet = false })
    del?.let { c -> ConfirmDialog("Remove this check?", "${Writer.clock(c.time)} · ${c.location} · ${c.behavior}", confirmLabel = "Remove", onDismiss = { del = null }) { BhStore.deleteCheck(ctx, c.id) } }
    if (add) PatientDialog(Bh.Patient(label = "", obs = Rounds.LEVELS.firstOrNull { Rounds.interval(it.first) == s.prefs.defaultInterval }?.first ?: "Q15"), true,
        onDismiss = { add = false }, onDischarge = {}) { BhStore.upsertPatient(ctx, it) }
}

private fun mmss(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/** Log everyone at once: each row defaults to their last location & behavior; "Set all" fills the whole round. */
@Composable
private fun BulkRoundDialog(s: Bh.State, people: List<Bh.Patient>, now: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val haptics = rememberHaptics()
    val last = remember { s.checks.groupBy { it.patientId }.mapValues { (_, v) -> v.maxByOrNull { it.time } } }
    val dueIds = remember { Rounds.due(s, now).filter { it.dueAt == null || it.dueAt <= now + 3 * 60_000L }.map { it.patient.id }.toSet() }
    var chosen by remember { mutableStateOf(people.filter { it.id in dueIds || Rounds.continuous(it.obs) }.map { it.id }.toSet().ifEmpty { people.map { it.id }.toSet() }) }
    var loc by remember { mutableStateOf(people.associate { it.id to (last[it.id]?.location ?: "In room") }) }
    var beh by remember { mutableStateOf(people.associate { it.id to (last[it.id]?.behavior ?: "Calm") }) }
    var note by remember { mutableStateOf(people.associate { it.id to "" }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log a round · ${Writer.clock(now)}") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Set all selected", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OptionalDropdown("Location", Library.CHECK_LOCATIONS, "", { v -> if (v.isNotBlank()) loc = loc.mapValues { (k, cur) -> if (k in chosen) v else cur } }, Modifier.weight(1f))
                OptionalDropdown("Behavior", Library.CHECK_BEHAVIORS, "", { v -> if (v.isNotBlank()) beh = beh.mapValues { (k, cur) -> if (k in chosen) v else cur } }, Modifier.weight(1f))
            }
            HorizontalDivider()
            people.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.id in chosen, { c -> chosen = if (c) chosen + p.id else chosen - p.id })
                    Text(p.display, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill(p.obs, Cobalt)
                }
                if (p.id in chosen) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.blacklab.app.ui.components.DropdownField("Location", Library.CHECK_LOCATIONS.map { it to it }, loc[p.id] ?: "", { v -> loc = loc + (p.id to v) }, Modifier.weight(1f))
                        com.blacklab.app.ui.components.DropdownField("Behavior", Library.CHECK_BEHAVIORS.map { it to it }, beh[p.id] ?: "", { v -> beh = beh + (p.id to v) }, Modifier.weight(1f))
                    }
                    OutlinedTextField(note[p.id] ?: "", { v -> note = note + (p.id to v) }, Modifier.fillMaxWidth(), placeholder = { Text("Note (optional)") }, singleLine = true)
                }
            }
        }
    }, confirmButton = {
        TextButton(enabled = chosen.isNotEmpty(), onClick = {
            val t = System.currentTimeMillis()
            BhStore.addChecks(ctx, people.filter { it.id in chosen }.map { p ->
                Bh.Check(patientId = p.id, time = t, location = loc[p.id] ?: "In room", behavior = beh[p.id] ?: "Calm", note = note[p.id].orEmpty().trim())
            })
            haptics.confirm(); shell.toast("${chosen.size} check${if (chosen.size == 1) "" else "s"} logged"); onDismiss()
        }) { Text("Log ${chosen.size}") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun CheckDialog(p: Bh.Patient, last: Bh.Check?, s: Bh.State, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    val haptics = rememberHaptics()
    var loc by remember { mutableStateOf(listOf(last?.location ?: "In room")) }
    var beh by remember { mutableStateOf(listOf(last?.behavior ?: "Calm")) }
    var note by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf(false) }
    val history = s.checks.filter { it.patientId == p.id }.take(8)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${p.display} · ${p.obs}") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.precautions.isNotEmpty()) Text(p.precautions.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Amber)
            Text("Location", style = MaterialTheme.typography.labelLarge)
            ChipSelect(Library.CHECK_LOCATIONS, loc, { if (it.isNotEmpty()) loc = it }, single = true, collapseAfter = 8)
            Text("Behavior", style = MaterialTheme.typography.labelLarge)
            ChipSelect(Library.CHECK_BEHAVIORS, beh, { if (it.isNotEmpty()) beh = it }, single = true, collapseAfter = 8)
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, singleLine = true)
            if (history.isNotEmpty()) {
                HorizontalDivider()
                history.forEach { c -> Text("${Writer.clock(c.time)} · ${c.location} · ${c.behavior}" + (c.note.ifBlank { null }?.let { " — $it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { edit = true }, Modifier.weight(1f)) { Text("Edit person") }
                OutlinedButton(onClick = { onDismiss(); shell.go("bh/form/${com.blacklab.app.bh.Specs.BEHAVIOR}?ref=${p.id}") }, Modifier.weight(1f)) { Text("Obs. note") }
            }
        }
    }, confirmButton = {
        TextButton(onClick = {
            BhStore.addChecks(ctx, listOf(Bh.Check(patientId = p.id, location = loc.first(), behavior = beh.first(), note = note.trim())))
            haptics.confirm(); onDismiss()
        }) { Text("Log check") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (edit) PatientDialog(p, false, onDismiss = { edit = false }, onDischarge = { BhStore.discharge(ctx, p.id); edit = false; onDismiss() }) { BhStore.upsertPatient(ctx, it) }
}
