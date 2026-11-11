package com.blacklab.app.facility

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * Facility: your buildings' equipment (assets with QR / asset tags), preventive-maintenance and life-safety
 * inspection schedules with what's due, and a log of every inspection, work order and finding. Kept in one
 * JSON file in app storage (included in encrypted backups). The logic below is pure so it is unit-tested.
 */
object Facility {

    val KINDS = listOf("HVAC", "Electrical", "Plumbing", "Life safety", "Generator", "Fire protection", "Elevator", "Roof & envelope", "Grounds", "Kitchen", "Other")

    data class Pm(
        val id: String = newId(),
        val title: String,
        val everyDays: Int,
        /** The code or standard it satisfies, e.g. "NFPA 10". */
        val standard: String = "",
        val checklist: List<String> = emptyList(),
        val lastDay: Long? = null,
        /** First due date when it has never been done. */
        val startDay: Long = LocalDate.now().toEpochDay(),
        val note: String = "",
    ) {
        val dueDay: Long get() = lastDay?.plus(everyDays) ?: startDay
    }

    data class Asset(
        val id: String = newId(),
        val name: String,
        val kind: String = "Other",
        val tag: String = "",
        val location: String = "",
        val make: String = "",
        val model: String = "",
        val serial: String = "",
        val installedDay: Long? = null,
        val notes: String = "",
        val pms: List<Pm> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
    )

    enum class Kind(val label: String) { PM("Inspection / PM"), WORK_ORDER("Work order"), FINDING("Finding"), NOTE("Note") }
    enum class Result(val label: String) { PASS("Pass"), FAIL("Fail"), NA("N/A"), NONE("") }

    data class Entry(
        val id: String = newId(),
        val assetId: String,
        val pmId: String? = null,
        val day: Long,
        val kind: Kind,
        val result: Result = Result.NONE,
        val text: String = "",
        val by: String = "",
        val taskId: Long? = null,
        val time: Long = System.currentTimeMillis(),
    )

    data class State(val assets: List<Asset> = emptyList(), val log: List<Entry> = emptyList(), val site: String = "")

    enum class Status { OVERDUE, DUE_SOON, OK }

    data class Due(val asset: Asset, val pm: Pm, val dueDay: Long, val daysLeft: Long, val status: Status) {
        fun describe(): String = when {
            daysLeft < 0 -> "${-daysLeft} day${if (daysLeft == -1L) "" else "s"} overdue"
            daysLeft == 0L -> "due today"
            daysLeft == 1L -> "due tomorrow"
            else -> "due in $daysLeft days"
        }
    }

    /** How far ahead something counts as "due soon": a day for weekly checks, a week for monthly, two weeks for longer. */
    fun soonWindow(everyDays: Int): Long = (everyDays / 4).coerceIn(1, 14).toLong()

    fun status(pm: Pm, today: Long): Status {
        val left = pm.dueDay - today
        return when {
            left < 0 -> Status.OVERDUE
            left <= soonWindow(pm.everyDays) -> Status.DUE_SOON
            else -> Status.OK
        }
    }

    /** Every schedule with its due date, soonest first. */
    fun due(s: State, today: Long = LocalDate.now().toEpochDay()): List<Due> =
        s.assets.flatMap { a -> a.pms.map { p -> Due(a, p, p.dueDay, p.dueDay - today, status(p, today)) } }.sortedWith(compareBy({ it.dueDay }, { it.asset.name }))

    fun normalizeTag(tag: String) = tag.trim().uppercase()

    fun findByTag(s: State, tag: String): Asset? = normalizeTag(tag).takeIf { it.isNotEmpty() }?.let { t -> s.assets.firstOrNull { normalizeTag(it.tag) == t } }

    /** Records a completed inspection or PM: logs it and moves the schedule forward from [day]. */
    fun complete(s: State, assetId: String, pmId: String, day: Long, result: Result, text: String, by: String, taskId: Long? = null): State {
        val entry = Entry(assetId = assetId, pmId = pmId, day = day, kind = Kind.PM, result = result, text = text.trim(), by = by.trim(), taskId = taskId)
        val assets = s.assets.map { a ->
            if (a.id != assetId) a else a.copy(pms = a.pms.map { p -> if (p.id == pmId && (p.lastDay == null || day >= p.lastDay)) p.copy(lastDay = day) else p })
        }
        return s.copy(assets = assets, log = listOf(entry) + s.log)
    }

