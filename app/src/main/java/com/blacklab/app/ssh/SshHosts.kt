package com.blacklab.app.ssh

import com.blacklab.app.core.JsonStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved SSH hosts and one-tap commands for the Terminal section (5.18).
 *
 * The first host is the BlackLab computer itself, filled in from the desktop link (its Tailscale address and login
 * name, from /phone/status) — no typing. Sign-in is Tailscale SSH: the phone proves who it is through the tailnet,
 * so there are no passwords or keys to store. Other hosts can be added (a work machine, a Pi…); they may ask for a
 * password, which is typed into the terminal and never saved.
 */
object SshHosts {
    data class Host(val id: String, val name: String, val host: String, val port: Int = 22, val user: String, val auto: Boolean = false) {
        val label: String get() = "$user@$host" + if (port != 22) ":$port" else ""
    }

    data class Command(val id: String, val label: String, val command: String, val confirm: Boolean = false, val builtIn: Boolean = false)

    data class State(val hosts: List<Host> = emptyList(), val commands: List<Command> = emptyList(), val lastHost: String = "")

    const val DESKTOP_ID = "blacklab-desktop"

    /** Built-in quick commands for the BlackLab computer (read-only except the two restarts, which ask first). */
    val BUILT_IN = listOf(
        Command("status", "Status report", "curl -s -H 'X-BlackLab: 1' -H 'Content-Type: application/json' -d '{\"text\":\"status report\"}' " +
            "http://127.0.0.1:8770/local/assistant | python3 -c 'import json,sys;print(json.load(sys.stdin)[\"text\"])'", builtIn = true),
        Command("services", "Services", "systemctl --user --no-pager --plain list-units 'blacklab-*' familycraft.service nightjar.service omnix-kb-core.service; " +
            "echo; for s in ollama tailscaled; do echo \"\$s: $(systemctl is-active \$s)\"; done", builtIn = true),
        Command("log", "Companion log", "journalctl --user -u blacklab-desktop -n 60 --no-pager -o short-iso", builtIn = true),
        Command("gpu", "GPUs", "nvidia-smi --query-gpu=name,memory.used,memory.total,utilization.gpu,temperature.gpu --format=csv; " +
            "for c in /sys/class/drm/card*/device; do [ -f \$c/mem_info_vram_used ] && echo \"AMD: $(( $(cat \$c/mem_info_vram_used)/1048576 )) / $(( $(cat \$c/mem_info_vram_total)/1048576 )) MiB, $(cat \$c/gpu_busy_percent)% busy\"; done", builtIn = true),
        Command("models", "AI models", "curl -s 127.0.0.1:11434/api/ps | python3 -c 'import json,sys;[print(m[\"name\"], round(m[\"size_vram\"]/2**30,1), \"GB on GPU\") for m in json.load(sys.stdin)[\"models\"]] or print(\"nothing loaded\")'", builtIn = true),
        Command("disk", "Disk & memory", "df -h / ~ | awk 'NR==1||!seen[$0]++'; echo; free -h; echo; uptime", builtIn = true),
        Command("sync", "Vault sync", "systemctl --user --no-pager status blacklab-syncthing | head -6; echo; git -C ~/Obsidian/BlackLab log --oneline -5", builtIn = true),
        Command("updates", "Updates waiting", "apt list --upgradable 2>/dev/null | tail -n +2 | head -40; echo \"$(apt list --upgradable 2>/dev/null | tail -n +2 | wc -l) package(s)\"", builtIn = true),
        Command("restart", "Restart BlackLab Desktop", "systemctl --user restart blacklab-desktop && sleep 3 && systemctl --user is-active blacklab-desktop", confirm = true, builtIn = true),
    )

    private val HOST_RE = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val USER_RE = Regex("^[a-z_][a-z0-9_.-]{0,31}$")

    /** "paul@homebox", "admin@10.0.0.5:2222" or just "homebox" (then [defaultUser]) → a host, or an error to show. */
    fun parse(text: String, name: String = "", defaultUser: String = ""): Result<Host> = runCatching {
        val t = text.trim().removePrefix("ssh ").trim()
        val at = t.lastIndexOf('@')
        val user = if (at >= 0) t.substring(0, at) else defaultUser
        var rest = if (at >= 0) t.substring(at + 1) else t
        var port = 22
        val colon = rest.lastIndexOf(':')
        if (colon > 0 && rest.count { it == ':' } == 1) {
            port = rest.substring(colon + 1).toIntOrNull() ?: throw IllegalArgumentException("The port must be a number")
            rest = rest.substring(0, colon)
        }
        require(port in 1..65535) { "The port must be 1–65535" }
        require(USER_RE.matches(user)) { if (user.isBlank()) "Add a user name, like paul@$rest" else "That user name isn't valid" }
        require(HOST_RE.matches(rest)) { "That host name or address isn't valid" }
        Host(id = "h" + Integer.toHexString("$user@$rest:$port".hashCode()), name = name.trim().ifBlank { rest }, host = rest, port = port, user = user)
    }

    /** The BlackLab computer as a host, from the desktop link's Tailscale URL ("http://100.x.y.z:8770") and its login name. */
    fun desktopHost(tailnetUrl: String?, user: String?, hostName: String?): Host? {
        val h = tailnetUrl?.substringAfter("://")?.substringBefore('/')?.substringBefore(':')?.takeIf { HOST_RE.matches(it) } ?: return null
        val u = user?.takeIf { USER_RE.matches(it) } ?: return null
        return Host(DESKTOP_ID, hostName?.takeIf { it.isNotBlank() } ?: "BlackLab computer", h, 22, u, auto = true)
    }

    /** Keeps the user's hosts, puts the (refreshed) BlackLab computer first. */
    fun withDesktop(s: State, desktop: Host?): State {
        if (desktop == null) return s
        val others = s.hosts.filter { it.id != DESKTOP_ID }
        return s.copy(hosts = listOf(desktop) + others)
    }

    fun commandsFor(s: State, host: Host): List<Command> = (if (host.id == DESKTOP_ID) BUILT_IN else emptyList()) + s.commands

    // ------------------------------------------------------------------ storage

    fun toJson(s: State): JSONObject = JSONObject()
        .put("hosts", JSONArray(s.hosts.map { JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("port", it.port).put("user", it.user).put("auto", it.auto) }))
        .put("commands", JSONArray(s.commands.map { JSONObject().put("id", it.id).put("label", it.label).put("command", it.command).put("confirm", it.confirm) }))
        .put("lastHost", s.lastHost)

    fun fromJson(o: JSONObject): State {
        val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { h -> Host(h.optString("id"), h.optString("name"), h.optString("host"), h.optInt("port", 22), h.optString("user"), h.optBoolean("auto")) }
        } }.orEmpty().filter { it.id.isNotBlank() && it.host.isNotBlank() }
        val cmds = o.optJSONArray("commands")?.let { a -> (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { c -> Command(c.optString("id"), c.optString("label"), c.optString("command"), c.optBoolean("confirm")) }
        } }.orEmpty().filter { it.id.isNotBlank() && it.command.isNotBlank() }
        return State(hosts, cmds, o.optString("lastHost"))
    }

    val store = JsonStore("Terminal hosts", version = 1, empty = { State() }, decode = ::fromJson, encode = ::toJson)
}
