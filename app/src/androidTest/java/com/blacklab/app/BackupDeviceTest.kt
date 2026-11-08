package com.blacklab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    private val app get() = DeviceTest.app

    @Test fun backupIsEncryptedAndVerifies() = runBlocking {
        val title = DeviceTest.tag()
        val id = TaskOps.save(app, TaskEntity(profile = app.settings.current.activeProfile.name, title = title))
        val garage = File(DeviceTest.context.filesDir, "garage/devicetest.json").apply { parentFile?.mkdirs(); writeText("{\"t\":1}") }
        val f = app.backups.backupNow()
        try {
            assertTrue(f.name.endsWith(".blbackup"))
            val raw = f.readBytes()
            assertFalse("task title must not appear in the file", String(raw, Charsets.ISO_8859_1).contains(title))
            val i = app.backups.inspect(f)
            assertTrue(i.encrypted && i.fromThisInstall)
            assertTrue(i.counts.first { it.first == "tasks" }.second >= 1)
            assertTrue(i.files.contains("garage/devicetest.json"))
            app.backups.discard()

            val bad = File(f.parentFile, ".devicetest-bad.blbackup").apply { writeBytes(raw.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }) }
            try { app.backups.inspect(bad); fail("tampered backup accepted") } catch (_: Exception) { } finally { bad.delete() }
        } finally {
            f.delete(); garage.delete()
            app.db.tasks().get(id)?.let { app.db.tasks().delete(it) }
        }
    }
}
