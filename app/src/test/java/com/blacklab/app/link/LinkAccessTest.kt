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

    private fun ip(s: String) = java.net.InetAddress.getByName(s) // literals only: no DNS

    @Test fun usbIsAlwaysAdmitted() {
        assertTrue(LinkAccess.admit(ip("127.0.0.1"), ip("127.0.0.1"), lan = false, tailnet = false))
    }

    @Test fun wifiPeersOnlyWhenWifiIsAllowed() {
        assertFalse(LinkAccess.admit(ip("192.168.1.20"), ip("192.168.1.5"), lan = false, tailnet = true))
        assertTrue(LinkAccess.admit(ip("192.168.1.20"), ip("192.168.1.5"), lan = true, tailnet = false))
    }

    /** Both ends must be tailnet addresses: a LAN device spoofing a 100.x source never completes the handshake, and Wi-Fi traffic stays out. */
    @Test fun tailnetPeersOnlyOverTheTailnet() {
        assertTrue(LinkAccess.admit(ip("100.101.102.103"), ip("100.90.1.2"), lan = false, tailnet = true))
        assertTrue(LinkAccess.admit(ip("fd7a:115c:a1e0::1"), ip("fd7a:115c:a1e0::2"), lan = false, tailnet = true))
        assertFalse(LinkAccess.admit(ip("100.101.102.103"), ip("100.90.1.2"), lan = false, tailnet = false))
        assertFalse(LinkAccess.admit(ip("100.101.102.103"), ip("192.168.1.5"), lan = false, tailnet = true))
        assertFalse(LinkAccess.admit(ip("100.128.0.1"), ip("100.90.1.2"), lan = false, tailnet = true)) // outside 100.64/10
    }

    @Test fun tailnetSwitchIsUsbOnly() {
        assertTrue(Keys.LINK_TAILNET in LinkAccess.usbOnlySettings)
    }
}
