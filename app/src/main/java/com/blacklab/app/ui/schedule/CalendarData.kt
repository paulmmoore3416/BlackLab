package com.blacklab.app.ui.schedule

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.data.CalendarLogic
import com.blacklab.app.data.EventEntity
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

enum class CalKind { EVENT, TASK, CLASS, ASSIGNMENT, EXAM, DEVICE }

data class CalItem(
    val key: String,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val allDay: Boolean,
    val color: Color,
    val kind: CalKind,
    val refId: Long,
    val subtitle: String = "",
    val location: String = "",
    val busy: Boolean = true,
    val profile: String = "",
    val event: EventEntity? = null,
)

/** Merges every source of dated items into one list for a visible range. */
@Composable
fun rememberCalendarItems(from: LocalDate, to: LocalDate): List<CalItem> {
    val app = rememberApp()
    val ctx = LocalContext.current
    val s = settingsState()
    val profiles = remember(s.activeProfile, s.showOtherProfileInCalendar) {
        if (s.showOtherProfileInCalendar) Profile.entries.map { it.name } else listOf(s.activeProfile.name)
    }
    val events by remember(profiles) { app.db.events().observe(profiles) }.collectAsState(emptyList())
    val calendars by remember(profiles) { app.db.events().observeCalendars(profiles) }.collectAsState(emptyList())
    val tasks by remember(profiles) { app.db.tasks().observeDated(profiles) }.collectAsState(emptyList())
    val courses by remember(profiles) { app.db.education().observeCourses(profiles) }.collectAsState(emptyList())
    val meetings by remember(profiles) { app.db.education().observeMeetings(profiles) }.collectAsState(emptyList())
    val assignments by remember(profiles) { app.db.education().observeAssignments(profiles) }.collectAsState(emptyList())
    val device by produceState(emptyList<CalItem>(), from, to, s.showDeviceCalendars, s.hiddenDeviceCalendars) {
        value = if (s.showDeviceCalendars) withContext(Dispatchers.IO) { DeviceCalendar.items(ctx, from, to, s.hiddenDeviceCalendars) } else emptyList()
    }
    return remember(events, calendars, tasks, courses, meetings, assignments, device, from, to, s.showTasksInCalendar, s.showClassesInCalendar) {
        val out = ArrayList<CalItem>()
        val calById = calendars.associateBy { it.id }
        events.forEach { e ->
            val cal = e.calendarId?.let { calById[it] }
            if (cal?.visible == false) return@forEach
            val color = Color(e.color ?: cal?.color ?: 0xFF4F8CFF.toInt())
            CalendarLogic.expand(e, from, to).forEach { o ->
                out += CalItem("e${e.id}@${o.start}", e.title.ifBlank { "(No title)" }, o.start, o.end, e.allDay, color, CalKind.EVENT, e.id,
                    subtitle = cal?.name ?: "", location = e.location, busy = e.busy, profile = e.profile, event = e)
            }
        }
        if (s.showTasksInCalendar) tasks.forEach { t ->
            val d = LocalDate.ofEpochDay(t.dueDay ?: return@forEach)
            if (d < from || d > to) return@forEach
            val start = if (t.dueMinute != null) d.atStartOfDay().plusMinutes(t.dueMinute.toLong()) else d.atStartOfDay()
            out += CalItem("t${t.id}", "☐ " + t.title, start, start.plusMinutes((t.estimateMinutes ?: 30).toLong()), t.dueMinute == null,
                Color(0xFF8A8F98), CalKind.TASK, t.id, subtitle = "Task", busy = false, profile = t.profile)
        }
        if (s.showClassesInCalendar) {
            val byId = courses.associateBy { it.id }
            CalendarLogic.classOccurrences(courses, meetings, emptyMap(), from, to).forEach { o ->
                out += CalItem("c${o.meeting.id}@${o.start}", o.course.code.ifBlank { o.course.title } + " · " + o.meeting.kind, o.start, o.end, false,
                    Color(o.course.color), CalKind.CLASS, o.course.id, subtitle = o.course.title, location = o.meeting.location.ifBlank { o.course.location }, profile = o.course.profile)
            }
            assignments.forEach { a ->
                val due = a.dueAt ?: return@forEach
                val ldt = Time.toLdt(due)
                if (ldt.toLocalDate() < from || ldt.toLocalDate() > to) return@forEach
                val c = byId[a.courseId] ?: return@forEach
                val exam = a.kind.equals("Exam", true) || a.kind.equals("Quiz", true) || a.kind.equals("Midterm", true) || a.kind.equals("Final", true)
                out += CalItem("a${a.id}", (if (exam) "📝 " else "📌 ") + a.title, ldt, ldt.plusMinutes(if (exam) 90 else 30), false, Color(c.color),
                    if (exam) CalKind.EXAM else CalKind.ASSIGNMENT, c.id, subtitle = "${c.code.ifBlank { c.title }} · ${a.kind}", busy = exam, profile = c.profile)
            }
        }
        out += device
        out.sortedWith(compareBy<CalItem>({ !it.allDay }, { it.start }))
    }
}

