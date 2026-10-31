package com.blacklab.app.assistant

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano through ML Kit's Prompt API. The model lives in the system's AICore service
 * (Pixel 9 / 10 and newer), so it adds nothing to the APK and runs with no network.
 */
object Nano {
    enum class Status(val label: String) {
        UNKNOWN("Checking…"), AVAILABLE("Ready · on-device"), DOWNLOADABLE("Model not downloaded"),
        DOWNLOADING("Downloading model…"), UNAVAILABLE("Not supported on this device"), ERROR("Unavailable")
    }

    private val _status = MutableStateFlow(Status.UNKNOWN)
    val status: StateFlow<Status> = _status.asStateFlow()
    val downloadProgress = MutableStateFlow(0L)
    var modelName: String = ""
        private set

    private var model: GenerativeModel? = null
    private val lock = Mutex()

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    suspend fun refresh(): Status {
        val s = runCatching {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> Status.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
                else -> Status.UNAVAILABLE
            }
        }.getOrDefault(Status.ERROR)
        _status.value = s
        if (s == Status.AVAILABLE && modelName.isBlank()) modelName = runCatching { client().getBaseModelName() }.getOrDefault("")
        return s
    }

    fun download(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        scope.launch {
            runCatching {
                client().download().collect { st ->
                    when (st) {
                        is DownloadStatus.DownloadStarted -> _status.value = Status.DOWNLOADING
                        is DownloadStatus.DownloadProgress -> { _status.value = Status.DOWNLOADING; downloadProgress.value = st.totalBytesDownloaded }
                        is DownloadStatus.DownloadCompleted -> refresh()
                        is DownloadStatus.DownloadFailed -> _status.value = Status.ERROR
                    }
                }
            }.onFailure { _status.value = Status.ERROR }
        }
    }

    val ready: Boolean get() = _status.value == Status.AVAILABLE

    /** Single-turn generation. Returns null when Nano isn't available or times out. */
    suspend fun generate(prompt: String, temperature: Float = 0.3f, maxTokens: Int = 320, timeoutMs: Long = 25_000): String? {
        if (_status.value != Status.AVAILABLE && refresh() != Status.AVAILABLE) return null
        return lock.withLock {
            withTimeoutOrNull(timeoutMs) {
                runCatching {
                    val req = generateContentRequest(TextPart(prompt)) {
                        this.temperature = temperature
                        this.topK = 16
                        this.maxOutputTokens = maxTokens
                    }
                    client().generateContent(req).candidates.firstOrNull()?.text?.trim()
                }.getOrNull()
            }
        }
    }
}
