package com.blacklab.app.facility

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacilityTest {
    private val today = 20_000L
    private fun pm(every: Int, last: Long? = null, start: Long = today) = Facility.Pm(title = "Check", everyDays = every, lastDay = last, startDay = start)

    @Test fun neverDoneIsDueOnItsStartDay() {
        assertEquals(today, pm(30).dueDay)
        assertEquals(Facility.Status.DUE_SOON, Facility.status(pm(30), today))
        assertEquals(Facility.Status.OVERDUE, Facility.status(pm(30, start = today - 1), today))
    }

    @Test fun dueMovesFromTheLastCompletion() {
        val p = pm(30, last = today - 10)
        assertEquals(today + 20, p.dueDay)
        assertEquals(Facility.Status.OK, Facility.status(p, today))
        assertEquals(Facility.Status.DUE_SOON, Facility.status(p, today + 13)) // monthly: a week's warning
        assertEquals(Facility.Status.OVERDUE, Facility.status(p, today + 21))
    }

    @Test fun soonWindowScalesWithTheInterval() {
        assertEquals(1, Facility.soonWindow(7))
        assertEquals(7, Facility.soonWindow(30))
        assertEquals(14, Facility.soonWindow(365))
    }

    @Test fun completingLogsAndAdvances() {
        val a = Facility.Asset(name = "Generator 1", pms = listOf(pm(7)))
        var s = Facility.upsertAsset(Facility.State(), a)
        s = Facility.complete(s, a.id, a.pms[0].id, today, Facility.Result.PASS, " ran fine ", "PM")
        assertEquals(today + 7, s.assets[0].pms[0].dueDay)
        assertEquals("ran fine", s.log[0].text)
        // logging an older date never moves the schedule backwards
        s = Facility.complete(s, a.id, a.pms[0].id, today - 3, Facility.Result.PASS, "", "")
        assertEquals(today + 7, s.assets[0].pms[0].dueDay)
        assertEquals(2, Facility.history(s, a.id).size)
        assertEquals(today, Facility.history(s, a.id).first().day)
    }

    @Test fun dueListIsSoonestFirst() {
        val s = Facility.State(assets = listOf(
            Facility.Asset(name = "B", pms = listOf(pm(30, last = today - 5))),
            Facility.Asset(name = "A", pms = listOf(pm(7, last = today - 9))),
        ))
        val d = Facility.due(s, today)
        assertEquals(listOf("A", "B"), d.map { it.asset.name })
        assertEquals(-2, d[0].daysLeft)
        assertEquals("2 days overdue", d[0].describe())
        // A is overdue; B is due in 25 days, outside its week of warning
        assertEquals(listOf("A"), FacilityReminders.upcoming(d).map { it.asset.name })
    }

    @Test fun tagsMatchIgnoringCaseAndSpace() {
        val s = Facility.State(assets = listOf(Facility.Asset(name = "RTU-4", tag = "ast-0042")))
        assertEquals("RTU-4", Facility.findByTag(s, "  AST-0042 ")?.name)
        assertNull(Facility.findByTag(s, ""))
        assertNull(Facility.findByTag(s, "AST-0043"))
    }

    @Test fun catalogGroupsSchedulesOnOneAssetPerArea() {
        val ext = Facility.STANDARDS.filter { it.asset == "Fire extinguishers" }
        assertEquals(2, ext.size)
        var s = ext.fold(Facility.State()) { acc, st -> Facility.addStandard(acc, st, "Building A", today) }
        s = Facility.addStandard(s, ext[0], "Building A", today) // adding again changes nothing
        assertEquals(1, s.assets.size)
        assertEquals("Fire extinguishers — Building A", s.assets[0].name)
        assertEquals(2, s.assets[0].pms.size)
        assertEquals("NFPA 10", s.assets[0].pms[0].standard)
        s = Facility.addStandard(s, ext[0], "Building B", today)
        assertEquals(2, s.assets.size)
        assertTrue(Facility.STANDARDS.map { it.id }.toSet().size == Facility.STANDARDS.size)
    }

    @Test fun jsonRoundTrips() {
        val a = Facility.Asset(name = "AHU-1", kind = "HVAC", tag = "T1", location = "Roof", make = "Trane", model = "M", serial = "S", installedDay = 19_000,
            notes = "n", pms = listOf(Facility.Pm(title = "Filters", everyDays = 91, standard = "Manufacturer", checklist = listOf("a", "b"), lastDay = today, startDay = today)))
        var s = Facility.upsertAsset(Facility.State(site = "Main campus"), a)
        s = Facility.addEntry(s, Facility.Entry(assetId = a.id, day = today, kind = Facility.Kind.FINDING, result = Facility.Result.FAIL, text = "belt", taskId = 9))
        s = Facility.addEntry(s, Facility.Entry(assetId = a.id, pmId = a.pms[0].id, day = today, kind = Facility.Kind.PM, result = Facility.Result.PASS))
        val back = Facility.fromJson(JSONObject(Facility.toJson(s).toString()))
        assertEquals(s, back)
    }

    @Test fun deletingAnAssetRemovesItsHistory() {
        val a = Facility.Asset(name = "X", pms = listOf(pm(7)))
        var s = Facility.upsertAsset(Facility.State(), a)
        s = Facility.complete(s, a.id, a.pms[0].id, today, Facility.Result.PASS, "", "")
        s = Facility.deleteAsset(s, a.id)
        assertTrue(s.assets.isEmpty() && s.log.isEmpty())
    }

    @Test fun binderListsSchedulesLogAndDeficiencies() {
        val a = Facility.Asset(name = "Eyewash <Lab>", tag = "EW-1", pms = listOf(pm(7, last = today - 10)))
        var s = Facility.upsertAsset(Facility.State(), a)
        s = Facility.addEntry(s, Facility.Entry(assetId = a.id, day = today - 1, kind = Facility.Kind.FINDING, result = Facility.Result.FAIL, text = "No flow"))
        s = Facility.addEntry(s, Facility.Entry(assetId = a.id, day = today - 400, kind = Facility.Kind.NOTE, text = "ancient"))
        val html = Facility.binderHtml(s, today - 30, today, "Main & Annex", today)
        assertTrue(html.contains("Main &amp; Annex"))
        assertTrue(html.contains("Eyewash &lt;Lab&gt;"))
        assertFalse(html.contains("<Lab>"))
        assertTrue(html.contains("OVERDUE"))
        assertTrue(html.contains("Deficiencies") && html.contains("No flow"))
        assertFalse(html.contains("ancient"))
    }
}