    fun upsertAsset(s: State, a: Asset): State =
        if (s.assets.any { it.id == a.id }) s.copy(assets = s.assets.map { if (it.id == a.id) a else it }) else s.copy(assets = s.assets + a)

    fun deleteAsset(s: State, id: String): State = s.copy(assets = s.assets.filterNot { it.id == id }, log = s.log.filterNot { it.assetId == id })

    fun addEntry(s: State, e: Entry): State = s.copy(log = listOf(e) + s.log)

    fun history(s: State, assetId: String): List<Entry> = s.log.filter { it.assetId == assetId }.sortedWith(compareByDescending<Entry> { it.day }.thenByDescending { it.time })

    // ---- life-safety & PM catalog ------------------------------------------------------------------------

    /**
     * Common inspection and testing intervals. They are starting points: the authority having jurisdiction,
     * your accreditor and the manufacturer can require more, so every schedule stays editable.
     */
    data class Standard(val id: String, val kind: String, val asset: String, val title: String, val everyDays: Int, val code: String, val checklist: List<String>)

    val STANDARDS = listOf(
        Standard("ext-monthly", "Life safety", "Fire extinguishers", "Monthly inspection", 30, "NFPA 10",
            listOf("In its place, visible and unobstructed", "Pressure gauge in the green", "Pin and tamper seal intact", "No damage, corrosion or leaks", "Tag initialed and dated")),
        Standard("ext-annual", "Life safety", "Fire extinguishers", "Annual maintenance (licensed contractor)", 365, "NFPA 10",
            listOf("Contractor maintenance tag attached", "6-year / hydrostatic test dates checked")),
        Standard("gen-weekly", "Generator", "Emergency generator", "Weekly inspection", 7, "NFPA 110",
            listOf("Fuel level", "Coolant level and heater working", "Oil level", "Battery and charger normal", "No leaks", "Transfer switch in Auto")),
        Standard("gen-monthly", "Generator", "Emergency generator", "Monthly load test (30 min)", 30, "NFPA 110",
            listOf("Ran under load at least 30 minutes", "Exhaust temperature or load % recorded", "Transfer time recorded", "Abnormal readings reported")),
        Standard("egress-monthly", "Life safety", "Emergency & exit lighting", "Monthly 30-second test", 30, "NFPA 101",
            listOf("Every unit lights on battery", "Exit signs legible", "Failures tagged and logged")),
        Standard("egress-annual", "Life safety", "Emergency & exit lighting", "Annual 90-minute test", 365, "NFPA 101",
            listOf("Ran 90 minutes on battery", "Every unit stayed lit", "Failures replaced and retested")),
        Standard("eyewash-weekly", "Plumbing", "Eyewash stations", "Weekly activation", 7, "ANSI Z358.1",
            listOf("Flushed until water runs clear", "Caps in place", "Unobstructed, signed, within 10 seconds of the hazard")),
        Standard("alarm-annual", "Fire protection", "Fire alarm system", "Annual inspection & test", 365, "NFPA 72",
            listOf("Contractor report received", "Deficiencies logged as work orders")),
        Standard("sprinkler-quarterly", "Fire protection", "Sprinkler system", "Quarterly inspection", 91, "NFPA 25",
            listOf("Gauges normal", "Control valves open and supervised", "Alarm devices tested", "Fire department connection clear")),
        Standard("doors-annual", "Life safety", "Fire doors", "Annual inspection", 365, "NFPA 80",
            listOf("Self-closes and latches", "No holes or added hardware", "Labels legible", "Gaps within limits")),
        Standard("hood-semiannual", "Kitchen", "Kitchen hood suppression", "Semiannual inspection", 182, "NFPA 96 / 17A",
            listOf("Contractor tag current", "Nozzles capped and aimed", "Manual pull station clear")),
        Standard("backflow-annual", "Plumbing", "Backflow preventers", "Annual test", 365, "Local water authority",
            listOf("Certified tester report", "Results filed with the water authority")),
        Standard("elevator-annual", "Elevator", "Elevators", "Annual inspection", 365, "ASME A17.1 / state",
            listOf("Certificate posted and current", "Phone and alarm tested")),
        Standard("filters-quarterly", "HVAC", "Air handlers / RTUs", "Filter change", 91, "Manufacturer",
            listOf("Filters replaced (size recorded)", "Belts checked", "Condensate drains clear")),
    )

