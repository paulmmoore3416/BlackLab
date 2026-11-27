package com.blacklab.app.ui.agentops

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.blacklab.app.assistant.DesktopAi
import com.blacklab.app.assistant.DesktopJobs
import com.blacklab.app.link.AgentOps
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ColorDot
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.viewer.HtmlView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val OpsGreen = Color(0xFF66BB6A)
val OpsAmber = Color(0xFFFFB74D)
val OpsRed = Color(0xFFEF5350)
val OpsPurple = Color(0xFFB39DDB)

fun statusColor(s: AgentOps.Status): Color = when (s) {
    AgentOps.Status.WORKING -> OpsAmber
    AgentOps.Status.IDLE -> OpsGreen
    AgentOps.Status.OFFLINE -> Color(0xFF9E9E9E)
}

/** Chip text and colour for the review: Reviewed / Skipped / Failed / Missed / Off / waiting. */
fun reviewChip(r: AgentOps.Review): Pair<String, Color> = when {
    !r.enabled -> "Review off" to Color(0xFF9E9E9E)
    r.health == "missed" -> "Review missed" to OpsRed
    r.status == "Failed" || r.health == "failed" -> "Review failed" to OpsRed
    r.status == "Reviewed" -> (if (r.summary.startsWith("No findings")) "Review: no findings" else "Review: findings") to (if (r.summary.startsWith("No findings")) OpsGreen else OpsAmber)
    r.status == "Skipped" -> "Review skipped" to Color(0xFF90A4AE)
    else -> "Review tonight" to Color(0xFF90A4AE)
}

