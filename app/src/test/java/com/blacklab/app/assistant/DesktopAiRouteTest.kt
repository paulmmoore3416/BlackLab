package com.blacklab.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the phone looks for the desktop AI: the USB link first, then the computer's Tailscale address. */
class DesktopAiRouteTest {
    @Test fun usbFirstThenTailnet() {
        assertEquals(listOf("http://127.0.0.1:8770", "http://100.101.102.103:8770"), DesktopAi.candidates(null, "http://100.101.102.103:8770/"))
        assertEquals(listOf("http://127.0.0.1:8770"), DesktopAi.candidates(null, null))
        assertEquals(listOf("http://127.0.0.1:9000"), DesktopAi.candidates("http://127.0.0.1:9000", ""))
    }

    /** The shared key goes to whatever URL is used, so a non-tailnet "tailnet" URL is ignored. */
    @Test fun onlyRealTailnetHostsAreTried() {
        assertEquals(listOf("http://127.0.0.1:8770", "http://desktop.example-tailnet.ts.net:8770"), DesktopAi.candidates(null, "http://desktop.example-tailnet.ts.net:8770"))
        listOf("http://192.168.1.4:8770", "http://evil.example:8770", "http://100.128.0.1:8770", "https://ts.net.evil.example", "ftp://100.101.102.103").forEach {
            assertEquals(it, listOf("http://127.0.0.1:8770"), DesktopAi.candidates(null, it))
        }
    }

    @Test fun duplicatesCollapse() {
        assertEquals(listOf("http://100.101.102.103:8770"), DesktopAi.candidates("http://100.101.102.103:8770", "http://100.101.102.103:8770"))
    }
}
