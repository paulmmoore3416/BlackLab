package com.blacklab.app.data

import com.blacklab.app.core.Recurrence
import com.blacklab.app.core.Time
import com.blacklab.app.core.parseExDates
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

data class Occurrence(val event: EventEntity, val start: LocalDateTime, val end: LocalDateTime)

data class ClassOccurrence(val course: CourseEntity, val meeting: ClassMeetingEntity, val start: LocalDateTime, val end: LocalDateTime)

object CalendarLogic {

    fun overlapsDay(start: LocalDateTime, end: LocalDateTime, day: LocalDate): Boolean {
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        return start < dayEnd && (end > dayStart || (end == start && start >= dayStart))
    }

    fun expand(e: EventEntity, from: LocalDate, to: LocalDate): List<Occurrence> {
        val s = Time.toLdt(e.start)
        val en = Time.toLdt(e.end).let { if (it < s) s else it }
        val dur = Duration.between(s, en)
        val rule = Recurrence.parse(e.repeatRule)
        if (rule == null) {
            val rangeStart = from.atStartOfDay()
            val rangeEnd = to.plusDays(1).atStartOfDay()
            return if (s < rangeEnd && (en > rangeStart || (en == s && s >= rangeStart))) listOf(Occurrence(e, s, en)) else emptyList()
        }
        val spanDays = ChronoUnit.DAYS.between(s.toLocalDate(), en.toLocalDate()).coerceAtLeast(0)
        return rule.occurrences(s.toLocalDate(), from.minusDays(spanDays), to, parseExDates(e.exDates)).map { d ->
            val st = d.atTime(s.toLocalTime())
            Occurrence(e, st, st.plus(dur))
        }
    }

    fun classOccurrences(
        courses: List<CourseEntity>,
        meetings: List<ClassMeetingEntity>,
        terms: Map<Long, TermEntity>,
        from: LocalDate,
        to: LocalDate,
    ): List<ClassOccurrence> {
        val byId = courses.associateBy { it.id }
        val out = ArrayList<ClassOccurrence>()
        for (m in meetings) {
            val c = byId[m.courseId] ?: continue
            if (c.status == "DROPPED" || c.status == "COMPLETED") continue
            val term = c.termId?.let { terms[it] }
            val startLimit = (c.startDay ?: term?.startDay)?.let(LocalDate::ofEpochDay)
            val endLimit = (c.endDay ?: term?.endDay)?.let(LocalDate::ofEpochDay)
            var d = from
            val dow = DayOfWeek.of(m.dayOfWeek.coerceIn(1, 7))
            while (d.dayOfWeek != dow) d = d.plusDays(1)
            while (d <= to) {
                if ((startLimit == null || d >= startLimit) && (endLimit == null || d <= endLimit)) {
                    out += ClassOccurrence(c, m, d.atStartOfDay().plusMinutes(m.startMinute.toLong()), d.atStartOfDay().plusMinutes(m.endMinute.toLong()))
                }
                d = d.plusWeeks(1)
            }
        }
        return out.sortedBy { it.start }
    }

    /** Free time slots between work hours on each day of [from]..[to] that can fit [minutes]. */
    fun freeSlots(
        busy: List<Pair<LocalDateTime, LocalDateTime>>,
        from: LocalDate,
        to: LocalDate,
        workStart: Int,
        workEnd: Int,
        minutes: Int,
        skipWeekends: Boolean = true,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        val out = ArrayList<Pair<LocalDateTime, LocalDateTime>>()
        val now = LocalDateTime.now()
        var day = from
        while (day <= to) {
            if (!(skipWeekends && (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY))) {
                var cursor = day.atStartOfDay().plusMinutes(workStart.toLong())
                if (cursor < now) cursor = now.withSecond(0).withNano(0).plusMinutes((15 - now.minute % 15).toLong() % 15)
                val endOfDay = day.atStartOfDay().plusMinutes(workEnd.toLong())
                val dayBusy = busy.filter { it.first < endOfDay && it.second > cursor }.sortedBy { it.first }
                for ((bs, be) in dayBusy) {
                    if (Duration.between(cursor, bs).toMinutes() >= minutes) out += cursor to bs
                    if (be > cursor) cursor = be
                }
                if (Duration.between(cursor, endOfDay).toMinutes() >= minutes) out += cursor to endOfDay
            }
            day = day.plusDays(1)
        }
        return out
    }
}
