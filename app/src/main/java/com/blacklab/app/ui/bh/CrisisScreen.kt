package com.blacklab.app.ui.bh

import com.blacklab.app.ui.theme.readable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.bh.Library
import com.blacklab.app.bh.Specs
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.ProgressRing
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success

private val phaseColors = listOf(Amber, Coral, Danger, Cobalt, Mint)

@Composable
fun BhCrisisScreen(startTab: String = "") = BhGuarded {
    val shell = LocalShell.current
    val s = bhState()
    val tabs = listOf("Guide", "What to say", "Grounding", "Trauma-informed", "Reports")
    var tab by rememberSaveable { mutableIntStateOf(when (startTab) { "ground" -> 2; "reports" -> 4; else -> 0 }) }
    Scaffold(topBar = { BlTopBar("Crisis & De-escalation", subtitle = "Follow your facility's training and the nurse in charge", onBack = { shell.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (tab) {
                    0 -> {
                        item { Text("Match your response to where the person is. Tap a phase to open it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(Library.CRISIS_PHASES.withIndex().toList(), key = { it.value.name }) { (i, ph) -> PhaseCard(ph, phaseColors[i], initiallyOpen = i == 0) }
                    }
                    1 -> {
                        item { SectionHeader("Ground rules") }
                        items(Library.DEESCALATION_TIPS) { t -> Row { Text("•  ", color = Mint.readable()); Text(t, style = MaterialTheme.typography.bodyMedium) } }
                        item { SectionHeader("Phrases that help") }
                        Library.CRISIS_PHASES.forEachIndexed { i, ph ->
                            item(key = "say$i") {
                                BlCard(Modifier.fillMaxWidth(), accent = phaseColors[i]) {
                                    Text(ph.name, style = MaterialTheme.typography.titleSmall, color = phaseColors[i].readable())
                                    ph.say.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 4.dp)) }
                                }
                            }
                        }
                    }
                    2 -> {
                        item { BreathingPacer() }
                        item { SectionHeader("Grounding techniques") }
                        items(Library.GROUNDING) { (name, how) ->
                            BlCard(Modifier.fillMaxWidth()) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { Text("Use these with someone, or for yourself after a hard moment.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    3 -> {
                        item { Text("SAMHSA's six principles of a trauma-informed approach — ask “what happened to you?” rather than “what's wrong with you?”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(Library.TRAUMA_INFORMED) { (name, how) ->
                            BlCard(Modifier.fillMaxWidth(), accent = Success) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { SectionHeader("The 4 R's") }
                        item {
                            Text("Realize how widespread trauma is · Recognize its signs in people, families and staff · Respond by building it into policy and practice · Resist re-traumatization.",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { shell.go("bh/form/${Specs.INCIDENT}") }, Modifier.weight(1f)) { Icon(Icons.Rounded.ReportProblem, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Incident") }
                                OutlinedButton(onClick = { shell.go("bh/form/${Specs.DEBRIEF}") }, Modifier.weight(1f)) { Icon(Icons.Rounded.Forum, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Debrief") }
                            }
                        }
                        item { Text("Drafts here are for the facility's incident system — that system is the official report. Use initials only.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val recs = (s.records(Specs.INCIDENT) + s.records(Specs.DEBRIEF)).sortedByDescending { it.created }
                        if (recs.isEmpty()) item { Text("No drafts yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(recs, key = { it.id }) { r -> RecordRow(r, s) { shell.go("bh/form/${r.kind}?id=${r.id}") } }
                        val restrictive = s.records(Specs.INCIDENT).count { it.on("restrictive") }
                        if (recs.isNotEmpty()) item {
                            Text("${s.records(Specs.INCIDENT).size} incident drafts · $restrictive with restrictive interventions · ${s.records(Specs.DEBRIEF).size} debriefs",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(ph: Library.Phase, color: Color, initiallyOpen: Boolean) {
    var open by remember { mutableStateOf(initiallyOpen) }
    BlCard(Modifier.fillMaxWidth(), accent = color, onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ph.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = color.readable())
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        Text(ph.looks.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("RESPOND", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                ph.respond.forEach { Text("✓  $it", style = MaterialTheme.typography.bodyMedium) }
                Text("SAY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                ph.say.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic) }
                Text("AVOID", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Danger.readable(), modifier = Modifier.padding(top = 6.dp))
                ph.avoid.forEach { Text("✗  $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** A 4-4-4-4 box-breathing coach. */
@Composable
private fun BreathingPacer() {
    var running by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    val now = rememberTicker(100)
    val steps = listOf("Breathe in", "Hold", "Breathe out", "Hold")
    val elapsed = if (running) now - startedAt else 0L
    val idx = ((elapsed / 4000) % 4).toInt()
    val frac = (elapsed % 4000) / 4000f
    val cycles = elapsed / 16_000
    BlCard(Modifier.fillMaxWidth(), accent = Mint) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(if (running) frac else 0f, 96.dp, color = if (idx % 2 == 0) Mint else Cobalt) {
                    Text(if (running) "${4 - (elapsed % 4000) / 1000}" else "4", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(if (running) steps[idx] else "Box breathing", style = MaterialTheme.typography.titleLarge)
                Text(if (running) "Round ${cycles + 1}" else "4 in · 4 hold · 4 out · 4 hold", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(8.dp))
                if (running) Pill("Breathe with the ring", Success)
                Button(onClick = { running = !running; startedAt = System.currentTimeMillis() }, Modifier.padding(top = 6.dp)) { Text(if (running) "Stop" else "Start") }
            }
        }
    }
}
