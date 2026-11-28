package com.blacklab.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHostsTest {
    @Test fun parsesAddresses() {
        val h = SshHosts.parse("paul@homebox").getOrThrow()
        assertEquals("paul", h.user); assertEquals("homebox", h.host); assertEquals(22, h.port); assertEquals("paul@homebox", h.label)
        val p = SshHosts.parse("ssh admin@10.0.0.5:2222", name = "Pi").getOrThrow()
        assertEquals(2222, p.port); assertEquals("Pi", p.name); assertEquals("admin@10.0.0.5:2222", p.label)
        assertEquals("paul", SshHosts.parse("box.example-tailnet.ts.net", defaultUser = "paul").getOrThrow().user)
    }

    @Test fun rejectsInjectionAndNonsense() {
        for (bad in listOf("homebox", "paul@", "paul@-oProxyCommand=sh", "paul@host;rm -rf ~", "Paul Moore@host", "paul@host:99999", "paul@host:abc", "paul@ho st"))
            assertTrue(bad, SshHosts.parse(bad).isFailure)
    }

    @Test fun theComputerComesFromTheDesktopLink() {
        val d = SshHosts.desktopHost("http://100.101.102.103:8770", "paul", "homebox")!!
        assertEquals(SshHosts.DESKTOP_ID, d.id); assertEquals("100.101.102.103", d.host); assertEquals("homebox", d.name); assertTrue(d.auto)
        assertNull(SshHosts.desktopHost("http://100.101.102.103:8770", "bad name", "x"))
        assertNull(SshHosts.desktopHost("", "paul", "x"))
        val mine = SshHosts.parse("pi@raspberrypi").getOrThrow()
        val s = SshHosts.withDesktop(SshHosts.State(hosts = listOf(mine, d.copy(host = "100.90.1.2"))), d)
        assertEquals(listOf(SshHosts.DESKTOP_ID, mine.id), s.hosts.map { it.id })
        assertEquals("100.101.102.103", s.hosts.first().host)
    }

    @Test fun roundTripsAndCommands() {
        val c = SshHosts.Command("c1", "Uptime", "uptime", confirm = true)
        val s = SshHosts.State(listOf(SshHosts.desktopHost("http://100.101.102.103:8770", "paul", "box")!!, SshHosts.parse("pi@pi").getOrThrow()), listOf(c), SshHosts.DESKTOP_ID)
        assertEquals(s, SshHosts.fromJson(SshHosts.toJson(s)))
        val desk = s.hosts[0]
        assertEquals(SshHosts.BUILT_IN.size + 1, SshHosts.commandsFor(s, desk).size)
        assertEquals(listOf(c), SshHosts.commandsFor(s, s.hosts[1]))
    }

    @Test fun textSizeIsRememberedAndClamped() {
        assertEquals(SshHosts.DEFAULT_FONT, SshHosts.fromJson(org.json.JSONObject()).fontSize)  // 5.18.0 files have none
        assertEquals(18, SshHosts.fromJson(SshHosts.toJson(SshHosts.State(fontSize = 18))).fontSize)
        assertEquals(SshHosts.MAX_FONT, SshHosts.fromJson(org.json.JSONObject().put("fontSize", 99)).fontSize)
        // only the restart asks first; every built-in is read-only otherwise
        assertEquals(listOf("restart"), SshHosts.BUILT_IN.filter { it.confirm }.map { it.id })
        assertFalse(SshHosts.BUILT_IN.any { "sudo" in it.command || "rm " in it.command })
    }

    @Test fun findsTailscaleSignInLinks() {
        assertEquals("https://login.tailscale.com/a/abc123", SshClient.linkIn("# Tailscale SSH requires an additional check.\n# To authenticate, visit: https://login.tailscale.com/a/abc123\n"))
        assertNull(SshClient.linkIn("Welcome to Ubuntu"))
    }
}
