package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturesTest {
    private fun sig(id: String, kind: String = Signatures.SIGNATURE, created: Long = 0, def: Boolean = false) = Signatures.Sig(id, kind, id, created, def, 10, 5)

    @Test fun indexRoundTrips() {
        val list = listOf(sig("a", created = 5, def = true), sig("b", Signatures.INITIALS, 7, true))
        assertEquals(list, Signatures.parse(Signatures.serialize(list)))
        assertEquals(emptyList<Signatures.Sig>(), Signatures.parse("not json"))
    }

    @Test fun oneDefaultPerKind() {
        val out = Signatures.normalizeDefaults(listOf(sig("a", created = 1), sig("b", created = 3), sig("i", Signatures.INITIALS, 2)))
        assertEquals(listOf("b"), out.filter { it.kind == Signatures.SIGNATURE && it.isDefault }.map { it.id }) // newest wins when none chosen
        assertTrue(out.single { it.id == "i" }.isDefault)
        val chosen = Signatures.normalizeDefaults(out, preferred = "a")
        assertEquals(listOf("a"), chosen.filter { it.kind == Signatures.SIGNATURE && it.isDefault }.map { it.id })
        assertTrue(chosen.single { it.id == "i" }.isDefault) // other kinds untouched
    }

    @Test fun deletingTheDefaultPromotesAnother() {
        val before = Signatures.normalizeDefaults(listOf(sig("a", created = 1), sig("b", created = 3)), preferred = "b")
        val after = Signatures.normalizeDefaults(before.filter { it.id != "b" })
        assertTrue(after.single().isDefault)
    }
}
