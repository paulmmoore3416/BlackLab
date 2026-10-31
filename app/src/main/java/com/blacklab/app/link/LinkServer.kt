package com.blacklab.app.link

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import com.blacklab.app.AppContainer
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.assistant.Origin
import com.blacklab.app.assistant.Secrets
import com.blacklab.app.assistant.toPublicJson
import com.blacklab.app.core.SettingsSchema
import com.blacklab.app.core.Time
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.reminders.Notifications
import com.blacklab.app.ui.tasks.QuickParse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Change tracking + lifecycle for the desktop link. [rev] increases whenever data or settings
 * change so BlackLab Desktop can long-poll /api/changes and refresh immediately.
 */
class LinkHub(private val app: AppContainer) {
    val rev = AtomicLong(1)
    val revFlow = MutableStateFlow(1L)
    val running = MutableStateFlow(false)
    val clients = MutableStateFlow<Map<String, Long>>(emptyMap()) // address -> last seen
    private var server: LinkServer? = null
    private var cloudJob: Job? = null

    fun bump() { revFlow.value = rev.incrementAndGet(); scheduleCloudPush() }

    fun watch() {
        app.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            arrayOf("documents", "journal", "tasks", "task_lists", "subtasks", "events", "event_calendars", "courses", "assignments", "certifications", "ceu_entries", "recordings"),
        ) {
            override fun onInvalidated(tables: Set<String>) = bump()
        })
        app.appScope.launch { app.settings.state.drop(1).collect { bump() } }
        app.appScope.launch { app.assistant.messages.drop(1).collect { bump() } }
    }

    /** Debounced push of the snapshot to Google Drive while the app process is alive. */
    private fun scheduleCloudPush() {
        if (!app.settings.current.cloudSync) return
        cloudJob?.cancel()
        cloudJob = app.appScope.launch { delay(45_000); CloudSync.sync(app) }
    }

    @Synchronized
    fun startServer() {
        val s = app.settings.current
        if (server?.matches(s.linkPort, s.linkLan) == true) return
        server?.stop()
        server = LinkServer(app, s.linkPort, s.linkLan).also { it.start() }
        running.value = true
    }

    @Synchronized
    fun stopServer() { server?.stop(); server = null; running.value = false }

    fun lanAddress(): String? {
        val cm = app.context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp: LinkProperties = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }
}

