package com.blacklab.app.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOpsTest {
    private fun fixture() = JSONObject(javaClass.classLoader!!.getResource("agentops.json")!!.readText())
    private val now = 1_795_800_000_000L

    @Test fun parsesFullPayload() {
        val s = AgentOps.parse(fixture(), now)
        assertEquals("1.15.2", s.version)
        assertEquals("qwen3:14b", s.worker)
        assertEquals("qwen3:14b · fresh context", s.advisorModel)
        assertEquals(12, s.forksTotal); assertEquals(5, s.forksToday)
        assertEquals(listOf(7, 5), s.forkDays)
        assertEquals(AgentOps.Fork("which_tool", 7, 2), s.forks.first())
        assertEquals(7, s.consultsTotal); assertEquals(2, s.consultsToday)
        assertEquals(listOf(5, 2), s.consultDays)
        assertEquals("Search the documents first.", s.feed.first().advice)
        assertTrue(s.feed[1].silent)
        assertEquals(2, s.live.size)
        assertEquals("j42", s.live[0].job); assertEquals("search_documents", s.live[0].steps[0].title)
        assertTrue(s.live[1].private); assertEquals(4, s.live[1].stepCount); assertTrue(s.live[1].steps.isEmpty())
        assertEquals("Phone (BlackLab)", s.jobs.single().origin)
        assertEquals("find files named budget", s.tasks.single().request)
        assertEquals("Reviewed", s.review.status); assertEquals("No findings.", s.review.summary)
        assertEquals("sonnet", s.review.model); assertEquals("opus", s.review.advisor); assertEquals("02:30", s.review.time)
        assertEquals("ok", s.review.health)
        assertTrue(s.review.reportMd.startsWith("# Nightly review"))
        assertEquals("Skipped", s.review.now?.status)
        assertEquals(listOf("auto", "off", "claude:opus"), s.controls.choices)
        assertEquals(false, s.controls.reviewRunning)
        assertEquals(now, s.at)
    }

    @Test fun parsesEmptyAndPartialPayloads() {
        val empty = AgentOps.parse(JSONObject(), now)
        assertEquals("", empty.worker); assertTrue(empty.forks.isEmpty()); assertTrue(empty.live.isEmpty())
        assertEquals("", empty.review.status); assertNull(empty.review.now)
        val partial = AgentOps.parse(JSONObject("""{"nightly_review":{}, "advisor":{"recent":[null]}, "review_now":{}}"""), now)
        assertTrue(partial.feed.isEmpty()); assertNull(partial.review.now)
    }

    @Test fun statusWorkingWhenLive() {
        val s = AgentOps.parse(fixture(), now)
        assertEquals(AgentOps.Status.WORKING, AgentOps.status(s, online = true))
        assertEquals(AgentOps.Status.OFFLINE, AgentOps.status(s, online = false))
        assertEquals(AgentOps.Status.IDLE, AgentOps.status(AgentOps.parse(JSONObject(), now), online = true))
        assertEquals("Working · 2 tasks", AgentOps.summary(s, online = true, now = now))
        assertEquals("Idle · qwen3:14b", AgentOps.summary(AgentOps.parse(JSONObject().put("model", "qwen3:14b"), now), true, now))
    }

    @Test fun offlineSummaryUsesCacheAge() {
        val s = AgentOps.parse(fixture(), now - 2 * 3_600_000L)
        assertEquals("Offline · updated 2 h ago", AgentOps.summary(s, online = false, now = now))
        assertEquals("Offline", AgentOps.summary(null, online = false, now = now))
        val stale = AgentOps.parse(JSONObject().put("model", "m"), now - 15 * 60_000L)
        assertEquals("Idle · m · updated 15 min ago", AgentOps.summary(stale, online = true, now = now))
    }

    @Test fun agentOpsWidgetLines() {
        assertEquals(listOf("Offline", "—", "Review: —"), AgentOps.widgetLines(null, online = false, now = now))
        assertEquals(listOf("Working · 2 tasks", "5 decisions · 2 consults today", "Review: no findings"),
            AgentOps.widgetLines(AgentOps.parse(fixture(), now), online = true, now = now))
    }

    @Test fun reviewLabels() {
        val r = AgentOps.parse(fixture(), now).review
        assertEquals("no findings", AgentOps.reviewLabel(r))
        assertEquals("findings", AgentOps.reviewLabel(r.copy(summary = "2 finding(s): …")))
        assertEquals("missed", AgentOps.reviewLabel(r.copy(health = "missed")))
        assertEquals("failed", AgentOps.reviewLabel(r.copy(status = "Failed", health = "failed")))
        assertEquals("off", AgentOps.reviewLabel(r.copy(enabled = false)))
        assertEquals("skipped", AgentOps.reviewLabel(r.copy(status = "Skipped")))
        assertEquals("tonight", AgentOps.reviewLabel(r.copy(status = "")))
    }

    @Test fun notFoundMeansOldCompanion() {
        assertEquals("Update the companion on your computer (needs 1.15.2)", AgentOps.errorFor(404))
        assertEquals("Update the companion on your computer (needs 1.15.2)", AgentOps.errorMessage(IllegalStateException("HTTP 404")))
        assertEquals("The computer rejected this phone's key", AgentOps.errorMessage(SecurityException("rejected")))
    }
}
