package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavLayoutTest {
    private fun def() = NavLayout.parse(null, null, null, null)

    @Test fun nothingIsListedTwice() {
        val l = def()
        assertEquals(NavLayout.DEFAULT_TABS, l.tabs)
        assertTrue(l.menu.none { it in l.tabs })
        assertEquals(NavLayout.sections.size, l.tabs.size + l.menu.size)
        assertEquals(l.menu.size, l.menu.distinct().size)
    }

    @Test fun everySectionIsReachableAndKnown() {
        val l = NavLayout.parse("home,bogus,tasks,tasks", "music,nope", "garage,journal", """{"tasks":"Work orders","garage":"x"}""")
        assertEquals(listOf("home", "tasks"), l.tabs)
        assertEquals(NavLayout.sections.size, l.order.size)
        assertEquals(setOf("journal"), l.hidden)
        assertEquals(mapOf("tasks" to "Work orders"), l.names)
        assertEquals("Work orders", l.label("tasks"))
        assertEquals("Library", l.label("library"))
        assertFalse("journal" in l.menu)
    }

    @Test fun homeCantBeHiddenAndTabsStayBetweenTwoAndFive() {
        val l = def()
        assertNull(NavLayout.toggleHidden(l, "home"))
        assertEquals(NavLayout.DEFAULT_TABS, NavLayout.parse(null, null, "home", null).tabs)
        assertNull(NavLayout.toggleTab(l.tabs, "journal"))  // already five
        val two = listOf("home", "tasks")
        assertNull(NavLayout.toggleTab(two, "tasks"))
        assertEquals(listOf("home", "tasks", "journal"), NavLayout.toggleTab(two, "journal"))
        assertEquals(NavLayout.DEFAULT_TABS, NavLayout.parse("home", null, null, null).tabs)  // one tab isn't enough
    }

    @Test fun hidingATabMovesItOutOfTheBar() {
        val l = NavLayout.toggleHidden(def(), "office")!!
        assertFalse("office" in l.tabs)
        assertTrue("office" in l.hidden)
        assertFalse("office" in l.menu)
        val back = NavLayout.toggleHidden(l, "office")!!
        assertTrue("office" in back.menu)
    }

    @Test fun newSectionsSlotInAfterTheirNeighbour() {
        val saved = NavLayout.sections.map { it.route }.filter { it != "office" }.reversed()
        val o = NavLayout.fullOrder(NavLayout.serialize(saved))
        assertEquals(NavLayout.sections.size, o.size)
        assertEquals(o.indexOf("notes") + 1, o.indexOf("office"))
    }

    @Test fun namesRoundTrip() {
        val s = NavLayout.serializeNames(mapOf("tasks" to "Work orders", "home" to ""))
        assertEquals(mapOf("tasks" to "Work orders"), NavLayout.parse(null, null, null, s).names)
    }
}
