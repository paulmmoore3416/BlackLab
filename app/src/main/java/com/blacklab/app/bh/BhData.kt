package com.blacklab.app.bh

import org.json.JSONObject
import java.util.UUID

/**
 * Behavioral Health workspace data — for working as a Mental Health Technician and Peer Support
 * Specialist. People are never stored by name: the census uses initials or a room/bed label and
 * peer participants use an alias you choose, so nothing here is a medical record. The facility's
 * EHR stays the record of care; BlackLab drafts notes you copy into it.
 */
object Bh {

    fun id(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    data class Prefs(
        val facility: String = "",
        val unit: String = "",
        val units: List<String> = listOf("Adult acute", "Adolescent", "Geriatric", "Detox / withdrawal management", "Residential", "PHP / IOP", "Crisis stabilization"),
        val role: String = "Both",
        val signature: String = "",
        val defaultInterval: Int = 15,
        val graceMinutes: Int = 2,
        val retentionHours: Int = 24,
        val requireUnlock: Boolean = true,
        val roundAlerts: Boolean = true,
        val roundSound: Boolean = true,
        val shiftHours: Int = 12,
        val state: String = "",
        /** Days to keep drafted notes about other people (0 = until you delete them). */
        val noteDays: Int = 14,
        val hideOnHome: Boolean = false,
    )

    /** One person on the census — initials or a room/bed label only. */
    data class Patient(
        val id: String = id(),
        val label: String,
        val room: String = "",
        val obs: String = "Q15",
        val precautions: List<String> = emptyList(),
        val notes: String = "",
        val added: Long = System.currentTimeMillis(),
        val active: Boolean = true,
    ) {
        val display: String get() = listOf(room.takeIf { it.isNotBlank() }?.let { "Rm $it" }, label.ifBlank { null }).filterNotNull().joinToString(" · ").ifBlank { "Unnamed" }
    }

    /** One observation check on one person. */
    data class Check(
        val id: String = id(),
        val patientId: String,
        val time: Long = System.currentTimeMillis(),
        val location: String,
        val behavior: String,
        val note: String = "",
        val by: String = "",
    )

    data class Shift(
        val id: String = id(),
        val start: Long = System.currentTimeMillis(),
        val end: Long? = null,
        val type: String = "Day",
        val unit: String = "",
        val role: String = "MHT",
        val done: List<String> = emptyList(),
        val notes: String = "",
    )

    data class Step(val id: String = id(), val text: String, val done: Boolean = false)
    data class Goal(
        val id: String = id(),
        val domain: String,
        val text: String,
        val why: String = "",
        val steps: List<Step> = emptyList(),
        val status: String = "Active",
        val target: Long? = null,
        val created: Long = System.currentTimeMillis(),
    )

    /** Someone you support as a peer — by alias. */
    data class Participant(
        val id: String = id(),
        val alias: String,
        val started: Long = System.currentTimeMillis(),
        val setting: String = "Inpatient",
        val stage: String = "",
        val pathways: List<String> = emptyList(),
        val strengths: String = "",
        val supports: String = "",
        val preferences: String = "",
        val goals: List<Goal> = emptyList(),
        val wrap: Boolean = false,
        val crisisPlan: Boolean = false,
        val active: Boolean = true,
        val notes: String = "",
        /** Recovery capital self-ratings by domain (0–5), latest first in history. */
        val capital: Map<String, Int> = emptyMap(),
        val capitalAt: Long? = null,
    )

    data class GroupPlan(
        val id: String = id(),
        val title: String,
        val category: String,
        val minutes: Int = 45,
        val audience: String = "Any",
        val objective: String,
        val opener: String,
        val activity: String,
        val discussion: List<String>,
        val closing: String,
        val materials: String = "",
        val adaptations: String = "",
        val builtin: Boolean = false,
    )

    data class Resource(
        val id: String = id(),
        val name: String,
        val category: String,
        val phone: String = "",
        val text: String = "",
        val url: String = "",
        val address: String = "",
        val hours: String = "",
        val eligibility: String = "",
        val cost: String = "",
        val notes: String = "",
        val builtin: Boolean = false,
    )

    data class AreaCheck(
        val id: String = id(),
        val area: String,
        val label: String = "",
        val results: Map<String, String> = emptyMap(), // item id → OK | ISSUE | NA
        val issues: Map<String, String> = emptyMap(),  // item id → what's wrong
        val risk: String = "",
        val mitigation: String = "",
        val taskId: Long? = null,
    ) {
        val issueCount: Int get() = results.count { it.value == "ISSUE" }
    }

    data class EocRound(
        val id: String = id(),
        val time: Long = System.currentTimeMillis(),
        val unit: String = "",
        val inspector: String = "",
        val kind: String = "Ligature risk & environmental safety",
        val areas: List<AreaCheck> = emptyList(),
        val notes: String = "",
        val closed: Boolean = false,
    )

    data class State(
        val prefs: Prefs = Prefs(),
        val patients: List<Patient> = emptyList(),
        val checks: List<Check> = emptyList(),
        val roundsOn: Boolean = false,
        val roundsSince: Long? = null,
        val shifts: List<Shift> = emptyList(),
        val participants: List<Participant> = emptyList(),
        val plans: List<GroupPlan> = emptyList(),
        val resources: List<Resource> = emptyList(),
        val favorites: Set<String> = emptySet(),
        val eoc: List<EocRound> = emptyList(),
        val records: List<Forms.Rec> = emptyList(),
    ) {
        val activeShift: Shift? get() = shifts.firstOrNull { it.end == null }
        val census: List<Patient> get() = patients.filter { it.active }
        fun records(kind: String) = records.filter { it.kind == kind }.sortedByDescending { it.created }
    }

    // ---------------------------------------------------------------- JSON

    fun toJson(s: State): JSONObject = JSONObject().apply {
        val p = s.prefs
        put("v", 1)
        put("prefs", JSONObject().put("facility", p.facility).put("unit", p.unit).put("units", p.units.toJsonArray { it }).put("role", p.role).put("signature", p.signature)
            .put("interval", p.defaultInterval).put("grace", p.graceMinutes).put("retention", p.retentionHours).put("unlock", p.requireUnlock).put("alerts", p.roundAlerts)
            .put("sound", p.roundSound).put("shiftHours", p.shiftHours).put("state", p.state).put("noteDays", p.noteDays).put("hideHome", p.hideOnHome))
        put("patients", s.patients.toJsonArray { x ->
            JSONObject().put("id", x.id).put("label", x.label).put("room", x.room).put("obs", x.obs).put("prec", x.precautions.toJsonArray { it }).put("notes", x.notes)
                .put("added", x.added).put("active", x.active)
        })
        put("checks", s.checks.toJsonArray { c ->
            JSONObject().put("id", c.id).put("p", c.patientId).put("t", c.time).put("loc", c.location).put("beh", c.behavior).put("note", c.note).put("by", c.by)
        })
        put("roundsOn", s.roundsOn); put("roundsSince", s.roundsSince ?: JSONObject.NULL)
        put("shifts", s.shifts.toJsonArray { x ->
            JSONObject().put("id", x.id).put("start", x.start).put("end", x.end ?: JSONObject.NULL).put("type", x.type).put("unit", x.unit).put("role", x.role)
                .put("done", x.done.toJsonArray { it }).put("notes", x.notes)
        })
        put("participants", s.participants.toJsonArray { x ->
            JSONObject().put("id", x.id).put("alias", x.alias).put("started", x.started).put("setting", x.setting).put("stage", x.stage).put("pathways", x.pathways.toJsonArray { it })
                .put("strengths", x.strengths).put("supports", x.supports).put("prefs", x.preferences).put("wrap", x.wrap).put("crisis", x.crisisPlan).put("active", x.active)
                .put("notes", x.notes).put("capital", JSONObject().apply { x.capital.forEach { (k, v) -> put(k, v) } }).put("capitalAt", x.capitalAt ?: JSONObject.NULL)
                .put("goals", x.goals.toJsonArray { g ->
                    JSONObject().put("id", g.id).put("domain", g.domain).put("text", g.text).put("why", g.why).put("status", g.status).put("target", g.target ?: JSONObject.NULL)
                        .put("created", g.created).put("steps", g.steps.toJsonArray { st -> JSONObject().put("id", st.id).put("text", st.text).put("done", st.done) })
                })
        })
        put("plans", s.plans.toJsonArray { g -> planJson(g) })
        put("resources", s.resources.toJsonArray { r -> resourceJson(r) })
        put("favorites", s.favorites.toList().toJsonArray { it })
        put("eoc", s.eoc.toJsonArray { e ->
            JSONObject().put("id", e.id).put("time", e.time).put("unit", e.unit).put("inspector", e.inspector).put("kind", e.kind).put("notes", e.notes).put("closed", e.closed)
                .put("areas", e.areas.toJsonArray { a ->
                    JSONObject().put("id", a.id).put("area", a.area).put("label", a.label).put("risk", a.risk).put("mitigation", a.mitigation).put("task", a.taskId ?: JSONObject.NULL)
                        .put("results", JSONObject().apply { a.results.forEach { (k, v) -> put(k, v) } })
                        .put("issues", JSONObject().apply { a.issues.forEach { (k, v) -> put(k, v) } })
                })
        })
        put("records", s.records.toJsonArray { Forms.toJson(it) })
    }

    private fun planJson(g: GroupPlan) = JSONObject().put("id", g.id).put("title", g.title).put("category", g.category).put("minutes", g.minutes).put("audience", g.audience)
        .put("objective", g.objective).put("opener", g.opener).put("activity", g.activity).put("discussion", g.discussion.toJsonArray { it }).put("closing", g.closing)
        .put("materials", g.materials).put("adaptations", g.adaptations)

    private fun resourceJson(r: Resource) = JSONObject().put("id", r.id).put("name", r.name).put("category", r.category).put("phone", r.phone).put("text", r.text)
        .put("url", r.url).put("address", r.address).put("hours", r.hours).put("eligibility", r.eligibility).put("cost", r.cost).put("notes", r.notes)

    private fun JSONObject.map(k: String): Map<String, String> = optJSONObject(k)?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } }.orEmpty()

    fun fromJson(o: JSONObject): State {
        val d = Prefs()
        val p = o.optJSONObject("prefs")?.let { j ->
            Prefs(j.optString("facility"), j.optString("unit"), j.optJSONArray("units")?.strings()?.takeIf { it.isNotEmpty() } ?: d.units, j.optString("role", d.role),
                j.optString("signature"), j.optInt("interval", d.defaultInterval), j.optInt("grace", d.graceMinutes), j.optInt("retention", d.retentionHours),
                j.optBoolean("unlock", true), j.optBoolean("alerts", true), j.optBoolean("sound", true), j.optInt("shiftHours", 12), j.optString("state"),
                j.optInt("noteDays", d.noteDays), j.optBoolean("hideHome"))
        } ?: d
        return State(
            prefs = p,
            patients = o.optJSONArray("patients").objects().map { j ->
                Patient(j.getString("id"), j.optString("label"), j.optString("room"), j.optString("obs", "Q15"), j.optJSONArray("prec").strings(), j.optString("notes"),
                    j.optLong("added"), j.optBoolean("active", true))
            },
            checks = o.optJSONArray("checks").objects().map { j ->
                Check(j.getString("id"), j.getString("p"), j.getLong("t"), j.optString("loc"), j.optString("beh"), j.optString("note"), j.optString("by"))
            },
            roundsOn = o.optBoolean("roundsOn"),
            roundsSince = o.optLongOrNull("roundsSince"),
            shifts = o.optJSONArray("shifts").objects().map { j ->
                Shift(j.getString("id"), j.getLong("start"), j.optLongOrNull("end"), j.optString("type", "Day"), j.optString("unit"), j.optString("role", "MHT"),
                    j.optJSONArray("done").strings(), j.optString("notes"))
            },
            participants = o.optJSONArray("participants").objects().map { j ->
                Participant(j.getString("id"), j.optString("alias"), j.optLong("started"), j.optString("setting", "Inpatient"), j.optString("stage"), j.optJSONArray("pathways").strings(),
                    j.optString("strengths"), j.optString("supports"), j.optString("prefs"),
                    j.optJSONArray("goals").objects().map { g ->
                        Goal(g.getString("id"), g.optString("domain"), g.optString("text"), g.optString("why"),
                            g.optJSONArray("steps").objects().map { st -> Step(st.getString("id"), st.optString("text"), st.optBoolean("done")) },
                            g.optString("status", "Active"), g.optLongOrNull("target"), g.optLong("created"))
                    },
                    j.optBoolean("wrap"), j.optBoolean("crisis"), j.optBoolean("active", true), j.optString("notes"),
                    j.optJSONObject("capital")?.let { c -> c.keys().asSequence().associateWith { c.optInt(it) } }.orEmpty(), j.optLongOrNull("capitalAt"))
            },
            plans = o.optJSONArray("plans").objects().map { j ->
                GroupPlan(j.getString("id"), j.optString("title"), j.optString("category"), j.optInt("minutes", 45), j.optString("audience", "Any"), j.optString("objective"),
                    j.optString("opener"), j.optString("activity"), j.optJSONArray("discussion").strings(), j.optString("closing"), j.optString("materials"), j.optString("adaptations"))
            },
            resources = o.optJSONArray("resources").objects().map { j ->
                Resource(j.getString("id"), j.optString("name"), j.optString("category"), j.optString("phone"), j.optString("text"), j.optString("url"), j.optString("address"),
                    j.optString("hours"), j.optString("eligibility"), j.optString("cost"), j.optString("notes"))
            },
            favorites = o.optJSONArray("favorites").strings().toSet(),
            eoc = o.optJSONArray("eoc").objects().map { j ->
                EocRound(j.getString("id"), j.optLong("time"), j.optString("unit"), j.optString("inspector"), j.optString("kind", "Ligature risk & environmental safety"),
                    j.optJSONArray("areas").objects().map { a ->
                        AreaCheck(a.getString("id"), a.optString("area"), a.optString("label"), a.map("results"), a.map("issues"), a.optString("risk"), a.optString("mitigation"), a.optLongOrNull("task"))
                    }, j.optString("notes"), j.optBoolean("closed"))
            },
            records = o.optJSONArray("records").objects().mapNotNull { runCatching { Forms.fromJson(it) }.getOrNull() },
        )
    }
}
