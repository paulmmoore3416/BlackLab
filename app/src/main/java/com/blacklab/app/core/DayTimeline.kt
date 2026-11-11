package com.blacklab.app.core

import com.blacklab.app.data.RecordingEntity
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.facility.Facility
import com.blacklab.app.vehicle.Telemetry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One timeline per day across BlackLab — work orders and inspections, tasks, meetings, recordings, notes, drives,
 * and Behavioral Health as **counts only** — plus the ops handoff: what happened, what's still open, what's next.
 * Pure (unit tested in DayTimelineTest); TimelineScreen and the assistant gather the inputs.
 *
 * The handoff text never includes Behavioral Health, not even counts: it's the part that gets shared.
 */
object DayTimeline {
    enum class Kind(val label: String, val emoji: String) {
        EVENT("Meeting", "📅"), WORK_ORDER("Work order", "🛠️"), INSPECTION("Inspection", "🔍"), FINDING("Finding", "⚠️"), FACILITY_NOTE("Facility note", "🏢"),
        TASK_DONE("Done", "✅"), TASK_NEW("New task", "➕"), RECORDING("Recording", "🎙️"), NOTE("Note", "📝"), TRIP("Drive", "🚙"), BH("Behavioral Health", "🔒"),
    }

    data class Item(val time: Long, val kind: Kind, val title: String, val detail: String = "", val route: String? = null)

    data class Event(val start: Long, val title: String, val location: String = "")

    data class BhCounts(val checks: Int = 0, val eocRounds: Int = 0, val eocIssues: Int = 0) {
        val any: Boolean get() = checks + eocRounds > 0
    }

    data class Inputs(
        val day: LocalDate,
        val zone: ZoneId,
        val tasks: List<TaskEntity> = emptyList(),
        val events: List<Event> = emptyList(),
        val facility: Facility.State = Facility.State(),
        val trips: List<Telemetry.Trip> = emptyList(),
        val recordings: List<RecordingEntity> = emptyList(),
        val notes: List<Pair<Long, String>> = emptyList(),
        val bh: BhCounts = BhCounts(),
    )

    private val hm = DateTimeFormatter.ofPattern("h:mm a")
    fun clock(t: Long, zone: ZoneId): String = Instant.ofEpochMilli(t).atZone(zone).format(hm)

    fun build(i: Inputs): List<Item> {
        val start = i.day.atStartOfDay(i.zone).toInstant().toEpochMilli()
        val end = i.day.plusDays(1).atStartOfDay(i.zone).toInstant().toEpochMilli()
        fun inDay(t: Long?) = t != null && t in start until end
        val out = ArrayList<Item>()
        i.events.filter { inDay(it.start) }.forEach { out += Item(it.start, Kind.EVENT, it.title.ifBlank { "Event" }, it.location) }
        val assets = i.facility.assets.associateBy { it.id }
        i.facility.log.filter { it.day == i.day.toEpochDay() }.forEach { e ->
            val a = assets[e.assetId]
            val pm = a?.pms?.firstOrNull { it.id == e.pmId }
            val kind = when (e.kind) {
                Facility.Kind.WORK_ORDER -> Kind.WORK_ORDER
                Facility.Kind.PM -> Kind.INSPECTION
                Facility.Kind.FINDING -> Kind.FINDING
                Facility.Kind.NOTE -> Kind.FACILITY_NOTE
            }
            val t = if (inDay(e.time)) e.time else start + 12 * 3_600_000L
            out += Item(t, kind, listOfNotNull(pm?.title, a?.name).joinToString(" · ").ifBlank { e.kind.label },
                listOfNotNull(e.result.label.ifBlank { null }, e.text.ifBlank { null }, e.by.ifBlank { null }?.let { "by $it" }).joinToString(" — "),
                a?.let { "facility/asset/${it.id}" })
        }
        i.tasks.forEach { t ->
            if (t.completed && inDay(t.completedAt)) out += Item(t.completedAt!!, Kind.TASK_DONE, t.title, route = "task/${t.id}")
            else if (inDay(t.createdAt)) out += Item(t.createdAt, if (isWorkOrder(t)) Kind.WORK_ORDER else Kind.TASK_NEW, t.title, route = "task/${t.id}")
        }
        i.recordings.filter { inDay(it.createdAt) }.forEach { r ->
            out += Item(r.createdAt, Kind.RECORDING, r.title.ifBlank { "Recording" }, if (r.durationMs > 0) "${(r.durationMs / 60_000).coerceAtLeast(1)} min" else "", "recording/${r.id}")
        }
        i.notes.filter { inDay(it.first) }.forEach { (t, title) -> out += Item(t, Kind.NOTE, title.ifBlank { "Note" }) }
        i.trips.filter { inDay(it.start) && !it.simulated }.forEach { tr ->
            out += Item(tr.start, Kind.TRIP, "%.1f mi".format(tr.miles) + (tr.rule.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), route = "garage")
        }
        if (i.bh.any) out += Item(end - 1, Kind.BH, "Behavioral Health", listOfNotNull(
            i.bh.checks.takeIf { it > 0 }?.let { "$it observation check${if (it == 1) "" else "s"}" },
            i.bh.eocRounds.takeIf { it > 0 }?.let { "$it environmental round${if (it == 1) "" else "s"}" + if (i.bh.eocIssues > 0) " (${i.bh.eocIssues} issue${if (i.bh.eocIssues == 1) "" else "s"})" else "" },
        ).joinToString(" · "), "bh")
        return out.sortedBy { it.time }
    }

