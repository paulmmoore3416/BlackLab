package com.blacklab.app.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.blacklab.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google's Gemini API (free key from Google AI Studio) — the cloud tier behind the desktop AI and
 * Gemini Nano, plus photo understanding (nameplates, "describe this photo", Smart sort).
 *
 * Modes (Settings › Assistant & desktop › Cloud AI): OFF, FALLBACK (default — only when neither the
 * desktop nor Gemini Nano can answer) or PREFER (before Gemini Nano). Photo features always ask
 * first. The Behavioral Health workspace never calls it. The key lives in the Keystore-encrypted
 * store and is sent only to generativelanguage.googleapis.com, in a header (never in the URL).
 */
object Gemini {
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/"
    @Volatile private var app: AppContainer? = null
    fun init(container: AppContainer) { app = container }

    private fun key(): String? = app?.secrets?.get(Secrets.GEMINI)
    private val mode: String get() = app?.settings?.current?.asstCloudAi ?: "OFF"
    private val model: String get() = app?.settings?.current?.asstGeminiModel?.ifBlank { null } ?: "gemini-2.5-flash"

    val configured: Boolean get() = key() != null
    /** Text generation may use the cloud right now. */
    val textEnabled: Boolean get() = configured && mode != "OFF" && app?.context?.isOnline() == true
    val prefer: Boolean get() = textEnabled && mode == "PREFER"
    /** Photo features (nameplates, describe, Smart sort): needs a key and a connection. */
    val visionEnabled: Boolean get() = configured && mode != "OFF" && app?.context?.isOnline() == true
    val label: String get() = "Gemini · $model (cloud)"
    /** A model picked by name in the assistant's menu can answer: a key and a connection (the Cloud AI mode is for Auto). */
    val reachable: Boolean get() = configured && app?.context?.isOnline() == true

    suspend fun generate(prompt: String, temperature: Float = 0.3f, maxTokens: Int = 400, modelOverride: String? = null): String? =
        request(JSONArray().put(JSONObject().put("text", prompt)), temperature, maxTokens, modelOverride)

    /** One Gemini text model from Google's list. */
    data class ModelInfo(val id: String, val label: String)
    @Volatile private var listed: List<ModelInfo> = emptyList()
    @Volatile private var listedAt = 0L
    val DEFAULT_MODELS = listOf(ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro"), ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash"), ModelInfo("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite"))
    private val skip = Regex("embedding|aqa|imagen|veo|tts|image|live|audio|learnlm|robotics|computer-use|native|-\\d{3}$|-exp|-preview-\\d", RegexOption.IGNORE_CASE)

    /** Text models this key can use (newest generation first), cached a day; the defaults when Google can't be reached. */
    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        if (listed.isNotEmpty() && System.currentTimeMillis() - listedAt < 86_400_000L) return@withContext listed
        val k = key() ?: return@withContext DEFAULT_MODELS
        val out = runCatching {
            val c = URL(ENDPOINT.removeSuffix("/") + "?pageSize=200").openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6_000; c.readTimeout = 10_000
                c.setRequestProperty("x-goog-api-key", k)
                if (c.responseCode !in 200..299) return@runCatching emptyList<ModelInfo>()
                val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { m ->
                    val id = m.optString("name").removePrefix("models/")
                    val gen = m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true
                    if (!id.startsWith("gemini-") || !gen || skip.containsMatchIn(id)) null else ModelInfo(id, m.optString("displayName").ifBlank { id })
                }
            } finally { c.disconnect() }
        }.getOrDefault(emptyList())
        val sorted = out.sortedWith(compareBy({ -(Regex("gemini-(\\d+(?:\\.\\d+)?)").find(it.id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0) },
            { if (it.id.contains("pro")) 0 else if (it.id.contains("lite")) 2 else 1 }, { it.id })).take(12)
        if (sorted.isNotEmpty()) { listed = sorted; listedAt = System.currentTimeMillis() }
        sorted.ifEmpty { DEFAULT_MODELS }
    }

    /** Asks about a photo. The image is downscaled (longest side 1280 px, JPEG) before it leaves the phone. */
    suspend fun vision(prompt: String, photo: File, temperature: Float = 0.2f, maxTokens: Int = 600): String? {
        val b64 = withContext(Dispatchers.IO) { encode(photo) } ?: return null
        return request(JSONArray().put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))), temperature, maxTokens)
    }

    private suspend fun request(parts: JSONArray, temperature: Float, maxTokens: Int, modelOverride: String? = null): String? = withContext(Dispatchers.IO) {
        val k = key() ?: return@withContext null
        val model = modelOverride?.takeIf { Regex("gemini-[a-z0-9.-]+").matches(it) } ?: this@Gemini.model
        runCatching {
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
                .put("generationConfig", JSONObject().put("temperature", temperature.toDouble()).put("maxOutputTokens", maxTokens).apply {
                    // 2.5 Flash models think by default; for short assistant replies that only adds latency
                    if (model.contains("2.5") && model.contains("flash")) put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                })
            val c = URL(ENDPOINT + model + ":generateContent").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8_000; c.readTimeout = 60_000
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("x-goog-api-key", k)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                if (c.responseCode !in 200..299) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val p = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@runCatching null
                (0 until p.length()).mapNotNull { p.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotBlank() } }.joinToString("").trim().ifBlank { null }
            } finally { c.disconnect() }
        }.getOrNull()
    }

    private fun encode(photo: File): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.path, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
        val raw = BitmapFactory.decodeFile(photo.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 1280f / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    /** Folder suggestions for photos: one short folder name per photo, reusing existing folders when they fit. */
    suspend fun suggestFolder(photo: File, existing: List<String>): String? {
        val out = vision(
            "You organize a facility manager's photo library. Suggest ONE short folder name (1-4 words, Title Case) for this photo. " +
                (if (existing.isNotEmpty()) "Prefer one of these existing folders if it fits: ${existing.joinToString("; ")}. " else "") +
                "Good examples: Boiler Room, Roof, Fire Safety, Work Orders, Receipts, Family, Vehicles, Grounds. Reply with the folder name only.",
            photo, temperature = 0.1f, maxTokens = 20,
        ) ?: return null
        return out.lineSequence().first().trim().trim('"', '\'', '.', '*').take(40).ifBlank { null }
    }
}
