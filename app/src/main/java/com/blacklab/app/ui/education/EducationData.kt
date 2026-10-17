package com.blacklab.app.ui.education

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Time
import com.blacklab.app.data.AssignmentEntity
import com.blacklab.app.data.CertificationEntity
import com.blacklab.app.data.CeuEntryEntity
import com.blacklab.app.data.ClassMeetingEntity
import com.blacklab.app.data.CourseEntity
import com.blacklab.app.data.DeckEntity
import com.blacklab.app.data.FlashcardEntity
import com.blacklab.app.data.GradeCategoryEntity
import com.blacklab.app.data.Grades
import com.blacklab.app.data.StudySessionEntity
import com.blacklab.app.data.TermEntity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import java.time.LocalDate

val CourseKinds = listOf("COLLEGE" to "College course", "CERTIFICATION" to "Certification prep", "TRAINING" to "Training course", "WORKSHOP" to "Workshop / seminar", "ONLINE" to "Online course")
val CourseStatuses = listOf("PLANNED" to "Planned", "IN_PROGRESS" to "In progress", "COMPLETED" to "Completed", "DROPPED" to "Dropped")
val AssignmentKinds = listOf("Homework", "Reading", "Quiz", "Exam", "Midterm", "Final", "Project", "Paper", "Lab", "Discussion", "Presentation", "Module")
val AssignmentStatuses = listOf("TODO" to "To do", "IN_PROGRESS" to "In progress", "SUBMITTED" to "Submitted", "GRADED" to "Graded")
val CertStatuses = listOf("PLANNED" to "Planned", "STUDYING" to "Studying", "EXAM_SCHEDULED" to "Exam scheduled", "EARNED" to "Earned", "EXPIRED" to "Expired")

/** Popular facility-management & safety credentials for quick entry. */
val SuggestedCerts = listOf(
    "CFM — Certified Facility Manager" to "IFMA", "FMP — Facility Management Professional" to "IFMA", "SFP — Sustainability Facility Professional" to "IFMA",
    "RPA — Real Property Administrator" to "BOMI", "FMA — Facilities Management Administrator" to "BOMI", "CHFM — Certified Healthcare Facility Manager" to "AHA",
    "CEM — Certified Energy Manager" to "AEE", "LEED Green Associate" to "USGBC", "LEED AP O+M" to "USGBC", "PMP — Project Management Professional" to "PMI",
    "OSHA 30-Hour General Industry" to "OSHA", "CSP — Certified Safety Professional" to "BCSP", "CPMM — Certified Plant Maintenance Manager" to "AFE",
    "CMRP — Certified Maintenance & Reliability Professional" to "SMRP", "EPA 608 Universal" to "EPA", "NFPA CFPS — Certified Fire Protection Specialist" to "NFPA",
)

data class EduData(
    val courses: List<CourseEntity>,
    val terms: List<TermEntity>,
    val meetings: List<ClassMeetingEntity>,
    val categories: List<GradeCategoryEntity>,
    val assignments: List<AssignmentEntity>,
    val certs: List<CertificationEntity>,
    val ceus: List<CeuEntryEntity>,
    val sessions: List<StudySessionEntity>,
    val decks: List<DeckEntity>,
    val cards: List<FlashcardEntity>,
) {
    val courseById: Map<Long, CourseEntity> = courses.associateBy { it.id }
    fun assignmentsFor(courseId: Long) = assignments.filter { it.courseId == courseId }
    fun categoriesFor(courseId: Long) = categories.filter { it.courseId == courseId }
    fun percent(courseId: Long): Double? = Grades.coursePercent(assignmentsFor(courseId), categoriesFor(courseId))
    fun dueCards(deckId: Long? = null): Int {
        val today = Time.today().toEpochDay()
        return cards.count { (deckId == null || it.deckId == deckId) && it.dueDay <= today }
    }
    fun ceuEarned(cert: CertificationEntity): Double =
        ceus.filter { it.certificationId == cert.id && (cert.earnedDay == null || it.day >= cert.earnedDay) }.sumOf { it.hours }

    /** Projected GPA including in-progress college courses at their current letter. */
    fun projectedGpa(): Double? {
        val projected = courses.filter { it.kind == "COLLEGE" && !it.passFail && it.status != "DROPPED" }.mapNotNull { c ->
            val letter = if (c.status == "COMPLETED") c.finalGrade else percent(c.id)?.let { Grades.letter(it) }
            letter?.let { Grades.points(it) }?.let { c.credits to it }
        }
        val cr = projected.sumOf { it.first }
        return if (cr > 0) projected.sumOf { it.first * it.second } / cr else null
    }

    fun studyMinutes(day: LocalDate): Int = sessions.filter { Time.toDate(it.startAt) == day }.sumOf { it.minutes }
}

@Composable
fun rememberEduData(): EduData {
    val app = rememberApp()
    val p = settingsState().activeProfile.name
    val dao = app.db.education()
    val courses by remember(p) { dao.observeCourses(listOf(p)) }.collectAsState(emptyList())
    val terms by remember(p) { dao.observeTerms(p) }.collectAsState(emptyList())
    val meetings by remember(p) { dao.observeMeetings(listOf(p)) }.collectAsState(emptyList())
    val categories by remember(p) { dao.observeCategories(p) }.collectAsState(emptyList())
    val assignments by remember(p) { dao.observeAssignments(listOf(p)) }.collectAsState(emptyList())
    val certs by remember(p) { dao.observeCerts(p) }.collectAsState(emptyList())
    val ceus by remember(p) { dao.observeCeus(p) }.collectAsState(emptyList())
    val sessions by remember(p) { dao.observeSessions(p) }.collectAsState(emptyList())
    val decks by remember(p) { dao.observeDecks(p) }.collectAsState(emptyList())
    val cards by remember(p) { dao.observeAllCards(p) }.collectAsState(emptyList())
    return remember(courses, terms, meetings, categories, assignments, certs, ceus, sessions, decks, cards) {
        EduData(courses, terms, meetings, categories, assignments, certs, ceus, sessions, decks, cards)
    }
}

/** Bottom-sheet form scaffold used by the education editors. */
@Composable
fun EditorSheet(title: String, onDismiss: () -> Unit, saveEnabled: Boolean = true, onSave: () -> Unit, extra: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Actions live in the header so they're reachable without scrolling long forms
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(); onDismiss() }, enabled = saveEnabled) { Text("Save") }
            }
            content()
            if (extra != null) {
                Spacer(Modifier.height(4.dp))
                Row { extra() }
            }
        }
    }
}
