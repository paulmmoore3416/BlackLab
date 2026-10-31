package com.blacklab.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantLogicTest {

    @Test fun nameplateRtu() {
        val ocr = """
            CARRIER CORPORATION  SYRACUSE NY
            MODEL NO. 48TCED08A2A5-0A0A0
            SERIAL NO. 2219G30123
            VOLTS 208/230  PH 3  HZ 60
            COMPRESSOR RLA 25.0  FAN MOTOR FLA 3.4
            MCA 35  MAX FUSE 50
            REFRIGERANT R-410A  CHARGE 9.5 LBS
        """.trimIndent()
        val a = Nameplate.parse(ocr).fields
        assertEquals("Carrier", a["Manufacturer"])
        assertEquals("48TCED08A2A5-0A0A0", a["Model"])
        assertEquals("2219G30123", a["Serial"])
        assertEquals("R-410A", a["Refrigerant"])
        assertEquals("25.0", a["RLA"])
        assertEquals("35", a["MCA"])
        assertTrue(a["Voltage"]!!.startsWith("208/230"))
    }

    @Test fun nameplateMotor() {
        val a = Nameplate.parse("BALDOR RELIANCE  SUPER-E\nCAT NO. EM3615T  SER F1912\n5 HP 1750 RPM 230/460 V 3 PH 60 HZ FLA 13.6/6.8").fields
        assertEquals("Baldor", a["Manufacturer"])
        assertEquals("5", a["Horsepower"])
        assertEquals("1750", a["RPM"])
        assertEquals("3", a["Phase"])
    }

    @Test fun meetingHeuristic() {
        val text = "Thanks everyone for joining today. We decided to replace the RTU on building C this fall. " +
            "Mike will send the revised quote by Friday. The weather has been nice lately for sure. " +
            "Sarah needs to schedule the crane for the lift next week. Let's review the budget again on Monday."
        val (decisions, actions) = MeetingActions.heuristic(text)
        assertEquals(1, decisions.size)
        assertTrue(decisions[0].contains("replace the RTU"))
        assertEquals(3, actions.size)
        assertTrue(actions.any { it.startsWith("Mike will send") })
    }

    @Test fun indexTokensAndChunks() {
        assertEquals(listOf("roof", "warranty", "inspection"), LocalIndex.tokens("What is the roof warranty inspections?"))
        val words = (1..250).joinToString(" ") { "w$it" }
        val chunks = LocalIndex.chunk(words)
        assertEquals(3, chunks.size)
        assertTrue(chunks[1].startsWith("w91 "))
    }
}
