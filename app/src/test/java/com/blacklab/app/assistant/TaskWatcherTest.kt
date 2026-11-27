package com.blacklab.app.assistant

import com.blacklab.app.link.AgentOps
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskWatcherTest {
    private fun job(id: String, status: String = "running", origin: String = "Phone (BlackLab)", private: Boolean = false, progress: String = "Asked the model") =
        DesktopJobs.Job(id, "ask", "check the generator log", status, progress, null, origin, private)

    private fun live(job: String, private: Boolean = false, steps: Int = 2) = AgentOps.LiveTask(
        if (private) "(private)" else "check the generator log", "Phone", "2026-11-27T18:40:00", job, private,
        if (private) emptyList() else (1..steps).map { AgentOps.Step("18:40:0$it", "tool", "step $it") }, steps)

    @Test fun watchesOnlyPhoneStartedRunningJobs() {
        val jobs = listOf(job("a"), job("b", status = "queued"), job("c", origin = "BlackLab Desktop (this computer)"), job("d", status = "done"), job("e", origin = "Phone (scheduled)"))
        assertEquals(listOf("a", "e"), TaskWatcher.watched(jobs).map { it.id })
    }

    @Test fun showsTheLatestStep() {
        assertEquals("Computer: check the generator log" to "step 2", TaskWatcher.text(job("a"), live("a")))
    }

    @Test fun privateTaskShowsOnlyStepCount() {
        assertEquals("Your computer is working" to "Working… (private) · step 4", TaskWatcher.text(job("a", private = true), live("a", private = true, steps = 4)))
    }

    @Test fun fallsBackToProgress() {
        assertEquals("Computer: check the generator log" to "Asked the model", TaskWatcher.text(job("a"), null))
        assertEquals("Your computer is working" to "Working… (private)", TaskWatcher.text(job("a", private = true), null))
    }

    @Test fun finishedWhenTheComputerSaysSoOrTheJobIsGone() {
        val row = { status: String -> AgentOps.JobRow("a", "ask", "t", status, "", "Phone", false) }
        assertEquals(false, TaskWatcher.finished(job("a"), row("running"), listOf(job("a"))))
        assertEquals(true, TaskWatcher.finished(job("a"), row("done"), listOf(job("a"))))
        assertEquals(true, TaskWatcher.finished(job("a"), null, emptyList()))       // dropped off both lists
        assertEquals(false, TaskWatcher.finished(job("a"), null, listOf(job("a")))) // still in the phone's running list
    }

    @Test fun titleIsShortened() {
        val long = job("a").copy(title = "x".repeat(80))
        assertEquals("Computer: " + "x".repeat(50) + "…", TaskWatcher.text(long, null).first)
    }
}