    /** Makes the asset (or adds to an existing one with the same name) for a catalog entry. */
    fun addStandard(s: State, st: Standard, location: String = "", today: Long = LocalDate.now().toEpochDay()): State {
        val pm = Pm(title = st.title, everyDays = st.everyDays, standard = st.code, checklist = st.checklist, startDay = today)
        val name = if (location.isBlank()) st.asset else "${st.asset} — ${location.trim()}"
        val existing = s.assets.firstOrNull { it.name.equals(name, ignoreCase = true) }
        return if (existing != null) {
            if (existing.pms.any { it.title == st.title }) s else upsertAsset(s, existing.copy(pms = existing.pms + pm))
        } else upsertAsset(s, Asset(name = name, kind = st.kind, location = location.trim(), pms = listOf(pm)))
    }

    // ---- survey binder ----------------------------------------------------------------------------------

    /** An HTML report of every schedule and log entry in [from]..[to] (epoch days), for surveyors and inspectors. */
    fun binderHtml(s: State, from: Long, to: Long, title: String, today: Long = LocalDate.now().toEpochDay()): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        fun d(day: Long) = LocalDate.ofEpochDay(day).toString()
        val sb = StringBuilder()
        sb.append("<h1 class=\"title\">${esc(title)}</h1>")
        sb.append("<p>${d(from)} to ${d(to)} · prepared ${d(today)}</p>")
        val dues = due(s, today)
        val overdue = dues.count { it.status == Status.OVERDUE }
        val inRange = s.log.filter { it.day in from..to }
        val fails = inRange.count { it.result == Result.FAIL }
        sb.append("<p><b>${s.assets.size}</b> assets · <b>${dues.size}</b> schedules · <b>$overdue</b> overdue today · <b>${inRange.size}</b> log entries in the period, <b>$fails</b> failed</p>")

        sb.append("<h2>Schedules</h2><table><tr><th>Asset</th><th>Location</th><th>Inspection / PM</th><th>Standard</th><th>Every</th><th>Last done</th><th>Next due</th><th>Status</th></tr>")
        dues.forEach { x ->
            val st = when (x.status) { Status.OVERDUE -> "OVERDUE"; Status.DUE_SOON -> "Due soon"; Status.OK -> "Current" }
            sb.append("<tr><td>${esc(x.asset.name)}</td><td>${esc(x.asset.location)}</td><td>${esc(x.pm.title)}</td><td>${esc(x.pm.standard)}</td>")
                .append("<td>${x.pm.everyDays} d</td><td>${x.pm.lastDay?.let(::d) ?: "—"}</td><td>${d(x.dueDay)}</td><td>$st</td></tr>")
        }
        sb.append("</table>")

        val byAsset = s.assets.associateBy { it.id }
        val pmTitle = s.assets.flatMap { a -> a.pms.map { it.id to it.title } }.toMap()
        sb.append("<h2>Inspection & work log</h2>")
        if (inRange.isEmpty()) sb.append("<p>No entries in this period.</p>") else {
            sb.append("<table><tr><th>Date</th><th>Asset</th><th>Type</th><th>Item</th><th>Result</th><th>Notes</th><th>By</th></tr>")
            inRange.sortedWith(compareBy<Entry> { it.day }.thenBy { it.time }).forEach { e ->
                sb.append("<tr><td>${d(e.day)}</td><td>${esc(byAsset[e.assetId]?.name ?: "(deleted)")}</td><td>${e.kind.label}</td>")
                    .append("<td>${esc(e.pmId?.let { pmTitle[it] } ?: "")}</td><td>${if (e.result == Result.FAIL) "<b>FAIL</b>" else e.result.label}</td>")
                    .append("<td>${esc(e.text)}</td><td>${esc(e.by)}</td></tr>")
            }
            sb.append("</table>")
        }

