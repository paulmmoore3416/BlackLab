package com.blacklab.app.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One page in the organizer: which source page and how much extra rotation to apply. */
data class PageRef(val index: Int, val rotate: Int = 0)

/** Things drawn onto pages. Coordinates are normalized to the page as displayed (0,0 = top-left). */
sealed class Stamp(open val page: Int) {
    data class Ink(override val page: Int, val points: List<PointF>, val color: Int, val width: Float, val alpha: Float = 1f) : Stamp(page)
    data class Text(override val page: Int, val x: Float, val y: Float, val text: String, val size: Float, val color: Int, val bold: Boolean = false) : Stamp(page)
    data class Image(override val page: Int, val rect: RectF, val bitmap: Bitmap) : Stamp(page)
    data class Box(override val page: Int, val rect: RectF, val color: Int, val filled: Boolean, val alpha: Float) : Stamp(page)
}

data class FormField(val name: String, val type: String, val value: String, val options: List<String> = emptyList())

object PdfTools {
    @Volatile private var ready = false
    fun init(context: Context) { if (!ready) { PDFBoxResourceLoader.init(context.applicationContext); ready = true } }

    private fun load(f: File, password: String? = null): PDDocument =
        if (password.isNullOrEmpty()) PDDocument.load(f, MemoryUsageSetting.setupMixed(64L * 1024 * 1024)) else PDDocument.load(f, password, MemoryUsageSetting.setupMixed(64L * 1024 * 1024))

    /** Saves [doc] over [out] atomically, so a failed save never destroys the original. */
    private fun save(doc: PDDocument, out: File) = FileOps.writeAtomically(out) { doc.save(it) }

    /** Standard-14 fonts only cover WinAnsi; anything else becomes "?" instead of crashing. */
    private fun safe(s: String) = s.map { if (it.code in 32..126 || it.code in 160..255 || it in "“”‘’–—•…€™") it else if (it == '\n') ' ' else '?' }.joinToString("")

    // ------------------------------------------------------------------ inspection

