package com.blacklab.app.assistant

import com.blacklab.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The assistant's model menu (5.17): ★ Claude and ★ Gemini first, then the computer's local models, Gemini Nano and Auto.
 *
 *  - Claude (Opus, Sonnet, Haiku, Fable) runs on the computer through Paul's Claude subscription (the companion runs the
 *    Claude Code CLI headless), so it needs the desktop link (USB or Tailscale). The catalog comes from /phone/models.
 *  - Gemini models go straight from the phone with its Gemini key (and Gemini Nano runs on the phone itself).
 *  - Local models are the computer's Ollama models.
 *
 * Every answer from a model is saved to the Obsidian vault by the computer (Claude glows orange, Gemini red).
 */
object Models {
    enum class Provider(val group: String) { CLAUDE("★ Claude"), GEMINI("★ Gemini"), LOCAL("Your computer"), NANO("On this phone"), AUTO("Auto") }

    data class Entry(val id: String, val provider: Provider, val label: String, val available: Boolean, val note: String = "") {
        val highlight: Boolean get() = provider == Provider.CLAUDE || provider == Provider.GEMINI
    }

    const val AUTO = "auto"
    const val NANO = "nano:on-device"
    private val CLAUDE_DEFAULTS = listOf("opus" to "Claude Opus 5.5", "sonnet" to "Claude Sonnet 5.5", "haiku" to "Claude Haiku 5.5", "fable" to "Claude Fable 5.2")

    private val _desktop = MutableStateFlow<List<Entry>>(emptyList())
    private val _gemini = MutableStateFlow<List<Gemini.ModelInfo>>(Gemini.DEFAULT_MODELS)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun providerOf(id: String?): Provider = when ((id ?: AUTO).substringBefore(':').lowercase()) {
        "claude" -> Provider.CLAUDE
        "gemini" -> Provider.GEMINI
        "local" -> Provider.LOCAL
        "nano" -> Provider.NANO
        else -> Provider.AUTO
    }

    /** "claude-opus-5-5" → "Claude Opus 5.5" (labels follow the real model the computer reports). */
    fun prettyClaude(canonical: String): String {
        val m = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d{1,2}))?(?:-\\d{8})?$").find(canonical) ?: return canonical.ifBlank { "Claude" }
        val (fam, major, minor) = m.destructured
        return "Claude ${fam.replaceFirstChar { it.uppercase() }} $major" + if (minor.isNotBlank()) ".$minor" else ""
    }

    /** Parses /phone/models into entries (Claude and local models live on the computer). */
    fun parseDesktop(j: JSONObject, online: Boolean): List<Entry> {
        val arr = j.optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { m ->
            val p = providerOf(m.optString("id"))
            if (p != Provider.CLAUDE && p != Provider.LOCAL) null
            else Entry(m.optString("id"), p, m.optString("label"), online && m.optBoolean("available"),
                if (!online) "needs your computer" else m.optString("note"))
        }
    }

    /** The menu, in order. [desktopOnline]: the computer link is up. */
    fun build(desktop: List<Entry>, gemini: List<Gemini.ModelInfo>, desktopOnline: Boolean, geminiReady: Boolean, nanoReady: Boolean): List<Entry> {
        val claude = desktop.filter { it.provider == Provider.CLAUDE }.ifEmpty {
            CLAUDE_DEFAULTS.map { (a, l) -> Entry("claude:$a", Provider.CLAUDE, l, false, if (desktopOnline) "not on the computer yet" else "needs your computer") }
        }
        val gem = gemini.map { Entry("gemini:${it.id}", Provider.GEMINI, it.label, geminiReady, if (geminiReady) "Gemini API from the phone" else "add a Gemini key (Settings › Assistant)") } +
            Entry(NANO, Provider.NANO, "Gemini Nano", nanoReady, if (nanoReady) "on this phone, offline" else "not ready on this phone")
        val local = desktop.filter { it.provider == Provider.LOCAL }
        return claude + gem + local + Entry(AUTO, Provider.AUTO, "Auto", true, "BlackLab picks: computer, then the phone, then cloud")
    }

    fun label(id: String): String = _entries.value.firstOrNull { it.id == id }?.label ?: when (providerOf(id)) {
        Provider.CLAUDE -> CLAUDE_DEFAULTS.firstOrNull { "claude:${it.first}" == id }?.second ?: "Claude"
        Provider.GEMINI -> id.substringAfter(':')
        Provider.NANO -> "Gemini Nano"
        Provider.LOCAL -> id.substringAfter(':')
        Provider.AUTO -> "Auto"
    }

    /** True when [id] can answer right now. */
    fun usable(id: String): Boolean = when (providerOf(id)) {
        Provider.CLAUDE, Provider.LOCAL -> DesktopAi.usable
        Provider.GEMINI -> Gemini.reachable
        Provider.NANO -> Nano.onDevice
        Provider.AUTO -> true
    }

    private fun rebuild() {
        _entries.value = build(_desktop.value, _gemini.value, DesktopAi.usable, Gemini.reachable, Nano.onDevice)
    }

    /** Refreshes the menu: the computer's catalog (when linked) and Gemini's model list (when there's a key). */
    suspend fun refresh(app: AppContainer) {
        withContext(Dispatchers.IO) {
            if (DesktopAi.usable) runCatching { DesktopAi.request("GET", "/phone/models", null, 15_000) }.getOrNull()?.let {
                _desktop.value = parseDesktop(it, true)
                app.context.getSharedPreferences("assistant_models", 0).edit().putString("desktop", it.toString()).apply()
            } else if (_desktop.value.isEmpty()) {
                app.context.getSharedPreferences("assistant_models", 0).getString("desktop", null)?.let { raw ->
                    _desktop.value = runCatching { parseDesktop(JSONObject(raw), false) }.getOrDefault(emptyList())
                }
            } else _desktop.value = _desktop.value.map { it.copy(available = false, note = "needs your computer") }
            if (Gemini.configured) _gemini.value = Gemini.listModels()
        }
        rebuild()
    }
}
