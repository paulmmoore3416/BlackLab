package com.blacklab.app.assistant

import com.blacklab.app.AppContainer
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.TaskEntity
import java.time.LocalDate
import java.time.LocalDateTime

/** Agenda and daily-briefing text built entirely from local data. */
object Briefing {

    data class AgendaItem(val start: LocalDateTime, val end: LocalDateTime, val title: String, val kind: String, val where: String, val route: String)

    suspend fun agenda(app: AppContainer, from: LocalDate, to: LocalDate): List<AgendaItem> {
        val profile = app.settings.current.activeProfile.name
        val events = app.db.events().all().filter { it.profile == profile }
            .flatMap { CalendarLogic.expand(it, from, to) }
            .map { AgendaItem(it.start, it.end, it.event.title, "Event", it.event.location, "event/${it.event.id}") }
        val edu = app.db.education()
        val courses = edu.allCourses().filter { it.profile == profile }
        val classes = CalendarLogic.classOccurrences(courses, edu.allMeetings(), edu.allTerms().associateBy { it.id }, from, to)
            .map { AgendaItem(it.start, it.end, it.course.code.ifBlank { it.course.title }, "Class", it.meeting.location.ifBlank { it.course.location }, "course/${it.course.id}") }
        val courseIds = courses.map { it.id }.toSet()
        val due = edu.allAssignments().filter { it.courseId in courseIds && it.dueAt != null && it.status != "SUBMITTED" && it.status != "GRADED" }
            .filter { Time.toDate(it.dueAt!!) in from..to }
            .map { val t = Time.toLdt(it.dueAt!!); AgendaItem(t, t, it.title, if (it.kind.contains("exam", true)) "Exam" else "Due", "", "course/${it.courseId}") }
        return (events + classes + due).sortedBy { it.start }
    }

    fun describe(items: List<AgendaItem>, day: LocalDate): String {
        if (items.isEmpty()) return "Nothing on the calendar ${Time.relativeDay(day).lowercase()}."
        return items.joinToString("\n") { i ->
            val time = if (i.kind == "Due") "due ${Time.time(i.start)}" else if (i.start.toLocalTime().toSecondOfDay() == 0 && i.end.toLocalDate() > i.start.toLocalDate()) "all day" else Time.time(i.start)
            "• $time — ${i.title}" + (i.where.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + if (i.kind != "Event") " · ${i.kind.lowercase()}" else ""
        }
    }

    suspend fun openTasks(app: AppContainer): List<TaskEntity> =
        app.db.tasks().all().filter { it.profile == app.settings.current.activeProfile.name && !it.completed }

    suspend fun build(app: AppContainer, persona: String): String {
        val s = app.settings.current
        val today = Time.today()
        val todayEpoch = today.toEpochDay()
        val name = s.userName.substringBefore(' ').ifBlank { null }
        val items = agenda(app, today, today).filter { it.end >= LocalDateTime.now() || it.kind == "Due" }
        val tasks = openTasks(app)
        val overdue = tasks.filter { it.dueDay != null && it.dueDay < todayEpoch }
        val dueToday = tasks.filter { it.dueDay == todayEpoch || it.myDayDay == todayEpoch }
        val certs = app.db.education().allCerts().filter { it.profile == s.activeProfile.name }
        val renewals = certs.filter { it.expiresDay != null && it.expiresDay - todayEpoch in 0..45 }
        val exams = certs.filter { it.examAt != null && Time.toDate(it.examAt) in today..today.plusDays(14) && it.status != "EARNED" }
        val soon = agenda(app, today.plusDays(1), today.plusDays(3)).filter { it.kind == "Due" || it.kind == "Exam" }

        return buildString {
            append(Time.greeting()); name?.let { append(", $it") }; append(". It's ${Time.longDate(today)}.\n\n")
            if (items.isEmpty()) append(if (persona == "LAIDBACK") "Calendar's wide open today — nice.\n" else "No events on the calendar today.\n")
            else { append("On deck today (${items.size}):\n"); append(describe(items.take(8), today)); append("\n") }
            append("\n")
            when {
                dueToday.isEmpty() && overdue.isEmpty() -> append("No tasks due today.\n")
                else -> {
                    append("Tasks: ${dueToday.size} due today")
                    if (overdue.isNotEmpty()) append(", ${overdue.size} overdue")
                    append(".\n")
                    (overdue + dueToday).distinctBy { it.id }.sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueDay ?: Long.MAX_VALUE }).take(4)
                        .forEach { append("• ${it.title}" + (it.dueMinute?.let { m -> " at ${Time.time(m)}" } ?: "") + "\n") }
                }
            }
            if (soon.isNotEmpty()) {
                append("\nComing up: ")
                append(soon.take(3).joinToString("; ") { "${it.title} ${Time.relativeDay(it.start.toLocalDate()).lowercase()}" })
                append(".\n")
            }
            exams.forEach { append("\nHeads up: ${it.name} exam ${Time.relativeDay(Time.toDate(it.examAt!!)).lowercase()}.") }
            renewals.forEach { append("\n${it.name} renews in ${it.expiresDay!! - todayEpoch} days.") }
            append("\n\n")
            append(when (persona) {
                "LAIDBACK" -> listOf("You got this, man.", "Alright, let's do this thing.", "Easy day. Well — easy-ish.", "Go get 'em.").random()
                "PROFESSIONAL" -> "Have a productive day."
                else -> ""
            })
        }.trim()
    }
}
