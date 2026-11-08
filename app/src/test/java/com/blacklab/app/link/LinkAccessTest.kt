package com.blacklab.app.link

import com.blacklab.app.core.Keys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAccessTest {
    @Test fun onlyBlackLabDesktopOriginsAreAllowed() {
        listOf("https://paulmmoore3416.github.io", "http://127.0.0.1:8770", "http://localhost:8770", "http://127.0.0.1").forEach {
            assertTrue(it, LinkAccess.originAllowed(it))
        }
        listOf("https://evil.example", "http://127.0.0.1.evil.example", "https://paulmmoore3416.github.io.evil.example",
            "http://localhost:8770@evil.example", "null", "http://192.168.1.20:8770", "https://127.0.0.1:8770").forEach {
            assertFalse(it, LinkAccess.originAllowed(it))
        }
    }

    @Test fun sensitiveCallsAreUsbOnly() {
        assertTrue(LinkAccess.usbOnly("GET", "/api/file"))
        assertTrue(LinkAccess.usbOnly("GET", "/api/files"))
        assertTrue(LinkAccess.usbOnly("POST", "/api/secrets"))
        assertTrue(LinkAccess.usbOnly("POST", "/api/backup"))
        assertFalse(LinkAccess.usbOnly("GET", "/api/snapshot"))
        assertFalse(LinkAccess.usbOnly("POST", "/api/tasks"))
    }

    @Test fun protectionsCantBeTurnedOffOverWifi() {
        assertTrue(Keys.APP_LOCK in LinkAccess.usbOnlySettings)
        assertTrue(Keys.LINK_LAN in LinkAccess.usbOnlySettings)
        assertTrue(Keys.CLOUD_SYNC in LinkAccess.usbOnlySettings)
    }
}
