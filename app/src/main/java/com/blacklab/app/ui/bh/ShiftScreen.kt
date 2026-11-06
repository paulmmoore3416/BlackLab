package com.blacklab.app.ui.bh

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
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
import com.blacklab.app.bh.Specs
import com.blacklab.app.bh.Writer
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
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint

@Composable
fun BhShiftScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    val now = rememberTicker(30_000)
    val shift = s.activeShift
    var editPatient by remember { mutableStateOf<Bh.Patient?>(null) }
    var endConfirm by remember { mutableStateOf(false) }
    var discharge by remember { mutableStateOf<Bh.Patient?>(null) }
    var notes by remember(shift?.id) { mutableStateOf(shift?.notes.orEmpty()) }

    Scaffold(topBar = {
        BlTopBar("Shift Hub", subtitle = shift?.let { "${it.type} · ${it.role} · since ${Writer.clock(it.start)}" } ?: "Not on shift", onBack = { shell.back() }) {
            IconButton(onClick = { shell.go("bh/records/${Specs.HANDOFF}") }) { Icon(Icons.Rounded.History, "Past handoffs") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (shift == null) item { StartShiftCard(s) }
            else {
                item {
                    val planned = shift.start + s.prefs.shiftHours * 3_600_000L
                    val elapsed = (now - shift.start).coerceAtLeast(0)
                    BlCard(Modifier.fillMaxWidth(), accent = Cobalt) {
                        Text("${shift.type} shift · ${shift.unit.ifBlank { "Unit not set" }}", style = MaterialTheme.typography.titleMedium)
                        Text("${"%.1f".format(elapsed / 3_600_000.0)} h in · scheduled to ${Writer.clock(planned)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { (elapsed / (s.prefs.shiftHours * 3_600_000f)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { shell.go("bh/form/${Specs.HANDOFF}") }, Modifier.weight(1f)) { Text("SBAR handoff") }
                            OutlinedButton(onClick = { endConfirm = true }, Modifier.weight(1f)) { Text("End shift") }
                        }
                    }
                }
                item { SectionHeader("Shift checklist · ${shift.done.size}/${Library.SHIFT_CHECKLIST.size}") }
                Library.SHIFT_CHECKLIST.groupBy { it.phase }.forEach { (phase, list) ->
                    item(key = "ph$phase") { Text(phase.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(list, key = { it.id }) { c ->
                        val done = c.id in shift.done
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(done, { on -> BhStore.upsertShift(ctx, shift.copy(done = if (on) shift.done + c.id else shift.done - c.id)) })
                            Text(c.text, style = MaterialTheme.typography.bodyMedium, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                item {
                    OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Shift notes (no names)") }, minLines = 2,
                        trailingIcon = { if (notes != shift.notes) IconButton(onClick = { BhStore.upsertShift(ctx, shift.copy(notes = notes)) }) { Icon(Icons.Rounded.Save, "Save") } })
                }
            }

            item { SectionHeader("Census · ${s.census.size}", action = "Add") { editPatient = Bh.Patient(label = "", obs = Rounds.LEVELS.firstOrNull { Rounds.interval(it.first) == s.prefs.defaultInterval }?.first ?: "Q15") } }
            if (s.census.isEmpty()) item {
                Text("Add the people on your assignment by initials or room/bed. They power rounds, observation notes and the SBAR census. Erased automatically after ${s.prefs.retentionHours} h.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(s.census.sortedBy { it.room.padStart(6, '0') }, key = { it.id }) { p ->
                val last = s.checks.filter { it.patientId == p.id }.maxByOrNull { it.time }
                BlCard(Modifier.fillMaxWidth(), onClick = { editPatient = p }, accent = if (p.precautions.any { it.startsWith("Suicide") || it.startsWith("Assault") }) Danger else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.display, style = MaterialTheme.typography.titleSmall)
                            if (p.precautions.isNotEmpty()) Text(p.precautions.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Amber)
                            Text(last?.let { "Last check ${Writer.clock(it.time)} · ${it.location} · ${it.behavior}" } ?: "No checks yet",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(p.obs, if (Rounds.continuous(p.obs)) Danger else Mint)
                        IconButton(onClick = { shell.go("bh/form/${Specs.BEHAVIOR}?ref=${p.id}") }) { Icon(Icons.Rounded.NoteAdd, "Observation note") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shell.go("bh/rounds") }, Modifier.weight(1f)) { Icon(Icons.Rounded.Timer, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Rounds") }
                    OutlinedButton(onClick = { shell.go("bh/records/${Specs.BEHAVIOR}") }, Modifier.weight(1f)) { Text("Obs. notes") }
                    OutlinedButton(onClick = { shell.go("bh/records/${Specs.INCIDENT}") }, Modifier.weight(1f)) { Text("Incidents") }
                }
            }

            val closed = s.shifts.filter { it.end != null }
            if (closed.isNotEmpty()) {
                val week = closed.filter { it.start >= now - 7 * 86_400_000L }.sumOf { it.end!! - it.start } / 3_600_000.0
                val month = closed.filter { it.start >= now - 30 * 86_400_000L }.sumOf { it.end!! - it.start } / 3_600_000.0
                item { SectionHeader("Hours") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Last 7 days", "%.1f h".format(week), Icons.Rounded.Schedule, Cobalt, Modifier.weight(1f))
                        StatTile("Last 30 days", "%.1f h".format(month), Icons.Rounded.CalendarMonth, Mint, Modifier.weight(1f))
                        StatTile("Shifts · 30 d", closed.count { it.start >= now - 30 * 86_400_000L }.toString(), Icons.Rounded.Badge, Amber, Modifier.weight(1f))
                    }
                }
                items(closed.take(10), key = { "sh" + it.id }) { sh ->
                    Text("${Writer.dayLabel(sh.start)} · ${sh.type} · ${sh.role} · ${Writer.clock(sh.start)}–${Writer.clock(sh.end!!)} (${"%.1f".format((sh.end - sh.start) / 3_600_000.0)} h)" +
                        " · ${sh.done.size}/${Library.SHIFT_CHECKLIST.size} checklist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    editPatient?.let { p -> PatientDialog(p, isNew = s.patients.none { it.id == p.id }, onDismiss = { editPatient = null }, onDischarge = { discharge = p }) { BhStore.upsertPatient(ctx, it) } }
    discharge?.let { p -> ConfirmDialog("Remove ${p.display} from the census?", "Their checks stay on the round sheet until the retention window ends.", confirmLabel = "Remove",
        onDismiss = { discharge = null }) { BhStore.discharge(ctx, p.id); editPatient = null } }
    if (endConfirm && shift != null) EndShiftDialog(shift, s, onDismiss = { endConfirm = false })
}

@Composable
private fun StartShiftCard(s: Bh.State) {
    val ctx = LocalContext.current
    var type by remember { mutableStateOf(guessShift()) }
    var role by remember { mutableStateOf(if (s.prefs.role == "PSS") "Peer Support" else "MHT") }
    var unit by remember { mutableStateOf(s.prefs.unit) }
    BlCard(Modifier.fillMaxWidth()) {
        Text("Start a shift", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField("Shift", Specs.SHIFTS.map { it to it }, type, { type = it })
            DropdownField("Working as", listOf("MHT" to "Mental Health Technician", "Peer Support" to "Peer Support Specialist", "MHT + Peer" to "Both"), role, { role = it })
            DropdownField("Unit", listOf("" to "—") + s.prefs.units.map { it to it }, unit, { unit = it })
            Button(onClick = {
                BhStore.upsertShift(ctx, Bh.Shift(type = type, role = role, unit = unit))
                if (unit != s.prefs.unit) BhStore.prefs(ctx) { it.copy(unit = unit) }
            }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Start shift") }
        }
    }
}

private fun guessShift(): String = when (java.time.LocalTime.now().hour) { in 5..10 -> "Day"; in 11..17 -> "Evening"; else -> "Night" }

@Composable
private fun EndShiftDialog(shift: Bh.Shift, s: Bh.State, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    var stopRounds by remember { mutableStateOf(s.roundsOn) }
    var clear by remember { mutableStateOf(false) }
    val missing = Library.SHIFT_CHECKLIST.filter { it.phase == "End" && it.id !in shift.done }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("End shift?") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (missing.isNotEmpty()) {
                Text("Still open on the end-of-shift list:", style = MaterialTheme.typography.labelLarge)
                missing.forEach { Text("• ${it.text}", style = MaterialTheme.typography.bodySmall) }
            }
            if (s.roundsOn) com.blacklab.app.ui.components.LabeledSwitchRow("Stop rounds alerts", null, stopRounds) { stopRounds = it }
            if (s.census.isNotEmpty()) com.blacklab.app.ui.components.LabeledSwitchRow("Clear the census and rounds now", "Otherwise erased after ${s.prefs.retentionHours} h", clear) { clear = it }
        }
    }, confirmButton = {
        TextButton(onClick = {
            BhStore.upsertShift(ctx, shift.copy(end = System.currentTimeMillis()))
            if (clear) BhStore.clearCensus(ctx) else if (stopRounds) BhStore.setRounds(ctx, false)
            onDismiss()
            shell.toast("Shift ended — take a minute for yourself", "Check in") { shell.go("bh/form/${Specs.CHECKIN}") }
        }) { Text("End shift") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
internal fun PatientDialog(p: Bh.Patient, isNew: Boolean, onDismiss: () -> Unit, onDischarge: () -> Unit, onSave: (Bh.Patient) -> Unit) {
    var x by remember { mutableStateOf(p) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (isNew) "Add to census" else x.display) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(x.room, { x = x.copy(room = it.take(8)) }, Modifier.weight(1f), label = { Text("Room / bed") }, singleLine = true)
                OutlinedTextField(x.label, { x = x.copy(label = it.uppercase().take(4)) }, Modifier.weight(1f), label = { Text("Initials") }, singleLine = true)
            }
            Text("Initials only — no names or record numbers.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DropdownField("Observation level", Rounds.LEVELS, x.obs, { x = x.copy(obs = it) })
            Text("Precautions", style = MaterialTheme.typography.labelLarge)
            ChipSelect(Library.PRECAUTIONS, x.precautions, { x = x.copy(precautions = it) }, collapseAfter = 8)
            OutlinedTextField(x.notes, { x = x.copy(notes = it) }, Modifier.fillMaxWidth(), label = { Text("Brief note for handoff") }, placeholder = { Text("e.g. new admit, prefers low lights") })
            if (!isNew) TextButton(onClick = onDischarge) { Text("Discharge / remove from census", color = Danger) }
        }
    }, confirmButton = {
        TextButton(enabled = x.room.isNotBlank() || x.label.isNotBlank(), onClick = { onSave(x.copy(room = x.room.trim(), label = x.label.trim())); onDismiss() }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
