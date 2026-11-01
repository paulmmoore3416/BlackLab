package com.blacklab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.core.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrashLogDeviceTest {
    @Test fun recordsListsSharesAndClears() {
        val ctx = DeviceTest.context
        val before = CrashLog.entries(ctx).map { it.file.name }.toSet()
        val f = CrashLog.write(ctx, System.currentTimeMillis(), "Crash", "IllegalStateException: device test",
            CrashLog.stackTrace(IllegalStateException("device test")), "main")
        try {
            val e = CrashLog.entries(ctx).first { it.file.name !in before }
            assertEquals("Crash", e.kind)
            assertTrue(e.text().contains("java.lang.IllegalStateException: device test"))
            CrashLog.refreshUnseen(ctx)
            assertTrue(CrashLog.unseen.value >= 1)
            assertTrue(CrashLog.exportAll(ctx).readText().contains("device test"))
            CrashLog.markSeen(ctx)
            assertEquals(0, CrashLog.unseen.value)
        } finally { f.delete() }
    }
}
