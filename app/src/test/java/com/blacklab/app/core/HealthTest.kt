package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L
    private fun inputs() = Health.Inputs(
        now = now, newestBackupAt = now - day, backupCount = 5, plainBackups = 0, autoBackup = true, backupPassphraseSet = true,
        storeProblems = emptyMap(), storeReadOnly = emptyList(), dbVersion = 2, desktopEnabled = true, desktopState = "ONLINE", desktopLatencyMs = 12,
        vaultEnabled = true, vaultLastSyncAt = now - 3_600_000L, crashes7d = 0, lastCrashTitle = null, freeBytes = 200_000_000_000L,
        nanoStatus = "AVAILABLE", indexPassages = 900,
    )

    @Test fun healthyPhoneIsAllGood() {
        val c = Health.evaluate(inputs())
        assertTrue(c.all { it.level == Health.Level.OK })
        assertEquals("All good", Health.summary(c))
    }

    @Test fun problemsComeFirstWithAFix() {
        val c = Health.evaluate(inputs().copy(newestBackupAt = null, storeProblems = mapOf("Facility" to "facility.json was damaged…"), crashes7d = 4, freeBytes = 1_000_000_000L))
        assertEquals(Health.Level.BAD, c.first().level)
        assertTrue(c.first { it.id == "backup" }.fixRoute != null)
        assertTrue(Health.summary(c).startsWith("4 problems"))
    }

    @Test fun staleThingsWarn() {
        val c = Health.evaluate(inputs().copy(newestBackupAt = now - 10 * day, vaultLastSyncAt = now - 3 * day, desktopState = "OFFLINE", plainBackups = 2))
        listOf("backup", "vault", "desktop", "backup_plain").forEach { id -> assertEquals(id, Health.Level.WARN, c.first { it.id == id }.level) }
        assertEquals("10 days ago", Health.ago(now - 10 * day, now))
    }

    @Test fun cloudSyncWithoutGoogleSignInWarns() {
        assertTrue(Health.evaluate(inputs()).none { it.id == "cloud" }) // off: no row
        val c = Health.evaluate(inputs().copy(cloudSyncEnabled = true, googleSignedIn = false)).first { it.id == "cloud" }
        assertEquals(Health.Level.WARN, c.level)
        assertTrue(c.detail.contains("signed in to Google"))
        assertTrue(c.fixRoute != null)
        assertEquals(Health.Level.WARN, Health.evaluate(inputs().copy(cloudSyncEnabled = true, googleSignedIn = true, cloudError = "Drive 403")).first { it.id == "cloud" }.level)
        assertEquals(Health.Level.WARN, Health.evaluate(inputs().copy(cloudSyncEnabled = true, googleSignedIn = true, cloudLastSyncAt = now - 2 * day)).first { it.id == "cloud" }.level)
        assertEquals(Health.Level.OK, Health.evaluate(inputs().copy(cloudSyncEnabled = true, googleSignedIn = true, cloudLastSyncAt = now - 600_000L)).first { it.id == "cloud" }.level)
    }
}
