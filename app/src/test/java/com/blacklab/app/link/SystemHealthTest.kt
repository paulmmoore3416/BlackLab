package com.blacklab.app.link

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemHealthTest {
    private fun report(status: String = "warn", id: String = "health-2026-11-22") = JSONObject()
        .put("id", id).put("date", "2026-11-22").put("status", status).put("title", "System check · Sun Nov 22")
        .put("summary", "2 things to look at out of 40 checks.")
        .put("counts", JSONObject().put("checks", 40).put("fail", 1).put("warn", 1))
        .put("fixed", JSONArray().put("Restarted the companion"))
        .put("issues", JSONArray()
            .put(JSONObject().put("area", "System").put("status", "warn").put("title", "Swap high").put("detail", "").put("fix", ""))
            .put(JSONObject().put("area", "Data").put("status", "fail").put("title", "Vault history damaged").put("detail", "d").put("fix", "repair")))

    @Test fun parsesTheDesktopReport() {
        val r = SystemHealth.parse(report(), docId = 7)
        assertEquals("health-2026-11-22", r.id)
        assertEquals(SystemHealth.Level.WARN, r.level)
        assertEquals(40, r.checks)
        assertEquals(7L, r.docId)
        assertEquals(listOf("Restarted the companion"), r.fixed)
        // the worst issue first, whatever order the computer sent
        assertEquals("Vault history damaged", r.issues.first().title)
        assertEquals(SystemHealth.Level.FAIL, r.issues.first().level)
    }

    @Test fun unknownStatusIsTreatedAsOk() {
        assertEquals(SystemHealth.Level.OK, SystemHealth.parse(JSONObject().put("id", "health-x")).level)
        assertEquals("System check", SystemHealth.parse(JSONObject()).title)
    }

    @Test fun aNewNightIsAlwaysShown() {
        val seen = SystemHealth.parse(report(id = "health-2026-11-21"), seen = true)
        assertTrue(SystemHealth.showAgain(seen, SystemHealth.parse(report(status = "ok"))))
        assertTrue(SystemHealth.showAgain(null, SystemHealth.parse(report())))
    }

    @Test fun sameNightAddendumOnlyComesBackWhenWorse() {
        val seenWarn = SystemHealth.parse(report(status = "warn"), seen = true)
        assertFalse(SystemHealth.showAgain(seenWarn, SystemHealth.parse(report(status = "ok"))))
        assertFalse(SystemHealth.showAgain(seenWarn, SystemHealth.parse(report(status = "warn"))))
        assertTrue(SystemHealth.showAgain(seenWarn, SystemHealth.parse(report(status = "fail"))))
        // not seen yet: the newer copy replaces it and is still shown
        assertTrue(SystemHealth.showAgain(SystemHealth.parse(report(status = "warn")), SystemHealth.parse(report(status = "ok"))))
    }
}
