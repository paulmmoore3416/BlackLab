package com.blacklab.app.assistant

import com.blacklab.app.assistant.ReverseEngineering.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseEngineeringTest {

    @Test fun reportsNameTheFileAndTheQuestion() {
        assertEquals(Route.Report("~/Downloads/tool.bin", ""), ReverseEngineering.route("reverse engineer ~/Downloads/tool.bin"))
        assertEquals(Route.Report("~/Downloads/tool.bin", "find how it checks the license"),
            ReverseEngineering.route("Reverse-engineer the binary ~/Downloads/tool.bin and find how it checks the license"))
        assertEquals(Route.Report("vendor-app.apk", ""), ReverseEngineering.route("reverse engineer vendor-app.apk"))
        assertEquals(Route.Report("/home/me/My Tools/x", "what it sends"), ReverseEngineering.route("triage \"/home/me/My Tools/x\" what it sends"))
        assertEquals(Route.Report("~/fw/thermostat.bin", ""), ReverseEngineering.route("run a full reverse engineering report on ~/fw/thermostat.bin"))
    }

    @Test fun statusAndHowToAreQuick() {
        listOf("rea status", "reverse engineering status", "ghidra status", "is ghidra installed?", "is rea ready on my computer",
            "how do I inspect an APK signature with rea?", "how can I decompile a Go binary", "what's the command to decompile with ghidra")
            .forEach { assertTrue(it, ReverseEngineering.route(it) is Route.Quick) }
    }

    @Test fun ghidraWorkRunsInTheBackground() {
        listOf("decompile 0x401000 in ~/Downloads/tool.bin", "what strings are in ~/Downloads/tool.bin", "what does ~/x/xxd do with -r? show me the code",
            "reverse engineer the thing I downloaded yesterday", "who calls 0x4012a0 in tool.bin, use the xrefs", "disassemble main in thing.so",
            "what functions does libfoo.so export")
            .forEach { assertTrue(it, ReverseEngineering.route(it) is Route.Background) }
    }

    @Test fun everythingElseIsNotOurs() {
        listOf("what inspections are due", "add a task to read the manual", "what's the weather", "open my downloads",
            "reading list", "remind me to call the vendor about the area rug", "summarize my notes", "brief me")
            .forEach { assertNull(it, ReverseEngineering.route(it)) }
    }

    @Test fun targets() {
        assertEquals("~/a.bin", ReverseEngineering.target("look at ~/a.bin please"))
        assertEquals("app-release.apk", ReverseEngineering.target("check app-release.apk"))
        assertNull(ReverseEngineering.target("the thing I downloaded"))
    }
}
