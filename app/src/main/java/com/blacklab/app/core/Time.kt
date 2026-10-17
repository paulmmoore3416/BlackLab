package com.blacklab.app.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Time {
    val zone: ZoneId get() = ZoneId.systemDefault()

    fun now(): Long = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now()

    fun toLdt(millis: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)
    fun toDate(millis: Long): LocalDate = toLdt(millis).toLocalDate()
    fun millis(ldt: LocalDateTime): Long = ldt.atZone(zone).toInstant().toEpochMilli()
    fun millis(date: LocalDate, minuteOfDay: Int = 0): Long =
        millis(date.atStartOfDay().plusMinutes(minuteOfDay.toLong()))

    fun minuteOfDay(ldt: LocalDateTime): Int = ldt.hour * 60 + ldt.minute
    fun minuteOfDay(millis: Long): Int = minuteOfDay(toLdt(millis))

    /** UTC-midnight millis used by Material DatePicker <-> LocalDate. */
    fun pickerMillis(date: LocalDate): Long = date.toEpochDay() * 86_400_000L
    fun fromPickerMillis(ms: Long): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(ms, 86_400_000L))

    private val fmtTime = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val fmtDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val fmtDateYear = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
    private val fmtLong = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val fmtMonthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val fmtShort = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val fmtNumeric = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)

    fun time(minuteOfDay: Int): String = LocalTime.of((minuteOfDay / 60) % 24, minuteOfDay % 60).format(fmtTime)
    fun time(millis: Long): String = toLdt(millis).format(fmtTime)
    fun time(ldt: LocalDateTime): String = ldt.format(fmtTime)
    fun date(date: LocalDate): String = if (date.year == today().year) date.format(fmtDate) else date.format(fmtDateYear)
    fun date(millis: Long): String = date(toDate(millis))
    fun longDate(date: LocalDate): String = date.format(fmtLong)
    fun monthYear(date: LocalDate): String = date.format(fmtMonthYear)
    fun short(date: LocalDate): String = date.format(fmtShort)
    fun numeric(date: LocalDate): String = date.format(fmtNumeric)
    fun dateTime(millis: Long): String = "${date(millis)} · ${time(millis)}"

    fun relativeDay(date: LocalDate): String {
        val t = today()
        return when (ChronoUnit.DAYS.between(t, date)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            in 2..6 -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            else -> date(date)
        }
    }

    fun ago(millis: Long): String {
        val d = Duration.between(Instant.ofEpochMilli(millis), Instant.now())
        return when {
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
            d.toHours() < 24 -> "${d.toHours()}h ago"
            d.toDays() < 7 -> "${d.toDays()}d ago"
            else -> date(millis)
        }
    }

    fun duration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun minutesLabel(min: Int): String = when {
        min <= 0 -> "At time"
        min < 60 -> "$min min"
        min % 1440 == 0 -> "${min / 1440} day" + if (min / 1440 > 1) "s" else ""
        min % 60 == 0 -> "${min / 60} hr"
        else -> "${min / 60} hr ${min % 60} min"
    }

    fun hoursLabel(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    fun weekStart(date: LocalDate, firstDay: Int): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.of(firstDay)))

    fun dayShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, Locale.US)
    fun dayNarrow(d: DayOfWeek): String = d.getDisplayName(TextStyle.NARROW, Locale.US)
    fun dayFull(d: DayOfWeek): String = d.getDisplayName(TextStyle.FULL, Locale.US)

    fun greeting(): String = when (LocalTime.now().hour) {
        in 4..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
}

/**
 * RRULE-style recurrence subset shared by tasks, events and reminders.
 * Serialized like "FREQ=WEEKLY;INTERVAL=1;BYDAY=MO,WE;UNTIL=20261231;COUNT=10".
 */
