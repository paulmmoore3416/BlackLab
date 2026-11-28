package com.blacklab.app.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedMemoryTest {
    private val now = 1_800_000_000_000L
    private val payload = JSONObject("""{"ok":true,"rev":42,
        "facts":["The chiller vendor is Trane","  ","Prefers short answers"],
        "recent":[
          {"at":${now - 60_000},"surface":"Desktop","you":"what's on the RTU-4 PM?","ai":"Filters and belts."},
          {"at":${now - 30_000},"surface":"Phone","you":"thanks","ai":"Anytime."},
          {"at":${now - 3_600_000},"surface":"Obsidian","you":"old one","ai":"stale"},
          {"at":${now - 1_000},"surface":"Desktop","you":"","ai":"no question"}]}""")

    @Test fun parseDropsBlanks() {
        val s = SharedMemory.parse(payload, now)
        assertEquals(42L, s.rev)
        assertEquals(listOf("The chiller vendor is Trane", "Prefers short answers"), s.facts)
        assertEquals(3, s.recent.size)
        assertEquals(now, s.fetchedAt)
    }

    @Test fun roundTripsThroughItsSavedCopy() {
        val s = SharedMemory.parse(payload, now)
        val back = SharedMemory.parse(SharedMemory.toJson(s), now)
        assertEquals(s.facts, back.facts)
        assertEquals(s.recent, back.recent)
    }

    @Test fun promptCarriesFactsAndOtherDevicesWhenThePhoneIsIdle() {
        val block = SharedMemory.promptBlock(SharedMemory.parse(payload, now), now, 2500, includeRecent = true, phoneIdle = true)
        assertTrue(block.contains("- The chiller vendor is Trane"))
        assertTrue(block.contains("[Desktop] User: what's on the RTU-4 PM?"))
        assertFalse("the phone's own turns are already in its history", block.contains("thanks"))
        assertFalse("older than the hand-off window", block.contains("old one"))
    }

    @Test fun noHandOffWhileThePhoneConversationIsActiveOrForNano() {
        val s = SharedMemory.parse(payload, now)
        assertFalse(SharedMemory.promptBlock(s, now, 2500, includeRecent = true, phoneIdle = false).contains("RTU-4"))
        assertFalse(SharedMemory.promptBlock(s, now, 600, includeRecent = false, phoneIdle = true).contains("RTU-4"))
    }

    @Test fun factsAreTrimmedToTheBudgetNewestKept() {
        val many = SharedMemory.State(facts = (1..100).map { "fact number $it about the building" })
        val block = SharedMemory.promptBlock(many, now, 600, includeRecent = false, phoneIdle = true)
        assertTrue(block.contains("fact number 100 "))
        assertFalse(block.contains("- fact number 1 "))
        assertTrue(block.length < 800)
    }

    @Test fun emptyMemoryAddsNothing() {
        assertEquals("", SharedMemory.promptBlock(SharedMemory.State(), now, 2500, includeRecent = true, phoneIdle = true))
    }
}
