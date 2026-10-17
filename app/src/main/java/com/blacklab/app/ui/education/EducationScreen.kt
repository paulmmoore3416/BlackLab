package com.blacklab.app.ui.education

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blacklab.app.core.Time
import com.blacklab.app.data.AssignmentEntity
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.CertificationEntity
import com.blacklab.app.data.CourseEntity
import com.blacklab.app.data.Grades
import com.blacklab.app.study.StudyTimer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ColorDot
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.MiniBars
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.ProgressRing
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Violet
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.util.Locale

private val Tabs = listOf("Overview", "Courses", "Assignments", "Grades", "Certifications", "Study", "Flashcards")

@Composable
fun EducationScreen(initialTab: String?) {
    val data = rememberEduData()
    var tab by rememberSaveable { mutableStateOf(Tabs.indexOfFirst { it.equals(initialTab, true) }.takeIf { it >= 0 } ?: 0) }
    var editor by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { BlTopBar("Education", subtitle = "${data.courses.count { it.status == "IN_PROGRESS" }} active courses · ${data.certs.size} credentials") },
        floatingActionButton = {
            val (label, key) = when (tab) {
                0, 1 -> "Course" to "course"
                2 -> "Assignment" to "assignment"
                4 -> "Credential" to "cert"
                5 -> "Log time" to "study"
                6 -> "Deck" to "deck"
                else -> "" to ""
            }
            if (key.isNotEmpty()) ExtendedFloatingActionButton(onClick = { editor = key }, icon = { Icon(Icons.Default.Add, null) }, text = { Text(label) })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.background, divider = {}) {
                Tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (tab) {
                0 -> Overview(data) { tab = it }
                1 -> CoursesTab(data)
                2 -> AssignmentsTab(data)
                3 -> GradesTab(data)
                4 -> CertsTab(data)
                5 -> StudyTab(data)
                6 -> DecksTab(data)
            }
        }
    }
    when (editor) {
        "course" -> CourseEditor(null, data) { editor = null }
        "assignment" -> AssignmentEditor(null, data, null) { editor = null }
        "cert" -> CertEditor(null, data) { editor = null }
        "study" -> ManualStudyDialog(data) { editor = null }
        "deck" -> DeckEditor(null, data) { editor = null }
    }
}

// ------------------------------------------------------------------ Overview

