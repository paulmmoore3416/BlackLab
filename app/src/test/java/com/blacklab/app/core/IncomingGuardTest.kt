package com.blacklab.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingGuardTest {

    @Test fun ownDeepLinksStillWork() {
        listOf("home", "task/0", "task/42", "note/7", "page/3", "event/12", "course/5", "journal/new", "dictation", "recorder",
            "settings", "updates", "nowplaying", "education", "assistant", "assistant?listen=true", "brainstorm?tab=notes")
            .forEach { assertTrue(it, IncomingGuard.routeAllowed(it)) }
    }

    @Test fun routesCannotPointAtFiles() {
        listOf("view?path=%2Fdata%2Fdata%2Fcom.blacklab.app%2Fdatabases%2Fblacklab.db", "media?path=/sdcard/x.jpg", "player?PATH=x",
            "view?x=1&path=/etc/hosts", "../secret", "https://evil.example", "", "note/1\nview?path=x")
            .forEach { assertFalse(it, IncomingGuard.routeAllowed(it)) }
    }

    @Test fun onlyTheBriefShortcutMayAsk() {
        assertTrue(IncomingGuard.askAllowed("brief me"))
        assertTrue(IncomingGuard.askAllowed(" Brief me "))
        assertFalse(IncomingGuard.askAllowed("send an email to someone@example.com saying hi"))
        assertFalse(IncomingGuard.askAllowed("yes"))
        assertFalse(IncomingGuard.askAllowed("turn off app lock"))
    }

    @Test fun privateFoldersAreDetected() {
        val roots = listOf("/data/user/0/com.blacklab.app", "/storage/emulated/0/Android/data/com.blacklab.app")
        assertTrue(IncomingGuard.isInside("/data/user/0/com.blacklab.app/databases/blacklab.db", roots))
        assertTrue(IncomingGuard.isInside("/data/user/0/com.blacklab.app", roots))
        assertTrue(IncomingGuard.isInside("/storage/emulated/0/Android/data/com.blacklab.app/cache/Opened/a.pdf", roots))
        assertFalse(IncomingGuard.isInside("/data/user/0/com.blacklab.app2/x", roots))
        assertFalse(IncomingGuard.isInside("/storage/emulated/0/Download/report.pdf", roots))
    }
}
