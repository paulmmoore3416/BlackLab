package com.blacklab.app.docs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

/** Writes a genuine Office Open XML (.docx) file that opens in Word, Google Docs and LibreOffice. */
object DocxWriter {

    private const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture""""

    private class Ctx(val page: PageSetup) {
        val rels = ArrayList<String>()
        val media = ArrayList<Pair<String, ByteArray>>()
        val numberedLists = LinkedHashMap<Int, Int>() // listId -> numId
        var nextRel = 10
        var nextDocPr = 1
        val contentWidthTwips: Int get() = ((page.widthPt - 2 * page.marginPt) * 20).roundToInt()

        fun numIdFor(listId: Int): Int = numberedLists.getOrPut(listId) { 2 + numberedLists.size }

        fun linkRel(url: String): String {
            val id = "rId${nextRel++}"
            rels += """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>"""
            return id
        }

        fun imageRel(bytes: ByteArray, ext: String): String {
            val id = "rId${nextRel++}"
            val name = "image${media.size + 1}.$ext"
            media += name to bytes
            rels += """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$name"/>"""
            return id
        }
    }

    fun write(blocks: List<Block>, out: File, title: String, page: PageSetup) {
        val ctx = Ctx(page)
        val body = StringBuilder()
        blocks.forEach { body.append(block(it, ctx)) }
        if (blocks.lastOrNull() !is Para) body.append("<w:p/>")
        val twW = (page.widthPt * 20).roundToInt()
        val twH = (page.heightPt * 20).roundToInt()
        val m = (page.marginPt * 20).roundToInt()
        body.append("""<w:sectPr><w:pgSz w:w="$twW" w:h="$twH"${if (page.landscape) " w:orient=\"landscape\"" else ""}/><w:pgMar w:top="$m" w:right="$m" w:bottom="$m" w:left="$m" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>""")

        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $NS><w:body>$body</w:body></w:document>"""

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            fun put(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes())
            put("_rels/.rels", rootRels())
            put("docProps/core.xml", core(title))
            put("docProps/app.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>BlackLab</Application></Properties>""")
            put("word/document.xml", document)
            put("word/styles.xml", styles())
            put("word/numbering.xml", numbering(ctx))
            put("word/_rels/document.xml.rels", docRels(ctx))
            ctx.media.forEach { (name, bytes) -> put("word/media/$name", bytes) }
        }
    }

    private fun block(b: Block, ctx: Ctx): String = when (b) {
        is Para -> para(b, ctx)
        is TableBlock -> table(b, ctx)
        is ImageBlock -> image(b, ctx)
        RuleBlock -> """<w:p><w:pPr><w:pBdr><w:bottom w:val="single" w:sz="6" w:space="1" w:color="9AA0A6"/></w:pBdr></w:pPr></w:p>"""
        PageBreakBlock -> """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""
    }

    private fun para(p: Para, ctx: Ctx): String {
        val s = p.style
        val ppr = StringBuilder()
        val styleId = when {
            s.heading == 9 -> "Title"
            s.heading in 1..6 -> "Heading${s.heading}"
            s.code -> "Code"
            s.quote -> "Quote"
            s.list == ListKind.BULLET || s.list == ListKind.NUMBER -> "ListParagraph"
            else -> null
        }
        styleId?.let { ppr.append("""<w:pStyle w:val="$it"/>""") }
        when (s.list) {
            ListKind.BULLET -> ppr.append("""<w:numPr><w:ilvl w:val="${s.level.coerceIn(0, 8)}"/><w:numId w:val="1"/></w:numPr>""")
            ListKind.NUMBER -> ppr.append("""<w:numPr><w:ilvl w:val="${s.level.coerceIn(0, 8)}"/><w:numId w:val="${ctx.numIdFor(s.listId)}"/></w:numPr>""")
            else -> Unit
        }
        s.lineHeight?.let { ppr.append("""<w:spacing w:line="${(it * 240).roundToInt()}" w:lineRule="auto"/>""") }
        val indentTw = s.indent * 720 + if (s.list == ListKind.CHECK) 360 + s.level * 360 else 0
        if (indentTw > 0) ppr.append("""<w:ind w:left="$indentTw"/>""")
        when (s.align) {
            Align.CENTER -> ppr.append("""<w:jc w:val="center"/>""")
            Align.RIGHT -> ppr.append("""<w:jc w:val="right"/>""")
            Align.JUSTIFY -> ppr.append("""<w:jc w:val="both"/>""")
            Align.LEFT -> Unit
        }
        val runs = StringBuilder()
        if (s.list == ListKind.CHECK) runs.append(runXml(Run(if (s.checked) "☑ " else "☐ ", RunStyle(font = "Segoe UI Symbol")), ctx))
        p.runs.forEach { r ->
            val rs = if (s.list == ListKind.CHECK && s.checked) r.style.copy(strike = true, color = r.style.color ?: 0xFF777777.toInt()) else r.style
            runs.append(runXml(r.copy(style = rs), ctx))
        }
        return "<w:p>" + (if (ppr.isNotEmpty()) "<w:pPr>$ppr</w:pPr>" else "") + runs + "</w:p>"
    }

    private fun runXml(r: Run, ctx: Ctx): String {
        val s = r.style
        val rpr = StringBuilder()
        if (s.link != null) rpr.append("""<w:rStyle w:val="Hyperlink"/>""")
        val font = if (s.code) "Courier New" else s.font
        font?.let { rpr.append("""<w:rFonts w:ascii="${esc(it)}" w:hAnsi="${esc(it)}" w:cs="${esc(it)}"/>""") }
        if (s.bold) rpr.append("<w:b/>")
        if (s.italic) rpr.append("<w:i/>")
        if (s.strike) rpr.append("<w:strike/>")
        s.color?.let { rpr.append("""<w:color w:val="${it.toHex6()}"/>""") }
        s.sizePt?.let { rpr.append("""<w:sz w:val="${(it * 2).roundToInt()}"/><w:szCs w:val="${(it * 2).roundToInt()}"/>""") }
        if (s.underline && s.link == null) rpr.append("""<w:u w:val="single"/>""")
        s.background?.let { rpr.append("""<w:shd w:val="clear" w:color="auto" w:fill="${it.toHex6()}"/>""") }
        if (s.sup) rpr.append("""<w:vertAlign w:val="superscript"/>""")
        if (s.sub) rpr.append("""<w:vertAlign w:val="subscript"/>""")

        val content = StringBuilder()
        r.text.split('\n').forEachIndexed { i, line ->
            if (i > 0) content.append("<w:br/>")
            line.split('\t').forEachIndexed { j, part ->
                if (j > 0) content.append("<w:tab/>")
                if (part.isNotEmpty()) content.append("""<w:t xml:space="preserve">${esc(part)}</w:t>""")
            }
        }
        val run = "<w:r>" + (if (rpr.isNotEmpty()) "<w:rPr>$rpr</w:rPr>" else "") + content + "</w:r>"
        return if (s.link != null) """<w:hyperlink r:id="${ctx.linkRel(s.link)}" w:history="1">$run</w:hyperlink>""" else run
    }

    private fun table(t: TableBlock, ctx: Ctx): String {
        val cols = t.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
        val total = ctx.contentWidthTwips
        val colW = total / cols
        val sb = StringBuilder("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$total\" w:type=\"dxa\"/><w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        repeat(cols) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
        sb.append("</w:tblGrid>")
        t.rows.forEachIndexed { ri, row ->
            val header = t.headerRow && ri == 0
            sb.append("<w:tr>")
            if (header) sb.append("<w:trPr><w:tblHeader/></w:trPr>")
            for (ci in 0 until cols) {
                val cell = row.getOrNull(ci) ?: listOf(Para(emptyList(), ParaStyle()))
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"$colW\" w:type=\"dxa\"/>")
                if (header) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EEF1F5\"/>")
                sb.append("</w:tcPr>")
                cell.forEach { p ->
                    val pp = if (header) p.copy(runs = p.runs.map { it.copy(style = it.style.copy(bold = true)) }) else p
                    sb.append(para(pp, ctx))
                }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun image(img: ImageBlock, ctx: Ctx): String {
        var bytes = img.bytes
        var ext = when (img.mime) { "image/png" -> "png"; "image/jpeg", "image/jpg" -> "jpeg"; else -> "" }
        if (ext.isEmpty()) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
            bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            ext = "png"
        }
        val rel = ctx.imageRel(bytes, ext)
        val maxEmu = ctx.contentWidthTwips.toLong() * 635
        var cx = img.widthPx.toLong() * 9525
        var cy = img.heightPx.toLong() * 9525
        if (cx > maxEmu) { cy = cy * maxEmu / cx; cx = maxEmu }
        val id = ctx.nextDocPr++
        val jc = when (img.align) { Align.CENTER -> "<w:jc w:val=\"center\"/>"; Align.RIGHT -> "<w:jc w:val=\"right\"/>"; else -> "" }
        return """<w:p>${if (jc.isNotEmpty()) "<w:pPr>$jc</w:pPr>" else ""}<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Picture $id"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect="1"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="$id" name="image$id.$ext"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="$rel"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""

    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private fun docRels(ctx: Ctx) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>${ctx.rels.joinToString("")}</Relationships>"""

    private fun core(title: String): String {
        val now = Instant.now().toString().substringBefore('.') + "Z"
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>BlackLab</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">${now.removeSuffix("ZZ").let { if (it.endsWith("Z")) it else it + "Z" }}</dcterms:created></cp:coreProperties>"""
    }

    private fun numbering(ctx: Ctx): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        val bullets = listOf("•", "◦", "▪")
        sb.append("""<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="hybridMultilevel"/>""")
        for (i in 0..8) sb.append("""<w:lvl w:ilvl="$i"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="${bullets[i % 3]}"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${720 * (i + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
        sb.append("</w:abstractNum>")
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        sb.append("""<w:abstractNum w:abstractNumId="1"><w:multiLevelType w:val="hybridMultilevel"/>""")
        for (i in 0..8) sb.append("""<w:lvl w:ilvl="$i"><w:start w:val="1"/><w:numFmt w:val="${fmts[i % 3]}"/><w:lvlText w:val="%${i + 1}."/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="${720 * (i + 1)}" w:hanging="360"/></w:pPr></w:lvl>""")
        sb.append("</w:abstractNum>")
        sb.append("""<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""")
        ctx.numberedLists.values.forEach { id ->
            sb.append("""<w:num w:numId="$id"><w:abstractNumId w:val="1"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>""")
        }
        sb.append("</w:numbering>")
        return sb.toString()
    }

    private fun styles(): String {
        fun heading(id: Int, size: Int, color: String, before: Int) =
            """<w:style w:type="paragraph" w:styleId="Heading$id"><w:name w:val="heading $id"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:uiPriority w:val="9"/><w:qFormat/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before="$before" w:after="80"/><w:outlineLvl w:val="${id - 1}"/></w:pPr><w:rPr><w:b/><w:color w:val="$color"/><w:sz w:val="$size"/><w:szCs w:val="$size"/></w:rPr></w:style>"""
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-US"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>""" +
            """<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="120"/></w:pPr><w:rPr><w:rFonts w:ascii="Calibri Light" w:hAnsi="Calibri Light"/><w:spacing w:val="-10"/><w:sz w:val="52"/><w:szCs w:val="52"/></w:rPr></w:style>""" +
            heading(1, 40, "1F3864", 360) + heading(2, 32, "2F5496", 240) + heading(3, 27, "2F5496", 200) +
            heading(4, 24, "2F5496", 160) + heading(5, 22, "2F5496", 120) + heading(6, 22, "404040", 120) +
            """<w:style w:type="paragraph" w:styleId="Quote"><w:name w:val="Quote"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:pBdr><w:left w:val="single" w:sz="18" w:space="8" w:color="4F8CFF"/></w:pBdr><w:ind w:left="360"/></w:pPr><w:rPr><w:i/><w:color w:val="404040"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/><w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F3F4F6"/><w:spacing w:after="0"/></w:pPr><w:rPr><w:rFonts w:ascii="Courier New" w:hAnsi="Courier New"/><w:sz w:val="20"/></w:rPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="40"/><w:ind w:left="720"/><w:contextualSpacing/></w:pPr></w:style>""" +
            """<w:style w:type="character" w:styleId="Hyperlink"><w:name w:val="Hyperlink"/><w:rPr><w:color w:val="0563C1"/><w:u w:val="single"/></w:rPr></w:style>""" +
            """<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/><w:left w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/><w:right w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="9AA0A6"/></w:tblBorders><w:tblCellMar><w:top w:w="60" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="60" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style>""" +
            "</w:styles>"
    }

    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit // invalid in XML 1.0
            else -> append(c)
        }
    }
}
