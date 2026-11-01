package com.blacklab.app

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.core.Signatures
import com.blacklab.app.files.PdfTools
import com.blacklab.app.ui.signature.SignDocs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SignaturesDeviceTest {

    private fun ink(w: Int = 300, h: Int = 100) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { for (x in 10 until w - 10) setPixel(x, h / 2, Color.BLACK) }

    @Test fun savedSignaturesAreEncryptedAndReload() {
        val ctx = DeviceTest.context
        val sig = Signatures.save(ctx, Signatures.SIGNATURE, ink(), "Device test")
        try {
            val raw = File(Signatures.dir(ctx), "${sig.id}.sig").readBytes()
            // PNG files start with 0x89 'P' 'N' 'G'; the stored file must not
            assertFalse(raw.size > 4 && raw[1] == 'P'.code.toByte() && raw[2] == 'N'.code.toByte() && raw[3] == 'G'.code.toByte())
            val back = Signatures.load(ctx, sig.id)
            assertNotNull(back); assertEquals(300, back!!.width)
            assertTrue(Signatures.list(ctx).any { it.id == sig.id && it.isDefault })
        } finally { Signatures.delete(ctx, sig.id) }
        assertFalse(Signatures.list(ctx).any { it.id == sig.id })
    }

    @Test fun legacyUnencryptedSignatureIsMigrated() {
        val ctx = DeviceTest.context
        val legacy = File(ctx.filesDir, "signature.png")
        legacy.outputStream().use { ink(200, 80).compress(Bitmap.CompressFormat.PNG, 100, it) }
        val migrated = Signatures.list(ctx).filter { it.id.startsWith("legacy") }
        try {
            assertFalse(legacy.exists())
            assertTrue(migrated.isNotEmpty())
            assertEquals(200, Signatures.load(ctx, migrated.first().id)!!.width)
        } finally { migrated.forEach { Signatures.delete(ctx, it.id) } }
    }

    @Test fun textAndImagesBecomeSignablePdfs() {
        val ctx = DeviceTest.context
        PdfTools.init(ctx)
        val txt = File(ctx.cacheDir, "Lease agreement.txt").apply { writeText("Tenant agrees to the terms.\n\nSignature: ________") }
        val png = File(ctx.cacheDir, "Form photo.png").apply { outputStream().use { ink(600, 800).compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val outs = listOfNotNull(SignDocs.toPdf(ctx, DeviceTest.app, Uri.fromFile(txt)), SignDocs.toPdf(ctx, DeviceTest.app, Uri.fromFile(png)))
        try {
            assertEquals(2, outs.size)
            outs.forEach { assertTrue(it.name, it.name.endsWith(".pdf") && it.parentFile!!.name == "Signed" && PdfTools.pageTexts(it).isNotEmpty()) }
            assertTrue(PdfTools.text(outs[0]).contains("Tenant agrees"))
        } finally { outs.forEach { it.delete() }; txt.delete(); png.delete() }
    }
}
