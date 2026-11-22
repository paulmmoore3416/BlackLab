package com.blacklab.app.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * SSH for the Terminal section (5.18), on JSch (mwiede fork, pure Java, no native code).
 *
 *  - **Sign-in**: Tailscale SSH accepts the phone by its tailnet identity, which arrives as SSH auth "none" — no key
 *    or password. If the tailnet policy asks for a re-check, Tailscale sends a sign-in link as a banner; [Prompts.banner]
 *    shows it and the connection waits (up to [CONNECT_TIMEOUT_MS]) while you approve it in the browser. Other servers
 *    can ask for a password or one-time code through [Prompts.secret]; nothing typed there is stored.
 *  - **Host keys**: trust on first use, like OpenSSH. A new server's fingerprint is shown once ([Prompts.trust]) and
 *    remembered in the app's private known_hosts. A key that *changes* stops the connection with [HostKeyChanged] —
 *    never connected through silently.
 *  - **Sessions**: [Shell] is an interactive terminal (PTY xterm-256color, resizable, keep-alives every 30 s);
 *    [exec] runs one command and returns its output (for the quick-command buttons).
 *
 * Context-free (a File for known_hosts, callbacks for prompts), so it is unit tested on the JVM against an
 * in-process SSH server.
 */
class SshClient(private val knownHosts: File) {

    interface Prompts {
        /** First connection to [host]: show the key fingerprint; true = trust it from now on. */
        fun trust(host: String, keyType: String, fingerprint: String): Boolean

        /** A password or one-time code the server asks for; null = cancel. */
        fun secret(prompt: String, echo: Boolean): String?

        /** A message from the server during sign-in (Tailscale's approval link arrives this way). */
        fun banner(text: String)
    }

    class HostKeyChanged(val host: String, val fingerprint: String) :
        Exception("The server's identity key changed since you last connected. If you didn't reinstall it, someone may be in the middle.")

    class AuthFailed(message: String) : Exception(message)

    fun open(h: SshHosts.Host, prompts: Prompts): Session {
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.writeText("")
        val jsch = JSch()
        jsch.setKnownHosts(knownHosts.absolutePath)
        val s = jsch.getSession(h.user, h.host, h.port)
        s.setConfig("StrictHostKeyChecking", "ask")
        s.setConfig("PreferredAuthentications", "keyboard-interactive,password")  // "none" (Tailscale) is always tried first
        s.setConfig("compression.s2c", "zlib@openssh.com,none")  // an SSH algorithm name, not an address — privacy-scan: allow
        s.setConfig("compression.c2s", "zlib@openssh.com,none")  // privacy-scan: allow
        s.userInfo = Ui(h.host, prompts, jsch)
        s.setServerAliveInterval(30_000)
        s.setServerAliveCountMax(4)
        try {
            s.connect(CONNECT_TIMEOUT_MS)
        } catch (e: JSchChangedHostKeyException) {
            throw HostKeyChanged(h.host, fingerprintOf(jsch, h))
        } catch (e: JSchException) {
            val m = e.message.orEmpty()
            when {
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                    throw AuthFailed(if (h.id == SshHosts.DESKTOP_ID) "The computer didn't let the phone in. Turn on Tailscale SSH there: sudo tailscale set --ssh" else "Sign-in failed.")
                m.contains("reject HostKey", true) -> throw AuthFailed("You didn't trust the server's key, so the connection was closed.")
                m.contains("timeout", true) || m.contains("timed out", true) ->
                    throw AuthFailed("No answer from ${h.host}:${h.port}. Is Tailscale on (on both devices), and is the SSH server running there?")
                m.contains("Connection refused", true) ->
                    throw AuthFailed("${h.host} refused the connection on port ${h.port} — the SSH server isn't running" + if (h.id == SshHosts.DESKTOP_ID) " (sudo tailscale set --ssh)." else ".")
                else -> throw e
            }
        }
        return s
    }

