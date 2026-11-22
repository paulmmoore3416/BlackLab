package com.blacklab.app.ssh

import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.UserAuthNoneFactory
import org.apache.sshd.server.auth.password.UserAuthPasswordFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.InteractiveProcessShellFactory
import org.apache.sshd.server.shell.ProcessShellCommandFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The phone's SSH client against a real (in-process) SSH server: Tailscale-style "none" sign-in, banners, trust on
 *  first use, a changed host key, passwords, exec and an interactive shell. */
class SshClientTest {
    private lateinit var dir: File
    private val servers = mutableListOf<SshServer>()

    @Before fun setUp() { dir = Files.createTempDirectory("ssh").toFile() }
    @After fun tearDown() { servers.forEach { runCatching { it.stop(true) } }; dir.deleteRecursively() }

    private fun server(keyFile: String = "hostkey", port: Int = 0, password: String? = null, banner: String? = null): SshServer =
        SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            this.port = port
            keyPairProvider = SimpleGeneratorHostKeyProvider(File(dir, keyFile).toPath())
            if (password == null) userAuthFactories = listOf(UserAuthNoneFactory.INSTANCE)
            else { userAuthFactories = listOf(UserAuthPasswordFactory.INSTANCE); setPasswordAuthenticator { _, p, _ -> p == password } }
            if (banner != null) {  // from a file: MINA treats banner text containing "://" as a URL to fetch
                val f = File(dir, "banner.txt").apply { writeText(banner) }
                CoreModuleProperties.WELCOME_BANNER.set(this, f)
            }
            commandFactory = ProcessShellCommandFactory.INSTANCE
            shellFactory = InteractiveProcessShellFactory.INSTANCE
            start()
            servers += this
        }

    private class P(val trustAnswer: Boolean = true, val secretAnswer: String? = null) : SshClient.Prompts {
        val trusted = mutableListOf<String>(); val banners = mutableListOf<String>(); var secrets = 0
        override fun trust(host: String, keyType: String, fingerprint: String): Boolean { trusted += fingerprint; return trustAnswer }
        override fun secret(prompt: String, echo: Boolean): String? { secrets++; return secretAnswer }
        override fun banner(text: String) { banners += text }
    }

    private fun host(s: SshServer) = SshHosts.Host("h", "test", "127.0.0.1", s.port, System.getProperty("user.name").lowercase().filter { it.isLetterOrDigit() }.ifBlank { "tester" })
    private val client get() = SshClient(File(dir, "known_hosts"))

    @Test fun tailscaleStyleSignInNeedsNoPasswordAndTrustsOnce() {
        val s = server(banner = "# To authenticate, visit: https://login.tailscale.com/a/test123\n")
        val p = P()
        val session = client.open(host(s), p)
        assertTrue(session.isConnected)
        assertEquals(1, p.trusted.size)
        assertEquals(0, p.secrets)
        assertEquals("https://login.tailscale.com/a/test123", SshClient.linkIn(p.banners.joinToString("\n")))
        session.disconnect()
        val again = P()
        client.open(host(s), again).disconnect()
        assertTrue("a known key isn't asked about again", again.trusted.isEmpty())
    }

    @Test fun refusingTheKeyStops() {
        val s = server()
        try { client.open(host(s), P(trustAnswer = false)); fail() } catch (e: SshClient.AuthFailed) { assertTrue(e.message!!.contains("didn't trust")) }
    }

    @Test fun aChangedHostKeyIsCaughtUntilForgotten() {
        val first = server("key-a")
        val port = first.port
        client.open(host(first), P()).disconnect()
        first.stop(true)
        val impostor = server("key-b", port = port)
        try { client.open(host(impostor), P()); fail("connected through a changed key") } catch (e: SshClient.HostKeyChanged) { assertEquals("127.0.0.1", e.host) }
        client.forget("127.0.0.1", port)
        val p = P()
        client.open(host(impostor), p).disconnect()
        assertEquals(1, p.trusted.size)
    }

    @Test fun passwordsAreAskedForAndWrongOnesFail() {
        val s = server(password = "hunter2")
        val ok = P(secretAnswer = "hunter2")
        client.open(host(s), ok).disconnect()
        assertTrue(ok.secrets >= 1)
        try { client.open(host(s), P(secretAnswer = "nope")); fail() } catch (e: SshClient.AuthFailed) { /* expected */ }
    }

    @Test fun execReturnsOutputAndExitStatus() {
        val s = server()
        val session = client.open(host(s), P())
        val r = client.exec(session, "echo hello; echo oops 1>&2; exit 3")
        assertTrue(r.output, r.output.contains("hello"))
        assertTrue(r.output, r.output.contains("oops"))
        assertEquals(3, r.exit)
        assertFalse(r.truncated)
        session.disconnect()
    }

    @Test fun interactiveShellEchoesAndCloses() {
        val s = server()
        val session = client.open(host(s), P())
        val out = ByteArrayOutputStream()
        val saw = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val sh = SshClient.Shell(session, 80, 24, onOutput = { b -> synchronized(out) { out.write(b); if (out.toString().contains("BLACKLAB_OK")) saw.countDown() } }, onClosed = { closed.countDown() })
        sh.write("echo BLACKLAB_$((1)) | sed 's/1/OK/'\n".replace("$((1))", "1").toByteArray())
        assertTrue("no echo from the shell: " + synchronized(out) { out.toString() }, saw.await(10, TimeUnit.SECONDS))
        sh.resize(120, 40)
        sh.write("exit\n".toByteArray())
        assertTrue(closed.await(10, TimeUnit.SECONDS))
        session.disconnect()
    }

    @Test fun unreachableHostsSayWhy() {
        val s = server(); val port = s.port; s.stop(true)
        try { client.open(SshHosts.Host(SshHosts.DESKTOP_ID, "box", "127.0.0.1", port, "paul"), P()); fail() }
        catch (e: SshClient.AuthFailed) { assertTrue(e.message!!, e.message!!.contains("refused") && e.message!!.contains("tailscale set --ssh")) }
    }
}