        val fixes = inRange.filter { it.result == Result.FAIL || it.kind == Kind.FINDING }
        if (fixes.isNotEmpty()) {
            sb.append("<h2>Deficiencies</h2><ul>")
            fixes.forEach { e -> sb.append("<li>${d(e.day)} — ${esc(byAsset[e.assetId]?.name ?: "")}: ${esc(e.text.ifBlank { "failed" })}${if (e.taskId != null) " (work order created)" else ""}</li>") }
            sb.append("</ul>")
        }
        sb.append("<h2>Asset register</h2><table><tr><th>Asset</th><th>Type</th><th>Tag</th><th>Location</th><th>Make / model</th><th>Serial</th></tr>")
        s.assets.sortedBy { it.name.lowercase() }.forEach { a ->
            sb.append("<tr><td>${esc(a.name)}</td><td>${esc(a.kind)}</td><td>${esc(a.tag)}</td><td>${esc(a.location)}</td><td>${esc(listOf(a.make, a.model).filter { it.isNotBlank() }.joinToString(" "))}</td><td>${esc(a.serial)}</td></tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    // ---- JSON -------------------------------------------------------------------------------------------

    fun newId(): String = UUID.randomUUID().toString().take(12)

    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map(it::getString) }.orEmpty()
    private fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null

    fun toJson(s: State): JSONObject = JSONObject().put("v", 1).put("site", s.site)
        .put("assets", JSONArray().apply {
            s.assets.forEach { a ->
                put(JSONObject().put("id", a.id).put("name", a.name).put("kind", a.kind).put("tag", a.tag).put("location", a.location)
                    .put("make", a.make).put("model", a.model).put("serial", a.serial).put("installed", a.installedDay ?: JSONObject.NULL)
                    .put("notes", a.notes).put("created", a.createdAt)
                    .put("pms", JSONArray().apply {
                        a.pms.forEach { p ->
                            put(JSONObject().put("id", p.id).put("title", p.title).put("every", p.everyDays).put("standard", p.standard)
                                .put("checklist", JSONArray(p.checklist)).put("last", p.lastDay ?: JSONObject.NULL).put("start", p.startDay).put("note", p.note))
                        }
                    }))
            }
        })
        .put("log", JSONArray().apply {
            s.log.forEach { e ->
                put(JSONObject().put("id", e.id).put("asset", e.assetId).put("pm", e.pmId ?: JSONObject.NULL).put("day", e.day).put("kind", e.kind.name)
                    .put("result", e.result.name).put("text", e.text).put("by", e.by).put("task", e.taskId ?: JSONObject.NULL).put("time", e.time))
            }
        })

    fun fromJson(o: JSONObject): State {
        val assets = o.optJSONArray("assets")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { a ->
                Asset(a.getString("id"), a.getString("name"), a.optString("kind", "Other"), a.optString("tag"), a.optString("location"),
                    a.optString("make"), a.optString("model"), a.optString("serial"), a.optLongOrNull("installed"), a.optString("notes"),
                    a.optJSONArray("pms")?.let { ps ->
                        (0 until ps.length()).map { ps.getJSONObject(it) }.map { p ->
                            Pm(p.getString("id"), p.getString("title"), p.getInt("every").coerceAtLeast(1), p.optString("standard"), strings(p.optJSONArray("checklist")),
                                p.optLongOrNull("last"), p.optLong("start", LocalDate.now().toEpochDay()), p.optString("note"))
                        }
                    }.orEmpty(), a.optLong("created"))
            }
        }.orEmpty()
        val log = o.optJSONArray("log")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { e ->
                Entry(e.getString("id"), e.getString("asset"), e.optString("pm").takeIf { !e.isNull("pm") && it.isNotBlank() }, e.getLong("day"),
                    runCatching { Kind.valueOf(e.getString("kind")) }.getOrDefault(Kind.NOTE), runCatching { Result.valueOf(e.optString("result")) }.getOrDefault(Result.NONE),
                    e.optString("text"), e.optString("by"), e.optLongOrNull("task"), e.optLong("time"))
            }
        }.orEmpty()
        return State(assets, log, o.optString("site"))
    }

    // ---- store ------------------------------------------------------------------------------------------

    val store = com.blacklab.app.core.JsonStore("Facility", version = 1, empty = { State() }, decode = ::fromJson, encode = ::toJson)
    val state: StateFlow<State> = store.state

    fun file(context: Context) = File(context.filesDir, "facility/facility.json")

    fun load(context: Context): State = store.load(file(context))

    fun update(context: Context, change: (State) -> State): State = store.update(file(context), change = change)
}
