package com.blacklab.app.ui.bh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Forms
import com.blacklab.app.bh.Specs
import com.blacklab.app.bh.Writer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.MiniBars
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.ui.theme.Violet

/** Credentials people in these roles commonly hold or renew — added to Education with one tap. */
val BH_CREDENTIALS = listOf(
    "CPSS / CPS — Certified Peer Support Specialist" to "State certification board",
    "NCPS — National Certified Peer Specialist" to "NAADAC",
    "CPRS — Certified Peer Recovery Specialist" to "State board / IC&RC",
    "MHT — Mental Health Technician" to "Employer / state",
    "BLS / CPR" to "American Heart Association / Red Cross",
    "First Aid" to "American Red Cross",
    "Crisis prevention & de-escalation (CPI, Mandt, Safety-Care…)" to "Training provider",
    "Mental Health First Aid" to "National Council for Mental Wellbeing",
    "QPR Suicide Prevention Gatekeeper" to "QPR Institute",
    "Trauma-Informed Care certificate" to "Training provider",
    "WRAP Facilitator" to "Copeland Center",
    "CADC / CAC — Addiction counselor" to "State board / IC&RC",
)

@Composable
fun BhWellnessScreen() = BhGuarded {
    val shell = LocalShell.current
    val s = bhState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val checkins = s.records(Specs.CHECKIN)
    val supervision = s.records(Specs.SUPERVISION)
    val selfcare = s.records(Specs.SELFCARE).firstOrNull()

    Scaffold(topBar = { BlTopBar("Wellness & Supervision", subtitle = "Taking care of the helper", onBack = { shell.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
                listOf("Check-ins", "Supervision", "Self-care plan", "Credentials").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    0 -> {
                        item { Button(onClick = { shell.go("bh/form/${Specs.CHECKIN}") }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Spa, null); Spacer(Modifier.size(6.dp)); Text("After-shift check-in") } }
                        val recent = checkins.take(14).reversed()
                        if (recent.size >= 2) {
                            item { TrendCard("Stress", recent, "stress", Coral) }
                            item { TrendCard("Emotionally drained", recent, "drained", Amber) }
                            item { TrendCard("Felt good about the help I gave", recent, "meaning", Success) }
                            item { TrendCard("Energy", recent, "energy", Mint) }
                        }
                        val last5 = checkins.take(5)
                        val avg = { k: String -> last5.mapNotNull { it.int(k) }.takeIf { it.isNotEmpty() }?.average() }
                        if ((avg("drained") ?: 0.0) >= 7 || (avg("carry") ?: 0.0) >= 7 || last5.count { it.on("support") } >= 2) item {
                            BlCard(Modifier.fillMaxWidth(), accent = Amber) {
                                Text("You've been carrying a lot", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("Your recent check-ins show high strain. That's common in this work and it's a signal, not a failing. Consider bringing it to supervision, using your EAP, or taking real time off. If you're in crisis, call or text 988.",
                                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                        if (checkins.isEmpty()) item {
                            Text("A two-minute check-in after each shift tracks stress, burnout and compassion satisfaction over time — and helps you notice when to ask for support.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(checkins.take(20), key = { it.id }) { r ->
                            BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("bh/form/${Specs.CHECKIN}?id=${r.id}") }) {
                                Text(Writer.when_(r.created), style = MaterialTheme.typography.titleSmall)
                                Text(listOfNotNull(r.v("stress").ifBlank { null }?.let { "Stress $it" }, r.v("drained").ifBlank { null }?.let { "Drained $it" },
                                    r.v("meaning").ifBlank { null }?.let { "Satisfaction $it" }, r.v("boundaries").ifBlank { null }).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                r.v("win").ifBlank { null }?.let { Text("Win: $it", style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    1 -> {
                        val hours = { list: List<Forms.Rec> -> list.sumOf { it.int("minutes") ?: 0 } / 60.0 }
                        val year = java.time.LocalDate.now().withDayOfYear(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                StatTile("All time", "%.1f h".format(hours(supervision)), Icons.Rounded.SupervisorAccount, Violet, Modifier.weight(1f))
                                StatTile("This year", "%.1f h".format(hours(supervision.filter { it.created >= year })), Icons.Rounded.CalendarMonth, Cobalt, Modifier.weight(1f))
                                StatTile("Credential", "%.1f h".format(hours(supervision.filter { it.on("counts") })), Icons.Rounded.WorkspacePremium, Amber, Modifier.weight(1f))
                            }
                        }
                        item { Button(onClick = { shell.go("bh/form/${Specs.SUPERVISION}") }, Modifier.fillMaxWidth()) { Text("Log supervision") } }
                        val byType = supervision.groupBy { it.v("type").ifBlank { "Other" } }
                        if (byType.isNotEmpty()) item {
                            Text(byType.entries.joinToString(" · ") { (k, v) -> "$k ${"%.1f".format(hours(v))} h" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val topics = supervision.flatMap { it.m("topics") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6)
                        if (topics.isNotEmpty()) item {
                            Text("Most discussed: " + topics.joinToString(", ") { "${it.key} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                        }
                        items(supervision, key = { it.id }) { r ->
                            BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("bh/form/${Specs.SUPERVISION}?id=${r.id}") }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(listOfNotNull(r.v("type").ifBlank { null }, r.v("supervisor").ifBlank { null }).joinToString(" · ").ifBlank { "Supervision" }, style = MaterialTheme.typography.titleSmall)
                                        Text(listOfNotNull(r.v("when").ifBlank { null }, r.v("minutes").ifBlank { null }?.let { "$it min" }).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (r.on("counts")) Pill("Counts", Amber)
                                }
                            }
                        }
                    }
                    2 -> {
                        item {
                            Text("Plan how you prepare, reset during a shift, and leave work at work. Peer specialists model wellness — this is yours.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item {
                            Button(onClick = { shell.go("bh/form/${Specs.SELFCARE}" + (selfcare?.let { "?id=${it.id}" } ?: "")) }, Modifier.fillMaxWidth()) {
                                Text(if (selfcare == null) "Write my self-care plan" else "Edit my self-care plan")
                            }
                        }
                        selfcare?.let { r ->
                            item {
                                BlCard(Modifier.fillMaxWidth(), accent = Success) {
                                    Text(Forms.render(Specs.selfcare, r, "My self-care plan"), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        item { SectionHeader("Quick resets") }
                        items(com.blacklab.app.bh.Library.GROUNDING.take(4)) { (n, how) -> Text("• $n — $how", style = MaterialTheme.typography.bodySmall) }
                        item { OutlinedButton(onClick = { shell.go("bh/crisis?tab=ground") }, Modifier.fillMaxWidth()) { Text("Breathing coach") } }
                    }
                    else -> {
                        item {
                            Text("Track exams, renewals and CEUs for your peer and MHT credentials in Education — it reminds you at 90, 30 and 7 days and keeps your CEU log.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item { Button(onClick = { shell.go("education?tab=Certifications") }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.WorkspacePremium, null); Spacer(Modifier.size(6.dp)); Text("Open my credentials") } }
                        item { SectionHeader("Common credentials in these roles") }
                        items(BH_CREDENTIALS) { (name, org) ->
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                                Text(org, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Text("Requirements (training hours, supervised hours, exam, renewal CEUs, ethics hours) vary by state and board — check your certifying body.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendCard(title: String, recs: List<Forms.Rec>, key: String, color: androidx.compose.ui.graphics.Color) {
    val vals = recs.map { (it.int(key) ?: 0).toFloat() }
    BlCard(Modifier.fillMaxWidth()) {
        Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall); Text("avg ${"%.1f".format(vals.average())}", style = MaterialTheme.typography.labelMedium) }
        MiniBars(vals, recs.map { Writer.dayLabel(it.created).substringAfter(' ').substringBefore(',') }, color, Modifier.fillMaxWidth().height(70.dp).padding(top = 6.dp))
    }
}
