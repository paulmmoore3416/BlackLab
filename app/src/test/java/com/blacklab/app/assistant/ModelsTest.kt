package com.blacklab.app.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    private val desktop = JSONObject("""{"models":[
        {"id":"claude:opus","provider":"claude","label":"Claude Opus 5.5","available":true,"note":"most capable"},
        {"id":"claude:sonnet","provider":"claude","label":"Claude Sonnet 5.5","available":true},
        {"id":"gemini:gemini-2.5-pro","provider":"gemini","label":"Gemini 2.5 Pro","available":false},
        {"id":"local:qwen3:8b","provider":"local","label":"qwen3:8b","available":true},
        {"id":"auto","provider":"auto","label":"Auto","available":true}]}""")

    @Test fun providers() {
        assertEquals(Models.Provider.CLAUDE, Models.providerOf("claude:opus"))
        assertEquals(Models.Provider.GEMINI, Models.providerOf("gemini:gemini-3-pro"))
        assertEquals(Models.Provider.LOCAL, Models.providerOf("local:qwen3:8b"))
        assertEquals(Models.Provider.NANO, Models.providerOf(Models.NANO))
        assertEquals(Models.Provider.AUTO, Models.providerOf("something"))
        assertEquals(Models.Provider.AUTO, Models.providerOf(null))
    }

    @Test fun prettyNamesFollowTheRealModel() {
        assertEquals("Claude Opus 5.5", Models.prettyClaude("claude-opus-5-5"))
        assertEquals("Claude Fable 5.2", Models.prettyClaude("claude-fable-5-2"))
        assertEquals("Claude Sonnet 4.5", Models.prettyClaude("claude-sonnet-4-5-20250929"))
        assertEquals("Claude Opus 6", Models.prettyClaude("claude-opus-6"))
    }

    @Test fun claudeAndGeminiComeFirst() {
        val e = Models.build(Models.parseDesktop(desktop, true), Gemini.DEFAULT_MODELS, desktopOnline = true, geminiReady = true, nanoReady = false)
        assertEquals(listOf("claude:opus", "claude:sonnet"), e.take(2).map { it.id })
        assertEquals(Models.Provider.GEMINI, e[2].provider)
        assertTrue(e.filter { it.provider == Models.Provider.CLAUDE || it.provider == Models.Provider.GEMINI }.all { it.highlight })
        assertEquals(Models.AUTO, e.last().id)
        assertTrue(e.any { it.id == "local:qwen3:8b" })
        assertFalse("the desktop's Gemini list isn't used: the phone calls Gemini itself", e.any { it.id == "gemini:gemini-2.5-pro" && !it.available })
        assertFalse(e.first { it.id == Models.NANO }.available)
    }

    @Test fun claudeNeedsTheComputer() {
        val off = Models.build(Models.parseDesktop(desktop, false), Gemini.DEFAULT_MODELS, desktopOnline = false, geminiReady = false, nanoReady = true)
        val claude = off.filter { it.provider == Models.Provider.CLAUDE }
        assertTrue(claude.isNotEmpty() && claude.none { it.available })
        assertTrue(claude.all { it.note.contains("computer") })
        val never = Models.build(emptyList(), emptyList(), desktopOnline = false, geminiReady = false, nanoReady = false)
        assertEquals(4, never.count { it.provider == Models.Provider.CLAUDE })  // the menu still shows Claude, marked unavailable
    }
}
