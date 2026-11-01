package com.blacklab.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blacklab.app.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The update checks run against a real APK: the one installed on this device. */
@RunWith(AndroidJUnit4::class)
class UpdaterDeviceTest {

    @Test fun installedApkPassesSignatureAndPackageButIsNotNewer() {
        val apk = File(DeviceTest.context.applicationInfo.sourceDir)
        val sha = Updater.hex(Updater.sha256(apk))
        val c = Updater.verify(DeviceTest.context, apk, sha)
        assertTrue(c.shaMatches)
        assertTrue("same signing key as the installed app", c.signerMatches)
        assertTrue(c.packageMatches)
        assertFalse("same build is not an update", c.newer)
        assertFalse(c.ok)
        assertEquals(Updater.installedSigner(DeviceTest.context), c.signer)
    }

    @Test fun aWrongChecksumIsRejected() {
        val apk = File(DeviceTest.context.applicationInfo.sourceDir)
        val c = Updater.verify(DeviceTest.context, apk, "0".repeat(64))
        assertFalse(c.shaMatches)
        assertFalse(c.ok)
    }

    @Test fun aFileThatIsNotAnApkFailsEveryCheck() {
        val junk = File(DeviceTest.context.cacheDir, "junk.apk").apply { writeText("not an apk") }
        try {
            val c = Updater.verify(DeviceTest.context, junk, Updater.hex(Updater.sha256(junk)))
            assertTrue(c.shaMatches) // the checksum alone proves nothing…
            assertFalse(c.signerMatches) // …the signature check is what blocks it
            assertFalse(c.packageMatches)
            assertFalse(c.ok)
        } finally { junk.delete() }
    }
}
