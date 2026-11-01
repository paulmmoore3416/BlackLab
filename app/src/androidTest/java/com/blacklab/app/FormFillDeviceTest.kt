package com.blacklab.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.core.FormAutofill
import com.blacklab.app.files.PdfTools
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FormFillDeviceTest {

    private fun makeForm(out: File) = PDDocument().use { doc ->
        val page = PDPage(PDRectangle.LETTER); doc.addPage(page)
        val form = PDAcroForm(doc); doc.documentCatalog.acroForm = form
        val res = PDResources(); res.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA); form.defaultResources = res
        form.defaultAppearance = "/Helv 10 Tf 0 g"
        fun text(name: String, y: Float) = PDTextField(form).apply {
            partialName = name
            widgets[0].rectangle = PDRectangle(72f, y, 300f, 20f); widgets[0].page = page
            page.annotations.add(widgets[0]); form.fields.add(this)
        }
        text("Employee Name", 700f); text("txtEmail", 660f); text("Date of Signature", 620f); text("Date of Birth", 580f)
        PDSignatureField(form).apply {
            partialName = "Employee Signature"
            widgets[0].rectangle = PDRectangle(72f, 520f, 240f, 50f); widgets[0].page = page
            page.annotations.add(widgets[0]); form.fields.add(this)
        }
        doc.save(out)
    }

    @Test fun autoFillsDetailsAndSignsTheSignatureBox() {
        val ctx = DeviceTest.context
        PdfTools.init(ctx)
        val dir = File(ctx.cacheDir, DeviceTest.tag()).also { it.mkdirs() }
        try {
            val src = File(dir, "form.pdf").also { makeForm(it) }
            val fields = PdfTools.formFields(src)
            assertEquals(5, fields.size)
            assertTrue(fields.any { it.name == "Employee Signature" && it.type == "signature" })

            val profile = FormAutofill.Profile(fullName = "Alex Morgan", email = "alex@example.com")
            val values = fields.filter { it.type == "text" }.associate { f ->
                f.name to (FormAutofill.classify(f.name, f.alt)?.let { FormAutofill.valueFor(it, profile) } ?: "")
            }
            assertEquals("Alex Morgan", values["Employee Name"]); assertEquals("alex@example.com", values["txtEmail"])
            assertTrue(values["Date of Signature"]!!.matches(Regex("\\d\\d/\\d\\d/\\d{4}"))); assertEquals("", values["Date of Birth"])

            val sig = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888).apply { for (x in 10 until 290) setPixel(x, 60, Color.BLACK) }
            val out = File(dir, "filled.pdf")
            PdfTools.fillForm(src, out, values, flatten = true, images = mapOf("Employee Signature" to sig))
            val text = PdfTools.text(out)
            assertTrue(text, text.contains("Alex Morgan") && text.contains("alex@example.com"))
            PDDocument.load(out).use { d ->
                val xobjects = d.getPage(0).resources.xObjectNames.toList()
                assertTrue("signature image drawn", xobjects.isNotEmpty())
                assertTrue("flattened", d.documentCatalog.acroForm?.fields.isNullOrEmpty())
            }
        } finally { dir.deleteRecursively() }
    }
}