    fun isWorkOrder(t: TaskEntity) = t.tags.contains("work-order", true) || t.tags.contains("workorder", true) || t.title.startsWith("WO ") || t.title.startsWith("WO:")

    /**
     * The ops handoff: highlights of the day, open work orders (most urgent first), inspections due soon, and tomorrow.
     * Never mentions Behavioral Health.
     */
    fun handoff(day: LocalDate, zone: ZoneId, items: List<Item>, openTasks: List<TaskEntity>, due: List<Facility.Due>, tomorrow: List<Event>, by: String = ""): String = buildString {
        appendLine("Ops handoff — ${day.format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy"))}" + (if (by.isNotBlank()) " · $by" else ""))
        appendLine()
        val shareable = items.filter { it.kind != Kind.BH }
        val wo = shareable.filter { it.kind == Kind.WORK_ORDER }
        val insp = shareable.filter { it.kind == Kind.INSPECTION || it.kind == Kind.FINDING }
        val done = shareable.filter { it.kind == Kind.TASK_DONE }
        appendLine("Today: ${wo.size} work order${if (wo.size == 1) "" else "s"} · ${insp.size} inspection${if (insp.size == 1) "" else "s"}/finding${if (insp.size == 1) "" else "s"} · ${done.size} task${if (done.size == 1) "" else "s"} done")
        if (insp.isNotEmpty() || wo.isNotEmpty()) {
            appendLine()
            appendLine("What happened")
            (wo + insp).sortedBy { it.time }.forEach { appendLine("• ${clock(it.time, zone)} ${it.kind.label}: ${it.title}" + if (it.detail.isNotBlank()) " — ${it.detail}" else "") }
        }
        val openWo = openTasks.filter { !it.completed && isWorkOrder(it) }.sortedWith(compareByDescending<TaskEntity> { it.priority }.thenBy { it.dueDay ?: Long.MAX_VALUE })
        appendLine()
        appendLine("Open work orders (${openWo.size})")
        if (openWo.isEmpty()) appendLine("• None")
        openWo.take(15).forEach { t ->
            val late = t.dueDay?.let { d -> if (d < day.toEpochDay()) " — OVERDUE" else if (d == day.toEpochDay()) " — due today" else "" } ?: ""
            appendLine("• ${t.title}$late")
        }
        if (openWo.size > 15) appendLine("• …and ${openWo.size - 15} more")
        val attention = due.filter { it.status != Facility.Status.OK }
        if (attention.isNotEmpty()) {
            appendLine()
            appendLine("Inspections needing attention (${attention.size})")
            attention.take(10).forEach { d -> appendLine("• ${d.pm.title} — ${d.asset.name}: ${d.describe()}") }
        }
        if (tomorrow.isNotEmpty()) {
            appendLine()
            appendLine("Tomorrow")
            tomorrow.sortedBy { it.start }.take(10).forEach { e -> appendLine("• ${clock(e.start, zone)} ${e.title}" + if (e.location.isNotBlank()) " · ${e.location}" else "") }
        }
    }.trim()
}
