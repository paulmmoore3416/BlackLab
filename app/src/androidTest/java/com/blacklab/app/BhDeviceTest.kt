package com.blacklab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.bh.Bh
import com.blacklab.app.bh.BhStore
import com.blacklab.app.bh.Forms
import com.blacklab.app.bh.Specs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BhDeviceTest {

    @Test fun workspaceIsEncryptedOnDiskAndReloads() {
        val ctx = DeviceTest.context
        val before = BhStore.load(ctx)
        try {
            val marker = "DEVICETEST-" + System.nanoTime()
            BhStore.update(ctx) { it.copy(patients = it.patients + Bh.Patient(id = "dt", label = "ZQ", room = marker),
                records = it.records + Forms.Rec(id = "dtr", kind = Specs.PEER_NOTE, values = mapOf("summary" to marker))) }
            val raw = File(BhStore.dir(ctx), "workspace.enc").readBytes()
            val asText = String(raw, Charsets.ISO_8859_1)
            assertFalse("plaintext marker must not be on disk", asText.contains(marker))
            assertFalse("not JSON on disk", asText.contains("\"patients\""))

            val back = BhStore.reload(ctx)
            assertEquals(marker, back.patients.first { it.id == "dt" }.room)
            assertTrue(back.records.any { it.id == "dtr" && it.v("summary") == marker })
        } finally {
            BhStore.update(ctx) { before }
        }
        assertFalse(BhStore.reload(ctx).patients.any { it.id == "dt" })
    }

    @Test fun workspaceIsExcludedFromBackups() {
        val ctx = DeviceTest.context
        val rules = listOf(com.blacklab.app.R.xml.backup_rules, com.blacklab.app.R.xml.data_extraction_rules).joinToString("\n") { id ->
            ctx.resources.getXml(id).let { p ->
                buildString {
                    while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) if (p.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "exclude") append(p.getAttributeValue(null, "path")).append('\n')
                }
            }
        }
        assertEquals(3, rules.lines().count { it == "bh/" })
    }
}
