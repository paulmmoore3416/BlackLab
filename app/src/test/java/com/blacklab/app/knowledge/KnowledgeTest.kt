package com.blacklab.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeTest {

    private val dir = listOf(File("src/main/assets/knowledge"), File("app/src/main/assets/knowledge")).first { it.isDirectory }
    private val bases = dir.listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }.map { Knowledge.parse(it.readText()) }
    private val index = Knowledge.Bm25(bases.flatMap { it.entries })
    private fun top(q: String, scope: Set<String>? = null) = index.search(q, 3, scope, emptyMap())

    @Test fun shipsFiveDomainAndSevenConversationBases() {
        assertEquals(5, bases.count { it.kind == Knowledge.Kind.DOMAIN })
        assertEquals(7, bases.count { it.kind == Knowledge.Kind.CONVERSATION })
        assertEquals(bases.size, bases.map { it.id }.toSet().size)
    }

    @Test fun everyBaseIsWellFormed() {
        val ids = bases.map { it.id }.toSet()
        bases.forEach { b ->
            assertTrue("${b.id} has a title", b.title.isNotBlank())
            assertTrue("${b.id} has a summary", b.summary.isNotBlank())
            assertTrue("${b.id} has enough entries (${b.entries.size})", b.entries.size >= if (b.kind == Knowledge.Kind.DOMAIN) 25 else 12)
            b.entries.forEach { e ->
                assertTrue("${e.id} has tags", e.tags.isNotEmpty())
                assertTrue("${e.id} body is substantial", e.body.split(Regex("\\s+")).size >= 60)
            }
            if (b.kind == Knowledge.Kind.CONVERSATION) {
                assertTrue("${b.id} persona", b.persona.length > 80)
                assertTrue("${b.id} opener", b.opener.isNotBlank())
                assertTrue("${b.id} starters", b.starters.size >= 3)
                assertTrue("${b.id} draws on known bases ${b.draws}", b.draws.isNotEmpty() && b.draws.all { it in ids })
            }
        }
    }

    @Test fun techConversationsDrawOnEveryDomainBase() {
        val domains = bases.filter { it.kind == Knowledge.Kind.DOMAIN }.map { it.id }.toSet()
        listOf("conv-architecture", "conv-code-craft", "conv-ai-builder", "conv-ship-it").forEach { id ->
            val c = bases.first { it.id == id }
            assertEquals("$id draws on all domain bases", domains, c.draws.toSet())
        }
    }

    @Test fun retrievalFindsTheRightEntries() {
        assertTrue(top("what is retrieval augmented generation").first().entry.title.contains("RAG"))
        assertTrue(top("how do I protect my main branch").any { it.entry.title.contains("protection", true) })
        assertTrue(top("explain transformers and attention").first().entry.title.contains("attention", true))
        assertTrue(top("when should we split a monolith into microservices").any { it.entry.title.contains("monolith", true) })
        assertTrue(top("how do I smoke a brisket").first().entry.title.contains("brisket", true))
        assertTrue(top("prompt injection defense").first().entry.title.contains("injection", true))
        assertTrue(top("emergency fund size", setOf("conv-money-home")).first().entry.title.contains("Emergency", true))
    }

    @Test fun parsesHeaderAndEntries() {
        val b = Knowledge.parse("---\nid: x\ntitle: X\nkind: conversation\ndraws: a, b\nstarters: one | two\n---\n## First\ntags: t1, t2\nask: q1 | q2\nBody one.\n\n## Second\nBody two.")
        assertEquals(Knowledge.Kind.CONVERSATION, b.kind)
        assertEquals(listOf("a", "b"), b.draws)
        assertEquals(listOf("one", "two"), b.starters)
        assertEquals(2, b.entries.size)
        assertEquals(listOf("t1", "t2"), b.entries[0].tags)
        assertEquals(listOf("q1", "q2"), b.entries[0].ask)
        assertEquals("Body one.", b.entries[0].body)
        assertEquals("x#2", b.entries[1].id)
    }

    @Test fun bestSentencesKeepsOrderAndRelevance() {
        val text = "Chillers make chilled water. Boilers make hot water. Cooling towers reject heat from chillers."
        val s = Text.bestSentences(text, "chiller heat", 2)
        assertEquals(listOf("Chillers make chilled water.", "Cooling towers reject heat from chillers."), s)
    }
}
