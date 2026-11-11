package com.blacklab.app.link

import com.blacklab.app.facility.Facility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The feed's Facility shape is what desktop/companion/vault.py reads — keep the keys in step (tests/test_vault.py FEED). */
class VaultFeedTest {
    @Test fun facilityShapeMatchesTheDesktop() {
        val st = Facility.State(site = "Main", assets = listOf(Facility.Asset(id = "a1", name = "AHU-2", kind = "HVAC", tag = "FAC-0042",
            pms = listOf(Facility.Pm(id = "p1", title = "Filter change", everyDays = 90, startDay = 0)))),
            log = listOf(Facility.Entry(assetId = "a1", pmId = "p1", day = 20400, kind = Facility.Kind.PM, result = Facility.Result.PASS, text = "ok", by = "J")))
        val j = VaultFeed.facility(st)
        val a = j.getJSONArray("assets").getJSONObject(0)
        listOf("id", "name", "kind", "tag", "location", "make", "model", "serial", "installed", "notes", "pms").forEach { assertTrue(it, a.has(it)) }
        val pm = a.getJSONArray("pms").getJSONObject(0)
        listOf("title", "everyDays", "standard", "lastDay", "dueDay", "status").forEach { assertTrue(it, pm.has(it)) }
        assertEquals("OVERDUE", pm.getString("status"))
        val e = j.getJSONArray("log").getJSONObject(0)
        assertEquals("Inspection / PM", e.getString("kind"))
        assertEquals("Pass", e.getString("result"))
    }
}
