package com.blacklab.app.ui.education

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Files
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Time
import com.blacklab.app.data.AssignmentEntity
import com.blacklab.app.data.CertificationEntity
import com.blacklab.app.data.CeuEntryEntity
import com.blacklab.app.data.ClassMeetingEntity
import com.blacklab.app.data.CourseEntity
import com.blacklab.app.data.DeckEntity
import com.blacklab.app.data.GradeCategoryEntity
import com.blacklab.app.data.Grades
import com.blacklab.app.data.StudySessionEntity
import com.blacklab.app.data.TermEntity
import com.blacklab.app.ui.components.ChipFlow
import com.blacklab.app.ui.components.ColorPickerRow
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DatePickerDialogBox
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.PickerRow
import com.blacklab.app.ui.components.TimePickerDialogBox
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.editor.Stepper
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Palette
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, number: Boolean = false, minLines: Int = 1, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = minLines == 1, minLines = minLines, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Text, capitalization = if (number) KeyboardCapitalization.None else KeyboardCapitalization.Sentences),
    )
}

private fun Double.clean(): String = if (this % 1.0 == 0.0) toInt().toString() else toString()

// ------------------------------------------------------------------ Course

@Composable
fun CourseEditor(course: CourseEntity?, data: EduData, onDismiss: () -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    var c by remember { mutableStateOf(course ?: CourseEntity(profile = s.activeProfile.name, title = "", color = Palette[data.courses.size % Palette.size], termId = data.terms.firstOrNull()?.id)) }
    var credits by remember { mutableStateOf(c.credits.clean()) }
    var modulesTotal by remember { mutableStateOf(c.modulesTotal.toString()) }
    var target by remember { mutableStateOf(c.targetPercent.clean()) }
    var pick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newTerm by remember { mutableStateOf(false) }

    EditorSheet(
        if (course == null) "New course" else "Edit course", onDismiss, saveEnabled = c.title.isNotBlank(),
        onSave = {
            val final = c.copy(credits = credits.toDoubleOrNull() ?: 0.0, modulesTotal = modulesTotal.toIntOrNull() ?: 0, targetPercent = target.toDoubleOrNull() ?: 90.0)
            app.io { if (final.id == 0L) app.db.education().insertCourse(final) else app.db.education().updateCourse(final); app.requestReminderSync() }
        },
        extra = { if (course != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = LocalBlColors.current.danger) } },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(c.code, { c = c.copy(code = it) }, "Code (e.g. FM 310)", modifier = Modifier.weight(0.4f))
            Field(c.title, { c = c.copy(title = it) }, "Title", modifier = Modifier.weight(0.6f))
        }
        DropdownField("Type", CourseKinds, c.kind, { c = c.copy(kind = it) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            DropdownField("Term", listOf<Pair<Long?, String>>(null to "None") + data.terms.map { it.id to it.name }, c.termId, { c = c.copy(termId = it) }, Modifier.weight(1f))
            IconButton(onClick = { newTerm = true }) { Icon(Icons.Default.Add, "New term") }
        }
        Field(c.institution, { c = c.copy(institution = it) }, if (c.kind == "COLLEGE") "College / university" else "Provider")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(c.instructor, { c = c.copy(instructor = it) }, "Instructor", modifier = Modifier.weight(1f))
            Field(c.instructorEmail, { c = c.copy(instructorEmail = it) }, "Instructor email", modifier = Modifier.weight(1f))
        }
        Field(c.officeHours, { c = c.copy(officeHours = it) }, "Office hours")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(c.location, { c = c.copy(location = it) }, "Room / location", modifier = Modifier.weight(1f))
            Field(credits, { credits = it }, if (c.kind == "COLLEGE") "Credits" else "Credit hours / CEUs", number = true, modifier = Modifier.weight(0.6f))
        }
        Field(c.onlineUrl, { c = c.copy(onlineUrl = it) }, "LMS / course link (Canvas, Blackboard, Moodle…)")
        DropdownField("Status", CourseStatuses, c.status, { c = c.copy(status = it) })
        if (c.status == "COMPLETED" && c.kind == "COLLEGE") {
            DropdownField("Final grade", listOf("" to "—") + Grades.letters.map { it to it } + listOf("P" to "Pass", "W" to "Withdrawn", "I" to "Incomplete"), c.finalGrade, { c = c.copy(finalGrade = it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(target, { target = it }, "Target grade %", number = true, modifier = Modifier.weight(1f))
            Field(modulesTotal, { modulesTotal = it }, "Modules / units", number = true, modifier = Modifier.weight(1f))
        }
        LabeledSwitchRow("Pass / fail", "Excluded from GPA", c.passFail) { c = c.copy(passFail = it) }
        PickerRow(Icons.Default.Event, "Start date", c.startDay?.let { Time.date(LocalDate.ofEpochDay(it)) }, onClear = { c = c.copy(startDay = null) }) { pick = "start" }
        PickerRow(Icons.Default.EventBusy, "End date", c.endDay?.let { Time.date(LocalDate.ofEpochDay(it)) }, onClear = { c = c.copy(endDay = null) }) { pick = "end" }
        Text("Color", style = MaterialTheme.typography.labelLarge)
        ColorPickerRow(c.color, { c = c.copy(color = it) })
        Field(c.notes, { c = c.copy(notes = it) }, "Notes", minLines = 3)
    }
    when (pick) {
        "start" -> DatePickerDialogBox(c.startDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> c = c.copy(startDay = d?.toEpochDay()) }
        "end" -> DatePickerDialogBox(c.endDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> c = c.copy(endDay = d?.toEpochDay()) }
    }
    if (newTerm) TermEditor(null) { newTerm = false }
    if (confirmDelete) ConfirmDialog("Delete course?", "“${c.title}” with its schedule, grades and assignments will be deleted.", onDismiss = { confirmDelete = false }) {
        app.io { app.db.education().deleteCourse(c); app.requestReminderSync() }; onDismiss()
    }
}

@Composable
fun TermsDialog(data: EduData, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf<TermEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terms / semesters") },
        text = {
            Column {
                data.terms.forEach { t ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name)
                            Text(listOfNotNull(t.institution.takeIf { it.isNotBlank() }, t.startDay?.let { Time.short(LocalDate.ofEpochDay(it)) }, t.endDay?.let { Time.short(LocalDate.ofEpochDay(it)) }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { edit = t }) { Icon(Icons.Default.Edit, "Edit") }
                    }
                }
                if (data.terms.isEmpty()) Text("No terms yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { creating = true }) { Icon(Icons.Default.Add, null); Text("Add term") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    edit?.let { t -> TermEditor(t) { edit = null } }
    if (creating) TermEditor(null) { creating = false }
}

@Composable
fun TermEditor(term: TermEntity?, onDismiss: () -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    val year = Time.today().year
    var t by remember { mutableStateOf(term ?: TermEntity(profile = s.activeProfile.name, name = "")) }
    var pick by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (term == null) "New term" else "Edit term") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(t.name, { t = t.copy(name = it) }, "Name (e.g. Fall $year)")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Spring $year", "Summer $year", "Fall $year", "Winter ${year + 1}", "Spring ${year + 1}").forEach { n -> AssistChip({ t = t.copy(name = n) }, { Text(n) }) }
                }
                Field(t.institution, { t = t.copy(institution = it) }, "School")
                PickerRow(Icons.Default.Event, "Start date", t.startDay?.let { Time.date(LocalDate.ofEpochDay(it)) }) { pick = "s" }
                PickerRow(Icons.Default.EventBusy, "End date", t.endDay?.let { Time.date(LocalDate.ofEpochDay(it)) }) { pick = "e" }
                if (term != null) TextButton(onClick = { app.io { app.db.education().detachTerm(term.id); app.db.education().deleteTerm(term) }; onDismiss() }) {
                    Text("Delete term", color = LocalBlColors.current.danger)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = t.name.isNotBlank(), onClick = {
                app.io { if (t.id == 0L) app.db.education().insertTerm(t) else app.db.education().updateTerm(t) }; onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (pick) {
        "s" -> DatePickerDialogBox(t.startDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> t = t.copy(startDay = d?.toEpochDay()) }
        "e" -> DatePickerDialogBox(t.endDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> t = t.copy(endDay = d?.toEpochDay()) }
    }
}

// ------------------------------------------------------------------ Meetings & grade categories

@Composable
fun MeetingEditor(meeting: ClassMeetingEntity?, courseId: Long, onDismiss: () -> Unit) {
    val app = rememberApp()
    var days by remember { mutableStateOf(setOf(DayOfWeek.of(meeting?.dayOfWeek ?: 1))) }
    var start by remember { mutableStateOf(meeting?.startMinute ?: 18 * 60) }
    var end by remember { mutableStateOf(meeting?.endMinute ?: (19 * 60 + 15)) }
    var location by remember { mutableStateOf(meeting?.location ?: "") }
    var kind by remember { mutableStateOf(meeting?.kind ?: "Lecture") }
    var pick by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (meeting == null) "Add class time" else "Edit class time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Days", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(d in days, { days = if (meeting != null) setOf(d) else if (d in days) days - d else days + d }, label = { Text(Time.dayNarrow(d)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pick = "s" }) { Text("Starts ${Time.time(start)}") }
                    TextButton(onClick = { pick = "e" }) { Text("Ends ${Time.time(end)}") }
                }
                ChipFlow { listOf("Lecture", "Lab", "Online", "Seminar", "Recitation", "Exam").forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k) }) } }
                Field(location, { location = it }, "Room / building / link")
                if (meeting != null) TextButton(onClick = { app.io { app.db.education().deleteMeeting(meeting) }; onDismiss() }) { Text("Delete", color = LocalBlColors.current.danger) }
            }
        },
        confirmButton = {
            TextButton(enabled = days.isNotEmpty() && end > start, onClick = {
                app.io {
                    if (meeting != null) app.db.education().updateMeeting(meeting.copy(dayOfWeek = days.first().value, startMinute = start, endMinute = end, location = location, kind = kind))
                    else days.forEach { d -> app.db.education().insertMeeting(ClassMeetingEntity(courseId = courseId, dayOfWeek = d.value, startMinute = start, endMinute = end, location = location, kind = kind)) }
                    app.requestReminderSync()
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (pick) {
        "s" -> TimePickerDialogBox(start, { pick = null }) { start = it; if (end <= it) end = it + 75 }
        "e" -> TimePickerDialogBox(end, { pick = null }) { end = it }
    }
}

@Composable
fun CategoriesEditor(courseId: Long, existing: List<GradeCategoryEntity>, onDismiss: () -> Unit) {
    val app = rememberApp()
    val rows = remember { mutableStateListOf<Triple<Long, String, String>>().apply { existing.forEach { add(Triple(it.id, it.name, it.weight.clean())) } } }
    val removed = remember { mutableStateListOf<GradeCategoryEntity>() }
    val total = rows.sumOf { it.third.toDoubleOrNull() ?: 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Grade weights") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("From your syllabus. Total: ${total.clean()}%", color = if (kotlin.math.abs(total - 100) < 0.01 || rows.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else LocalBlColors.current.warning)
                rows.forEachIndexed { i, (id, name, w) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(name, { rows[i] = Triple(id, it, w) }, label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(w, { rows[i] = Triple(id, name, it) }, label = { Text("%") }, singleLine = true, modifier = Modifier.width(76.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        IconButton(onClick = { existing.firstOrNull { it.id == id }?.let(removed::add); rows.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp)) }
                    }
                }
                TextButton(onClick = { rows.add(Triple(0L, "", "")) }) { Icon(Icons.Default.Add, null); Text("Add category") }
                if (rows.isEmpty()) {
                    Text("Quick start", style = MaterialTheme.typography.labelLarge)
                    ChipFlow {
                        AssistChip({ listOf("Exams" to "40", "Assignments" to "30", "Quizzes" to "15", "Participation" to "15").forEach { rows.add(Triple(0L, it.first, it.second)) } }, { Text("Exams/Assign/Quiz/Part.") })
                        AssistChip({ listOf("Midterm" to "25", "Final" to "35", "Homework" to "25", "Project" to "15").forEach { rows.add(Triple(0L, it.first, it.second)) } }, { Text("Midterm/Final/HW/Project") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.io {
                    val dao = app.db.education()
                    removed.forEach { dao.deleteCategory(it) }
                    rows.filter { it.second.isNotBlank() }.forEach { (id, name, w) ->
                        val e = GradeCategoryEntity(id = id, courseId = courseId, name = name.trim(), weight = w.toDoubleOrNull() ?: 0.0)
                        if (id == 0L) dao.insertCategory(e) else dao.updateCategory(e)
                    }
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ Assignment

@Composable
fun AssignmentEditor(assignment: AssignmentEntity?, data: EduData, courseId: Long?, onDismiss: () -> Unit) {
    val app = rememberApp()
    val open = data.courses.filter { it.status != "DROPPED" }
    var a by remember {
        mutableStateOf(assignment ?: AssignmentEntity(courseId = courseId ?: open.firstOrNull()?.id ?: 0, title = "", dueAt = Time.millis(Time.today().plusDays(7), 23 * 60 + 59)))
    }
    var earned by remember { mutableStateOf(a.pointsEarned?.clean() ?: "") }
    var possible by remember { mutableStateOf(a.pointsPossible?.clean() ?: "100") }
    var pick by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val cats = data.categoriesFor(a.courseId)
    if (open.isEmpty()) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a course first") }, text = { Text("Assignments belong to a course.") }, confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } })
        return
    }
    EditorSheet(
        if (assignment == null) "New assignment" else "Edit assignment", onDismiss, saveEnabled = a.title.isNotBlank() && a.courseId != 0L,
        onSave = {
            val e = earned.toDoubleOrNull()
            val final = a.copy(pointsEarned = e, pointsPossible = possible.toDoubleOrNull(), status = if (e != null && a.status != "GRADED") "GRADED" else a.status,
                completedAt = if (a.status == "SUBMITTED" || a.status == "GRADED" || e != null) (a.completedAt ?: System.currentTimeMillis()) else null)
            app.io { if (final.id == 0L) app.db.education().insertAssignment(final) else app.db.education().updateAssignment(final); app.requestReminderSync() }
        },
        extra = { if (assignment != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = LocalBlColors.current.danger) } },
    ) {
        Field(a.title, { a = a.copy(title = it) }, "Title")
        DropdownField("Course", open.map { it.id to "${it.code} ${it.title}".trim() }, a.courseId, { a = a.copy(courseId = it, categoryId = null) })
        Text("Type", style = MaterialTheme.typography.labelLarge)
        ChipFlow { AssignmentKinds.forEach { k -> FilterChip(a.kind == k, { a = a.copy(kind = k) }, label = { Text(k) }) } }
        if (cats.isNotEmpty()) DropdownField("Grade category", listOf<Pair<Long?, String>>(null to "None") + cats.map { it.id to "${it.name} (${it.weight.clean()}%)" }, a.categoryId, { a = a.copy(categoryId = it) })
        Row {
            PickerRow(Icons.Default.Event, "Due date", a.dueAt?.let { Time.date(it) }, Modifier.weight(1f), onClear = { a = a.copy(dueAt = null) }) { pick = "date" }
            if (a.dueAt != null) TextButton(onClick = { pick = "time" }, modifier = Modifier.align(Alignment.CenterVertically)) { Text(Time.time(a.dueAt!!)) }
        }
        DropdownField("Reminder", listOf<Pair<Int?, String>>(null to "None", 60 to "1 hour before", 180 to "3 hours before", 1440 to "1 day before", 2880 to "2 days before", 10080 to "1 week before"),
            a.reminderMinutes, { a = a.copy(reminderMinutes = it) })
        DropdownField("Status", AssignmentStatuses, a.status, { a = a.copy(status = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(earned, { earned = it }, "Points earned", number = true, modifier = Modifier.weight(1f))
            Field(possible, { possible = it }, "Points possible", number = true, modifier = Modifier.weight(1f))
        }
        Field(a.notes, { a = a.copy(notes = it) }, "Notes / instructions", minLines = 3)
    }
    when (pick) {
        "date" -> DatePickerDialogBox(a.dueAt?.let { Time.toDate(it) }, { pick = null }) { d -> d?.let { a = a.copy(dueAt = Time.millis(it, a.dueAt?.let(Time::minuteOfDay) ?: (23 * 60 + 59))) } }
        "time" -> TimePickerDialogBox(a.dueAt?.let(Time::minuteOfDay) ?: (23 * 60 + 59), { pick = null }) { m -> a = a.copy(dueAt = Time.millis(Time.toDate(a.dueAt ?: Time.now()), m)) }
    }
    if (confirmDelete) ConfirmDialog("Delete assignment?", "“${a.title}” will be deleted.", onDismiss = { confirmDelete = false }) {
        app.io { app.db.education().deleteAssignment(a); app.requestReminderSync() }; onDismiss()
    }
}

// ------------------------------------------------------------------ Certification

@Composable
fun CertEditor(cert: CertificationEntity?, data: EduData, onDismiss: () -> Unit) {
    val app = rememberApp()
    val ctx = LocalContext.current
    val s = settingsState()
    var c by remember { mutableStateOf(cert ?: CertificationEntity(profile = s.activeProfile.name, name = "")) }
    var ceuReq by remember { mutableStateOf(if (c.ceuRequired > 0) c.ceuRequired.clean() else "") }
    var renewal by remember { mutableStateOf(if (c.renewalMonths > 0) c.renewalMonths.toString() else "") }
    var cost by remember { mutableStateOf(if (c.cost > 0) c.cost.clean() else "") }
    var pick by remember { mutableStateOf<String?>(null) }
    var addCeu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.io {
            val name = Files.displayName(ctx, uri)
            val dir = java.io.File(app.storage.dir(s.activeProfile, Folder.EDUCATION), "Certificates")
            val dest = Files.unique(dir, name.substringBeforeLast('.'), name.substringAfterLast('.', "pdf"))
            if (Files.copyFromUri(ctx, uri, dest)) c = c.copy(filePath = app.storage.relative(dest))
        }
    }
    EditorSheet(
        if (cert == null) "New credential" else "Credential", onDismiss, saveEnabled = c.name.isNotBlank(),
        onSave = {
            var final = c.copy(ceuRequired = ceuReq.toDoubleOrNull() ?: 0.0, renewalMonths = renewal.toIntOrNull() ?: 0, cost = cost.toDoubleOrNull() ?: 0.0)
            if (final.expiresDay == null && final.earnedDay != null && final.renewalMonths > 0)
                final = final.copy(expiresDay = LocalDate.ofEpochDay(final.earnedDay!!).plusMonths(final.renewalMonths.toLong()).toEpochDay())
            app.io { if (final.id == 0L) app.db.education().insertCert(final) else app.db.education().updateCert(final); app.requestReminderSync() }
        },
        extra = { if (cert != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = LocalBlColors.current.danger) } },
    ) {
        Field(c.name, { c = c.copy(name = it) }, "Credential name")
        if (cert == null) {
            Text("Common facility credentials", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SuggestedCerts.forEach { (n, i) -> AssistChip({ c = c.copy(name = n, issuer = i) }, { Text(n.substringBefore(" —")) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(c.issuer, { c = c.copy(issuer = it) }, "Issuing body", modifier = Modifier.weight(1f))
            Field(c.credentialId, { c = c.copy(credentialId = it) }, "Credential ID", modifier = Modifier.weight(1f))
        }
        DropdownField("Status", CertStatuses, c.status, { c = c.copy(status = it) })
        PickerRow(Icons.Default.Quiz, "Exam date & time", c.examAt?.let { Time.dateTime(it) }, onClear = { c = c.copy(examAt = null) }) { pick = "exam" }
        PickerRow(Icons.Default.EmojiEvents, "Date earned", c.earnedDay?.let { Time.date(LocalDate.ofEpochDay(it)) }, onClear = { c = c.copy(earnedDay = null) }) { pick = "earned" }
        PickerRow(Icons.Default.EventBusy, "Expires / renewal due", c.expiresDay?.let { Time.date(LocalDate.ofEpochDay(it)) }, onClear = { c = c.copy(expiresDay = null) }) { pick = "expires" }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(renewal, { renewal = it }, "Renewal cycle (months)", number = true, modifier = Modifier.weight(1f))
            Field(ceuReq, { ceuReq = it }, "CEUs / PDHs required", number = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(cost, { cost = it }, "Cost ($)", number = true, modifier = Modifier.weight(1f))
            DropdownField("Prep course", listOf<Pair<Long?, String>>(null to "None") + data.courses.map { it.id to "${it.code} ${it.title}".trim() }, c.courseId, { c = c.copy(courseId = it) }, Modifier.weight(1f))
        }
        Field(c.url, { c = c.copy(url = it) }, "Verification / portal URL")
        Row(verticalAlignment = Alignment.CenterVertically) {
            PickerRow(Icons.Default.AttachFile, "Attach certificate (PDF/image)", c.filePath.takeIf { it.isNotBlank() }?.substringAfterLast('/'), Modifier.weight(1f),
                onClear = { c = c.copy(filePath = "") }) {
                if (c.filePath.isNotBlank()) Files.open(ctx, app.storage.resolve(c.filePath)) else attach.launch(arrayOf("application/pdf", "image/*"))
            }
            if (c.url.isNotBlank()) IconButton(onClick = { Files.openUrl(ctx, c.url) }) { Icon(Icons.Default.OpenInNew, "Open portal") }
        }
        Field(c.notes, { c = c.copy(notes = it) }, "Notes / study plan", minLines = 3)
        if (cert != null) {
            HorizontalDivider()
            val entries = data.ceus.filter { it.certificationId == cert.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CEU log · ${fmt(data.ceuEarned(cert))} hrs this cycle", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { addCeu = true }) { Icon(Icons.Default.Add, null); Text("Log") }
            }
            entries.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.activity)
                        Text("${Time.date(LocalDate.ofEpochDay(e.day))}${if (e.provider.isNotBlank()) " · ${e.provider}" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${fmt(e.hours)} h", style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = { app.io { app.db.education().deleteCeu(e) } }) { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) }
                }
            }
        }
    }
    when (pick) {
        "exam" -> DatePickerDialogBox(c.examAt?.let { Time.toDate(it) }, { if (pick == "exam") pick = null }) { d -> d?.let { c = c.copy(examAt = Time.millis(it, c.examAt?.let(Time::minuteOfDay) ?: (9 * 60))); pick = "examTime" } }
        "examTime" -> TimePickerDialogBox(c.examAt?.let(Time::minuteOfDay) ?: (9 * 60), { pick = null }) { m -> c = c.copy(examAt = Time.millis(Time.toDate(c.examAt ?: Time.now()), m)) }
        "earned" -> DatePickerDialogBox(c.earnedDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> c = c.copy(earnedDay = d?.toEpochDay(), status = if (d != null && c.status != "EXPIRED") "EARNED" else c.status) }
        "expires" -> DatePickerDialogBox(c.expiresDay?.let(LocalDate::ofEpochDay), { pick = null }) { d -> c = c.copy(expiresDay = d?.toEpochDay()) }
    }
    if (addCeu && cert != null) CeuDialog(cert.id) { addCeu = false }
    if (confirmDelete) ConfirmDialog("Delete credential?", "“${c.name}” and its CEU log will be deleted.", onDismiss = { confirmDelete = false }) {
        app.io { app.db.education().deleteCert(c); app.requestReminderSync() }; onDismiss()
    }
}

@Composable
private fun CeuDialog(certId: Long, onDismiss: () -> Unit) {
    val app = rememberApp()
    var activity by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("1") }
    var day by remember { mutableStateOf(Time.today()) }
    var pick by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log CEUs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(activity, { activity = it }, "Activity (course, webinar, conference)")
                Field(provider, { provider = it }, "Provider")
                Field(hours, { hours = it }, "Hours", number = true)
                PickerRow(Icons.Default.Event, "Date", Time.date(day)) { pick = true }
            }
        },
        confirmButton = {
            TextButton(enabled = activity.isNotBlank() && (hours.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                app.io { app.db.education().insertCeu(CeuEntryEntity(certificationId = certId, day = day.toEpochDay(), activity = activity.trim(), provider = provider.trim(), hours = hours.toDouble())) }
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pick) DatePickerDialogBox(day, { pick = false }) { d -> d?.let { day = it } }
}

// ------------------------------------------------------------------ Study log, decks, what-if

@Composable
fun ManualStudyDialog(data: EduData, presetCourse: Long? = null, onDismiss: () -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    var minutes by remember { mutableStateOf(30) }
    var course by remember { mutableStateOf(presetCourse) }
    var notes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log study time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField("Course", listOf<Pair<Long?, String>>(null to "General study") + data.courses.map { it.id to "${it.code} ${it.title}".trim() }, course, { course = it })
                Stepper("Minutes", minutes, 5..600) { minutes = it }
                ChipFlow { listOf(15, 30, 45, 60, 90, 120).forEach { m -> FilterChip(minutes == m, { minutes = m }, label = { Text(Time.hoursLabel(m)) }) } }
                Field(notes, { notes = it }, "What did you cover?")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.io { app.db.education().insertSession(StudySessionEntity(profile = s.activeProfile.name, courseId = course, startAt = System.currentTimeMillis() - minutes * 60_000L, minutes = minutes, kind = "MANUAL", notes = notes)) }
                onDismiss()
            }) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeckEditor(deck: DeckEntity?, data: EduData, onDismiss: () -> Unit) {
    val app = rememberApp()
    val s = settingsState()
    var name by remember { mutableStateOf(deck?.name ?: "") }
    var course by remember { mutableStateOf(deck?.courseId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (deck == null) "New deck" else "Edit deck") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "Deck name")
                DropdownField("Course", listOf<Pair<Long?, String>>(null to "None") + data.courses.map { it.id to "${it.code} ${it.title}".trim() }, course, { course = it })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                app.io {
                    if (deck == null) app.db.education().insertDeck(DeckEntity(profile = s.activeProfile.name, name = name.trim(), courseId = course))
                    else app.db.education().updateDeck(deck.copy(name = name.trim(), courseId = course))
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun WhatIfDialog(data: EduData, onDismiss: () -> Unit) {
    val base = Grades.gpa(data.courses)
    val rows = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            data.courses.filter { it.kind == "COLLEGE" && it.status == "IN_PROGRESS" && !it.passFail }.forEach { c ->
                add(c.credits.clean() to (data.percent(c.id)?.let { Grades.letter(it) } ?: "A"))
            }
            if (isEmpty()) add("3" to "A")
        }
    }
    val addCr = rows.sumOf { it.first.toDoubleOrNull() ?: 0.0 }
    val addQp = rows.sumOf { (it.first.toDoubleOrNull() ?: 0.0) * (Grades.points(it.second) ?: 0.0) }
    val total = base.credits + addCr
    val projected = if (total > 0) (base.qualityPoints + addQp) / total else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What-if GPA") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current: ${base.gpa?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} over ${fmt(base.credits)} credits", color = MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEachIndexed { i, (cr, letter) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(cr, { rows[i] = it to letter }, label = { Text("Credits") }, singleLine = true, modifier = Modifier.width(90.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Spacer(Modifier.width(8.dp))
                        DropdownField("Grade", Grades.letters.map { it to it }, letter, { rows[i] = cr to it }, Modifier.weight(1f))
                        IconButton(onClick = { rows.removeAt(i) }) { Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp)) }
                    }
                }
                TextButton(onClick = { rows.add("3" to "A") }) { Icon(Icons.Default.Add, null); Text("Add course") }
                HorizontalDivider()
                Text("Projected GPA: ${projected?.let { String.format(Locale.US, "%.3f", it) } ?: "—"}", style = MaterialTheme.typography.titleLarge)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
