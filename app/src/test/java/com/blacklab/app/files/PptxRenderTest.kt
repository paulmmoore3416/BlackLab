package com.blacklab.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PptxRenderTest {
    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private fun rels(vararg r: Pair<String, String>) = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        r.joinToString("") { (id, t) -> "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/${t.substringBefore('|')}\" Target=\"${t.substringAfter('|')}\"/>" } + "</Relationships>"

    private fun deck(): File {
        val f = File.createTempFile("deck", ".pptx")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
            put("ppt/presentation.xml", "<p:presentation $p><p:sldIdLst><p:sldId id=\"256\" r:id=\"rId2\"/></p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/></p:presentation>")
            put("ppt/_rels/presentation.xml.rels", rels("rId2" to "slide|slides/slide1.xml"))
            put("ppt/slides/slide1.xml", "<p:sld $p><p:cSld><p:spTree>" +
                // title placeholder without a position: inherits the layout's
                "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr><p:spPr/>" +
                "<p:txBody><a:bodyPr/><a:p><a:r><a:rPr sz=\"2400\" b=\"1\"/><a:t>Quarterly &amp; plan</a:t></a:r></a:p></p:txBody></p:sp>" +
                // a filled card in the theme accent, darkened 50 %
                "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Card\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"952500\" y=\"1905000\"/><a:ext cx=\"1905000\" cy=\"952500\"/></a:xfrm>" +
                "<a:prstGeom prst=\"roundRect\"/><a:solidFill><a:schemeClr val=\"accent1\"><a:lumMod val=\"50000\"/></a:schemeClr></a:solidFill></p:spPr>" +
                "<p:txBody><a:bodyPr anchor=\"ctr\"/><a:p><a:pPr algn=\"ctr\"/><a:r><a:rPr sz=\"1800\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:rPr><a:t>\$1.24M</a:t></a:r></a:p></p:txBody></p:sp>" +
                "<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"4\" name=\"Chart\"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr><p:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"3810000\" cy=\"2857500\"/></p:xfrm>" +
                "<a:graphic><a:graphicData><c:chart xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" r:id=\"rId3\"/></a:graphicData></a:graphic></p:graphicFrame>" +
                "</p:spTree></p:cSld></p:sld>")
            put("ppt/slides/_rels/slide1.xml.rels", rels("rId1" to "slideLayout|../slideLayouts/slideLayout1.xml", "rId3" to "chart|/ppt/charts/chart1.xml", "rId4" to "notesSlide|../notesSlides/notesSlide1.xml"))
            put("ppt/slideLayouts/slideLayout1.xml", "<p:sldLayout $p><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"T\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"title\"/></p:nvPr></p:nvSpPr>" +
                "<p:spPr><a:xfrm><a:off x=\"476250\" y=\"285750\"/><a:ext cx=\"9525000\" cy=\"952500\"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:t>Click to add title</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sldLayout>")
            put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", rels("rId1" to "slideMaster|../slideMasters/slideMaster1.xml"))
            put("ppt/slideMasters/slideMaster1.xml", "<p:sldMaster $p><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"F3F3F3\"/></a:solidFill></p:bgPr></p:bg><p:spTree/></p:cSld></p:sldMaster>")
            put("ppt/slideMasters/_rels/slideMaster1.xml.rels", rels("rId1" to "theme|../theme/theme1.xml"))
            put("ppt/theme/theme1.xml", "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:themeElements><a:clrScheme name=\"x\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1>" +
                "<a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:accent1><a:srgbClr val=\"0F6CBD\"/></a:accent1></a:clrScheme><a:fontScheme name=\"f\"><a:majorFont><a:latin typeface=\"Georgia\"/></a:majorFont>" +
                "<a:minorFont><a:latin typeface=\"Calibri\"/></a:minorFont></a:fontScheme></a:themeElements></a:theme>")
            put("ppt/notesSlides/notesSlide1.xml", "<p:notes $p><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"n\"/><p:cNvSpPr/><p:nvPr><p:ph type=\"body\"/></p:nvPr></p:nvSpPr>" +
                "<p:txBody><a:p><a:r><a:t>Lead with the ask.</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:notes>")
            put("ppt/charts/chart1.xml", "<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart><c:autoTitleDeleted val=\"1\"/><c:plotArea><c:barChart><c:barDir val=\"col\"/><c:grouping val=\"clustered\"/>" +
                "<c:ser><c:tx><c:v>Revenue</c:v></c:tx><c:cat><c:strRef><c:strCache><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>Year 1</c:v></c:pt><c:pt idx=\"1\"><c:v>Year 2</c:v></c:pt></c:strCache></c:strRef></c:cat>" +
                "<c:val><c:numRef><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"2\"/><c:pt idx=\"0\"><c:v>4.1</c:v></c:pt><c:pt idx=\"1\"><c:v>6.8</c:v></c:pt></c:numCache></c:numRef></c:val></c:ser>" +
                "</c:barChart></c:plotArea></c:chart></c:chartSpace>")
        }
        return f
    }

    @Test fun rendersSlidesLikePowerPoint() {
        val d = PptxRender.render(deck())
        assertEquals(1280, d.width); assertEquals(720, d.height)
        val s = d.slides.single()
        assertEquals("Quarterly & plan", s.title)
        assertEquals("Lead with the ask.", s.notes)
        val h = s.html
        assertTrue("master background", h.contains("background:#F3F3F3"))
        assertTrue("title sits where the layout put it", h.contains("left:50px;top:30px;width:1000px;height:100px"))
        assertTrue("24 pt bold in the theme's heading font", h.contains("font-size:32px;font-family:'Georgia'") && h.contains("font-weight:700"))
        assertTrue("theme accent darkened by lumMod", h.contains("background:#08365F"))
        assertTrue("round corners", h.contains("border-radius:"))
        assertTrue("text escaped", h.contains("Quarterly &amp; plan"))
        assertTrue("chart drawn from cached values", h.contains("<svg viewBox") && h.contains("Year 2") && h.contains("<rect"))
        assertFalse("layout prompt text never shows", h.contains("Click to add title"))
    }

    @Test fun presentPageScalesToFit() {
        val page = PptxRender.page(PptxRender.render(deck()), present = true, dark = true)
        assertTrue(page.contains("PRESENT=true") && page.contains("function show("))
    }
}
