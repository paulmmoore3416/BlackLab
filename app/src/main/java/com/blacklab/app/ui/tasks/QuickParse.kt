package com.blacklab.app.ui.tasks

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Natural-language quick add, e.g.
 *   "Inspect boiler tomorrow 9am !high #hvac every week @Inspections"
 */
object QuickParse {
    data class Parsed(
        val title: String,
        val dueDay: LocalDate? = null,
        val dueMinute: Int? = null,
        val priority: Int = 0,
        val tags: List<String> = emptyList(),
        val repeat: String? = null,
        val listName: String? = null,
        val important: Boolean = false,
    )

    private val days = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "weds" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY, "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private const val DAY_RE = "(monday|mon|tuesday|tues|tue|wednesday|weds|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)"
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(input: String, today: LocalDate = LocalDate.now(), now: LocalTime = LocalTime.now()): Parsed {
        var text = " $input "
        var due: LocalDate? = null
        var minute: Int? = null
        var priority = 0
        var repeat: String? = null
        var important = false
        val tags = ArrayList<String>()
        var list: String? = null

        fun take(re: Regex, block: (MatchResult) -> Unit) {
            val m = re.find(text) ?: return
            block(m)
            text = text.removeRange(m.range).let { " " + it.trim() + " " }
        }
        val i = RegexOption.IGNORE_CASE

        // tags, list, priority, importance
        Regex("(?<=\\s)#([\\p{L}\\p{N}_-]+)").findAll(text).toList().forEach { tags += it.groupValues[1] }
        text = text.replace(Regex("(?<=\\s)#[\\p{L}\\p{N}_-]+"), " ")
        take(Regex("(?<=\\s)@([\\p{L}\\p{N}_-]+)")) { list = it.groupValues[1] }
        take(Regex("(?<=\\s)!(urgent|high|medium|med|low|[1-4]|!{0,3})(?=\\s)", i)) {
            priority = when (it.groupValues[1].lowercase(Locale.US)) {
                "urgent", "4", "!!!" -> 4
                "high", "3", "!!" -> 3
                "medium", "med", "2", "!" -> 2
                else -> 1
            }
        }
        take(Regex("(?<=\\s)\\*(?=\\s)")) { important = true }

        // recurrence
        take(Regex("\\s(every\\s+weekday|weekdays)(?=\\s)", i)) { repeat = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" }
        take(Regex("\\severy\\s+(\\d+)\\s+(day|week|month|year)s?(?=\\s)", i)) {
            repeat = "FREQ=${freq(it.groupValues[2])};INTERVAL=${it.groupValues[1]}"
        }
        take(Regex("\\severy\\s+other\\s+(day|week|month|year)(?=\\s)", i)) { repeat = "FREQ=${freq(it.groupValues[1])};INTERVAL=2" }
        take(Regex("\\severy\\s+$DAY_RE(?=\\s)", i)) {
            val d = days[it.groupValues[1].lowercase(Locale.US)]!!
            repeat = "FREQ=WEEKLY;BYDAY=${d.name.take(2)}"
            if (due == null) due = today.with(TemporalAdjusters.nextOrSame(d))
        }
        take(Regex("\\s(every\\s+(day|week|month|year)|daily|weekly|monthly|yearly|annually)(?=\\s)", i)) {
            val w = it.groupValues[1].lowercase(Locale.US)
            repeat = when {
                w.contains("day") || w == "daily" -> "FREQ=DAILY"
                w.contains("week") -> "FREQ=WEEKLY"
                w.contains("month") -> "FREQ=MONTHLY"
                else -> "FREQ=YEARLY"
            }
        }

        // time
        take(Regex("\\s(?:at\\s+|@\\s*)?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a|p)(?=\\s)", i)) {
            var h = it.groupValues[1].toInt() % 12
            if (it.groupValues[3].lowercase(Locale.US).startsWith("p")) h += 12
            minute = h * 60 + (it.groupValues[2].toIntOrNull() ?: 0)
        }
        if (minute == null) take(Regex("\\sat\\s+(\\d{1,2}):(\\d{2})(?=\\s)", i)) {
            minute = it.groupValues[1].toInt().coerceIn(0, 23) * 60 + it.groupValues[2].toInt().coerceIn(0, 59)
        }
        if (minute == null) take(Regex("\\s(?:at\\s+)?(\\d{1,2}):(\\d{2})(?=\\s)", i)) {
            minute = it.groupValues[1].toInt().coerceIn(0, 23) * 60 + it.groupValues[2].toInt().coerceIn(0, 59)
        }
        if (minute == null) take(Regex("\\s(?:at\\s+|in\\s+the\\s+)?(noon|midnight|morning|afternoon|evening|tonight|eod|end of day)(?=\\s)", i)) {
            when (it.groupValues[1].lowercase(Locale.US)) {
                "noon" -> minute = 12 * 60
                "midnight" -> minute = 23 * 60 + 59
                "morning" -> minute = 9 * 60
                "afternoon" -> minute = 14 * 60
                "evening" -> minute = 18 * 60
                "tonight" -> { minute = 20 * 60; if (due == null) due = today }
                else -> minute = 17 * 60
            }
        }

        // dates
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+)?(today|tod)(?=\\s)", i)) { due = today }
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+)?(tomorrow|tmrw|tmr|tom)(?=\\s)", i)) { due = today.plusDays(1) }
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+)?(day after tomorrow)(?=\\s)", i)) { due = today.plusDays(2) }
        take(Regex("\\sin\\s+(\\d+|a|an|one|two|three)\\s+(day|week|month)s?(?=\\s)", i)) {
            val n = when (it.groupValues[1].lowercase(Locale.US)) { "a", "an", "one" -> 1L; "two" -> 2L; "three" -> 3L; else -> it.groupValues[1].toLong() }
            due = when (it.groupValues[2].lowercase(Locale.US)) { "day" -> today.plusDays(n); "week" -> today.plusWeeks(n); else -> today.plusMonths(n) }
        }
        take(Regex("\\s(?:by\\s+)?(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(week|month)(?=\\s)", i)) {
            due = if (it.groupValues[1].equals("week", true)) today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)) else today.with(TemporalAdjusters.lastDayOfMonth())
        }
        take(Regex("\\snext\\s+(week|month)(?=\\s)", i)) {
            due = if (it.groupValues[1].equals("week", true)) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else today.plusMonths(1).withDayOfMonth(1)
        }
        take(Regex("\\snext\\s+$DAY_RE(?=\\s)", i)) {
            val d = days[it.groupValues[1].lowercase(Locale.US)]!!
            due = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(d))
        }
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+|this\\s+)?$DAY_RE(?=\\s)", i)) {
            due = today.with(TemporalAdjusters.nextOrSame(days[it.groupValues[1].lowercase(Locale.US)]!!))
        }
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?(?=\\s)", i)) {
            val m = months.indexOf(it.groupValues[1].lowercase(Locale.US).take(3)) + 1
            val d = it.groupValues[2].toInt()
            val y = it.groupValues[3].toIntOrNull()
            due = runCatching {
                var date = LocalDate.of(y ?: today.year, m, d)
                if (y == null && date < today) date = date.plusYears(1)
                date
            }.getOrNull()
        }
        take(Regex("\\s(?:on\\s+|by\\s+|due\\s+)?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?(?=\\s)", i)) {
            val y = it.groupValues[3].toIntOrNull()?.let { yy -> if (yy < 100) 2000 + yy else yy }
            due = runCatching {
                var date = LocalDate.of(y ?: today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
                if (y == null && date < today) date = date.plusYears(1)
                date
            }.getOrNull()
        }

        if (minute != null && due == null) {
            due = if (minute!! <= now.hour * 60 + now.minute) today.plusDays(1) else today
        }
        if (repeat != null && due == null) due = today

        val title = text.replace(Regex("\\s+"), " ").trim()
            .replace(Regex("\\s+(on|at|by|due|for)$", i), "")
            .trim()
        return Parsed(title.ifBlank { input.trim() }, due, minute, priority, tags, repeat, list, important)
    }

    private fun freq(unit: String) = when (unit.lowercase(Locale.US)) {
        "day" -> "DAILY"; "week" -> "WEEKLY"; "month" -> "MONTHLY"; else -> "YEARLY"
    }
}
