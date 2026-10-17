package com.blacklab.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.TaskOps
import com.blacklab.app.recorder.RecState
import com.blacklab.app.recorder.RecorderService
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.CodeResultDialog
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.ProfileBadge
import com.blacklab.app.ui.components.ScanDoneDialog
import com.blacklab.app.ui.components.ScanResult
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberCodeScanner
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.education.rememberEduData
import com.blacklab.app.ui.journal.promptOfDay
import com.blacklab.app.ui.schedule.CalKind
import com.blacklab.app.ui.schedule.ItemRow
import com.blacklab.app.ui.schedule.rememberCalendarItems
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Coral
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Violet
import com.google.mlkit.vision.barcode.common.Barcode
import java.time.LocalDate

private data class QuickAction(val label: String, val icon: ImageVector, val color: Color, val onClick: () -> Unit)

@Composable
fun HomeScreen() {
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val profile = s.activeProfile
    val today = Time.today()
    val rec by RecorderService.state.collectAsState()
    val tasks by remember(profile) { app.db.tasks().observe(profile.name) }.collectAsState(emptyList())
    val docs by remember(profile) { app.db.documents().observe(profile.name) }.collectAsState(emptyList())
    val agenda = rememberCalendarItems(today, today)
    val edu = rememberEduData()
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var code by remember { mutableStateOf<Barcode?>(null) }
    val docScanner = rememberDocumentScanner(app, profile) { scan = it }
    val codeScanner = rememberCodeScanner { code = it }

    val todayEpoch = today.toEpochDay()
    val open = tasks.filter { !it.completed }
    val focus = open.filter { it.myDayDay == todayEpoch || (it.dueDay != null && it.dueDay <= todayEpoch) }
        .sortedWith(compareBy({ it.dueDay ?: Long.MAX_VALUE }, { -it.priority }))
    val overdue = open.count { it.dueDay != null && it.dueDay < todayEpoch }

    val actions = listOf(
        QuickAction("Photo", Icons.Default.PhotoCamera, Mint) { shell.go("camera?mode=photo") },
        QuickAction("Video", Icons.Default.Videocam, Coral) { shell.go("camera?mode=video") },
        QuickAction("Record", Icons.Default.Mic, Color(0xFFE53935)) { shell.go("recorder") },
        QuickAction("Scan", Icons.Default.DocumentScanner, Cobalt) { docScanner() },
        QuickAction("Document", Icons.Default.NoteAdd, Violet) { shell.go("documents") },
        QuickAction("Journal", Icons.Default.AutoStories, Amber) { shell.go("journal/0") },
        QuickAction("Task", Icons.Default.AddTask, Mint) { shell.go("task/0") },
        QuickAction("Event", Icons.Default.EventAvailable, Cobalt) { shell.go("event/0?start=${Time.millis(today, ((java.time.LocalTime.now().hour + 1) * 60).coerceAtMost(23 * 60))}") },
        QuickAction("QR / Asset", Icons.Default.QrCodeScanner, Coral) { codeScanner() },
        QuickAction("Focus", Icons.Default.Timer, Violet) { shell.go("education?tab=Study") },
    )

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = shell.openDrawer) { Icon(Icons.Default.Menu, "Menu") }
                Spacer(Modifier.weight(1f))
                ProfileBadge(profile, app.settings.profileName(profile)) { shell.switchProfile(profile.other) }
                IconButton(onClick = { shell.go("settings") }) { Icon(Icons.Default.Settings, "Settings") }
            }
            Text("${Time.greeting()}${s.userName.takeIf { it.isNotBlank() }?.let { ", ${it.substringBefore(' ')}" } ?: ""}",
                style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 4.dp))
            Text(Time.longDate(today) + (s.jobTitle.takeIf { it.isNotBlank() && profile.name == "PROFESSIONAL" }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rec.status != RecState.Status.IDLE) item {
            Surface(onClick = { shell.go("recorder") }, shape = RoundedCornerShape(16.dp), color = Color(0xFFE53935).copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFE53935)))
                    Spacer(Modifier.width(10.dp))
                    Text(if (rec.status == RecState.Status.PAUSED) "Recording paused" else "Recording in progress", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(Time.duration(rec.elapsedMs), fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            // 5 × 2 grid of quick actions
            actions.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { a ->
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable(onClick = a.onClick).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(a.color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                                Icon(a.icon, a.label, tint = a.color)
                            }
                            Text(a.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
        item { SectionHeader("Today", action = "Schedule") { shell.go("schedule") } }
        item {
            if (agenda.isEmpty()) BlCard(Modifier.fillMaxWidth()) { Text("Nothing on the calendar today.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else Column { agenda.filter { it.kind != CalKind.TASK }.take(6).forEach { ItemRow(it, today) { when (it.kind) { CalKind.EVENT -> shell.go("event/${it.refId}"); CalKind.CLASS, CalKind.ASSIGNMENT, CalKind.EXAM -> shell.go("course/${it.refId}"); else -> shell.go("schedule") } } } }
        }
        item {
            SectionHeader("Focus · ${focus.size} task(s)" + if (overdue > 0) " · $overdue overdue" else "", action = "To-Do") { shell.go("tasks") }
        }
        item {
            BlCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 4.dp)) {
                if (focus.isEmpty()) Text("No tasks due. Add one to My Day from To-Do.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
                focus.take(6).forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { shell.go("task/${t.id}") }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(false, { app.io { TaskOps.setCompleted(app, t, true) }; shell.toast("Completed “${t.title.take(30)}”") })
                        Column(Modifier.weight(1f)) {
                            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            t.dueDay?.let { d ->
                                Text(Time.relativeDay(LocalDate.ofEpochDay(d)) + (t.dueMinute?.let { " " + Time.time(it) } ?: ""), style = MaterialTheme.typography.labelSmall,
                                    color = if (d < todayEpoch) LocalBlColors.current.danger else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (t.important) Icon(Icons.Default.Star, null, tint = Amber, modifier = Modifier.padding(end = 12.dp).size(18.dp))
                    }
                }
            }
        }
        // Education snapshot
        val classes = CalendarLogic.classOccurrences(edu.courses, edu.meetings, edu.terms.associateBy { it.id }, today, today.plusDays(6)).filter { it.end > java.time.LocalDateTime.now() }
        val dueSoon = edu.assignments.filter { it.status != "SUBMITTED" && it.status != "GRADED" && it.dueAt != null && Time.toDate(it.dueAt) <= today.plusDays(5) }.sortedBy { it.dueAt }
        val certAlerts = edu.certs.filter { c -> (c.expiresDay != null && c.expiresDay - todayEpoch in 0..90) || (c.examAt != null && Time.toDate(c.examAt) >= today && Time.toDate(c.examAt) <= today.plusDays(30)) }
        if (classes.isNotEmpty() || dueSoon.isNotEmpty() || certAlerts.isNotEmpty()) {
            item { SectionHeader("Learning", action = "Education") { shell.go("education") } }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    classes.firstOrNull()?.let { o ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shell.go("course/${o.course.id}") }.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.School, null, tint = Color(o.course.color))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Next class: ${o.course.code.ifBlank { o.course.title }}", style = MaterialTheme.typography.titleSmall)
                                Text("${Time.relativeDay(o.start.toLocalDate())} ${Time.time(o.start)}" + (o.meeting.location.ifBlank { o.course.location }.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    dueSoon.take(3).forEach { a ->
                        val c = edu.courseById[a.courseId]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shell.go("course/${a.courseId}") }.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.Assignment, null, tint = c?.let { Color(it.color) } ?: Cobalt, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(a.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Time.relativeDay(Time.toDate(a.dueAt!!)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    certAlerts.take(2).forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { shell.go("education?tab=Certifications") }.padding(vertical = 4.dp)) {
                            Icon(Icons.Default.WorkspacePremium, null, tint = Amber, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(c.name.substringBefore(" —"), Modifier.weight(1f), maxLines = 1)
                            Text(c.examAt?.takeIf { c.status != "EARNED" }?.let { "Exam ${Time.relativeDay(Time.toDate(it))}" } ?: "Renew in ${c.expiresDay!! - todayEpoch}d",
                                style = MaterialTheme.typography.labelMedium, color = Amber)
                        }
                    }
                }
            }
        }
        if (docs.isNotEmpty()) {
            item { SectionHeader("Recent documents", action = "All") { shell.go("documents") } }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(docs.sortedByDescending { it.updatedAt }.take(8), key = { it.id }) { d ->
                        Surface(onClick = { shell.go("doc/${d.id}") }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.width(150.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                                Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                Text(Time.ago(d.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Reflect")
            BlCard(Modifier.fillMaxWidth(), onClick = { shell.go("journal") }) {
                Text(promptOfDay(profile), style = MaterialTheme.typography.titleSmall)
                Text("Open journal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (!app.storage.isPublic) item {
            BlCard(Modifier.fillMaxWidth().padding(top = 12.dp), accent = Amber) {
                Text("Files are in app storage", style = MaterialTheme.typography.titleSmall)
                Text("Allow All-files access so BlackLab can keep everything in Internal storage › BlackLab (visible in Files, kept across reinstalls).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { shell.go("settings") }, modifier = Modifier.padding(top = 6.dp)) { Text("Fix in Settings") }
            }
        }
    }
    scan?.let { r -> ScanDoneDialog(r) { scan = null } }
    code?.let { c -> CodeResultDialog(c) { code = null } }
}
