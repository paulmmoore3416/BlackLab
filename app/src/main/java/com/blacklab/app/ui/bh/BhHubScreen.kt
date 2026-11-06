package com.blacklab.app.ui.bh

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Rounds
import com.blacklab.app.bh.Writer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.IconBadge
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.ui.theme.Violet

internal data class Tool(val route: String, val title: String, val blurb: String, val icon: ImageVector, val color: Color)

internal val TOOLS = listOf(
    Tool("bh/shift", "Shift Hub", "Start & end shift, checklists, census, observation notes, SBAR handoff", Icons.Rounded.Badge, Cobalt),
    Tool("bh/rounds", "Observation Rounds", "Q5–Q60, line of sight & 1:1 timers with alerts and a round sheet", Icons.Rounded.Timer, Mint),
    Tool("bh/crisis", "Crisis & De-escalation", "Phase-by-phase guide, what to say, grounding, incident & debrief drafts", Icons.Rounded.Shield, Danger),
    Tool("bh/peer", "Peer Support", "Participants, recovery goals & capital, DAP/SOAP/BIRP/GIRP notes, wellness plans", Icons.Rounded.Diversity3, Violet),
    Tool("bh/groups", "Group Facilitation", "18 ready group plans, your own plans, attendance and group notes", Icons.Rounded.Groups, Amber),
    Tool("bh/resources", "Resources & Referrals", "Crisis lines, recovery & community resources, referral tracking", Icons.Rounded.Support, Color(0xFF5BC0EB)),
    Tool("bh/eoc", "Safety Rounds (EOC)", "Ligature-risk & environment-of-care rounds by area, work orders, reports", Icons.Rounded.HealthAndSafety, Coral),
    Tool("bh/wellness", "Wellness & Supervision", "After-shift check-ins, compassion-fatigue trends, supervision hours, self-care", Icons.Rounded.Spa, Success),
)

