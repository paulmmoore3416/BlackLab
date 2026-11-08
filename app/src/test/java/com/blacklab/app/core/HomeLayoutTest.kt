package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutTest {
    private val defaults = HomeLayout.cards.map { it.id }

    @Test fun blankMeansDefaultOrder() {
        assertEquals(defaults, HomeLayout.order(""))
        assertEquals(defaults, HomeLayout.order(null))
    }

    @Test fun savedOrderWinsAndUnknownIdsAreDropped() {
        val saved = listOf("notes", "today", "ask", "gone", "today") + defaults
        val o = HomeLayout.order(saved.joinToString(","))
        assertEquals(listOf("notes", "today", "ask"), o.take(3))
        assertEquals(defaults.toSet(), o.toSet())
        assertEquals(defaults.size, o.size)
    }

    @Test fun newCardsSlotInAfterTheirDefaultNeighbour() {
        // A layout saved before the Notes card existed: notes lands right after feed
        val old = defaults.filter { it != "notes" }.reversed()
        val o = HomeLayout.order(old.joinToString(","))
        assertEquals(o.indexOf("feed") + 1, o.indexOf("notes"))
        // A missing first card goes to the top
        val noAsk = HomeLayout.order(defaults.drop(1).joinToString(","))
        assertEquals("ask", noAsk.first())
    }

    @Test fun moveStepsAndStopsAtTheEnds() {
        val o = HomeLayout.order("")
        assertEquals(listOf("tools", "ask"), HomeLayout.move(o, "tools", -1).take(2))
        assertEquals(o, HomeLayout.move(o, "ask", -1))
        assertEquals(o, HomeLayout.move(o, defaults.last(), 1))
        assertEquals(o, HomeLayout.move(o, "nope", 1))
        assertEquals(HomeLayout.serialize(o), HomeLayout.serialize(HomeLayout.order(HomeLayout.serialize(o))))
    }

    @Test fun hiddenCardsLeaveTheVisibleList() {
        val s = Settings(homeOrder = HomeLayout.order((listOf("notes") + defaults).joinToString(",")), homeHidden = setOf("weather", "bh"))
        assertEquals("notes", s.homeCards.first())
        assertTrue("weather" !in s.homeCards && "bh" !in s.homeCards)
        assertEquals(defaults.size - 2, s.homeCards.size)
    }

    @Test fun everyCardHasASettingInTheSchema() {
        HomeLayout.cards.forEach { c -> assertTrue(c.id, SettingsSchema.def(HomeLayout.showKey(c.id)) != null) }
        assertTrue(SettingsSchema.def(HomeLayout.ORDER_KEY) != null)
    }
}
