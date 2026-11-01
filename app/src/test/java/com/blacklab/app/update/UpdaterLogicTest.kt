package com.blacklab.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterLogicTest {

    @Test fun versionsCompareNumerically() {
        assertTrue(Updater.compareVersions("4.3.0", "4.2.0") > 0)
        assertTrue(Updater.compareVersions("v4.10.0", "4.9.9") > 0) // not a string compare
        assertTrue(Updater.compareVersions("4.3", "4.3.1") < 0)
        assertEquals(0, Updater.compareVersions("v4.3.0", "4.3.0-debug"))
        assertEquals(0, Updater.compareVersions("4.3.0", "4.3"))
        assertEquals(listOf(5, 0, 1), Updater.versionParts("V5.0.1+build7"))
    }

    private fun release(tag: String, vararg assets: Pair<String, String>, draft: Boolean = false) = """
        {"tag_name":"$tag","name":"BlackLab $tag","draft":$draft,"prerelease":false,"body":"Notes here","published_at":"2026-11-02T10:00:00Z",
         "html_url":"https://github.com/paulmmoore3416/BlackLab/releases/tag/$tag",
         "assets":[${assets.joinToString(",") { (n, u) -> """{"name":"$n","size":19000000,"browser_download_url":"$u"}""" }}]}
    """.trimIndent()

    @Test fun parsesTheReleaseApkAndChecksum() {
        val r = Updater.parseRelease(release("v4.3.0",
            "BlackLab-v4.3.0.apk" to "https://github.com/paulmmoore3416/BlackLab/releases/download/v4.3.0/BlackLab-v4.3.0.apk",
            "BlackLab-v4.3.0.apk.sha256" to "https://github.com/paulmmoore3416/BlackLab/releases/download/v4.3.0/BlackLab-v4.3.0.apk.sha256",
            "notes.txt" to "https://example.com/x"))
        assertNotNull(r); r!!
        assertEquals("4.3.0", r.version)
        assertTrue(r.apkUrl.endsWith("BlackLab-v4.3.0.apk"))
        assertTrue(r.shaUrl!!.endsWith(".apk.sha256"))
        assertEquals(19000000L, r.apkSize)
        assertEquals("Notes here", r.notes)
    }

    @Test fun ignoresDraftsAndReleasesWithoutAnApk() {
        assertNull(Updater.parseRelease(release("v4.3.0", "BlackLab-v4.3.0.apk" to "https://github.com/a", draft = true)))
        assertNull(Updater.parseRelease(release("v4.3.0", "source.zip" to "https://github.com/a")))
        assertNull(Updater.parseRelease(release("v4.3.0", "Evil.apk" to "https://github.com/a")))
        assertNull(Updater.parseRelease("not json"))
        // A release without a checksum still parses; download() then refuses it
        assertNull(Updater.parseRelease(release("v4.3.0", "BlackLab-v4.3.0.apk" to "https://github.com/a"))!!.shaUrl)
    }

    @Test fun readsSha256sumOutput() {
        val h = "e17715f390c63ee196a0f110dcc544441d351ee4848f0ff1ecc9dcdde3e1b3db"
        assertEquals(h, Updater.parseSha256("$h  BlackLab-v4.2.0.apk\n"))
        assertEquals(h, Updater.parseSha256(h.uppercase()))
        assertNull(Updater.parseSha256("not-a-hash BlackLab.apk"))
        assertNull(Updater.parseSha256(""))
        assertNull(Updater.parseSha256(h.dropLast(1)))
    }

    @Test fun onlyDownloadsFromGitHubOverHttps() {
        assertTrue(Updater.isAllowedUrl("https://github.com/paulmmoore3416/BlackLab/releases/download/v4.3.0/BlackLab-v4.3.0.apk"))
        assertTrue(Updater.isAllowedUrl("https://api.github.com/repos/paulmmoore3416/BlackLab/releases/latest"))
        assertTrue(Updater.isAllowedUrl("https://objects.githubusercontent.com/github-production-release-asset/1"))
        assertTrue(Updater.isAllowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
        assertFalse(Updater.isAllowedUrl("http://github.com/x.apk"))
        assertFalse(Updater.isAllowedUrl("https://github.com.evil.example/x.apk"))
        assertFalse(Updater.isAllowedUrl("https://evilgithubusercontent.com/x.apk"))
        assertFalse(Updater.isAllowedUrl("file:///sdcard/x.apk"))
        assertFalse(Updater.isAllowedUrl("garbage"))
    }

    @Test fun fingerprintsFormatForDisplay() {
        assertEquals("0a0b", Updater.hex(byteArrayOf(10, 11)))
        assertEquals("4F:F8:A3:AB…", Updater.prettyFingerprint("4ff8a3abc5ac", groups = 4))
    }
}