    fun needsPassword(f: File): Boolean = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).close() }; false
    }.getOrElse { it is SecurityException }

    fun pageTexts(f: File, password: String? = null): List<String> = load(f, password).use { doc ->
        val st = PDFTextStripper()
        (1..doc.numberOfPages).map { p -> st.startPage = p; st.endPage = p; runCatching { st.getText(doc) }.getOrDefault("") }
    }

    fun text(f: File, password: String? = null): String = pageTexts(f, password).mapIndexed { i, t -> "— Page ${i + 1} —\n${t.trim()}" }.joinToString("\n\n")

    fun info(f: File): Map<String, String> = runCatching {
        load(f).use { d ->
            val i = d.documentInformation
            linkedMapOf(
                "Pages" to d.numberOfPages.toString(),
                "Title" to i.title.orEmpty(), "Author" to i.author.orEmpty(), "Subject" to i.subject.orEmpty(), "Keywords" to i.keywords.orEmpty(),
                "Creator" to i.creator.orEmpty(), "Producer" to i.producer.orEmpty(), "PDF version" to d.version.toString(),
                "Encrypted" to if (d.isEncrypted) "Yes" else "No",
                "Form fields" to (d.documentCatalog.acroForm?.fields?.size ?: 0).toString(),
            ).filterValues { it.isNotBlank() }
        }
    }.getOrDefault(emptyMap())

    fun setInfo(f: File, out: File, title: String, author: String, subject: String, keywords: String) = load(f).use { d ->
        d.documentInformation.apply { this.title = title; this.author = author; this.subject = subject; this.keywords = keywords }
        save(d, out)
    }

    // ------------------------------------------------------------------ page organizer

    /** Rebuilds the document with [pages] in order (deletes, duplicates, reorders, rotates). */
    fun organize(f: File, out: File, pages: List<PageRef>) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        load(f).use { src ->
            PDDocument().use { dst ->
                dst.documentInformation = src.documentInformation
                pages.forEach { ref ->
                    val p = dst.importPage(src.getPage(ref.index))
                    p.rotation = ((p.rotation + ref.rotate) % 360 + 360) % 360
                }
                src.documentCatalog.acroForm?.let { if (pages.map { it.index }.distinct().size == src.numberOfPages) dst.documentCatalog.acroForm = it }
                save(dst, out)
            }
        }
    }

    fun merge(inputs: List<File>, out: File, cache: File) {
        val temps = mutableListOf<File>()
        try {
            val m = PDFMergerUtility()
            inputs.forEach { f ->
                if (FileKinds.of(f) == FileKind.IMAGE) {
                    val t = File(cache, "merge_${System.nanoTime()}.pdf"); imagesToPdf(listOf(f), t, "FIT"); temps += t; m.addSource(t)
                } else m.addSource(f)
            }
            val tmp = File(out.parentFile, ".${out.name}.merge"); temps += tmp
            m.destinationFileName = tmp.path
            m.mergeDocuments(MemoryUsageSetting.setupMixed(64L * 1024 * 1024))
            if (out.exists()) out.delete()
            tmp.renameTo(out)
        } finally { temps.forEach { it.delete() } }
    }

    /** Splits into one file per range (1-based, inclusive). */
    fun split(f: File, outDir: File, ranges: List<IntRange>): List<File> = load(f).use { src ->
        ranges.map { r ->
            PDDocument().use { dst ->
                for (p in r) if (p in 1..src.numberOfPages) dst.importPage(src.getPage(p - 1))
                val o = com.blacklab.app.core.Files.unique(outDir, f.nameWithoutExtension + if (r.first == r.last) " p${r.first}" else " p${r.first}-${r.last}", "pdf")
                dst.save(o); o
            }
        }
    }

    /** "1-3, 5, 8-10" → ranges; "all" or blank → every page as its own file. */
    fun parseRanges(spec: String, pageCount: Int): List<IntRange> {
        if (spec.isBlank() || spec.trim().equals("all", true)) return (1..pageCount).map { it..it }
        return spec.split(',', ';').mapNotNull { part ->
            val t = part.trim()
            when {
                t.isEmpty() -> null
                '-' in t -> { val (a, b) = t.split('-', limit = 2).map { it.trim().toIntOrNull() }; if (a != null) a.coerceIn(1, pageCount)..(b ?: pageCount).coerceIn(1, pageCount) else null }
                else -> t.toIntOrNull()?.coerceIn(1, pageCount)?.let { it..it }
            }
        }
    }

    // ------------------------------------------------------------------ drawing on pages

    /** Matrix that lets us draw in "displayed page" space (points, origin bottom-left, y up) whatever the page rotation. */
    private fun displayMatrix(page: PDPage): Triple<Matrix, Float, Float> {
        val mb = page.cropBox
        val w = mb.width; val h = mb.height; val x0 = mb.lowerLeftX; val y0 = mb.lowerLeftY
        return when ((page.rotation % 360 + 360) % 360) {
            90 -> Triple(Matrix(0f, 1f, -1f, 0f, x0 + w, y0), h, w)
            180 -> Triple(Matrix(-1f, 0f, 0f, -1f, x0 + w, y0 + h), w, h)
            270 -> Triple(Matrix(0f, -1f, 1f, 0f, x0, y0 + h), h, w)
            else -> Triple(Matrix(1f, 0f, 0f, 1f, x0, y0), w, h)
        }
    }

    private fun rgb(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    private fun alphaState(doc: PDDocument, a: Float) = PDExtendedGraphicsState().apply { strokingAlphaConstant = a; nonStrokingAlphaConstant = a }

    fun applyStamps(f: File, out: File, stamps: List<Stamp>) = load(f).use { doc ->
        stamps.groupBy { it.page }.forEach { (pi, list) ->
            if (pi !in 0 until doc.numberOfPages) return@forEach
            val page = doc.getPage(pi)
            val (m, dw, dh) = displayMatrix(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState(); cs.transform(m)
                list.forEach { s ->
                    when (s) {
                        is Stamp.Ink -> if (s.points.isNotEmpty()) {
                            cs.saveGraphicsState()
                            if (s.alpha < 1f) cs.setGraphicsStateParameters(alphaState(doc, s.alpha))
                            val (r, g, b) = rgb(s.color).let { Triple(it[0], it[1], it[2]) }
                            cs.setStrokingColor(r, g, b); cs.setLineWidth(s.width * dw); cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                            cs.moveTo(s.points[0].x * dw, (1 - s.points[0].y) * dh)
                            if (s.points.size == 1) cs.lineTo(s.points[0].x * dw + 0.1f, (1 - s.points[0].y) * dh)
                            s.points.drop(1).forEach { p -> cs.lineTo(p.x * dw, (1 - p.y) * dh) }
                            cs.stroke(); cs.restoreGraphicsState()
                        }
                        is Stamp.Text -> {
                            val font = if (s.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
                            val size = s.size * dh
                            val (r, g, b) = rgb(s.color).let { Triple(it[0], it[1], it[2]) }
                            cs.beginText(); cs.setFont(font, size); cs.setNonStrokingColor(r, g, b)
                            var y = (1 - s.y) * dh - size
                            s.text.lines().forEach { line -> cs.newLineAtOffset(0f, 0f); cs.setTextMatrix(Matrix.getTranslateInstance(s.x * dw, y)); cs.showText(safe(line)); y -= size * 1.25f }
                            cs.endText()
                        }
                        is Stamp.Image -> {
                            val img = LosslessFactory.createFromImage(doc, s.bitmap)
                            cs.drawImage(img, s.rect.left * dw, (1 - s.rect.bottom) * dh, s.rect.width() * dw, s.rect.height() * dh)
                        }
                        is Stamp.Box -> {
                            cs.saveGraphicsState()
                            cs.setGraphicsStateParameters(alphaState(doc, s.alpha))
                            val (r, g, b) = rgb(s.color).let { Triple(it[0], it[1], it[2]) }
                            cs.addRect(s.rect.left * dw, (1 - s.rect.bottom) * dh, s.rect.width() * dw, s.rect.height() * dh)
                            if (s.filled) { cs.setNonStrokingColor(r, g, b); cs.fill() } else { cs.setStrokingColor(r, g, b); cs.setLineWidth(0.004f * dw); cs.stroke() }
                            cs.restoreGraphicsState()
                        }
                    }
                }
                cs.restoreGraphicsState()
            }
        }
        save(doc, out)
    }

    fun watermark(f: File, out: File, text: String, color: Int, opacity: Float, sizeFrac: Float = 0.08f) = load(f).use { doc ->
        val font = PDType1Font.HELVETICA_BOLD
        val t = safe(text)
        doc.pages.forEach { page ->
            val (m, dw, dh) = displayMatrix(page)
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState(); cs.transform(m)
                cs.setGraphicsStateParameters(alphaState(doc, opacity))
                val size = min(dw, dh) * sizeFrac
                val width = font.getStringWidth(t) / 1000f * size
                val (r, g, b) = rgb(color).let { Triple(it[0], it[1], it[2]) }
                cs.beginText(); cs.setFont(font, size); cs.setNonStrokingColor(r, g, b)
                val angle = Math.atan2(dh.toDouble(), dw.toDouble())
                val tm = Matrix.getRotateInstance(angle, dw / 2, dh / 2)
                tm.translate(-width / 2, -size / 3)
                cs.setTextMatrix(tm); cs.showText(t); cs.endText()
                cs.restoreGraphicsState()
            }
        }
        save(doc, out)
    }

    /** [format] uses {n} and {total}; [position] is one of BC, BR, BL, TC, TR, TL. */
    fun pageNumbers(f: File, out: File, format: String = "Page {n} of {total}", position: String = "BC", startAt: Int = 1) = load(f).use { doc ->
        val font = PDType1Font.HELVETICA
        val total = doc.numberOfPages
        doc.pages.forEachIndexed { i, page ->
            val (m, dw, dh) = displayMatrix(page)
            val label = safe(format.replace("{n}", (i + startAt).toString()).replace("{total}", (total + startAt - 1).toString()))
            val size = 10f
            val w = font.getStringWidth(label) / 1000f * size
            val margin = 28f
            val x = when (position.last()) { 'L' -> margin; 'R' -> dw - margin - w; else -> (dw - w) / 2 }
            val y = if (position.first() == 'T') dh - margin else margin - size / 2
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                cs.saveGraphicsState(); cs.transform(m)
                cs.beginText(); cs.setFont(font, size); cs.setNonStrokingColor(0.25f, 0.25f, 0.25f)
                cs.setTextMatrix(Matrix.getTranslateInstance(x, y)); cs.showText(label); cs.endText()
                cs.restoreGraphicsState()
            }
        }
        save(doc, out)
    }

    // ------------------------------------------------------------------ security

    fun protect(f: File, out: File, userPassword: String, ownerPassword: String = userPassword, allowPrint: Boolean = true, allowCopy: Boolean = false) = load(f).use { doc ->
        val perms = AccessPermission().apply { setCanPrint(allowPrint); setCanExtractContent(allowCopy); setCanModify(false) }
        doc.protect(StandardProtectionPolicy(ownerPassword, userPassword, perms).apply { encryptionKeyLength = 256 })
        save(doc, out)
    }

    fun unlock(f: File, out: File, password: String) = load(f, password).use { doc ->
        doc.isAllSecurityToBeRemoved = true
        save(doc, out)
    }

    // ------------------------------------------------------------------ forms

    fun formFields(f: File): List<FormField> = load(f).use { doc ->
        val form = doc.documentCatalog.acroForm ?: return emptyList()
        form.fieldTree.filterIsInstance<PDTerminalField>().map { fld ->
            when (fld) {
                is PDCheckBox -> FormField(fld.fullyQualifiedName, "check", if (fld.isChecked) "Yes" else "Off")
                is PDRadioButton -> FormField(fld.fullyQualifiedName, "radio", fld.value.orEmpty(), fld.exportValues.orEmpty().toList())
                is PDChoice -> FormField(fld.fullyQualifiedName, "choice", fld.value.firstOrNull().orEmpty(), fld.options.orEmpty().toList())
                is PDTextField -> FormField(fld.fullyQualifiedName, "text", fld.value.orEmpty())
                else -> FormField(fld.fullyQualifiedName, "other", fld.valueAsString.orEmpty())
            }
        }.filter { it.type != "other" }
    }

    fun fillForm(f: File, out: File, values: Map<String, String>, flatten: Boolean) = load(f).use { doc ->
        val form = doc.documentCatalog.acroForm ?: error("This PDF has no form")
        form.needAppearances = true
        values.forEach { (name, v) ->
            when (val fld = form.getField(name)) {
                is PDCheckBox -> if (v == "Yes" || v == "true") fld.check() else fld.unCheck()
                is PDRadioButton -> if (v.isNotBlank()) runCatching { fld.value = v }
                is PDChoice -> if (v.isNotBlank()) runCatching { fld.setValue(v) }
                is PDTextField -> runCatching { fld.value = v }.onFailure { form.needAppearances = true }
                else -> {}
            }
        }
        if (flatten) runCatching { form.flatten() }
        save(doc, out)
    }

    // ------------------------------------------------------------------ conversions

    private fun decodeScaled(f: File, maxDim: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = runCatching { androidx.exifinterface.media.ExifInterface(f).rotationDegrees }.getOrDefault(0)
        return if (exif == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(exif.toFloat()) }, true)
    }

    /** [pageSize]: FIT (page = picture), LETTER or A4 (picture centered with margins). */
    fun imagesToPdf(images: List<File>, out: File, pageSize: String = "LETTER", quality: Float = 0.85f) {
        PDDocument().use { doc ->
            images.forEach { f ->
                val bmp = decodeScaled(f, 2400) ?: return@forEach
                val img = if (bmp.hasAlpha() && f.extension.equals("png", true)) LosslessFactory.createFromImage(doc, bmp) else JPEGFactory.createFromImage(doc, bmp, quality)
                val box = when (pageSize) {
                    "A4" -> PDRectangle.A4
                    "LETTER" -> PDRectangle.LETTER
                    else -> PDRectangle(bmp.width * 72f / 150f, bmp.height * 72f / 150f)
                }.let { if (pageSize != "FIT" && bmp.width > bmp.height) PDRectangle(it.height, it.width) else it }
                val page = PDPage(box); doc.addPage(page)
                val margin = if (pageSize == "FIT") 0f else 24f
                val scale = min((box.width - 2 * margin) / bmp.width, (box.height - 2 * margin) / bmp.height)
                val w = bmp.width * scale; val h = bmp.height * scale
                PDPageContentStream(doc, page).use { it.drawImage(img, (box.width - w) / 2, (box.height - h) / 2, w, h) }
            }
            require(doc.numberOfPages > 0) { "No pictures could be read" }
            save(doc, out)
        }
    }

    fun renderPage(f: File, index: Int, widthPx: Int): Bitmap? = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                r.openPage(index).use { p ->
                    val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888).also { b ->
                        b.eraseColor(Color.WHITE); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()

    /** Every page to a JPEG (or PNG) at [dpi]; returns the files written. */
    fun toImages(f: File, outDir: File, dpi: Int = 150, png: Boolean = false, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<File> {
        val out = mutableListOf<File>()
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                for (i in 0 until r.pageCount) {
                    onProgress(i + 1, r.pageCount)
                    r.openPage(i).use { p ->
                        val w = (p.width * dpi / 72f).toInt().coerceIn(1, 6000); val h = (p.height * dpi / 72f).toInt().coerceIn(1, 6000)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val o = com.blacklab.app.core.Files.unique(outDir, "${f.nameWithoutExtension} p${i + 1}", if (png) "png" else "jpg")
                        o.outputStream().use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
                        b.recycle(); out += o
                    }
                }
            }
        }
        return out
    }

    /** Re-renders every page as a JPEG — much smaller for scans and photos (text becomes an image). */
    fun compress(f: File, out: File, dpi: Int = 110, quality: Float = 0.6f) {
        PDDocument().use { doc ->
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { r ->
                    for (i in 0 until r.pageCount) r.openPage(i).use { p ->
                        val w = (p.width * dpi / 72f).toInt().coerceAtLeast(1); val h = (p.height * dpi / 72f).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val page = PDPage(PDRectangle(p.width.toFloat(), p.height.toFloat())); doc.addPage(page)
                        val img = JPEGFactory.createFromImage(doc, b, quality)
                        PDPageContentStream(doc, page).use { it.drawImage(img, 0f, 0f, p.width.toFloat(), p.height.toFloat()) }
                        b.recycle()
                    }
                }
            }
            save(doc, out)
        }
    }

    /** A blank PDF with [pages] Letter pages — a starting point for notes, sign-in sheets, forms. */
    fun blank(out: File, pages: Int = 1, size: String = "LETTER") = PDDocument().use { d ->
        repeat(pages) { d.addPage(PDPage(if (size == "A4") PDRectangle.A4 else PDRectangle.LETTER)) }
        save(d, out)
    }
}
