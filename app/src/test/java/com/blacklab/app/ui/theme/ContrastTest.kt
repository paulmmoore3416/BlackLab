package com.blacklab.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastTest {
    private fun i(c: Long) = c.toInt()
    private val white = i(0xFFFFFFFF); private val black = i(0xFF000000); private val darkBg = i(0xFF0E1014)

    @Test fun ratioMatchesWcag() {
        assertEquals(21.0, Contrast.ratio(black, white), 0.01)
        assertEquals(1.0, Contrast.ratio(white, white), 0.001)
        assertEquals(4.48, Contrast.ratio(i(0xFF777777), white), 0.02)
    }

    @Test fun ensureDarkensOnLightAndLightensOnDark() {
        val mint = i(0xFF2BD9A8)
        assertTrue(Contrast.ratio(mint, white) < 2.0)
        val fixed = Contrast.ensure(mint, white, Contrast.AA)
        assertTrue(Contrast.ratio(fixed, white) >= Contrast.AA)
        assertTrue("keeps the hue", kotlin.math.abs(Contrast.toHsl(fixed)[0] - Contrast.toHsl(mint)[0]) < 6f)
        assertEquals("unchanged when already fine", mint, Contrast.ensure(mint, darkBg, Contrast.AA))
        val navy = i(0xFF1D2A5A)
        assertTrue(Contrast.ratio(Contrast.ensure(navy, darkBg, Contrast.AAA), darkBg) >= Contrast.AAA)
    }

    @Test fun everyPaletteShadeMeetsAaOnEveryBackground() {
        val lightBgs = LightStyle.entries.map { i(it.background) } + white
        AccentPalette.entries.forEach { p ->
            listOf(p.lightAccent, p.lightSecondary, p.lightTertiary).forEach { c ->
                lightBgs.forEach { bg -> assertTrue("${p.name} ${c.toString(16)} on ${bg.toUInt().toString(16)}", Contrast.ratio(i(c), bg) >= Contrast.AA) }
            }
            listOf(p.darkAccent, p.darkSecondary, p.darkTertiary).forEach { c -> assertTrue("${p.name} dark ${c.toString(16)}", Contrast.ratio(i(c), darkBg) >= Contrast.AA) }
        }
        assertEquals(17, AccentPalette.entries.size)
    }

    @Test fun lightStylesKeepTextAndBordersReadable() {
        LightStyle.entries.forEach { st ->
            listOf(st.background, st.card).forEach { bg ->
                assertTrue("${st.name} ink", Contrast.ratio(i(st.ink), i(bg)) >= 12.0)
                assertTrue("${st.name} soft ink", Contrast.ratio(i(st.inkSoft), i(bg)) >= Contrast.AAA)
            }
            assertTrue("${st.name} borders", Contrast.ratio(i(0xFF7E858F), i(st.background)) >= Contrast.UI)
        }
    }
}
