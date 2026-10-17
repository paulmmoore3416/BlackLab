package com.blacklab.app.ui.education

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Time
import com.blacklab.app.data.ClassMeetingEntity
import com.blacklab.app.data.FlashcardEntity
import com.blacklab.app.data.Grades
import com.blacklab.app.data.Srs
import com.blacklab.app.study.StudyTimer
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.EmptyState
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.theme.LocalBlColors
import java.time.DayOfWeek
import java.util.Locale

@Composable
fun CourseDetailScreen(id: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val data = rememberEduData()
    val course = data.courseById[id]
    val notes by remember(id) { app.db.documents().observeForCourse(id) }.collectAsState(emptyList())
    var edit by remember { mutableStateOf<String?>(null) }
    var meetingEdit by remember { mutableStateOf<ClassMeetingEntity?>(null) }

    if (course == null) {
        Scaffold(topBar = { BlTopBar("Course", onBack = { shell.back() }) }) { pad -> Box(Modifier.padding(pad)) { EmptyState(Icons.Default.School, "Course not found", "It may have been deleted.") } }
        return
    }
    val color = Color(course.color)
    val assignments = data.assignmentsFor(id).sortedBy { it.dueAt ?: Long.MAX_VALUE }
    val cats = data.categoriesFor(id)
    val pct = data.percent(id)
    val needed = Grades.neededOnRemaining(assignments, cats, course.targetPercent)
    val meetings = data.meetings.filter { it.courseId == id }.sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute }))
    val studied = data.sessions.filter { it.courseId == id }.sumOf { it.minutes }

    Scaffold(
        topBar = {
            BlTopBar(course.code.ifBlank { "Course" }, onBack = { shell.back() }, actions = {
                IconButton(onClick = { edit = "course" }) { Icon(Icons.Default.Edit, "Edit") }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { edit = "assignment" }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Assignment") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            item {
                BlCard(Modifier.fillMaxWidth(), accent = color) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(course.title, style = MaterialTheme.typography.headlineSmall)
                            Text(listOfNotNull(course.institution.takeIf { it.isNotBlank() }, data.terms.firstOrNull { it.id == course.termId }?.name,
                                if (course.kind == "COLLEGE") "${fmt(course.credits)} credits" else if (course.credits > 0) "${fmt(course.credits)} hrs" else null).joinToString(" · "),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Pill(CourseKinds.first { it.first == course.kind }.second, color)
                                Pill(CourseStatuses.first { it.first == course.status }.second)
                            }
                        }
                        if (course.status == "COMPLETED" && course.finalGrade.isNotBlank()) GradeBadge(course.finalGrade, null)
                        else pct?.let { GradeBadge(Grades.letter(it), it) }
                    }
                    if (course.instructor.isNotBlank() || course.officeHours.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(course.instructor.ifBlank { "Instructor" })
                                if (course.officeHours.isNotBlank()) Text("Office hours: ${course.officeHours}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (course.instructorEmail.isNotBlank()) IconButton(onClick = { Files.email(ctx, course.instructorEmail, "${course.code} ${course.title}".trim()) }) { Icon(Icons.Default.Email, "Email instructor") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        if (course.onlineUrl.isNotBlank()) FilledTonalButton(onClick = { Files.openUrl(ctx, course.onlineUrl) }) { Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Course site") }
                        if (course.location.isNotBlank()) OutlinedButton(onClick = { Files.openMap(ctx, course.location) }) { Icon(Icons.Default.LocationOn, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(course.location, maxLines = 1) }
                    }
                }
            }

            if (course.modulesTotal > 0) item {
                SectionHeader("Progress")
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${course.modulesDone} of ${course.modulesTotal} modules", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { app.io { app.db.education().updateCourse(course.copy(modulesDone = (course.modulesDone - 1).coerceAtLeast(0))) } }) { Icon(Icons.Default.Remove, "Less") }
                        IconButton(onClick = {
                            val done = (course.modulesDone + 1).coerceAtMost(course.modulesTotal)
                            app.io { app.db.education().updateCourse(course.copy(modulesDone = done, status = if (done == course.modulesTotal && course.kind != "COLLEGE") "COMPLETED" else course.status)) }
                        }) { Icon(Icons.Default.Add, "More") }
                    }
                    LinearProgressIndicator(progress = { course.modulesDone.toFloat() / course.modulesTotal }, color = color, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
            }

            item { SectionHeader("Class schedule", action = "Add") { meetingEdit = ClassMeetingEntity(courseId = id, dayOfWeek = 1, startMinute = 0, endMinute = 0) } }
            if (meetings.isEmpty()) item { Text("No meeting times. Add them to see classes on your calendar and get reminders.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            items(meetings, key = { "m${it.id}" }) { m ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { meetingEdit = m }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Time.dayShort(DayOfWeek.of(m.dayOfWeek)), Modifier.width(48.dp), fontWeight = FontWeight.Bold, color = color)
                    Text("${Time.time(m.startMinute)} – ${Time.time(m.endMinute)}", Modifier.weight(1f))
                    Text(listOf(m.kind, m.location).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { SectionHeader("Grade", action = "Weights") { edit = "categories" } }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pct?.let { String.format(Locale.US, "%.1f%%", it) } ?: "No graded work yet", style = MaterialTheme.typography.headlineSmall)
                            Text("Target ${fmt(course.targetPercent)}% (${Grades.letter(course.targetPercent)})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        needed?.let { n ->
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (n > 100) "Out of reach" else if (n <= 0) "Locked in ✓" else String.format(Locale.US, "%.1f%%", n), style = MaterialTheme.typography.titleMedium,
                                    color = when { n > 100 -> LocalBlColors.current.danger; n > 90 -> LocalBlColors.current.warning; else -> LocalBlColors.current.success })
                                Text("needed on the rest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (cats.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        cats.forEach { cat ->
                            val graded = assignments.filter { it.categoryId == cat.id && it.pointsEarned != null && (it.pointsPossible ?: 0.0) > 0 }
                            val cp = if (graded.isEmpty()) null else graded.sumOf { it.pointsEarned!! } / graded.sumOf { it.pointsPossible!! } * 100
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(cat.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${fmt(cat.weight)}%", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(cp?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—", Modifier.width(64.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else Text("Tip: add grade weights from your syllabus for an accurate weighted grade.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }

            item { SectionHeader("Assignments & exams (${assignments.size})") }
            if (assignments.isEmpty()) item { Text("Nothing yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(assignments, key = { "a${it.id}" }) { AssignmentRow(it, data) }

            item { SectionHeader("Notes (${notes.size})", action = "New note") { shell.go("doc/0?template=cornell&course=$id") } }
            items(notes, key = { "n${it.id}" }) { d ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { shell.go("doc/${d.id}") }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = color, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(d.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Time.ago(d.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item {
                SectionHeader("Study")
                BlCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${Time.hoursLabel(studied)} studied", style = MaterialTheme.typography.titleMedium)
                            Text("${data.decks.count { it.courseId == id }} flashcard deck(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { StudyTimer.get(ctx).setCourse(id); shell.go("education?tab=Study") }) { Icon(Icons.Default.Timer, null); Spacer(Modifier.width(6.dp)); Text("Focus") }
                    }
                }
            }
            if (course.notes.isNotBlank()) item { SectionHeader("Course notes"); Text(course.notes) }
        }
    }
    when (edit) {
        "course" -> CourseEditor(course, data) { edit = null }
        "assignment" -> AssignmentEditor(null, data, id) { edit = null }
        "categories" -> CategoriesEditor(id, cats) { edit = null }
    }
    meetingEdit?.let { m -> MeetingEditor(m.takeIf { it.id != 0L }, id) { meetingEdit = null } }
}

// ------------------------------------------------------------------ Flashcards

@Composable
fun DeckScreen(id: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val data = rememberEduData()
    val deck = data.decks.firstOrNull { it.id == id }
    val cards by remember(id) { app.db.education().observeCards(id) }.collectAsState(emptyList())
    var editCard by remember { mutableStateOf<FlashcardEntity?>(null) }
    var editDeck by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val due = cards.count { it.dueDay <= Time.today().toEpochDay() }
    Scaffold(
        topBar = {
            BlTopBar(deck?.name ?: "Deck", subtitle = "${cards.size} cards · $due due", onBack = { shell.back() }, actions = {
                IconButton(onClick = { editDeck = true }) { Icon(Icons.Default.Edit, "Edit deck") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete deck") }
            })
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { editCard = FlashcardEntity(deckId = id, front = "", back = "", dueDay = Time.today().toEpochDay()) }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Card") }) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            item {
                Button(onClick = { shell.go("review/$id") }, enabled = cards.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (due > 0) "Review $due due card(s)" else "Practice all cards")
                }
            }
            if (cards.isEmpty()) item { EmptyState(Icons.Default.Style, "No cards yet", "Add a question on the front and the answer on the back.") }
            items(cards, key = { it.id }) { c ->
                Surface(onClick = { editCard = c }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.front, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(c.back, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        Text(if (c.dueDay <= Time.today().toEpochDay()) "Due now" else "Next review ${Time.relativeDay(java.time.LocalDate.ofEpochDay(c.dueDay))} · every ${c.intervalDays}d",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
    editCard?.let { c ->
        var front by remember(c) { mutableStateOf(c.front) }
        var back by remember(c) { mutableStateOf(c.back) }
        AlertDialog(
            onDismissRequest = { editCard = null },
            title = { Text(if (c.id == 0L) "New card" else "Edit card") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(front, { front = it }, label = { Text("Front (question / term)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(back, { back = it }, label = { Text("Back (answer / definition)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    if (c.id != 0L) TextButton(onClick = { app.io { app.db.education().deleteCard(c) }; editCard = null }) { Text("Delete card", color = LocalBlColors.current.danger) }
                }
            },
            confirmButton = {
                TextButton(enabled = front.isNotBlank() && back.isNotBlank(), onClick = {
                    val n = c.copy(front = front.trim(), back = back.trim())
                    app.io { if (n.id == 0L) app.db.education().insertCard(n) else app.db.education().updateCard(n) }
                    editCard = if (c.id == 0L) FlashcardEntity(deckId = id, front = "", back = "", dueDay = Time.today().toEpochDay()) else null
                }) { Text(if (c.id == 0L) "Save & next" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editCard = null }) { Text("Done") } },
        )
    }
    if (editDeck && deck != null) DeckEditor(deck, data) { editDeck = false }
    if (confirmDelete && deck != null) ConfirmDialog("Delete deck?", "“${deck.name}” and its ${cards.size} cards will be deleted.", onDismiss = { confirmDelete = false }) {
        app.io { app.db.education().deleteDeck(deck) }; shell.back()
    }
}

@Composable
fun ReviewScreen(deckId: Long) {
    val app = rememberApp()
    val shell = LocalShell.current
    val queue = remember { mutableStateListOf<FlashcardEntity>() }
    var loaded by remember { mutableStateOf(false) }
    var flipped by remember { mutableStateOf(false) }
    var reviewed by remember { mutableIntStateOf(0) }
    var correct by remember { mutableIntStateOf(0) }
    LaunchedEffect(deckId) {
        val due = app.db.education().dueCards(deckId, Time.today().toEpochDay())
        queue.addAll(due.ifEmpty { app.db.education().dueCards(deckId, Long.MAX_VALUE / 2).shuffled().take(20) })
        loaded = true
    }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(350), label = "flip")
    Scaffold(topBar = { BlTopBar("Review", subtitle = if (loaded) "${queue.size} left · $reviewed reviewed" else null, onBack = { shell.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val card = queue.firstOrNull()
            if (loaded && card == null) {
                EmptyState(Icons.Default.EmojiEvents, "Session complete", if (reviewed > 0) "$reviewed cards reviewed · ${correct * 100 / reviewed.coerceAtLeast(1)}% recalled" else "No cards to review right now.") {
                    Button(onClick = { shell.back() }) { Text("Done") }
                }
                return@Column
            }
            if (card == null) return@Column
            Box(
                Modifier.fillMaxWidth().weight(1f).padding(vertical = 12.dp)
                    .graphicsLayer { rotationY = rotation; cameraDistance = 14 * density }
                    .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { flipped = !flipped }.padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(Modifier.graphicsLayer { rotationY = if (rotation > 90f) 180f else 0f }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (rotation > 90f) "ANSWER" else "QUESTION", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(if (rotation > 90f) card.back else card.front, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (rotation <= 90f) Text("Tap to reveal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                }
            }
            if (flipped) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("Again" to 0, "Hard" to 1, "Good" to 2, "Easy" to 3).forEach { (label, g) ->
                        val next = Srs.review(card, g)
                        val color = when (g) { 0 -> LocalBlColors.current.danger; 1 -> LocalBlColors.current.warning; 2 -> MaterialTheme.colorScheme.primary; else -> LocalBlColors.current.success }
                        Button(onClick = {
                            app.io { app.db.education().updateCard(next) }
                            queue.removeAt(0)
                            if (g == 0) queue.add(next) else correct++
                            reviewed++
                            flipped = false
                        }, modifier = Modifier.weight(1f), colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.25f), contentColor = color)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, fontWeight = FontWeight.Bold)
                                Text(if (next.intervalDays == 0) "<1d" else "${next.intervalDays}d", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else {
                Button(onClick = { flipped = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Show answer") }
            }
        }
    }
}