data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    fun toRule(): String = buildString {
        append("FREQ=").append(freq.name)
        if (interval > 1) append(";INTERVAL=").append(interval)
        if (byDay.isNotEmpty()) append(";BYDAY=").append(byDay.sorted().joinToString(",") { code(it) })
        until?.let { append(";UNTIL=").append(it.format(DateTimeFormatter.BASIC_ISO_DATE)) }
        count?.let { append(";COUNT=").append(it) }
    }

    fun describe(anchor: LocalDate? = null): String {
        val base = when (freq) {
            Freq.DAILY -> if (interval == 1) "Every day" else "Every $interval days"
            Freq.WEEKLY -> {
                val days = byDay.ifEmpty { anchor?.let { setOf(it.dayOfWeek) } ?: emptySet() }
                val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
                when {
                    interval == 1 && days == weekdays -> "Every weekday"
                    else -> (if (interval == 1) "Weekly" else "Every $interval weeks") +
                        if (days.isNotEmpty()) " on " + days.sorted().joinToString(", ") { Time.dayShort(it) } else ""
                }
            }
            Freq.MONTHLY -> (if (interval == 1) "Monthly" else "Every $interval months") + (anchor?.let { " on day ${it.dayOfMonth}" } ?: "")
            Freq.YEARLY -> (if (interval == 1) "Yearly" else "Every $interval years") + (anchor?.let { " on ${Time.short(it)}" } ?: "")
        }
        val end = when {
            until != null -> " until ${Time.date(until)}"
            count != null -> ", $count times"
            else -> ""
        }
        return base + end
    }

    /** All occurrence dates of a series anchored at [anchor] that fall in [from]..[to]. */
    fun occurrences(anchor: LocalDate, from: LocalDate, to: LocalDate, exDates: Set<LocalDate> = emptySet()): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var produced = 0
        val stop = minOf(to, until ?: to)
        fun accept(d: LocalDate): Boolean {
            if (d < anchor) return true
            if (d > stop) return false
            produced++
            if (count != null && produced > count) return false
            if (d >= from && d !in exDates) out.add(d)
            return true
        }
        when (freq) {
            Freq.DAILY -> {
                var d = anchor
                // jump close to the window for long-running series without COUNT
                if (count == null && from > anchor) {
                    val skip = ChronoUnit.DAYS.between(anchor, from) / interval
                    d = anchor.plusDays(skip * interval)
                }
                while (accept(d)) d = d.plusDays(interval.toLong())
            }
            Freq.WEEKLY -> {
                val days = byDay.ifEmpty { setOf(anchor.dayOfWeek) }.sorted()
                var weekStart = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                if (count == null && from > anchor) {
                    val weeks = ChronoUnit.WEEKS.between(weekStart, from) / interval
                    weekStart = weekStart.plusWeeks(weeks * interval)
                }
                loop@ while (true) {
                    for (dow in days) {
                        val d = weekStart.plusDays((dow.value - 1).toLong())
                        if (!accept(d)) break@loop
                    }
                    weekStart = weekStart.plusWeeks(interval.toLong())
                    if (weekStart > stop) break
                }
            }
            Freq.MONTHLY -> {
                var k = 0L
                while (true) {
                    val d = anchor.plusMonths(k * interval)
                    if (!accept(d)) break
                    k++
                }
            }
            Freq.YEARLY -> {
                var k = 0L
                while (true) {
                    val d = anchor.plusYears(k * interval)
                    if (!accept(d)) break
                    k++
                }
            }
        }
        return out
    }

    /** First occurrence strictly after [after]. */
    fun nextAfter(anchor: LocalDate, after: LocalDate): LocalDate? =
        occurrences(anchor, after.plusDays(1), after.plusYears(5).plusDays(1)).firstOrNull()

    companion object {
        private fun code(d: DayOfWeek) = d.name.take(2)
        private fun fromCode(c: String) = DayOfWeek.entries.firstOrNull { it.name.startsWith(c.uppercase()) }

        fun parse(rule: String?): Recurrence? {
            if (rule.isNullOrBlank()) return null
            val parts = rule.removePrefix("RRULE:").split(';').mapNotNull {
                val i = it.indexOf('='); if (i < 0) null else it.substring(0, i).uppercase() to it.substring(i + 1)
            }.toMap()
            val freq = runCatching { Freq.valueOf(parts["FREQ"] ?: return null) }.getOrNull() ?: return null
            return Recurrence(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                byDay = parts["BYDAY"]?.split(',')?.mapNotNull { fromCode(it.takeLast(2)) }?.toSet() ?: emptySet(),
                until = parts["UNTIL"]?.let { runCatching { LocalDate.parse(it.take(8), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() },
                count = parts["COUNT"]?.toIntOrNull(),
            )
        }

        val presets: List<Pair<String, String?>> = listOf(
            "Does not repeat" to null,
            "Every day" to "FREQ=DAILY",
            "Every weekday (Mon–Fri)" to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            "Every week" to "FREQ=WEEKLY",
            "Every 2 weeks" to "FREQ=WEEKLY;INTERVAL=2",
            "Every month" to "FREQ=MONTHLY",
            "Every 3 months" to "FREQ=MONTHLY;INTERVAL=3",
            "Every year" to "FREQ=YEARLY",
        )
    }
}

fun parseExDates(s: String): Set<LocalDate> =
    s.split(',').mapNotNull { it.trim().toLongOrNull()?.let(LocalDate::ofEpochDay) }.toSet()

fun Set<LocalDate>.toExDateString(): String = joinToString(",") { it.toEpochDay().toString() }