/** Keeps the link server alive while BlackLab is in the background. */
class LinkService : Service() {
    companion object {
        fun sync(context: Context) {
            val i = Intent(context, LinkService::class.java)
            if (context.app.settings.current.linkEnabled) runCatching { ContextCompat.startForegroundService(context, i) }
            else context.stopService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext.app
        val s = app.settings.current
        if (!s.linkEnabled) { stopSelf(); return START_NOT_STICKY }
        val where = if (s.linkLan) (app.link.lanAddress()?.let { "$it:${s.linkPort}" } ?: "Wi-Fi") + " · USB" else "USB (adb) only"
        val n: Notification = NotificationCompat.Builder(this, Notifications.CH_LINK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BlackLab Desktop link")
            .setContentText("Listening on $where · pairing code in Settings")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifications.openAppIntent(this, "settings", 7701))
            .build()
        startForeground(7701, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        app.link.startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        applicationContext.app.link.stopServer()
        super.onDestroy()
    }
}

/**
 * A small HTTP/1.1 JSON API for BlackLab Desktop. Binds to localhost (reachable over USB with
 * `adb forward`) and, when allowed, to the Wi-Fi interface. Every call except /api/ping needs the
 * pairing code as a bearer token; repeated bad codes lock the client out for a minute.
 */
class LinkServer(private val app: AppContainer, private val port: Int, private val lan: Boolean) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile private var stopped = false

    fun matches(port: Int, lan: Boolean) = !stopped && this.port == port && this.lan == lan

    fun start() {
        pool.execute {
            runCatching {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getLoopbackAddress(), port))
                socket = ss
                while (!stopped) {
                    val c = ss.accept()
                    pool.execute { runCatching { serve(c) }; runCatching { c.close() } }
                }
            }
        }
    }

    fun stop() {
        stopped = true
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 60_000
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        val first = readLine(input) ?: return
        val parts = first.split(' ')
        if (parts.size < 2) return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull()?.coerceAtMost(4_000_000) ?: 0
        val body = if (len > 0) String(input.readNBytes(len), Charsets.UTF_8) else ""
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val req = Req(parts[0].uppercase(), path, query, headers, body)
        val origin = headers["origin"]

        if (req.method == "OPTIONS") { respond(out, 204, "", origin); return }
        if (path == "/api/ping") {
            respond(out, 200, JSONObject().put("app", "BlackLab").put("version", com.blacklab.app.BuildConfig.VERSION_NAME).put("pairing", true).toString(), origin); return
        }

        val addr = c.inetAddress.hostAddress ?: "?"
        failures[addr]?.let { (n, t) -> if (n >= 8 && Time.now() - t < 60_000) { respond(out, 429, err("Too many wrong pairing codes. Wait a minute."), origin); return } }
        val token = headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: query["token"]
        if (token == null || !MessageDigest.isEqual(token.uppercase().toByteArray(), app.secrets.linkToken().toByteArray())) {
            val (n, _) = failures[addr] ?: (0 to 0L)
            failures[addr] = (n + 1) to Time.now()
            respond(out, 401, err("Pairing code required"), origin); return
        }
        failures.remove(addr)
        app.link.clients.value = app.link.clients.value + (addr to Time.now())

        if (req.method == "GET" && path == "/api/file") { sendFile(out, req.query["path"].orEmpty(), origin); return }
        val (code, json) = runCatching { runBlocking { handle(req) } }.getOrElse { 500 to err(it.message ?: "error") }
        respond(out, code, json, origin)
    }

    private fun err(m: String) = JSONObject().put("error", m).toString()

    private suspend fun handle(r: Req): Pair<Int, String> {
        val body = if (r.body.isNotBlank()) runCatching { JSONObject(r.body) }.getOrDefault(JSONObject()) else JSONObject()
        return when {
            r.method == "GET" && r.path == "/api/info" -> 200 to Snapshot.info(app).toString()
            r.method == "GET" && r.path == "/api/schema" -> 200 to JSONObject().put("sections", SettingsSchema.toJson()).toString()
            r.method == "GET" && r.path == "/api/settings" -> 200 to SettingsSchema.values(app.settings.prefs).toString()
            (r.method == "PUT" || r.method == "POST") && r.path == "/api/settings" -> {
                val applied = SettingsSchema.apply(app.settings, body)
                app.requestReminderSync()
                LinkService.sync(app.context)
                200 to JSONObject().put("applied", JSONArray(applied)).put("values", SettingsSchema.values(app.settings.prefs)).toString()
            }
            r.method == "GET" && r.path == "/api/snapshot" -> 200 to Snapshot.build(app).toString()
            r.method == "GET" && r.path == "/api/changes" -> {
                val since = r.query["since"]?.toLongOrNull() ?: 0
                val wait = (r.query["wait"]?.toLongOrNull() ?: 25).coerceIn(0, 50) * 1000
                if (app.link.rev.get() <= since) withTimeoutOrNull(wait) { app.link.revFlow.first { it > since } }
                200 to JSONObject().put("rev", app.link.rev.get()).toString()
            }
            r.method == "POST" && r.path == "/api/assistant" -> {
                val reply = app.assistant.handle(body.optString("text"), Origin.DESKTOP)
                if (body.optBoolean("speakOnPhone")) app.speaker.speak(reply.text)
                200 to JSONObject().put("text", reply.text).put("source", reply.source)
                    .put("links", JSONArray().apply { reply.links.forEach { put(JSONObject().put("label", it.label).put("route", it.route ?: "").put("url", it.url ?: "")) } })
                    .toString()
            }
            r.method == "GET" && r.path == "/api/assistant" -> 200 to JSONObject().put("messages", JSONArray().apply { app.assistant.messages.value.forEach { put(it.toPublicJson()) } }).toString()
            r.method == "POST" && r.path == "/api/assistant/clear" -> { app.assistant.clearHistory(); 200 to "{}" }
            r.method == "POST" && r.path == "/api/tasks" -> {
                val p = QuickParse.parse(body.optString("text"))
                val profile = body.optString("profile").ifBlank { app.settings.current.activeProfile.name }
                val id = TaskOps.save(app, TaskEntity(profile = profile, title = p.title, dueDay = p.dueDay?.toEpochDay(), dueMinute = p.dueMinute,
                    reminderAt = if (p.dueDay != null && p.dueMinute != null) Time.millis(p.dueDay, p.dueMinute) else null,
                    priority = p.priority, important = p.important, tags = p.tags.joinToString(","), repeatRule = p.repeat))
                200 to JSONObject().put("id", id).toString()
            }
            r.method == "POST" && r.path.matches(Regex("/api/tasks/\\d+/(complete|reopen)")) -> {
                val id = r.path.split('/')[3].toLong()
                val t = app.db.tasks().get(id) ?: return 404 to err("No such task")
                TaskOps.setCompleted(app, t, r.path.endsWith("complete"))
                200 to "{}"
            }
            r.method == "POST" && r.path == "/api/secrets" -> {
                val set = ArrayList<String>()
                body.keys().forEach { k -> if (k in Secrets.all) { app.secrets.put(k, body.optString(k)); set += k } }
                200 to JSONObject().put("set", JSONArray(set)).toString()
            }
            r.method == "POST" && r.path == "/api/backup" -> 200 to JSONObject().put("file", app.backups.backupNow().name).toString()
            r.method == "POST" && r.path == "/api/sync/cloud" -> 200 to JSONObject().put("ok", CloudSync.sync(app)).toString()
            r.method == "GET" && r.path == "/api/files" -> {
                val dir = safeFile(r.query["path"].orEmpty()) ?: return 400 to err("Bad path")
                200 to JSONObject().put("path", app.storage.relative(dir)).put("files", JSONArray().apply {
                    (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                        put(JSONObject().put("name", f.name).put("dir", f.isDirectory).put("size", if (f.isFile) f.length() else 0).put("modified", f.lastModified())
                            .put("path", app.storage.relative(f)))
                    }
                }).toString()
            }
            else -> 404 to err("Unknown endpoint ${r.method} ${r.path}")
        }
    }

    /** Resolves a path inside the BlackLab folder, refusing anything that escapes it. */
    private fun safeFile(rel: String): File? {
        val root = app.storage.root.canonicalFile
        val f = File(root, rel.trimStart('/')).canonicalFile
        return if (f.path == root.path || f.path.startsWith(root.path + File.separator)) f else null
    }

    private fun sendFile(out: OutputStream, rel: String, origin: String?) {
        val f = safeFile(rel)?.takeIf { it.isFile } ?: run { respond(out, 404, err("No such file"), origin); return }
        val head = StringBuilder("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ${com.blacklab.app.core.Files.mime(f)}\r\n")
            .append("Content-Length: ${f.length()}\r\n")
            .append("Content-Disposition: attachment; filename=\"${f.name.replace("\"", "")}\"\r\n")
            .append(cors(origin)).append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        f.inputStream().use { it.copyTo(out) }
        out.flush()
    }

    private fun cors(origin: String?) = "Access-Control-Allow-Origin: ${origin ?: "*"}\r\nVary: Origin\r\n" +
        "Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\nAccess-Control-Allow-Headers: Authorization, Content-Type\r\n" +
        "Access-Control-Allow-Private-Network: true\r\nAccess-Control-Max-Age: 600\r\n"

    private fun respond(out: OutputStream, code: Int, json: String, origin: String?) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Error" }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\n" +
            cors(origin) + "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }
}