@Composable
fun BhHubScreen() = BhGuarded {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val s = bhState()
    val now = rememberTicker(15_000)
    var settings by remember { mutableStateOf(false) }
    val shift = s.activeShift
    val dueList = remember(s, now / 30_000) { Rounds.due(s, now) }
    val overdue = remember(s, now / 30_000) { Rounds.overdue(s, now) }
    val next = Rounds.nextDue(s, now)

    Scaffold(topBar = {
        BlTopBar("Behavioral Health", subtitle = listOf(s.prefs.facility, s.prefs.unit).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "MHT & Peer Support workspace · encrypted" }, onBack = { shell.back() }) {
            IconButton(onClick = { BhGate.lock(); shell.back() }) { Icon(Icons.Rounded.Lock, "Lock now") }
            IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Settings, "Workspace settings") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth(), accent = if (shift != null) Cobalt else null, onClick = { shell.go("bh/shift") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Badge, Cobalt)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            if (shift != null) {
                                Text("On shift · ${shift.type} · ${shift.role}", style = MaterialTheme.typography.titleMedium)
                                val hrs = (now - shift.start) / 3_600_000.0
                                Text("Since ${Writer.clock(shift.start)} (${"%.1f".format(hrs)} h)" + (shift.unit.ifBlank { null }?.let { " · $it" } ?: "") +
                                    " · ${shift.done.size}/${com.blacklab.app.bh.Library.SHIFT_CHECKLIST.size} checklist", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Not on shift", style = MaterialTheme.typography.titleMedium)
                                Text("Start a shift to run the checklist, census and rounds", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
            if (s.roundsOn || s.census.isNotEmpty()) item {
                val c = if (overdue.isNotEmpty()) Danger else Mint
                BlCard(Modifier.fillMaxWidth(), accent = c, onClick = { shell.go("bh/rounds") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Timer, c)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(when {
                                !s.roundsOn -> "Rounds paused"
                                overdue.isNotEmpty() -> "${overdue.size} overdue"
                                next != null -> "Next check in ${((next - now) / 60_000).coerceAtLeast(0)} min"
                                else -> "Rounds on"
                            }, style = MaterialTheme.typography.titleMedium, color = if (overdue.isNotEmpty()) Danger else MaterialTheme.colorScheme.onSurface)
                            Text("${s.census.size} on census · ${dueList.count { Rounds.continuous(it.patient.obs) }} continuous · ${s.checks.size} checks logged",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:988"))) }, Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Danger)) { Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("988") }
                    OutlinedButton(onClick = { shell.go("bh/crisis") }, Modifier.weight(1f)) { Text("De-escalate") }
                    OutlinedButton(onClick = { shell.go("bh/crisis?tab=ground") }, Modifier.weight(1f)) { Text("Grounding") }
                }
            }
            item { SectionHeader("Tools") }
            items(TOOLS, key = { it.route }) { t ->
                BlCard(Modifier.fillMaxWidth(), onClick = { shell.go(t.route) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(t.icon, t.color)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleMedium)
                            Text(t.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        countFor(t.route, s)?.let { Pill(it, t.color) }
                    }
                }
            }
            item { SectionHeader("Learn & talk it through") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { shell.go("kb/peer-support") }, Modifier.weight(1f)) { Text("Peer practice") }
                    OutlinedButton(onClick = { shell.go("kb/mht-practice") }, Modifier.weight(1f)) { Text("MHT practice") }
                }
            }
            item {
                OutlinedButton(onClick = { shell.go("talk/conv-peer-lab") }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Forum, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Talk: Shift & Peer Practice Lab")
                }
            }
            val recent = s.records.sortedByDescending { it.updated }.take(5)
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recent drafts") }
                items(recent, key = { "r" + it.id }) { r -> RecordRow(r, s) { shell.go("bh/form/${r.kind}?id=${r.id}") } }
            }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.VerifiedUser, null, tint = Success)
                        Text("Privacy", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Everything here is encrypted with a key that never leaves this phone. It isn't backed up, synced to Desktop or Drive, searched by “Ask my files” or read aloud in the car. " +
                        "Use initials, room numbers or an alias — never full names or record numbers. Census and rounds are erased after ${s.prefs.retentionHours} hours, drafts about others after " +
                        (if (s.prefs.noteDays > 0) "${s.prefs.noteDays} days" else "you delete them") + ". Your facility's EHR is always the official record; follow its policy on personal devices.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    if (settings) BhSettingsDialog(s.prefs, onDismiss = { settings = false })
}

private fun countFor(route: String, s: Bh.State): String? = when (route) {
    "bh/peer" -> s.participants.count { it.active }.takeIf { it > 0 }?.toString()
    "bh/rounds" -> s.census.size.takeIf { it > 0 }?.toString()
    "bh/eoc" -> s.eoc.count { !it.closed }.takeIf { it > 0 }?.let { "$it open" }
    "bh/resources" -> s.favorites.size.takeIf { it > 0 }?.let { "$it ★" }
    else -> null
}

@Composable
private fun BhSettingsDialog(p: Bh.Prefs, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val shell = LocalShell.current
    var x by remember { mutableStateOf(p) }
    var newUnit by remember { mutableStateOf("") }
    var wipe by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Workspace settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(x.facility, { x = x.copy(facility = it) }, Modifier.fillMaxWidth(), label = { Text("Facility") }, singleLine = true)
            DropdownField("Unit", listOf("" to "—") + x.units.map { it to it }, x.unit, { x = x.copy(unit = it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newUnit, { newUnit = it }, Modifier.weight(1f), label = { Text("Add a unit") }, singleLine = true)
                TextButton(enabled = newUnit.isNotBlank(), onClick = { x = x.copy(units = (x.units + newUnit.trim()).distinct(), unit = newUnit.trim()); newUnit = "" }) { Text("Add") }
            }
            DropdownField("My role", listOf("MHT" to "Mental Health Technician", "PSS" to "Peer Support Specialist", "Both" to "Both roles"), x.role, { x = x.copy(role = it) })
            OutlinedTextField(x.signature, { x = x.copy(signature = it) }, Modifier.fillMaxWidth(), label = { Text("Note signature") }, placeholder = { Text("e.g. P. Moore, CPSS, MHT") }, singleLine = true)
            OutlinedTextField(x.state, { x = x.copy(state = it) }, Modifier.fillMaxWidth(), label = { Text("Certifying state (for your reference)") }, singleLine = true)
            SectionHeader("Rounds")
            DropdownField("Default observation level", Rounds.LEVELS, Rounds.LEVELS.firstOrNull { Rounds.interval(it.first) == x.defaultInterval }?.first ?: "Q15",
                { x = x.copy(defaultInterval = Rounds.interval(it) ?: 15) })
            DropdownField("Overdue after (grace)", listOf(1, 2, 3, 5).map { it to "$it min" }, x.graceMinutes, { x = x.copy(graceMinutes = it) })
            DropdownField("Shift length", listOf(8, 10, 12, 16).map { it to "$it hours" }, x.shiftHours, { x = x.copy(shiftHours = it) })
            LabeledSwitchRow("Round alerts", "Notify when checks are due and overdue (counts only)", x.roundAlerts) { x = x.copy(roundAlerts = it) }
            LabeledSwitchRow("Alert sound & vibration", null, x.roundSound) { x = x.copy(roundSound = it) }
            SectionHeader("Privacy")
            LabeledSwitchRow("Require fingerprint / screen lock", "Ask before opening this workspace", x.requireUnlock) { x = x.copy(requireUnlock = it) }
            DropdownField("Erase census & rounds after", listOf(8, 12, 16, 24, 48, 72).map { it to "$it hours" }, x.retentionHours, { x = x.copy(retentionHours = it) })
            DropdownField("Erase drafts about others after", listOf(1, 3, 7, 14, 30, 90, 0).map { it to if (it == 0) "Keep until I delete" else "$it day${if (it == 1) "" else "s"}" },
                x.noteDays, { x = x.copy(noteDays = it) })
            LabeledSwitchRow("Hide from Home", "Don't show the rounds card on the Home dashboard", x.hideOnHome) { x = x.copy(hideOnHome = it) }
            TextButton(onClick = { wipe = true }) { Text("Erase the whole workspace…", color = Danger) }
        }
    }, confirmButton = { TextButton(onClick = { BhStore.prefs(ctx) { x }; onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (wipe) ConfirmDialog("Erase everything?", "Census, rounds, notes, participants, plans, safety rounds, check-ins and settings in this workspace are deleted from the phone. This can't be undone.",
        confirmLabel = "Erase", onDismiss = { wipe = false }) { BhStore.wipe(ctx); onDismiss(); shell.toast("Workspace erased") }
}
