package com.blacklab.app.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.blacklab.app.AppContainer
import com.blacklab.app.app
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import java.time.LocalDate

/**
 * A single exact alarm always points at the next due reminder across every source.
 * When it fires we post everything due since the last check and re-arm.
 */
object ReminderScheduler {
    private const val PREFS = "blacklab_reminders"
    private const val KEY_LAST = "last_check"
    private const val HORIZON_DAYS = 35L

    enum class Kind { TASK, EVENT, ASSIGNMENT, CLASS, CERT, AGENDA, JOURNAL }

    data class Due(
        val key: String,
        val kind: Kind,
        val refId: Long,
        val at: Long,
        val title: String,
        val text: String,
        val route: String?,
        val profileLabel: String? = null,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun reschedule(context: Context) {
        val app = context.app
        val p = prefs(context)
        val now = Time.now()
        var last = p.getLong(KEY_LAST, 0L)
        if (last == 0L || last > now + 120_000) {
            last = now
            p.edit().putLong(KEY_LAST, now).apply()
        }
        val after = maxOf(last, now - 1000)
        val next = runCatching { collect(app, after, after + HORIZON_DAYS * 86_400_000L, buildText = false).minOfOrNull { it.at } }.getOrNull()
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = firePendingIntent(context)
        if (next == null) {
            am.cancel(pi)
            return
        }
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    suspend fun onAlarm(context: Context) {
        val app = context.app
        val p = prefs(context)
        val now = Time.now()
        val last = p.getLong(KEY_LAST, now - 60_000).coerceAtLeast(now - 6 * 3_600_000L)
        val to = now + 30_000
        val due = runCatching { collect(app, last, to, buildText = true) }.getOrDefault(emptyList())
        due.sortedBy { it.at }.forEach { Notifications.show(context, it) }
        p.edit().putLong(KEY_LAST, to).apply()
        reschedule(context)
    }

    private fun firePendingIntent(context: Context): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE)
        return PendingIntent.getBroadcast(context, 4242, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** All reminders whose trigger time is in (from, to]. */
    private suspend fun collect(app: AppContainer, from: Long, to: Long, buildText: Boolean): List<Due> {
        val out = ArrayList<Due>()
        val s = app.settings.current
        fun inWindow(t: Long) = t > from && t <= to
        fun label(profile: String) = app.settings.profileName(Profile.from(profile))
        val fromDate = Time.toDate(from).minusDays(8)
        val toDate = Time.toDate(to).plusDays(8)

        // Tasks
        app.db.tasks().remindersBetween(from, to).forEach { t ->
            val due = t.dueDay?.let { "Due " + Time.relativeDay(LocalDate.ofEpochDay(it)) + (t.dueMinute?.let { m -> " at " + Time.time(m) } ?: "") }
            out += Due("task:${t.id}:${t.reminderAt}", Kind.TASK, t.id, t.reminderAt!!, t.title,
                listOfNotNull(due, t.notes.takeIf { it.isNotBlank() }?.take(120)).joinToString(" · ").ifBlank { "Task reminder" },
                "task/${t.id}", label(t.profile))
        }

        // Events
        val calendars = app.db.events().allCalendars().associateBy { it.id }
        app.db.events().all().filter { it.reminders.isNotBlank() }.forEach { e ->
            val offsets = e.reminders.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (offsets.isEmpty()) return@forEach
            if (e.calendarId != null && calendars[e.calendarId]?.visible == false) return@forEach
            CalendarLogic.expand(e, fromDate, toDate).forEach { occ ->
                val startMs = Time.millis(occ.start)
                offsets.forEach { off ->
                    val at = if (e.allDay) Time.millis(occ.start.toLocalDate(), 9 * 60) - (if (off >= 1440) off * 60_000L else 0L)
                    else startMs - off * 60_000L
                    if (inWindow(at)) {
                        val whenText = if (e.allDay) "All day · " + Time.relativeDay(occ.start.toLocalDate())
                        else Time.relativeDay(occ.start.toLocalDate()) + " " + Time.time(occ.start) + "–" + Time.time(occ.end)
                        out += Due("event:${e.id}:$startMs:$off", Kind.EVENT, e.id, at, e.title.ifBlank { "Event" },
                            listOfNotNull(whenText, e.location.takeIf { it.isNotBlank() }).joinToString(" · "),
                            "event/${e.id}", label(e.profile))
                    }
                }
            }
        }

        // Education: assignments, classes
        val edu = app.db.education()
        val courses = edu.allCourses()
        val courseById = courses.associateBy { it.id }
        edu.allAssignments().forEach { a ->
            val dueAt = a.dueAt ?: return@forEach
            val off = a.reminderMinutes ?: return@forEach
            if (a.status == "SUBMITTED" || a.status == "GRADED") return@forEach
            val at = dueAt - off * 60_000L
            if (inWindow(at)) {
                val c = courseById[a.courseId]
                out += Due("assign:${a.id}:$at", Kind.ASSIGNMENT, a.id, at, "${a.kind}: ${a.title}",
                    "${c?.code?.ifBlank { c.title } ?: "Course"} · due ${Time.relativeDay(Time.toDate(dueAt))} ${Time.time(dueAt)}",
                    "course/${a.courseId}", c?.let { label(it.profile) })
            }
        }
        if (s.classReminderMinutes > 0) {
            val terms = emptyMap<Long, com.blacklab.app.data.TermEntity>()
            CalendarLogic.classOccurrences(courses, edu.allMeetings(), terms, Time.toDate(from), Time.toDate(to).plusDays(1)).forEach { o ->
                val at = Time.millis(o.start) - s.classReminderMinutes * 60_000L
                if (inWindow(at)) {
                    out += Due("class:${o.meeting.id}:$at", Kind.CLASS, o.course.id, at,
                        "${o.course.code.ifBlank { o.course.title }} ${o.meeting.kind} in ${s.classReminderMinutes} min",
                        listOfNotNull(Time.time(o.start) + "–" + Time.time(o.end), (o.meeting.location.ifBlank { o.course.location }).takeIf { it.isNotBlank() }).joinToString(" · "),
                        "course/${o.course.id}", label(o.course.profile))
                }
            }
        }
        edu.allCerts().forEach { c ->
            c.expiresDay?.let { exp ->
                for (daysBefore in listOf(90, 30, 7, 0)) {
                    val at = Time.millis(LocalDate.ofEpochDay(exp).minusDays(daysBefore.toLong()), 9 * 60)
                    if (inWindow(at)) out += Due("cert:${c.id}:$daysBefore", Kind.CERT, c.id, at,
                        if (daysBefore == 0) "${c.name} expires today" else "${c.name} expires in $daysBefore days",
                        "Renew with ${c.issuer.ifBlank { "the issuer" }} · expires ${Time.date(LocalDate.ofEpochDay(exp))}",
                        "education", label(c.profile))
                }
            }
            c.examAt?.let { exam ->
                val dayBefore = Time.millis(Time.toDate(exam).minusDays(1), 9 * 60)
                if (inWindow(dayBefore)) out += Due("exam:${c.id}:1d", Kind.CERT, c.id, dayBefore, "${c.name} exam tomorrow",
                    "At ${Time.time(exam)} — you've got this.", "education", label(c.profile))
                val twoHours = exam - 2 * 3_600_000L
                if (inWindow(twoHours)) out += Due("exam:${c.id}:2h", Kind.CERT, c.id, twoHours, "${c.name} exam in 2 hours",
                    "Starts at ${Time.time(exam)}", "education", label(c.profile))
            }
        }

        // Daily agenda + journal nudge
        var d = Time.toDate(from)
        while (d <= Time.toDate(to)) {
            if (s.agendaNotify) {
                val at = Time.millis(d, s.agendaMinute)
                if (inWindow(at)) out += Due("agenda:${d.toEpochDay()}", Kind.AGENDA, 0, at, "Today · ${Time.longDate(d)}",
                    if (buildText) agendaText(app, d) else "", "home")
            }
            if (s.journalReminder) {
                val at = Time.millis(d, s.journalMinute)
                if (inWindow(at)) {
                    val wrote = buildText && Profile.entries.any { app.db.journal().countOn(it.name, d.toEpochDay()) > 0 }
                    if (!wrote) out += Due("journal:${d.toEpochDay()}", Kind.JOURNAL, 0, at, "Journal",
                        "Take two minutes to capture today.", "journal/new")
                }
            }
            d = d.plusDays(1)
        }
        return out
    }

    private suspend fun agendaText(app: AppContainer, day: LocalDate): String {
        val parts = ArrayList<String>()
        val events = app.db.events().all()
        val courses = app.db.education().allCourses()
        val meetings = app.db.education().allMeetings()
        for (p in Profile.entries) {
            val ev = events.filter { it.profile == p.name }.sumOf { CalendarLogic.expand(it, day, day).size }
            val tasks = app.db.tasks().dueByDay(p.name, day.toEpochDay()).size
            val classes = CalendarLogic.classOccurrences(courses.filter { it.profile == p.name }, meetings, emptyMap(), day, day).size
            val bits = listOfNotNull(
                ev.takeIf { it > 0 }?.let { "$it event" + if (it > 1) "s" else "" },
                tasks.takeIf { it > 0 }?.let { "$it task" + (if (it > 1) "s" else "") + " due" },
                classes.takeIf { it > 0 }?.let { "$it class" + if (it > 1) "es" else "" },
            )
            if (bits.isNotEmpty()) parts += app.settings.profileName(p) + ": " + bits.joinToString(", ")
        }
        return if (parts.isEmpty()) "Nothing scheduled. A clear runway." else parts.joinToString("\n")
    }
}
