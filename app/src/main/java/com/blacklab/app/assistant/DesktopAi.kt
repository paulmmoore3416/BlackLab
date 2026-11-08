package com.blacklab.app.assistant

import com.blacklab.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The desktop's local AI (Ollama, through the BlackLab Desktop companion) as a brain for the phone.
 *
 * Transport: the companion listens on 127.0.0.1 only; `./daddyshome` (and the companion itself)
 * run `adb reverse tcp:8770 tcp:8770`, so the phone reaches it at http://127.0.0.1:8770 over USB —
 * or over Wi-Fi when the phone is paired with Wireless debugging. No network port is opened on the
 * computer. Every request carries a shared token that `./daddyshome` provisions into the encrypted
 * key store; the companion rejects anything without it.
 *
 * When it's reachable, every on-device AI feature (assistant, summaries, Talk, Ask my files,
 * meeting actions, Brainstorm tools) uses the bigger desktop model; when it isn't, they fall back
 * to Gemini Nano and then the offline rules — so the assistant always works.
 */
object DesktopAi {
    enum class State(val label: String) { NOT_PAIRED("Not paired — run ./daddyshome on the computer"), OFF("Turned off"), CHECKING("Checking…"), ONLINE("Connected"), OFFLINE("Computer not reachable") }

    data class Status(val state: State = State.CHECKING, val model: String = "", val models: List<String> = emptyList(), val host: String = "", val checkedAt: Long = 0, val latencyMs: Long = 0, val note: String = "")

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    @Volatile private var app: AppContainer? = null
    private val lock = Mutex()
    const val DEFAULT_URL = "http://127.0.0.1:8770"

    fun init(container: AppContainer) {
        if (app != null) return
        app = container
        container.appScope.launch {
            while (true) {
                probe()
                if (_status.value.state == State.ONLINE) runCatching { DesktopJobs.sync(container) }
                delay(if (_status.value.state == State.ONLINE) (if (DesktopJobs.jobs.value.isNotEmpty()) 20_000 else 60_000) else 20_000)
            }
        }
    }

    private val enabled: Boolean get() = app?.settings?.current?.asstDesktopAi ?: false
    private fun token(): String? = app?.secrets?.get(Secrets.DESKTOP_AI)
    private fun base(): String = (app?.secrets?.get(Secrets.DESKTOP_AI_URL) ?: DEFAULT_URL).trimEnd('/')

    private val handOff = Regex("^(?:on (?:my |the )?(?:desktop|computer|pc|laptop)\\b|(?:ask|tell|have) (?:my |the )?(?:desktop|computer|pc|laptop)\\b|(?:desktop|computer)[:,])")
    private val bare = Regex("^(?:what'?s|whats|how'?s|hows|check|show)?\\s*(?:the |my )?(?:desktop|computer|pc|laptop)(?:'?s)? (?:ai|brain|model|llm|assistant)(?: status| connection)?$|^(?:desktop|computer) (?:status|connection)$|^(?:is|are) (?:the |my )?(?:desktop|computer|pc|laptop)(?:'?s)? (?:ai )?(?:up|down)$")
    private val aiNoun = Regex("\\b(?:desktop|computer|pc|laptop)(?:'?s)? (?:ai|brain|model|llm|assistant|link|connection)\\b")
    private val noun = Regex("\\b(?:desktop|computer|pc|laptop)(?:'?s)?\\b")
    private val stateWord = Regex("\\b(?:status|state|connected|connection|online|offline|reachable|unreachable|working|running|linked|paired|available|up|down|alive|there|doing|ok|okay)\\b|\\b(?:what|which) model\\b")
    private val linkWord = Regex("\\b(?:connected|connection|online|offline|reachable|unreachable|linked|paired)\\b")
    private val machineTopic = Regex("\\b(?:disk|space|storage|files?|folders?|downloads?|cpu|ram|memory|gpu|battery|temp(?:erature)?|apps?|uptime|process(?:es)?)\\b")

