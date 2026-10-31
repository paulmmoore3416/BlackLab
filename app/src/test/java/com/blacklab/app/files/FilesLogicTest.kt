package com.blacklab.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilesLogicTest {

    private fun sheet(vararg rows: List<String>) = Sheet("S", rows.map { it.toMutableList() }.toMutableList())

    @Test fun formulasEvaluate() {
        val s = sheet(listOf("2", "3", "=A1*B1"), listOf("=SUM(A1:C1)", "=AVERAGE(A1:B1)", "=IF(C1>5,\"big\",\"small\")"), listOf("=ROUND(10/3,2)", "=A1^3", "=CONCAT(\"x\",A1)"))
        val e = Evaluator(s)
        assertEquals("6", e.display(0, 2))
        assertEquals("11", e.display(1, 0))
        assertEquals("2.5", e.display(1, 1))
        assertEquals("big", e.display(1, 2))
        assertEquals("3.33", e.display(2, 0))
        assertEquals("8", e.display(2, 1))
        assertEquals("x2", e.display(2, 2))
    }

    @Test fun formulaErrors() {
        val s = sheet(listOf("=B1", "=A1", "=1/0", "=NOPE(1)"))
        val e = Evaluator(s)
        assertEquals("#CIRC!", e.display(0, 1).let { if (it == "#CIRC!") it else e.display(0, 0) })
        assertEquals("#DIV/0!", e.display(0, 2))
        assertEquals("#NAME?", e.display(0, 3))
    }

    @Test fun csvRoundTrip() {
        val rows = Sheets.parseCsv("Name,Note\r\n\"Smith, J\",\"said \"\"hi\"\"\"\nBolts,\n")
        assertEquals(listOf("Smith, J", "said \"hi\""), rows[1])
        assertEquals(listOf("Bolts", ""), rows[2])
        assertEquals(rows, Sheets.parseCsv(Sheets.toCsv(rows)))
        assertEquals(';', Sheets.detectDelimiter("a;b;c"))
    }

    @Test fun columnNames() {
        assertEquals("A", Sheets.colName(0)); assertEquals("Z", Sheets.colName(25)); assertEquals("AA", Sheets.colName(26))
        assertEquals(27, Sheets.colIndex("AB"))
    }

    @Test fun xlsxRoundTripKeepsFormulasAndSheets() {
        val f = File.createTempFile("blacklab", ".xlsx")
        val a = sheet(listOf("Item", "Cost"), listOf("Filters", "150"), listOf("Belts", "79.5"), listOf("", "=SUM(B2:B3)"))
        val b = Sheet("Second", mutableListOf(mutableListOf("007", "<tag> & more")))
        Sheets.writeXlsx(listOf(a, b), f)
        val back = Sheets.readXlsx(f)
        assertEquals(2, back.size)
        assertEquals("Second", back[1].name)
        assertEquals("=SUM(B2:B3)", back[0][3, 1])
        assertEquals("150", back[0][1, 1])
        assertEquals("007", back[1][0, 0]) // leading zeros stay text
        assertEquals("<tag> & more", back[1][0, 1])
        assertEquals("229.5", Evaluator(back[0]).display(3, 1))
        f.delete()
    }

    @Test fun pageRanges() {
        assertEquals(listOf(1..3, 5..5, 8..10), PdfTools.parseRanges("1-3, 5, 8-", 10))
        assertEquals(4, PdfTools.parseRanges("", 4).size)
        assertEquals(listOf(9..9), PdfTools.parseRanges("12", 9))
    }

    @Test fun kindsAndPaths() {
        assertEquals(FileKind.PDF, FileKinds.ofName("a.PDF"))
        assertEquals(FileKind.CODE, FileKinds.ofName("app.ts"))
        assertEquals(FileKind.SHEET, FileKinds.ofName("x.xlsx"))
        assertEquals(FileKind.AUDIO, FileKinds.ofName("song.flac"))
        assertEquals("ppt/media/image1.png", OfficeReader.resolve("ppt/slides/slide1.xml", "../media/image1.png"))
        assertTrue(FileKinds.isTextLike(FileKind.MARKDOWN))
    }

    @Test fun unzipRefusesZipSlip() {
        val z = File.createTempFile("blacklab", ".zip")
        java.util.zip.ZipOutputStream(z.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("../evil.txt")); zos.write("x".toByteArray()); zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("ok/good.txt")); zos.write("y".toByteArray()); zos.closeEntry()
        }
        val dest = File(System.getProperty("java.io.tmpdir"), "bl_unzip_${System.nanoTime()}")
        assertEquals(1, FileOps.unzip(z, dest))
        assertTrue(File(dest, "ok/good.txt").exists())
        assertTrue(!File(dest.parentFile, "evil.txt").exists())
        dest.deleteRecursively(); z.delete()
    }
}
