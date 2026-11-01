package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files as NioFiles

class CrashLogTest {

    @Test fun reportHasTheBuildDeviceAndTrace() {
        val trace = CrashLog.stackTrace(IllegalStateException("boom", RuntimeException("root cause")))
        val text = CrashLog.format(1_700_000_000_000, "Crash", "IllegalStateException: boom", trace, "4.3.0", 8, "Google Pixel 10 Pro XL", "17 (API 37)", "main")
        assertTrue(text.startsWith("BlackLab problem report"))
        assertTrue(text.contains("Version: 4.3.0 (build 8)"))
        assertTrue(text.contains("Device:  Google Pixel 10 Pro XL · Android 17 (API 37)"))
        assertTrue(text.contains("Thread:  main"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException: root cause"))
    }

    @Test fun fileNamesSortByTimeAndParseBack() {
        val dir = NioFiles.createTempDirectory("crashes").toFile()
        try {
            val a = File(dir, CrashLog.fileName(1_700_000_000_000, "Crash")).apply { writeText(CrashLog.format(1_700_000_000_000, "Crash", "first", "", "4.3.0", 8, "d", "a")) }
            val b = File(dir, CrashLog.fileName(1_700_000_100_000, "ANR")).apply { writeText(CrashLog.format(1_700_000_100_000, "ANR", "second", "", "4.3.0", 8, "d", "a")) }
            assertTrue(b.name > a.name)
            assertTrue(b.name.endsWith("_anr.txt"))
            val e = CrashLog.parse(b)!!
            assertEquals("ANR", e.kind)
            assertEquals("second", e.title)
        } finally { dir.deleteRecursively() }
    }

    @Test fun pruneKeepsTheNewest() {
        val dir = NioFiles.createTempDirectory("crashes").toFile()
        try {
            (1..40).forEach { i -> File(dir, CrashLog.fileName(1_700_000_000_000 + i * 1000L, "Crash")).writeText("x") }
            CrashLog.prune(dir, keep = 30)
            val left = dir.listFiles()!!.map { it.name }.sorted()
            assertEquals(30, left.size)
            assertEquals(CrashLog.fileName(1_700_000_040_000, "Crash"), left.last())
            assertEquals(CrashLog.fileName(1_700_000_011_000, "Crash"), left.first())
        } finally { dir.deleteRecursively() }
    }
}
