package com.blacklab.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class LibraryOpsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/Chicago")
    private fun file(dir: File, name: String, day: LocalDate? = null, text: String = "x") = File(dir, name).apply {
        parentFile.mkdirs(); writeText(text); day?.let { setLastModified(it.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000) }
    }

    @Test fun newFolderCleansAndUniquifies() {
        val root = tmp.newFolder("Photos")
        val a = LibraryOps.newFolder(root, "  Boiler room / east  ")!!
        assertEquals("Boiler room east", a.name)
        assertEquals("Boiler room east (2)", LibraryOps.newFolder(root, "Boiler room east")!!.name)
        assertNull(LibraryOps.newFolder(root, " ... "))
    }

    @Test fun moveBringsTranscriptsAndAvoidsCollisions() {
        val root = tmp.newFolder("Recordings")
        val audio = file(root, "Meeting.m4a"); file(root, "Meeting.txt")
        val dest = File(root, "Safety committee").apply { mkdirs() }
        file(dest, "Meeting.m4a", text = "older")
        val moved = LibraryOps.move(listOf(audio), dest)
        assertEquals(setOf("Meeting (2).m4a", "Meeting (2).txt"), moved.map { it.to.name }.toSet())
        assertFalse(File(root, "Meeting.m4a").exists()); assertFalse(File(root, "Meeting.txt").exists())
        assertEquals("older", File(dest, "Meeting.m4a").readText())
    }

    @Test fun neverMovesAFolderIntoItself() {
        val root = tmp.newFolder("Scans")
        val a = File(root, "A").apply { mkdirs() }; val b = File(a, "B").apply { mkdirs() }
        assertTrue(LibraryOps.move(listOf(a), b).isEmpty())
        assertTrue(a.isDirectory && b.isDirectory)
    }

    @Test fun autoSortGroupsByMonthAndLeavesSidecars() {
        val root = tmp.newFolder("Photos")
        file(root, "a.jpg", LocalDate.of(2026, 10, 3)); file(root, "b.jpg", LocalDate.of(2026, 10, 30)); file(root, "c.jpg", LocalDate.of(2026, 11, 5))
        File(root, "Already").mkdirs()
        val moved = LibraryOps.autoSortByMonth(root, zone)
        assertEquals(3, moved.size)
        assertEquals(setOf("a.jpg", "b.jpg"), File(root, "2026-10 October").list()!!.toSet())
        assertEquals(setOf("c.jpg"), File(root, "2026-11 November").list()!!.toSet())
        assertTrue(File(root, "Already").isDirectory)
    }

    @Test fun sortingPutsFoldersFirst() {
        val root = tmp.newFolder("Exports")
        val big = file(root, "big.zip", text = "x".repeat(500)); val small = file(root, "a.txt"); val dir = File(root, "Zed").apply { mkdirs() }
        assertEquals(listOf(dir, small, big), LibraryOps.sorted(listOf(big, small, dir), LibraryOps.Sort.NAME_AZ))
        assertEquals(listOf(dir, big, small), LibraryOps.sorted(listOf(small, big, dir), LibraryOps.Sort.LARGEST))
    }

    @Test fun renameKeepsTheExtension() {
        val root = tmp.newFolder("Imports")
        val f = file(root, "IMG_0001.jpg")
        assertEquals("Roof drain.jpg", LibraryOps.rename(f, "Roof drain")!!.name)
    }

    @Test fun folderTreeListsNestedFolders() {
        val root = tmp.newFolder("Videos")
        File(root, "B/inner").mkdirs(); File(root, "A").mkdirs()
        assertEquals(listOf("Videos" to 0, "A" to 1, "B" to 1, "inner" to 2), LibraryOps.folderTree(root).map { it.first.name to it.second })
    }
}