@Composable
private fun Overview(data: EduData, goTab: (Int) -> Unit) {
    val s = settingsState()
    val shell = LocalShell.current
    val today = Time.today()
    val gpa = Grades.gpa(data.courses)
    val dueSoon = data.assignments.filter { it.status != "SUBMITTED" && it.status != "GRADED" && it.dueAt != null && Time.toDate(it.dueAt) <= today.plusDays(7) }.sortedBy { it.dueAt }
    val weekStart = Time.weekStart(today, s.weekStart)
    val weekMinutes = (0..6).sumOf { data.studyMinutes(weekStart.plusDays(it.toLong())) }
    val todayClasses = CalendarLogic.classOccurrences(data.courses, data.meetings, data.terms.associateBy { it.id }, today, today)
    val credits = data.courses.filter { it.kind == "COLLEGE" && it.status == "COMPLETED" }.sumOf { it.credits }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("GPA", gpa.gpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—", Icons.Default.Grade, Amber, Modifier.weight(1f)) { goTab(3) }
                StatTile("Due this week", "${dueSoon.size}", Icons.Default.Assignment, Cobalt, Modifier.weight(1f)) { goTab(2) }
                StatTile("Study / week", Time.hoursLabel(weekMinutes), Icons.Default.Timer, Violet, Modifier.weight(1f)) { goTab(5) }
            }
        }
        item { SectionHeader("Today's classes") }
        if (todayClasses.isEmpty()) item { Text("No classes today.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(todayClasses, key = { "tc${it.meeting.id}" }) { o ->
            BlCard(Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = { shell.go("course/${o.course.id}") }, accent = Color(o.course.color)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ColorDot(Color(o.course.color), 12.dp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${o.course.code} ${o.course.title}".trim(), style = MaterialTheme.typography.titleSmall)
                        Text("${Time.time(o.start)} – ${Time.time(o.end)} · ${o.meeting.kind}" + (o.meeting.location.ifBlank { o.course.location }.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionHeader("Due in the next 7 days", action = "All") { goTab(2) } }
        if (dueSoon.isEmpty()) item { Text("Nothing due. 🎉", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(dueSoon.take(6), key = { "ds${it.id}" }) { AssignmentRow(it, data) }
        item { SectionHeader("Progress") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BlCard(Modifier.weight(1f)) {
                    Text("Degree progress", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    ProgressRing((credits / s.targetCredits).toFloat(), 96.dp, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(fmt(credits), style = MaterialTheme.typography.titleLarge)
                            Text("of ${fmt(s.targetCredits)}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text("Projected GPA ${data.projectedGpa()?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
                }
                BlCard(Modifier.weight(1f)) {
                    Text("Study this week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Time.hoursLabel(weekMinutes)} / ${Time.hoursLabel(s.weeklyStudyGoalMin)}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    MiniBars(
                        (0..6).map { data.studyMinutes(weekStart.plusDays(it.toLong())).toFloat() },
                        (0..6).map { Time.dayNarrow(weekStart.plusDays(it.toLong()).dayOfWeek) },
                        MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(100.dp), highlightLast = false,
                    )
                }
            }
        }
        val upcomingCerts = data.certs.filter { it.status != "EXPIRED" && (it.expiresDay != null || it.examAt != null) }
            .sortedBy { it.examAt?.let { e -> Time.toDate(e).toEpochDay() } ?: it.expiresDay ?: Long.MAX_VALUE }.take(3)
        if (upcomingCerts.isNotEmpty()) {
            item { SectionHeader("Credentials", action = "All") { goTab(4) } }
            items(upcomingCerts, key = { "uc${it.id}" }) { CertCard(it, data, compact = true) }
        }
        val due = data.dueCards()
        if (data.decks.isNotEmpty()) item {
            SectionHeader("Flashcards")
            BlCard(Modifier.fillMaxWidth(), onClick = { goTab(6) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Style, null, tint = Violet)
                    Spacer(Modifier.width(12.dp))
                    Text(if (due > 0) "$due cards due for review" else "All caught up", Modifier.weight(1f))
                    if (due > 0) {
                        val deck = data.decks.firstOrNull { data.dueCards(it.id) > 0 }
                        FilledTonalButton(onClick = { deck?.let { shell.go("review/${it.id}") } }) { Text("Review") }
                    }
                }
            }
        }
        if (data.courses.isEmpty()) item {
            EmptyState(Icons.Default.School, "Set up your studies", "Add your college classes, certification prep and training courses. Track grades, GPA, deadlines, CEUs and study time in one place.")
        }
    }
}

fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else String.format(Locale.US, "%.1f", d)

// ------------------------------------------------------------------ Courses

@Composable
private fun CoursesTab(data: EduData) {
    var term by rememberSaveable { mutableStateOf<Long?>(-1L) }
    var manageTerms by remember { mutableStateOf(false) }
    val shown = data.courses.filter { term == -1L || it.termId == term }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(term == -1L, { term = -1L }, label = { Text("All") })
            data.terms.forEach { t -> FilterChip(term == t.id, { term = t.id }, label = { Text(t.name) }) }
            FilterChip(term == null, { term = null }, label = { Text("No term") })
            FilterChip(false, { manageTerms = true }, label = { Text("Terms…") }, leadingIcon = { Icon(Icons.Default.Edit, null, Modifier.size(14.dp)) })
        }
        if (shown.isEmpty()) EmptyState(Icons.Default.MenuBook, "No courses", "Tap + Course to add a class, certification prep or training course.")
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            items(shown, key = { it.id }) { CourseCard(it, data) }
        }
    }
    if (manageTerms) TermsDialog(data) { manageTerms = false }
}

@Composable
fun CourseCard(c: CourseEntity, data: EduData) {
    val shell = LocalShell.current
    val pct = data.percent(c.id)
    val today = Time.today()
    val next = CalendarLogic.classOccurrences(listOf(c), data.meetings.filter { it.courseId == c.id }, data.terms.associateBy { it.id }, today, today.plusDays(7))
        .firstOrNull { it.end > java.time.LocalDateTime.now() }
    Surface(onClick = { shell.go("course/${c.id}") }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Box(Modifier.width(6.dp).height(120.dp).background(Color(c.color)))
            Column(Modifier.padding(14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (c.code.isNotBlank()) Text(c.code, style = MaterialTheme.typography.labelLarge, color = Color(c.color))
                        Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.status == "COMPLETED" && c.finalGrade.isNotBlank()) GradeBadge(c.finalGrade, null)
                    else pct?.let { GradeBadge(Grades.letter(it), it) }
                }
                Text(listOfNotNull(c.instructor.takeIf { it.isNotBlank() }, c.institution.takeIf { it.isNotBlank() }, if (c.kind == "COLLEGE") "${fmt(c.credits)} cr" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Pill(CourseKinds.first { it.first == c.kind }.second, Color(c.color))
                    Pill(CourseStatuses.first { it.first == c.status }.second, if (c.status == "COMPLETED") LocalBlColors.current.success else MaterialTheme.colorScheme.onSurfaceVariant)
                    next?.let { Pill("Next: ${Time.relativeDay(it.start.toLocalDate())} ${Time.time(it.start)}", MaterialTheme.colorScheme.tertiary) }
                }
                if (c.modulesTotal > 0) {
                    LinearProgressIndicator(progress = { c.modulesDone.toFloat() / c.modulesTotal }, color = Color(c.color),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp)))
                    Text("${c.modulesDone}/${c.modulesTotal} modules", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun GradeBadge(letter: String, percent: Double?) {
    val color = when (letter.firstOrNull()) { 'A' -> LocalBlColors.current.success; 'B' -> Cobalt; 'C' -> Amber; else -> LocalBlColors.current.danger }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(letter, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
        percent?.let { Text(String.format(Locale.US, "%.1f%%", it), style = MaterialTheme.typography.labelSmall, color = color) }
    }
}

// ------------------------------------------------------------------ Assignments

@Composable
private fun AssignmentsTab(data: EduData) {
    var filter by rememberSaveable { mutableStateOf("UPCOMING") }
    val now = System.currentTimeMillis()
    val shown = data.assignments.filter { a ->
        val done = a.status == "SUBMITTED" || a.status == "GRADED"
        when (filter) {
            "UPCOMING" -> !done
            "OVERDUE" -> !done && (a.dueAt ?: Long.MAX_VALUE) < now
            "DONE" -> done
            else -> true
        }
    }.sortedWith(compareBy<AssignmentEntity> { it.dueAt ?: Long.MAX_VALUE }.let { if (filter == "DONE") it.reversed() else it })
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("UPCOMING" to "Upcoming", "OVERDUE" to "Overdue", "DONE" to "Completed", "ALL" to "All").forEach { (k, l) ->
                FilterChip(filter == k, { filter = k }, label = { Text(l) })
            }
        }
        if (shown.isEmpty()) EmptyState(Icons.Default.Assignment, "No assignments", if (data.courses.isEmpty()) "Add a course first." else "Tap + Assignment to track homework, exams, projects and modules.")
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            var lastLabel = ""
            shown.forEach { a ->
                val label = a.dueAt?.let { Time.relativeDay(Time.toDate(it)).let { r -> if (Time.toDate(it) < Time.today() && filter != "DONE") "Overdue" else r } } ?: "No due date"
                if (label != lastLabel) { lastLabel = label; item(key = "h$label${a.id}") { SectionHeader(label) } }
                item(key = a.id) { AssignmentRow(a, data) }
            }
        }
    }
}

@Composable
fun AssignmentRow(a: AssignmentEntity, data: EduData) {
    val app = rememberApp()
    var edit by remember { mutableStateOf(false) }
    val c = data.courseById[a.courseId]
    val done = a.status == "SUBMITTED" || a.status == "GRADED"
    val overdue = !done && (a.dueAt ?: Long.MAX_VALUE) < System.currentTimeMillis()
    Surface(onClick = { edit = true }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(done, { checked ->
                app.io { app.db.education().updateAssignment(a.copy(status = if (checked) "SUBMITTED" else "TODO", completedAt = if (checked) System.currentTimeMillis() else null)); app.requestReminderSync() }
            })
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else null)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    c?.let { Pill(it.code.ifBlank { it.title.take(14) }, Color(it.color)) }
                    Text(a.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.dueAt?.let { Text("· " + Time.date(it) + " " + Time.time(it), style = MaterialTheme.typography.labelSmall, color = if (overdue) LocalBlColors.current.danger else MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (a.pointsEarned != null && (a.pointsPossible ?: 0.0) > 0) {
                val pct = a.pointsEarned / a.pointsPossible!! * 100
                Text(String.format(Locale.US, "%.0f%%", pct), style = MaterialTheme.typography.titleSmall, color = if (pct >= 90) LocalBlColors.current.success else if (pct >= 70) Amber else LocalBlColors.current.danger)
            }
        }
    }
    if (edit) AssignmentEditor(a, data, a.courseId) { edit = false }
}

// ------------------------------------------------------------------ Grades

@Composable
private fun GradesTab(data: EduData) {
    val s = settingsState()
    val gpa = Grades.gpa(data.courses)
    var whatIf by remember { mutableStateOf(false) }
    val college = data.courses.filter { it.kind == "COLLEGE" && it.status != "DROPPED" }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            BlCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(((gpa.gpa ?: 0.0) / 4.0).toFloat(), 110.dp, 10.dp, Amber) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(gpa.gpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—", style = MaterialTheme.typography.headlineMedium)
                            Text("GPA", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cumulative (completed)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Projected: ${data.projectedGpa()?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}", style = MaterialTheme.typography.titleMedium)
                        Text("Credits: ${fmt(gpa.credits)} graded · ${fmt(college.filter { it.status == "COMPLETED" }.sumOf { it.credits })} earned of ${fmt(s.targetCredits)}", style = MaterialTheme.typography.bodySmall)
                        Text("Quality points: ${fmt(gpa.qualityPoints)}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { whatIf = true }) { Icon(Icons.Default.Calculate, null); Spacer(Modifier.width(6.dp)); Text("What-if GPA") }
                    }
                }
            }
        }
        val byTerm = college.groupBy { it.termId }
        data.terms.forEach { t ->
            val list = byTerm[t.id].orEmpty()
            if (list.isEmpty()) return@forEach
            item(key = "t${t.id}") {
                val tg = Grades.gpa(list)
                SectionHeader("${t.name}${t.institution.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""} — GPA ${tg.gpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}")
            }
            items(list, key = { "g${it.id}" }) { GradeRow(it, data) }
        }
        val noTerm = byTerm[null].orEmpty()
        if (noTerm.isNotEmpty()) {
            item { SectionHeader("Other courses") }
            items(noTerm, key = { "g${it.id}" }) { GradeRow(it, data) }
        }
        item {
            SectionHeader("Grade scale")
            BlCard(Modifier.fillMaxWidth()) {
                Grades.scale.chunked(4).forEach { row ->
                    Row { row.forEach { st -> Text("${st.letter}  ≥${st.minPercent.toInt()}%  ${st.points}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) } }
                }
            }
        }
    }
    if (whatIf) WhatIfDialog(data) { whatIf = false }
}

@Composable
private fun GradeRow(c: CourseEntity, data: EduData) {
    val shell = LocalShell.current
    val pct = data.percent(c.id)
    val letter = if (c.status == "COMPLETED") c.finalGrade else pct?.let { Grades.letter(it) } ?: ""
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { shell.go("course/${c.id}") }.padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ColorDot(Color(c.color), 10.dp); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("${c.code} ${c.title}".trim(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmt(c.credits)} credits · ${if (c.status == "COMPLETED") "Final" else "Current"}${if (c.passFail) " · Pass/Fail" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pct?.takeIf { c.status != "COMPLETED" }?.let { Text(String.format(Locale.US, "%.1f%%  ", it), style = MaterialTheme.typography.labelMedium) }
        Text(letter.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

// ------------------------------------------------------------------ Certifications

@Composable
private fun CertsTab(data: EduData) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        if (data.certs.isEmpty()) item {
            EmptyState(Icons.Default.WorkspacePremium, "Track your credentials", "Add certifications you hold or are working toward — CFM, FMP, SFP, CEM, LEED, PMP, OSHA… BlackLab reminds you of exams and renewals and logs your CEUs.")
        }
        items(data.certs, key = { it.id }) { CertCard(it, data) }
    }
}

@Composable
fun CertCard(c: CertificationEntity, data: EduData, compact: Boolean = false) {
    var edit by remember { mutableStateOf(false) }
    val today = Time.today().toEpochDay()
    val daysLeft = c.expiresDay?.let { it - today }
    val statusColor = when (c.status) {
        "EARNED" -> LocalBlColors.current.success; "EXPIRED" -> LocalBlColors.current.danger; "EXAM_SCHEDULED" -> Amber; else -> Cobalt
    }
    Surface(onClick = { edit = true }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WorkspacePremium, null, tint = statusColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    if (c.issuer.isNotBlank()) Text(c.issuer, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(CertStatuses.first { it.first == c.status }.second, statusColor)
            }
            if (!compact || c.examAt != null || daysLeft != null) Spacer(Modifier.height(6.dp))
            c.examAt?.takeIf { c.status != "EARNED" }?.let { Text("📝 Exam ${Time.dateTime(it)}", style = MaterialTheme.typography.bodySmall) }
            daysLeft?.let { d ->
                Text(
                    if (d < 0) "Expired ${-d} days ago" else "Renew by ${Time.date(java.time.LocalDate.ofEpochDay(c.expiresDay!!))} · $d days left",
                    style = MaterialTheme.typography.bodySmall,
                    color = when { d < 30 -> LocalBlColors.current.danger; d < 90 -> Amber; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                )
            }
            if (c.ceuRequired > 0 && !compact) {
                val earned = data.ceuEarned(c)
                LinearProgressIndicator(progress = { (earned / c.ceuRequired).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(4.dp)))
                Text("CEUs ${fmt(earned)} / ${fmt(c.ceuRequired)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (c.credentialId.isNotBlank() && !compact) Text("ID ${c.credentialId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (edit) CertEditor(c, data) { edit = false }
}

// ------------------------------------------------------------------ Study

@Composable
private fun StudyTab(data: EduData) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val s = settingsState()
    val timer = remember { StudyTimer.get(ctx) }
    val st by timer.state.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st.running) {
        while (st.running) { now = System.currentTimeMillis(); timer.checkCompletion(); delay(250) }
        now = System.currentTimeMillis()
    }
    val remaining = if (st.phase == StudyTimer.Phase.IDLE) s.pomodoroWork * 60_000L else st.remaining(now)
    val total = if (st.phase == StudyTimer.Phase.IDLE) s.pomodoroWork * 60_000L else st.totalMs.coerceAtLeast(1)
    val phaseColor = when (st.phase) { StudyTimer.Phase.BREAK, StudyTimer.Phase.LONG_BREAK -> LocalBlColors.current.success; else -> MaterialTheme.colorScheme.primary }
    val today = Time.today()
    val weekStart = Time.weekStart(today, s.weekStart)
    val week = (0..6).map { data.studyMinutes(weekStart.plusDays(it.toLong())) }
    val streak = run { var d = today; var n = 0; if (data.studyMinutes(d) == 0) d = d.minusDays(1); while (data.studyMinutes(d) > 0) { n++; d = d.minusDays(1) }; n }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        item {
            BlCard(Modifier.fillMaxWidth()) {
                Text(when (st.phase) { StudyTimer.Phase.IDLE -> "Focus timer"; StudyTimer.Phase.FOCUS -> "Focus"; StudyTimer.Phase.BREAK -> "Short break"; StudyTimer.Phase.LONG_BREAK -> "Long break" },
                    style = MaterialTheme.typography.titleMedium, color = phaseColor, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
                ProgressRing(1f - remaining.toFloat() / total, 220.dp, 12.dp, phaseColor, Modifier.align(Alignment.CenterHorizontally)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(String.format(Locale.US, "%02d:%02d", remaining / 60_000, (remaining / 1000) % 60), fontSize = 52.sp, fontWeight = FontWeight.Light)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(4) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (i < st.completedFocus % 4 || (st.completedFocus > 0 && st.completedFocus % 4 == 0 && st.phase == StudyTimer.Phase.LONG_BREAK)) phaseColor else MaterialTheme.colorScheme.surfaceContainerHighest)) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                DropdownField("Studying for", listOf<Pair<Long?, String>>(null to "General study") + data.courses.filter { it.status != "COMPLETED" && it.status != "DROPPED" }.map { it.id to "${it.code} ${it.title}".trim() },
                    st.courseId, { timer.setCourse(it) })
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { timer.reset() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Reset", maxLines = 1) }
                    Button(onClick = { if (st.running) timer.pause() else timer.start(s.activeProfile.name) }, modifier = Modifier.weight(1.4f)) {
                        Icon(if (st.running) Icons.Default.Pause else Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(if (st.running) "Pause" else if (st.phase == StudyTimer.Phase.IDLE) "Start focus" else "Resume")
                    }
                    OutlinedButton(onClick = { timer.skip() }, modifier = Modifier.weight(1f), enabled = st.phase != StudyTimer.Phase.IDLE, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Skip", maxLines = 1) }
                }
                Text("${s.pomodoroWork} min focus · ${s.pomodoroBreak} min break · ${s.pomodoroLongBreak} min long break (Settings)", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                StatTile("Today", Time.hoursLabel(data.studyMinutes(today)), Icons.Default.Today, Cobalt, Modifier.weight(1f))
                StatTile("This week", "${Time.hoursLabel(week.sum())}", Icons.Default.DateRange, Violet, Modifier.weight(1f))
                StatTile("Streak", "$streak d", Icons.Default.LocalFireDepartment, Amber, Modifier.weight(1f))
            }
        }
        item {
            SectionHeader("This week · goal ${Time.hoursLabel(s.weeklyStudyGoalMin)}")
            BlCard(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(progress = { (week.sum().toFloat() / s.weeklyStudyGoalMin).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.height(12.dp))
                MiniBars(week.map { it.toFloat() }, (0..6).map { Time.dayNarrow(weekStart.plusDays(it.toLong()).dayOfWeek) }, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(110.dp), highlightLast = false)
                val byCourse = data.sessions.filter { Time.toDate(it.startAt) >= weekStart }.groupBy { it.courseId }.mapValues { e -> e.value.sumOf { it.minutes } }
                if (byCourse.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                byCourse.entries.sortedByDescending { it.value }.forEach { (cid, min) ->
                    val c = cid?.let { data.courseById[it] }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        ColorDot(c?.let { Color(it.color) } ?: MaterialTheme.colorScheme.onSurfaceVariant, 10.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(c?.let { "${it.code} ${it.title}".trim() } ?: "General", Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodySmall)
                        Text(Time.hoursLabel(min), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        item { SectionHeader("Recent sessions") }
        if (data.sessions.isEmpty()) item { Text("Completed focus sessions are logged automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(data.sessions.take(20), key = { "ss${it.id}" }) { ses ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ses.kind == "FOCUS") Icons.Default.Timer else Icons.Default.EditNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ses.courseId?.let { data.courseById[it] }?.let { "${it.code} ${it.title}".trim() } ?: "General study", maxLines = 1)
                    Text(Time.dateTime(ses.startAt) + if (ses.notes.isNotBlank()) " · ${ses.notes}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(Time.hoursLabel(ses.minutes), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { app.io { app.db.education().deleteSession(ses) } }) { Icon(Icons.Default.Close, "Delete", Modifier.size(16.dp)) }
            }
        }
    }
}

// ------------------------------------------------------------------ Flashcards

@Composable
private fun DecksTab(data: EduData) {
    val shell = LocalShell.current
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        if (data.decks.isEmpty()) item {
            EmptyState(Icons.Default.Style, "Learn it for good", "Create flashcard decks per course or exam. BlackLab schedules reviews with spaced repetition (SM-2) so you study what you're about to forget.")
        }
        items(data.decks, key = { it.id }) { d ->
            val cards = data.cards.count { it.deckId == d.id }
            val due = data.dueCards(d.id)
            val course = d.courseId?.let { data.courseById[it] }
            Surface(onClick = { shell.go("deck/${d.id}") }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Style, null, tint = course?.let { Color(it.color) } ?: Violet)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall)
                        Text("$cards cards" + (course?.let { " · ${it.code.ifBlank { it.title }}" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (due > 0) FilledTonalButton(onClick = { shell.go("review/${d.id}") }) { Text("Review $due") }
                    else TextButton(onClick = { shell.go("deck/${d.id}") }) { Text("Open") }
                }
            }
        }
    }
}

@Suppress("unused")
private fun LazyListScope.spacer() = item { Spacer(Modifier.height(8.dp)) }

@Suppress("unused")
private fun daysUntil(d: LocalDate) = d.toEpochDay() - Time.today().toEpochDay()
