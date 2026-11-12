package com.blacklab.app.link

import com.blacklab.app.core.DayTimeline
import com.blacklab.app.core.Health
import com.blacklab.app.facility.Facility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DesktopSectionsTest {
    private val today = LocalDate.of(2026, 11, 10).toEpochDay()

    private val state = Facility.State(
        site = "Main campus",
        assets = listOf(
            Facility.Asset(id = "gen", name = "Generator 1", kind = "Generator", tag = "GEN-1", location = "Yard",
                pms = listOf(Facility.Pm(id = "load", title = "Monthly load test", everyDays = 30, lastDay = today - 40))),
            Facility.Asset(id = "ahu", name = "AHU-2", kind = "HVAC", pms = listOf(Facility.Pm(id = "filt", title = "Filters", everyDays = 90, lastDay = today - 10))),
        ),
        log = listOf(
            Facility.Entry(id = "e1", assetId = "gen", pmId = "load", day = today - 40, kind = Facility.Kind.PM, result = Facility.Result.PASS, text = "ok", by = "J"),
            Facility.Entry(id = "e2", assetId = "ahu", day = today - 400, kind = Facility.Kind.NOTE, text = "old"),
        ),
    )

    @Test fun facilityCarriesAssetsDueAndRecentLog() {
        val o = DesktopSections.facility(state, today)
        assertEquals("Main campus", o.getString("site"))
        assertEquals(2, o.getJSONArray("assets").length())
        val gen = o.getJSONArray("assets").getJSONObject(0)
        assertEquals("GEN-1", gen.getString("tag"))
        val pm = gen.getJSONArray("pms").getJSONObject(0)
        assertEquals("OVERDUE", pm.getString("status"))
        assertEquals(-10, pm.getLong("daysLeft"))
        val due = o.getJSONArray("due")
        assertEquals("Monthly load test", due.getJSONObject(0).getString("pm")) // most urgent first
        assertEquals(1, o.getJSONObject("counts").getInt("overdue"))
        // the log keeps recent entries only, newest first, with asset names resolved
        val log = o.getJSONArray("log")
        assertEquals(1, log.length())
        assertEquals("Generator 1", log.getJSONObject(0).getString("asset"))
        assertEquals("Monthly load test", log.getJSONObject(0).getString("pmTitle"))
    }

    @Test fun timelineNeverIncludesBehavioralHealth() {
        val items = listOf(
            DayTimeline.Item(1L, DayTimeline.Kind.WORK_ORDER, "WO P2: Leak", "room 214", "task/1"),
            DayTimeline.Item(2L, DayTimeline.Kind.BH, "Behavioral Health", "32 observation checks", "bh"),
        )
        val o = DesktopSections.timeline(LocalDate.ofEpochDay(today), items, "Ops handoff — test")
        val arr = o.getJSONArray("items")
        assertEquals(1, arr.length())
        assertEquals("WORK_ORDER", arr.getJSONObject(0).getString("kind"))
        assertEquals("Work order", arr.getJSONObject(0).getString("label"))
        assertFalse(o.toString().contains("observation", ignoreCase = true))
        assertEquals("Ops handoff — test", o.getString("handoff"))
    }

    @Test fun healthListsChecksWithSummary() {
        val checks = listOf(Health.Check("backup", "Backups", Health.Level.WARN, "Last backup 9 days ago", "Open backups", "settings"))
        val o = DesktopSections.health(checks)
        assertEquals("1 thing to look at", o.getString("summary"))
        val c = o.getJSONArray("checks").getJSONObject(0)
        assertEquals("WARN", c.getString("level"))
        assertEquals("Open backups", c.getString("fix"))
        assertTrue(c.getString("detail").contains("9 days"))
    }
}
