package com.blacklab.app.summarize

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizerTest {

    private val meeting = Summarizer.Source("Recording", "Chiller planning", """
        We met to review the chiller replacement for Building C. The current chiller is 24 years old and failed twice this summer.
        We decided to go with the magnetic-bearing chiller from the second bid. The budget request goes to finance next week.
        Mike will send the revised scope to the vendor by Friday. Sarah needs to schedule the crane permit with the city.
        One risk is that the lead time for the compressor could delay installation until spring. Is the electrical service large enough?
        The energy model shows roughly thirty percent lower cooling energy. We agreed to keep the old cooling tower for now.
    """.trimIndent(), 1_700_000_000_000)
    private val task = Summarizer.Source("Task", "Submit budget request", "Task: Submit budget request. Due Mon Nov 3, 2026.", 1_700_000_100_000, due = 1_700_000_500_000)
    private val note = Summarizer.Source("Note", "Roof walk", """
        Walked the roof of Building A after the storm. Two drains were clogged with leaves and the flashing near RTU-3 is lifting.
        Ponding water on the north side is a concern for a leak into the server room. Roofer can come Thursday.
    """.trimIndent(), 1_700_000_200_000)

    @Test fun styleFromUnderstandsRequests() {
        assertEquals(Summarizer.Style.ACTIONS, Summarizer.styleFrom("give me the action items"))
        assertEquals(Summarizer.Style.EXECUTIVE, Summarizer.styleFrom("a status report for my boss"))
        assertEquals(Summarizer.Style.TIMELINE, Summarizer.styleFrom("put it in chronological order"))
        assertEquals(Summarizer.Style.COMPARE, Summarizer.styleFrom("compare these two"))
        assertEquals(Summarizer.Style.STUDY, Summarizer.styleFrom("make me a study guide for the exam"))
        assertEquals(Summarizer.Style.SPOKEN, Summarizer.styleFrom("read it to me while I'm driving"))
        assertEquals(Summarizer.Style.TLDR, Summarizer.styleFrom("just the gist"))
        assertNull(Summarizer.styleFrom("hmm"))
        assertEquals(Summarizer.Length.SHORT, Summarizer.lengthFrom("keep it short"))
    }

    @Test fun actionsIncludeCuesAndTasks() {
        val (text, actions) = Extractive.summarize(listOf(meeting, task), Summarizer.Style.ACTIONS, Summarizer.Length.MEDIUM)
        assertTrue(actions.any { it.contains("Mike will send") })
        assertTrue(actions.any { it.contains("Sarah needs to schedule") })
        assertTrue(actions.any { it.startsWith("Submit budget request") })
        assertTrue(text.startsWith("Action items:"))
    }

    @Test fun executiveBriefHasAllSections() {
        val (text, _) = Extractive.summarize(listOf(meeting, note), Summarizer.Style.EXECUTIVE, Summarizer.Length.MEDIUM)
        listOf("Bottom line:", "Highlights:", "Risks & issues:", "Next steps:").forEach { assertTrue("missing $it", text.contains(it)) }
        assertTrue(text.contains("delay") || text.contains("concern"))
    }

    @Test fun decisionsAndQuestions() {
        val (text, _) = Extractive.summarize(listOf(meeting), Summarizer.Style.DECISIONS, Summarizer.Length.MEDIUM)
        assertTrue(text.contains("decided to go with the magnetic-bearing chiller"))
        assertTrue(text.contains("Is the electrical service large enough?"))
    }

    @Test fun timelineIsChronological() {
        val (text, _) = Extractive.summarize(listOf(note, meeting, task), Summarizer.Style.TIMELINE, Summarizer.Length.MEDIUM)
        val lines = text.lines().filter { it.startsWith("- ") }
        assertTrue(lines.indexOfFirst { it.contains("Chiller planning") } < lines.indexOfFirst { it.contains("Roof walk") })
    }

    @Test fun everyStyleProducesText() {
        Summarizer.Style.entries.forEach { st ->
            val (text, _) = Extractive.summarize(listOf(meeting, note, task), st, Summarizer.Length.SHORT)
            assertTrue("$st was empty", text.isNotBlank())
        }
    }

    @Test fun keyPointsSkipNearDuplicates() {
        val dup = meeting.copy(title = "Copy")
        val (text, _) = Extractive.summarize(listOf(meeting, dup), Summarizer.Style.KEY_POINTS, Summarizer.Length.MEDIUM)
        val bullets = text.lines().filter { it.startsWith("- ") }
        assertEquals(bullets.size, bullets.toSet().size)
    }
}