    /** Forget the saved key for [host] (after the user confirms a reinstall changed it). */
    fun forget(host: String, port: Int = 22) {
        val jsch = JSch()
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) return
        jsch.setKnownHosts(knownHosts.absolutePath)
        val repo = jsch.hostKeyRepository
        repo.remove(if (port == 22) host else "[$host]:$port", null)
    }

    /** One command, its combined output (capped at [maxBytes]) and exit status. */
    data class ExecResult(val output: String, val exit: Int, val truncated: Boolean)

    fun exec(session: Session, command: String, timeoutMs: Long = 60_000, maxBytes: Int = 256 * 1024): ExecResult {
        val ch = session.openChannel("exec") as ChannelExec
        ch.setCommand(command)
        ch.setPty(false)
        val out = ByteArrayOutputStream()
        ch.setErrStream(out, true)
        val input = ch.inputStream
        ch.connect(15_000)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val buf = ByteArray(8192)
        var truncated = false
        try {
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (out.size() < maxBytes) out.write(buf, 0, minOf(n, maxBytes - out.size())) else truncated = true
                }
                if (ch.isClosed && input.available() <= 0) break
                if (System.nanoTime() > deadline) { truncated = true; break }
                Thread.sleep(40)
            }
        } finally {
            ch.disconnect()
        }
        return ExecResult(out.toString(Charsets.UTF_8), ch.exitStatus, truncated)
    }

    /** An interactive terminal. Output arrives on a background thread through [onOutput]; [onClosed] fires once. */
    class Shell(session: Session, cols: Int, rows: Int, private val onOutput: (ByteArray) -> Unit, private val onClosed: (String?) -> Unit) {
        private val ch = session.openChannel("shell") as ChannelShell
        private val toServer: OutputStream
        private val fromServer: InputStream

        init {
            ch.setPtyType("xterm-256color", cols.coerceIn(10, 500), rows.coerceIn(4, 300), 0, 0)
            ch.setAgentForwarding(false)
            ch.setXForwarding(false)
            fromServer = ch.inputStream
            toServer = ch.outputStream
            ch.connect(15_000)
            thread(name = "ssh-shell-reader", isDaemon = true) {
                val buf = ByteArray(16 * 1024)
                var why: String? = null
                try {
                    while (true) {
                        val n = fromServer.read(buf)
                        if (n < 0) break
                        if (n > 0) onOutput(buf.copyOf(n))
                    }
                } catch (e: Exception) {
                    why = e.message
                }
                onClosed(why ?: if (ch.exitStatus >= 0) "Session ended (exit ${ch.exitStatus})." else "Session ended.")
            }
        }

        val open: Boolean get() = ch.isConnected && !ch.isClosed

        fun write(bytes: ByteArray) {
            if (!open) return
            synchronized(toServer) { toServer.write(bytes); toServer.flush() }
        }

        fun resize(cols: Int, rows: Int) { if (open) ch.setPtySize(cols.coerceIn(10, 500), rows.coerceIn(4, 300), 0, 0) }

        fun close() = runCatching { ch.disconnect() }
    }

    private class Ui(private val host: String, private val p: Prompts, private val jsch: JSch) : UserInfo, UIKeyboardInteractive {
        private var lastSecret: String? = null
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = lastSecret
        override fun promptPassword(message: String): Boolean { lastSecret = p.secret(message, false); return lastSecret != null }
        override fun promptPassphrase(message: String): Boolean = false
        override fun promptYesNo(message: String): Boolean {
            // A CHANGED key comes through here too ("REMOTE HOST IDENTIFICATION HAS CHANGED … delete the old key and insert the
            // new key?"). Never: refusing makes JSch fail with JSchChangedHostKeyException, which open() turns into HostKeyChanged.
            if (message.contains("HAS CHANGED", ignoreCase = true) || message.contains("man-in-the-middle", ignoreCase = true)) return false
            // Anything else that isn't a first-contact question: say no rather than guess
            if (!message.contains("authenticity of host", ignoreCase = true)) return false
            // First contact: "The authenticity of host … can't be established. RSA key fingerprint is …"
            val fp = Regex("fingerprint is ([^\\s.]+(?:\\.[^\\s.]+)*)", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1)
                ?: Regex("(SHA256:[A-Za-z0-9+/=]+)").find(message)?.value ?: message
            val type = Regex("(\\S+) key fingerprint", RegexOption.IGNORE_CASE).find(message)?.groupValues?.get(1) ?: "host"
            return p.trust(host, type, fp)
        }
        override fun showMessage(message: String) = p.banner(message)
        override fun promptKeyboardInteractive(destination: String, name: String, instruction: String, prompt: Array<String>, echo: BooleanArray): Array<String>? {
            if (instruction.isNotBlank()) p.banner(instruction)
            if (prompt.isEmpty()) return emptyArray()
            return Array(prompt.size) { i -> p.secret(prompt[i], echo.getOrElse(i) { false }) ?: return null }
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 180_000  // time to approve a Tailscale "check" sign-in in the browser

        fun fingerprintOf(jsch: JSch, h: SshHosts.Host): String = runCatching {
            jsch.hostKeyRepository.getHostKey(if (h.port == 22) h.host else "[${h.host}]:${h.port}", null).firstOrNull()?.getFingerPrint(jsch).orEmpty()
        }.getOrDefault("")

        /** The first https link in a server banner (Tailscale's "To authenticate, visit: …"). */
        fun linkIn(text: String): String? = Regex("https://[\\w.-]+(?:/[\\w./?=&%#~+-]*)?").find(text)?.value?.trimEnd('.', ',', ')')
    }
}
