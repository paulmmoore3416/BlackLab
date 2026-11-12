package com.blacklab.app.link

import com.blacklab.app.core.DayTimeline
import com.blacklab.app.core.Health
import com.blacklab.app.facility.Facility
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The snapshot sections that let BlackLab Desktop (and the web UI over cloud sync) show the same Facility,
 * Timeline and Health the phone does. Pure, unit tested in DesktopSectionsTest; [Snapshot.build] gathers the inputs.
 *
 * Behavioral Health never leaves the phone this way — the timeline drops its item (even the counts), the same
 * rule the ops handoff follows.
 */
object DesktopSections {
    private const val LOG_DAYS = 90L
    private const val LOG_MAX = 150

    fun facility(s: Facility.State, today: Long): JSONObject {
        val dues = Facility.due(s, today)
        val byPm = dues.associateBy { it.asset.id to it.pm.id }
        val assets = s.assets.associateBy { it.id }
        return JSONObject().put("site", s.site)
            .put("counts", JSONObject().put("assets", s.assets.size)
                .put("overdue", dues.count { it.status == Facility.Status.OVERDUE })
                .put("dueSoon", dues.count { it.status == Facility.Status.DUE_SOON })
                .put("fails30", s.log.count { it.result == Facility.Result.FAIL && it.day > today - 30 }))
            .put("assets", JSONArray().apply {
                s.assets.forEach { a ->
                    put(JSONObject().put("id", a.id).put("name", a.name).put("kind", a.kind).put("tag", a.tag).put("location", a.location)
                        .put("make", a.make).put("model", a.model).put("serial", a.serial).put("notes", a.notes)
                        .put("installed", a.installedDay?.let { day(it) } ?: JSONObject.NULL)
                        .put("pms", JSONArray().apply {
                            a.pms.forEach { p ->
                                val d = byPm[a.id to p.id]
                                put(JSONObject().put("id", p.id).put("title", p.title).put("every", p.everyDays).put("standard", p.standard)
                                    .put("checklist", JSONArray(p.checklist)).put("last", p.lastDay?.let { day(it) } ?: JSONObject.NULL)
                                    .put("due", day(p.dueDay)).put("daysLeft", p.dueDay - today).put("status", (d?.status ?: Facility.status(p, today)).name))
                            }
                        }))
                }
            })
            .put("due", JSONArray().apply {
                dues.forEach { d ->
                    put(JSONObject().put("asset", d.asset.name).put("assetId", d.asset.id).put("pm", d.pm.title).put("due", day(d.dueDay))
                        .put("daysLeft", d.daysLeft).put("status", d.status.name).put("text", d.describe()).put("standard", d.pm.standard))
                }
            })
            .put("log", JSONArray().apply {
                s.log.filter { it.day > today - LOG_DAYS }.sortedWith(compareByDescending<Facility.Entry> { it.day }.thenByDescending { it.time }).take(LOG_MAX).forEach { e ->
                    val a = assets[e.assetId]
                    put(JSONObject().put("id", e.id).put("assetId", e.assetId).put("asset", a?.name ?: "").put("pmTitle", a?.pms?.firstOrNull { it.id == e.pmId }?.title ?: "")
                        .put("day", day(e.day)).put("time", e.time).put("kind", e.kind.name).put("kindLabel", e.kind.label)
                        .put("result", e.result.name).put("text", e.text).put("by", e.by))
                }
            })
    }

    fun timeline(date: LocalDate, items: List<DayTimeline.Item>, handoff: String): JSONObject =
        JSONObject().put("day", date.toString())
            .put("items", JSONArray().apply {
                items.filter { it.kind != DayTimeline.Kind.BH }.forEach { i ->
                    put(JSONObject().put("time", i.time).put("kind", i.kind.name).put("label", i.kind.label).put("emoji", i.kind.emoji)
                        .put("title", i.title).put("detail", i.detail))
                }
            })
            .put("handoff", handoff)

    fun health(checks: List<Health.Check>): JSONObject =
        JSONObject().put("summary", Health.summary(checks))
            .put("checks", JSONArray().apply {
                checks.forEach { c -> put(JSONObject().put("id", c.id).put("title", c.title).put("level", c.level.name).put("detail", c.detail).put("fix", c.fixLabel ?: "")) }
            })

    /** Epoch day → millis at local midnight, the convention the rest of the snapshot uses for days. */
    private fun day(epochDay: Long): Long = com.blacklab.app.core.Time.millis(LocalDate.ofEpochDay(epochDay))
}