object DeviceCalendar {
    data class Cal(val id: Long, val name: String, val account: String, val color: Int)

    fun hasPermission(ctx: Context) = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(ctx: Context): List<Cal> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<Cal>()
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.CALENDAR_COLOR),
                null, null, null,
            )?.use { c -> while (c.moveToNext()) out += Cal(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getInt(3)) }
        }
        return out
    }

    fun items(ctx: Context, from: LocalDate, to: LocalDate, hidden: Set<String>): List<CalItem> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<CalItem>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, Time.millis(from.minusDays(1)))
            ContentUris.appendId(it, Time.millis(to.plusDays(2)))
        }.build()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.EVENT_LOCATION,
                    CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.AVAILABILITY,
                ),
                null, null, CalendarContract.Instances.BEGIN,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(7).toString() in hidden) continue
                    val allDay = c.getInt(4) == 1
                    val (s, e) = if (allDay) {
                        val sd = Instant.ofEpochMilli(c.getLong(2)).atZone(ZoneOffset.UTC).toLocalDate()
                        val ed = Instant.ofEpochMilli(c.getLong(3)).atZone(ZoneOffset.UTC).toLocalDate()
                        sd.atStartOfDay() to ed.atStartOfDay()
                    } else Time.toLdt(c.getLong(2)) to Time.toLdt(c.getLong(3))
                    if (e.toLocalDate() < from || s.toLocalDate() > to) continue
                    out += CalItem("d${c.getLong(0)}@${c.getLong(2)}", c.getString(1) ?: "(busy)", s, e, allDay, Color(c.getInt(5)), CalKind.DEVICE,
                        c.getLong(0), subtitle = c.getString(8) ?: "", location = c.getString(6) ?: "",
                        busy = c.getInt(9) != CalendarContract.Events.AVAILABILITY_FREE)
                }
            }
        }
        return out
    }

    fun openEvent(ctx: Context, eventId: Long) {
        val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(i) }
    }

    /** Hands the event to Google Calendar (or any calendar app) for syncing to a cloud account. */
    fun insertIntent(e: EventEntity): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.start)
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
        putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, e.allDay)
        putExtra(CalendarContract.Events.TITLE, e.title)
        putExtra(CalendarContract.Events.EVENT_LOCATION, e.location)
        putExtra(CalendarContract.Events.DESCRIPTION, listOf(e.notes, e.url).filter { it.isNotBlank() }.joinToString("\n\n"))
        if (e.repeatRule != null) putExtra(CalendarContract.Events.RRULE, e.repeatRule)
        if (e.attendees.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, e.attendees)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** iCalendar (.ics) export/import so events move to and from Outlook / Google. */