private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentOpsScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val scope = rememberCoroutineScope()
    val snap by AgentOps.snapshot.collectAsStateWithLifecycle()
    val err by AgentOps.error.collectAsStateWithLifecycle()
    val desk by DesktopAi.status.collectAsStateWithLifecycle()
    val online = desk.state == DesktopAi.State.ONLINE
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showReport by remember { mutableStateOf(false) }
    var confirmReview by remember { mutableStateOf(false) }
    var confirmAdvisor by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // live while the screen is on top: every 2 s, stopping as soon as it isn't
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { AgentOps.refresh(app); delay(2_000) }
        }
    }

    val s = snap
    val status = AgentOps.status(s, online)
    Scaffold(topBar = { BlTopBar("Agent Ops", subtitle = "Your computer's AI, live", onBack = { shell.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth().padding(top = 8.dp), accent = statusColor(status)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(statusColor(status), 12.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(AgentOps.summary(s, online, System.currentTimeMillis()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        if (s != null) Text("companion ${s.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s != null) Text("Worker ${s.worker.ifBlank { "—" }} · ${s.consultsTotal} consults · ${s.forksTotal} fork decisions in all",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    if (status == AgentOps.Status.WORKING) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp), color = OpsAmber)
                    if (err.isNotBlank()) Text(err, color = OpsRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    if (!online) Text("Agent Ops reads your computer over the desktop link (USB or Tailscale). Showing the last snapshot.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (s == null) return@LazyColumn
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Running", "${s.live.size}", Icons.Rounded.Memory, OpsAmber, Modifier.weight(1f))
                    StatTile("Consults today", "${s.consultsToday}", Icons.Rounded.Psychology, OpsPurple, Modifier.weight(1f))
                    StatTile("Forks today", "${s.forksToday}", Icons.Rounded.AccountTree, OpsGreen, Modifier.weight(1f))
                }
            }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Text("Advisor", style = MaterialTheme.typography.titleSmall)
                    Text(if (s.advisorMode == "off") "Off" else s.advisorModel.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text("Consults, 14 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Sparkline(s.consultDays, Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp), OpsPurple)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Fork decisions, 14 days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Sparkline(s.forkDays, Modifier.fillMaxWidth().height(28.dp).padding(top = 4.dp), OpsGreen)
                        }
                    }
                }
            }

            item { SectionHeader("Running now") }
            if (s.live.isEmpty() && s.jobs.none { it.status == "running" || it.status == "queued" }) item {
                Text("Idle.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.live.forEach { t ->
                item {
                    BlCard(Modifier.fillMaxWidth(), accent = OpsAmber) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (t.private) "Private task" else t.request, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Pill(t.origin.substringBefore(" ").ifBlank { "Desktop" }, OpsAmber)
                        }
                        if (t.private) Text("Step ${t.stepCount} · details stay on the computer", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        t.steps.takeLast(6).forEach { st ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(st.at, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                Text(st.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            s.jobs.filter { it.status == "queued" }.forEach { j ->
                item { Text("Queued: ${j.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (s.forks.isNotEmpty()) {
                item { SectionHeader("Fork layer") }
                item {
                    BlCard(Modifier.fillMaxWidth()) {
                        s.forks.forEach { f ->
                            val n = f.sharp + f.split
                            val rate = if (n > 0) f.sharp.toFloat() / n else 0f
                            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name.replace('_', ' '), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                                LinearProgressIndicator(progress = { rate }, Modifier.weight(1f), color = if (rate >= 0.8f) OpsGreen else OpsAmber)
                                Spacer(Modifier.width(8.dp))
                                Text("%.2f · $n".format(rate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text("sharp = settled in code · split = a model decides", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            item { SectionHeader("Advisor feed") }
            if (s.feed.isEmpty()) item {
                Text("The advisor speaks before a long plan, when the same error comes back, and before “done”.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            s.feed.asReversed().take(6).forEach { c ->
                item {
                    Row(verticalAlignment = Alignment.Top) {
                        Pill(c.stage, if (c.error.isNotBlank()) OpsRed else OpsPurple)
                        Spacer(Modifier.width(8.dp))
                        Text(when {
                            c.error.isNotBlank() -> "unavailable: ${c.error}"
                            c.silent -> "nothing to add"
                            c.advice.isBlank() -> "(private task: advice not shown)"
                            else -> c.advice
                        }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            item { SectionHeader("Session log") }
            s.tasks.take(8).forEach { t ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.started.drop(11).take(5), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(t.request, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Pill(t.status.ifBlank { "—" }, if (t.status == "Done") OpsGreen else OpsAmber)
                    }
                }
            }

            item { SectionHeader("Nightly code review") }
            item {
                val r = s.review
                val (chip, chipColor) = reviewChip(r)
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (!r.enabled) "Off" else cap(r.model.ifBlank { "sonnet" }) + (if (r.advisor.isNotBlank()) " · ${cap(r.advisor)} advises" else "") +
                            " · ${r.time.ifBlank { "02:30" }} · read-only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Pill(chip, chipColor)
                    }
                    if (r.health == "missed" || r.health == "failed") Text("⚠ ${r.healthNote}", color = OpsRed, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp))
                    if (r.summary.isNotBlank()) Text(r.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    r.now?.let { n ->
                        Text("On demand: ${n.status} · ${n.summary}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (r.reportMd.isNotBlank()) FilledTonalButton(onClick = { showReport = true }) { Text("Open report") }
                        FilledTonalButton(onClick = { confirmReview = true }, enabled = online && r.enabled && !s.controls.reviewRunning && !busy) {
                            Text(if (s.controls.reviewRunning) "Review running…" else "Run review now")
                        }
                    }
                }
            }

            if (s.controls.choices.isNotEmpty()) {
                item { SectionHeader("Advisor setting") }
                item {
                    BlCard(Modifier.fillMaxWidth()) {
                        if (s.controls.advisorLabel.startsWith("custom")) Text(s.controls.advisorLabel, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            s.controls.choices.forEachIndexed { i, c ->
                                SegmentedButton(selected = s.controls.advisor == c, enabled = online && !busy,
                                    onClick = { if (s.controls.advisor != c) confirmAdvisor = c },
                                    shape = SegmentedButtonDefaults.itemShape(i, s.controls.choices.size)) {
                                    Text(when (c) { "auto" -> "Local"; "off" -> "Off"; "claude:opus" -> "Claude"; else -> c })
                                }
                            }
                        }
                        Text("Local is free and offline. Claude sends the task's transcript to Anthropic; private requests stay local either way.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmReview) ConfirmDialog("Run the code review now?", "Claude Code reviews the commits since the last review, read-only. It uses your Claude subscription.",
        confirmLabel = "Run review", destructive = false, onDismiss = { confirmReview = false }) {
        confirmReview = false; busy = true
        scope.launch { val (ok, note) = AgentOps.runReview(); busy = false; shell.toast(if (ok) "Review started — you'll get a notification" else note); AgentOps.refresh(app) }
    }
    confirmAdvisor?.let { c ->
        ConfirmDialog("Switch the advisor?", when (c) {
            "claude:opus" -> "Claude Opus will review the desktop AI's work. Task transcripts go to Anthropic; private requests stay on the local advisor."
            "off" -> "The desktop AI will work without a reviewer."
            else -> "The local model reviews the work again in a fresh context — free and offline."
        }, confirmLabel = "Switch", destructive = false, onDismiss = { confirmAdvisor = null }) {
            confirmAdvisor = null; busy = true
            scope.launch { val (_, note) = AgentOps.setAdvisor(c); busy = false; shell.toast(note); AgentOps.refresh(app) }
        }
    }
    if (showReport && s != null) ModalBottomSheet(onDismissRequest = { showReport = false }) {
        Text("Code review report", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        HtmlView(DesktopJobs.markdownToHtml(s.review.reportMd), Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 640.dp).padding(16.dp))
        TextButton(onClick = { showReport = false }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 16.dp)) { Text("Close") }
    }
}
