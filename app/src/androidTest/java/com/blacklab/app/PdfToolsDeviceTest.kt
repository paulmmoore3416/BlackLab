package com.blacklab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.files.PdfTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The PDF tools run on PdfBox + Bouncy Castle (password protection), so a library bump is checked here. */
@RunWith(AndroidJUnit4::class)
class PdfToolsDeviceTest {

    @Test fun createStampMergeSplitProtectAndUnlock() {
        val ctx = DeviceTest.context
        PdfTools.init(ctx)
        val dir = File(ctx.cacheDir, DeviceTest.tag()).also { it.mkdirs() }
        try {
            val a = File(dir, "a.pdf").also { PdfTools.blank(it, 2) }
            val b = File(dir, "b.pdf").also { PdfTools.blank(it, 1) }
            val numbered = File(dir, "n.pdf").also { PdfTools.pageNumbers(a, it) }
            assertTrue(PdfTools.text(numbered).contains("Page 2 of 2"))

            val merged = File(dir, "m.pdf").also { PdfTools.merge(listOf(numbered, b), it, dir) }
            assertEquals(3, PdfTools.pageTexts(merged).size)
            assertEquals(listOf(1, 2), PdfTools.split(merged, File(dir, "parts"), listOf(1..1, 2..3)).map { PdfTools.pageTexts(it).size })

            val locked = File(dir, "locked.pdf").also { PdfTools.protect(merged, it, "s3cret-Pass") }
            assertTrue(PdfTools.needsPassword(locked))
            val open = File(dir, "open.pdf").also { PdfTools.unlock(locked, it, "s3cret-Pass") }
            assertFalse(PdfTools.needsPassword(open))
            assertEquals(3, PdfTools.pageTexts(open).size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
