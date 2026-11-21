package com.blacklab.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class XlsxBookTest {
    private val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val chart = "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><!-- untouched --></c:chartSpace>"

    /** A small "financial model": Inputs (styled, frozen, merged), Model (cross-sheet formulas), a chart part and a calc chain. */
    private fun workbook(): File {
        val f = File.createTempFile("book", ".xlsx")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/xl/workbook.xml\" ContentType=\"x\"/><Override PartName=\"/xl/calcChain.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.calcChain+xml\"/></Types>")
            put("xl/workbook.xml", "<workbook $ns><sheets><sheet name=\"Inputs\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Rate Model\" sheetId=\"2\" r:id=\"rId2\"/></sheets>" +
                "<definedNames><definedName name=\"Beds\">Inputs!\$B\$2</definedName></definedNames><calcPr calcId=\"191029\"/></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"ws\" Target=\"worksheets/sheet1.xml\"/>" +
                "<Relationship Id=\"rId2\" Type=\"ws\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId9\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/calcChain\" Target=\"calcChain.xml\"/></Relationships>")
            put("xl/styles.xml", "<styleSheet $ns><numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"&quot;\$&quot;#,##0\"/><numFmt numFmtId=\"165\" formatCode=\"0.0%\"/></numFmts>" +
                "<fonts count=\"2\"><font><sz val=\"11\"/></font><font><b/><color rgb=\"FF1F4E79\"/></font></fonts>" +
                "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill><fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDDEBF7\"/></patternFill></fill></fills>" +
                "<cellXfs count=\"4\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\"><alignment horizontal=\"center\"/></xf><xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\"/><xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\"/></cellXfs></styleSheet>")
            put("xl/worksheets/sheet1.xml", "<worksheet $ns><sheetViews><sheetView><pane ySplit=\"1\" topLeftCell=\"A2\" state=\"frozen\"/></sheetView></sheetViews>" +
                "<cols><col min=\"1\" max=\"1\" width=\"30\" customWidth=\"1\"/></cols><sheetData>" +
                "<row r=\"1\" spans=\"1:2\"><c r=\"A1\" s=\"1\" t=\"inlineStr\"><is><t>Input</t></is></c><c r=\"B1\" s=\"1\" t=\"inlineStr\"><is><t>Value</t></is></c></row>" +
                "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Beds</t></is></c><c r=\"B2\"><v>25</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Occupancy</t></is></c><c r=\"B3\" s=\"3\"><v>0.75</v></c></row>" +
                "<row r=\"4\"><c r=\"A4\" t=\"inlineStr\"><is><t>Rate</t></is></c><c r=\"B4\" s=\"2\"><v>1000</v></c></row>" +
                "</sheetData><mergeCells count=\"1\"><mergeCell ref=\"A6:B6\"/></mergeCells></worksheet>")
            put("xl/worksheets/sheet2.xml", "<worksheet $ns><sheetData>" +
                "<row r=\"1\"><c r=\"A1\" s=\"2\"><f>Inputs!B2*Inputs!B3*'Inputs'!B4*365</f><v>6843750</v></c><c r=\"B1\"><f>Beds*2</f><v>50</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><f>MYSTERY(A1)</f><v>42</v></c></row>" +
                "</sheetData></worksheet>")
            put("xl/calcChain.xml", "<calcChain $ns><c r=\"A1\" i=\"2\"/></calcChain>")
            put("xl/charts/chart1.xml", chart)
        }
        return f
    }

    @Test fun readsWhatExcelShows() {
        val b = XlsxBook.load(workbook())
        assertEquals(listOf("Inputs", "Rate Model"), b.sheets.map { it.name })
        assertEquals("=Inputs!B2*Inputs!B3*'Inputs'!B4*365", b.sheets[1][0, 0])
        assertEquals("6843750", b.cached(1, 0, 0))
        val head = b.style(0, 0, 1)
        assertTrue(head.bold); assertEquals(0xFFDDEBF7, head.fill); assertEquals("center", head.align); assertEquals(0xFF1F4E79, head.color)
        assertEquals("0.0%", b.style(0, 2, 1).numFmt)
        assertEquals("\"\$\"#,##0", b.style(1, 0, 0).numFmt)
        assertEquals(1, b.info[0].frozenRows)
        assertEquals(30.0, b.info[0].widths[0]!!, 0.01)
        assertEquals(listOf(5, 0, 5, 1), b.info[0].merges.single().toList())
        assertEquals("Inputs!\$B\$2", b.names["BEDS"])
    }

    @Test fun numberFormatsLookLikeExcel() {
        val f = XlsxBook::format
        assertEquals("1,234.50", f("1234.5", "#,##0.00"))
        assertEquals("18.1%", f("0.181", "0.0%"))
        assertEquals("(1,234)", f("-1234", "#,##0;(#,##0)"))
        assertEquals("-1,234", f("-1234", "#,##0"))
        assertEquals("\$6,843,750", f("6843750", "\"\$\"#,##0"))
        assertEquals("\$1,500", f("1500", "[\$\$-409]#,##0"))
        assertEquals("\$1,240,000.00", f("1240000", "_(\"\$\"* #,##0.00_);_(\"\$\"* (#,##0.00);_(\"\$\"* \"-\"??_);_(@_)"))
        assertEquals("1.23E+04", f("12345", "0.00E+00"))
        assertEquals("1234.5", f("1234.5", "General"))
        assertEquals("1.2", f("1.2", "0.0#"))
        assertEquals("\$1.41M", f("1410000", "\"\$\"0.00,,\"M\""))
        assertEquals("11/21/2026", f(Evaluator.serial(java.time.LocalDate.of(2026, 11, 21)).toString(), "m/d/yyyy"))
        assertEquals("Nov-26", f(Evaluator.serial(java.time.LocalDate.of(2026, 11, 21)).toString(), "mmm-yy"))
        assertEquals("text", f("text", "#,##0"))
    }

    @Test fun formulasAcrossSheetsAndNames() {
        val b = XlsxBook.load(workbook())
        val e = BookEvaluator(b.sheets, b.names)
        assertEquals(6843750.0, e[1].value(0, 0) as Double, 0.001)
        assertEquals(50.0, e[1].value(0, 1) as Double, 0.001)
        assertTrue("unknown functions are flagged, not guessed", Evaluator.isUnsupported(e[1].value(1, 0)))
    }

    @Test fun savingRewritesOnlyTheEditedCells() {
        val src = workbook()
        val b = XlsxBook.load(src)
        val out = File.createTempFile("patched", ".xlsx")
        XlsxBook.patch(src, out, b, mapOf(
            0 to mapOf(XlsxBook.key(1, 1) to "30", XlsxBook.key(2, 1) to "0.8", XlsxBook.key(9, 2) to "note & more"),
            1 to mapOf(XlsxBook.key(0, 1) to "=Beds*3"),
        ))
        ZipFile(out).use { z ->
            fun text(n: String) = z.getEntry(n)?.let { e -> z.getInputStream(e).use { String(it.readBytes()) } }
            assertEquals(chart, text("xl/charts/chart1.xml"))
            assertNull(text("xl/calcChain.xml"))
            assertFalse(text("[Content_Types].xml")!!.contains("calcChain"))
            assertFalse(text("xl/_rels/workbook.xml.rels")!!.contains("calcChain"))
            assertTrue(text("xl/workbook.xml")!!.contains("<calcPr fullCalcOnLoad=\"1\" calcId=\"191029\"/>"))
            val s1 = text("xl/worksheets/sheet1.xml")!!
            assertTrue("keeps the cell style", s1.contains("<c r=\"B3\" s=\"3\"><v>0.8</v></c>"))
            assertTrue("new row in order", s1.indexOf("<row r=\"4\"") < s1.indexOf("<row r=\"10\""))
            assertTrue(s1.contains("<c r=\"C10\" t=\"inlineStr\"><is><t xml:space=\"preserve\">note &amp; more</t></is></c>"))
            assertTrue("untouched parts of the sheet stay", s1.contains("<mergeCell ref=\"A6:B6\"/>") && s1.contains("state=\"frozen\""))
            val s2 = text("xl/worksheets/sheet2.xml")!!
            assertTrue("edited formula gets its fresh result", s2.contains("<c r=\"B1\"><f>Beds*3</f><v>90</v></c>"))
            assertTrue("dependent formulas refresh their cached value", s2.contains("<c r=\"A1\" s=\"2\"><f>Inputs!B2*Inputs!B3*'Inputs'!B4*365</f><v>8760000</v></c>"))
            assertTrue("formulas the engine can't compute keep Excel's value", s2.contains("<f>MYSTERY(A1)</f><v>42</v>"))
        }
        val back = XlsxBook.load(out)
        assertEquals("30", back.sheets[0][1, 1])
        assertEquals("=Beds*3", back.sheets[1][0, 1])
        assertEquals("0.0%", back.style(0, 2, 1).numFmt)
        assertEquals(90.0, BookEvaluator(back.sheets, back.names)[1].value(0, 1) as Double, 0.001)
        assertEquals("8760000", back.cached(1, 0, 0))
    }

    @Test fun sharedFormulasExpandAndShift() {
        assertEquals("A3*2+\$B\$1+Sheet2!C4", XlsxBook.shift("A2*2+\$B\$1+Sheet2!C3", 1, 0))
        assertEquals("SUM(C2:D2)&\"A1\"", XlsxBook.shift("SUM(B2:C2)&\"A1\"", 0, 1))
        assertEquals("LOG10(B2)", XlsxBook.shift("LOG10(A2)", 0, 1))
        assertEquals("'My A1 sheet'!B3", XlsxBook.shift("'My A1 sheet'!B2", 1, 0))
        val f = File.createTempFile("shared", ".xlsx")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("xl/workbook.xml", "<workbook $ns><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"ws\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            put("xl/worksheets/sheet1.xml", "<worksheet $ns><sheetData>" +
                "<row r=\"1\"><c r=\"A1\"><v>1</v></c><c r=\"B1\"><f t=\"shared\" ref=\"B1:B3\" si=\"0\">A1*10</f><v>10</v></c></row>" +
                "<row r=\"2\"><c r=\"A2\"><v>2</v></c><c r=\"B2\"><f t=\"shared\" si=\"0\"/><v>20</v></c></row>" +
                "<row r=\"3\"><c r=\"A3\"><v>3</v></c><c r=\"B3\"><f t=\"shared\" si=\"0\"/><v>30</v></c></row></sheetData></worksheet>")
        }
        val b = XlsxBook.load(f)
        assertEquals("=A3*10", b.sheets[0][2, 1])
        b.sheets[0][1, 1] = "=A2*100"
        val out = File.createTempFile("shared-out", ".xlsx")
        XlsxBook.patch(f, out, b, mapOf(0 to mapOf(XlsxBook.key(1, 1) to "=A2*100")))
        val back = XlsxBook.load(out)
        assertEquals(listOf("=A1*10", "=A2*100", "=A3*10"), (0..2).map { back.sheets[0][it, 1] })
        assertEquals("200", back.cached(0, 1, 1))
        assertEquals("30", back.cached(0, 2, 1))
    }

    @Test fun recalcFlagIsAddedWhenMissing() {
        assertEquals("<workbook><sheets/><calcPr fullCalcOnLoad=\"1\"/></workbook>", XlsxBook.recalcOnLoad("<workbook><sheets/></workbook>"))
        assertEquals("<calcPr fullCalcOnLoad=\"1\" calcId=\"1\"/>", XlsxBook.recalcOnLoad("<calcPr fullCalcOnLoad=\"0\" calcId=\"1\"/>"))
    }

    private fun sheet(name: String, vararg rows: List<String>) = Sheet(name, rows.map { it.toMutableList() }.toMutableList())

    @Test fun lookupsAndConditionalMath() {
        val data = sheet("Data",
            listOf("Payer", "Days", "Rate"),
            listOf("VA", "4000", "1100"),
            listOf("TRICARE", "600", "1000"),
            listOf("Medicare", "900", "950"),
        )
        val calc = sheet("Calc",
            listOf("=VLOOKUP(\"TRICARE\",Data!A2:C4,3,FALSE)", "=INDEX(Data!C2:C4,MATCH(\"Medicare\",Data!A2:A4,0))", "=SUMPRODUCT(Data!B2:B4,Data!C2:C4)"),
            listOf("=SUMIF(Data!A2:A4,\"<>VA\",Data!B2:B4)", "=COUNTIF(Data!B2:B4,\">=900\")", "=IFERROR(1/0,\"n/a\")"),
            listOf("=IF(B4=0,0,1/B4)", "=ROUNDUP(2.341,1)", "=AVERAGEIFS(Data!C2:C4,Data!B2:B4,\">500\")"),
            listOf("", "0", "=MOD(-7,3)"),
            listOf("=VLOOKUP(\"Nope\",Data!A2:C4,2,FALSE)", "=IFNA(A5,\"missing\")", "=PMT(0.05/12,60,-10000)"),
        )
        val e = BookEvaluator(listOf(data, calc))[1]
        assertEquals(1000.0, e.value(0, 0))
        assertEquals(950.0, e.value(0, 1))
        assertEquals(4000 * 1100.0 + 600 * 1000 + 900 * 950, e.value(0, 2) as Double, 0.001)
        assertEquals(1500.0, e.value(1, 0))
        assertEquals(2.0, e.value(1, 1))
        assertEquals("n/a", e.value(1, 2))
        assertEquals("lazy IF", 0.0, e.value(2, 0))
        assertEquals(2.4, e.value(2, 1) as Double, 1e-9)
        assertEquals(1016.6666, e.value(2, 2) as Double, 0.001)
        assertEquals(2.0, e.value(3, 2))
        assertEquals("#N/A", e.value(4, 0))
        assertEquals("missing", e.value(4, 1))
        assertEquals(188.71, e.value(4, 2) as Double, 0.01)
    }
}