    /**
     * True when [l] (lowercased, normalized) asks whether the desktop AI / link is up — "what's the status of
     * the desktop AI", "is my computer online" — rather than handing the computer a request
     * ("ask my desktop how much disk space is left").
     */
    fun isStatusQuestion(l: String): Boolean {
        val q = l.trim().trimEnd('?', '!', '.').trim()
        if (handOff.containsMatchIn(q)) return false
        if (bare.containsMatchIn(q)) return true
        if (aiNoun.containsMatchIn(q) && stateWord.containsMatchIn(q)) return true
        return noun.containsMatchIn(q) && linkWord.containsMatchIn(q) && !machineTopic.containsMatchIn(q)
    }

    /** True when requests should go to the desktop right now. */
    val usable: Boolean get() = enabled && _status.value.state == State.ONLINE

    suspend fun probe(): Status = withContext(Dispatchers.IO) {
        val s = when {
            !enabled -> Status(State.OFF)
            token() == null -> Status(State.NOT_PAIRED)
            else -> {
                val t0 = System.currentTimeMillis()
                runCatching { call("GET", "/phone/status", null, 1_500) }.fold(
                    onSuccess = { j ->
                        val models = j.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                        if (j.optBoolean("ollama")) Status(State.ONLINE, j.optString("model"), models, j.optString("host"), System.currentTimeMillis(), System.currentTimeMillis() - t0)
                        else Status(State.OFFLINE, note = "The companion is up but Ollama isn't — run ./daddyshome", checkedAt = System.currentTimeMillis())
                    },
                    onFailure = { e -> Status(State.OFFLINE, note = if (e is SecurityException) "The computer rejected this phone's key — run ./daddyshome again" else "", checkedAt = System.currentTimeMillis()) },
                )
            }
        }
        _status.value = s
        s
    }

    /** Single-turn generation on the desktop model. Null when it isn't reachable or fails (callers fall back). */
    suspend fun generate(prompt: String, temperature: Float, maxTokens: Int, timeoutMs: Long = 90_000): String? {
        if (!usable) return null
        return lock.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    call("POST", "/phone/generate", JSONObject().put("prompt", prompt).put("temperature", temperature.toDouble()).put("max_tokens", maxTokens), timeoutMs.toInt())
                        .optString("text").trim().takeIf { it.isNotBlank() }
                }.getOrElse { _status.value = _status.value.copy(state = State.OFFLINE, checkedAt = System.currentTimeMillis()); null }
            }
        }
    }

    data class DesktopReply(val text: String, val source: String)

    /** Hands a request to the desktop assistant itself (files, apps, system status on the computer). */
    suspend fun ask(text: String): DesktopReply? {
        if (!usable && probe().state != State.ONLINE) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val j = call("POST", "/phone/ask", JSONObject().put("text", text), 180_000)
                DesktopReply(j.optString("text"), j.optString("source", "Desktop"))
            }.getOrNull()
        }
    }

    /** For DesktopJobs: one authenticated call to the companion (throws on failure). */
    internal fun request(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject = call(method, path, body, timeoutMs)

    /** Streams a file to the companion's inbox and returns its upload id, or null if the computer refused it. */
    internal fun upload(file: java.io.File): String? {
        val c = URL(base() + "/phone/upload?name=" + java.net.URLEncoder.encode(file.name, "UTF-8")).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 3_000
            c.readTimeout = 600_000
            c.doOutput = true
            c.setFixedLengthStreamingMode(file.length())
            c.setRequestProperty("Authorization", "Bearer " + (token() ?: ""))
            c.setRequestProperty("X-BlackLab-Phone", android.os.Build.MODEL ?: "phone")
            c.setRequestProperty("Content-Type", "application/octet-stream")
            file.inputStream().use { inp -> c.outputStream.use { inp.copyTo(it, 1 shl 16) } }
            if (c.responseCode !in 200..299) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("upload").takeIf { it.isNotBlank() }
        } finally {
            c.disconnect()
        }
    }

    private fun call(method: String, path: String, body: JSONObject?, timeoutMs: Int): JSONObject {
        val c = URL(base() + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = minOf(timeoutMs, 2_000)
            c.readTimeout = timeoutMs
            c.setRequestProperty("Authorization", "Bearer " + (token() ?: ""))
            c.setRequestProperty("X-BlackLab-Phone", android.os.Build.MODEL ?: "phone")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            if (code == 401 || code == 403) throw SecurityException("rejected")
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(text.ifBlank { "{}" })
        } finally {
            c.disconnect()
        }
    }
}