object Ics {
    private val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US)
    private val dateOnly = DateTimeFormatter.BASIC_ISO_DATE

    fun export(events: List<EventEntity>): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//BlackLab//EN\r\nCALSCALE:GREGORIAN\r\n")
        val stamp = ZonedDateTime.now(ZoneOffset.UTC).format(utc)
        events.forEach { e ->
            append("BEGIN:VEVENT\r\n")
            append("UID:blacklab-${e.id}-${e.createdAt}@blacklab\r\n")
            append("DTSTAMP:$stamp\r\n")
            if (e.allDay) {
                append("DTSTART;VALUE=DATE:${Time.toDate(e.start).format(dateOnly)}\r\n")
                append("DTEND;VALUE=DATE:${Time.toDate(e.end).let { if (it <= Time.toDate(e.start)) it.plusDays(1) else it }.format(dateOnly)}\r\n")
            } else {
                append("DTSTART:${Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).format(utc)}\r\n")
                append("DTEND:${Instant.ofEpochMilli(e.end).atZone(ZoneOffset.UTC).format(utc)}\r\n")
            }
            fold("SUMMARY", e.title)
            if (e.location.isNotBlank()) fold("LOCATION", e.location)
            val desc = listOf(e.notes, e.url).filter { it.isNotBlank() }.joinToString("\n\n")
            if (desc.isNotBlank()) fold("DESCRIPTION", desc)
            if (e.url.isNotBlank()) fold("URL", e.url)
            e.repeatRule?.let { append("RRULE:${it.replace(Regex("UNTIL=(\\d{8})(?!T)"), "UNTIL=$1T235959Z")}\r\n") }
            e.attendees.split(',', ';', ' ').map { it.trim() }.filter { it.contains('@') }.forEach { append("ATTENDEE:mailto:$it\r\n") }
            if (!e.busy) append("TRANSP:TRANSPARENT\r\n")
            e.reminders.split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { m ->
                append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\nTRIGGER:-PT${m}M\r\nEND:VALARM\r\n")
            }
            append("END:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

    private fun StringBuilder.fold(key: String, value: String) {
        val esc = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        val line = "$key:$esc"
        var i = 0
        while (i < line.length) {
            val end = minOf(line.length, i + if (i == 0) 74 else 73)
            if (i > 0) append(' ')
            append(line, i, end).append("\r\n")
            i = end
        }
    }

    fun parse(text: String, profile: String, calendarId: Long?): List<EventEntity> {
        val lines = text.replace("\r\n ", "").replace("\n ", "").replace("\r\n\t", "").lines()
        val out = ArrayList<EventEntity>()
        var cur: MutableMap<String, String>? = null
        val alarms = ArrayList<Int>()
        var inAlarm = false
        for (raw in lines) {
            val line = raw.trimEnd()
            when {
                line == "BEGIN:VEVENT" -> { cur = HashMap(); alarms.clear() }
                line == "BEGIN:VALARM" -> inAlarm = true
                line == "END:VALARM" -> inAlarm = false
                line == "END:VEVENT" -> {
                    cur?.let { m -> toEvent(m, alarms, profile, calendarId)?.let(out::add) }
                    cur = null
                }
                cur != null -> {
                    val idx = line.indexOf(':'); if (idx <= 0) continue
                    val keyFull = line.substring(0, idx); val value = line.substring(idx + 1)
                    val key = keyFull.substringBefore(';').uppercase(Locale.US)
                    if (inAlarm) {
                        if (key == "TRIGGER") Regex("-?P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?").find(value)?.let { mm ->
                            alarms += (mm.groupValues[1].toIntOrNull() ?: 0) * 1440 + (mm.groupValues[2].toIntOrNull() ?: 0) * 60 + (mm.groupValues[3].toIntOrNull() ?: 0)
                        }
                    } else {
                        cur!![key] = value
                        cur!![key + "#params"] = keyFull
                    }
                }
            }
        }
        return out
    }

    private fun unescape(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseTime(value: String, params: String): Pair<Long, Boolean>? = runCatching {
        if (params.contains("VALUE=DATE") || value.length == 8) {
            Time.millis(LocalDate.parse(value.take(8), dateOnly)) to true
        } else if (value.endsWith("Z")) {
            LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")).toInstant(ZoneOffset.UTC).toEpochMilli() to false
        } else {
            val tz = Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)
            val ldt = LocalDateTime.parse(value.take(15), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            val zone = tz?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: Time.zone
            ldt.atZone(zone).toInstant().toEpochMilli() to false
        }
    }.getOrNull()

    private fun toEvent(m: Map<String, String>, alarms: List<Int>, profile: String, calendarId: Long?): EventEntity? {
        val (start, allDay) = parseTime(m["DTSTART"] ?: return null, m["DTSTART#params"] ?: "") ?: return null
        val end = m["DTEND"]?.let { parseTime(it, m["DTEND#params"] ?: "")?.first }
            ?: m["DURATION"]?.let { d -> Regex("P(?:(\\d+)D)?T?(?:(\\d+)H)?(?:(\\d+)M)?").find(d)?.let { mm ->
                start + ((mm.groupValues[1].toLongOrNull() ?: 0) * 1440 + (mm.groupValues[2].toLongOrNull() ?: 0) * 60 + (mm.groupValues[3].toLongOrNull() ?: 0)) * 60_000
            } }
            ?: (start + if (allDay) 86_400_000L else 3_600_000L)
        val rule = m["RRULE"]?.let { r -> Recurrence.parse(r)?.toRule() }
        return EventEntity(
            profile = profile, calendarId = calendarId,
            title = unescape(m["SUMMARY"] ?: "Event"),
            location = unescape(m["LOCATION"] ?: ""),
            notes = unescape(m["DESCRIPTION"] ?: ""),
            url = m["URL"] ?: "",
            start = start, end = end, allDay = allDay, repeatRule = rule,
            reminders = alarms.distinct().joinToString(","),
            busy = m["TRANSP"]?.uppercase(Locale.US) != "TRANSPARENT",
        )
    }

    @Suppress("unused")
    fun uid(): String = UUID.randomUUID().toString()
}
