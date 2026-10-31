package com.blacklab.app.ui.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Range
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CamMode(val label: String, val extension: Int?) {
    NIGHT("Night", ExtensionMode.NIGHT),
    PORTRAIT("Portrait", ExtensionMode.BOKEH),
    PHOTO("Photo", null),
    HDR("HDR+", ExtensionMode.HDR),
    PRO("Pro", null),
    PANO("Panorama", null),
    VIDEO("Video", null),
}

/** Manual (Pro) exposure, focus and white balance. Null = automatic. */
data class ManualParams(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
) {
    val manualExposure: Boolean get() = iso != null || shutterNs != null
}

data class CamConfig(
    val mode: CamMode = CamMode.PHOTO,
    val front: Boolean = false,
    val flash: Int = ImageCapture.FLASH_MODE_OFF,
    val aspect: String = "4:3",
    val ultraHdr: Boolean = true,
    val maxRes: Boolean = false,
    val quality: String = "UHD",
    val fps: Int = 30,
    val stabilization: Boolean = true,
    val hdrVideo: Boolean = false,
    val panoUltra: Boolean = false,
)

data class CamState(
    val ready: Boolean = false,
    val zoom: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val exposureIndex: Int = 0,
    val exposureMin: Int = 0,
    val exposureMax: Int = 0,
    val exposureStep: Float = 0f,
    val hasFlash: Boolean = true,
    val ultraHdrActive: Boolean = false,
    val qualities: List<String> = emptyList(),
    val fpsOptions: List<Int> = listOf(30),
    val hdrVideoSupported: Boolean = false,
    val hdrVideoActive: Boolean = false,
    val stabilizationActive: Boolean = false,
    val modes: List<CamMode> = listOf(CamMode.PHOTO, CamMode.VIDEO),
    val recording: Boolean = false,
    val paused: Boolean = false,
    val recordMs: Long = 0,
    val resolution: String = "",
    val error: String? = null,
    val isoRange: IntRange? = null,
    val shutterRangeNs: LongRange? = null,
    val minFocusDiopters: Float = 0f,
    val awbModes: List<Int> = emptyList(),
    /** Focal length in pixels of the analysis stream (panorama). */
    val analysisFocalPx: Float = 0f,
)

suspend fun <T> ListenableFuture<T>.await(context: Context): T = suspendCancellableCoroutine { cont ->
    addListener({
        runCatching { get() }.fold({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
    }, ContextCompat.getMainExecutor(context))
}

/** Owns CameraX use cases and exposes device capabilities to the UI. */
class CameraEngine(private val context: Context) {
    private var provider: ProcessCameraProvider? = null
    private var extensions: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var analysis: ImageAnalysis? = null
    private val executor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var analyzer: ImageAnalysis.Analyzer? = null
    private var manual = ManualParams()
    private var aeAwbLocked = false
    val state = MutableStateFlow(CamState())

    suspend fun init() {
        if (provider != null) return
        val p = ProcessCameraProvider.getInstance(context).await(context)
        provider = p
        extensions = runCatching { ExtensionsManager.getInstanceAsync(context, p).await(context) }.getOrNull()
    }

    private fun base(front: Boolean) = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

    fun modesFor(front: Boolean): List<CamMode> {
        val ext = extensions
        return CamMode.entries.filter { m ->
            m.extension == null || (ext != null && runCatching { ext.isExtensionAvailable(base(front), m.extension) }.getOrDefault(false))
        }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView, cfg: CamConfig) {
        val p = provider ?: return
        if (recording != null) return
        p.unbindAll()
        imageCapture = null
        videoCapture = null
        analysis = null
        var selector = base(cfg.front)
        val ext = extensions
        val useExt = cfg.mode.extension != null && ext != null && runCatching { ext.isExtensionAvailable(selector, cfg.mode.extension) }.getOrDefault(false)
        if (useExt) selector = ext!!.getExtensionEnabledCameraSelector(selector, cfg.mode.extension!!)
        val info = runCatching { selector.filter(p.availableCameraInfos).firstOrNull() }.getOrNull()
        if (info == null) { state.update { it.copy(error = "Camera unavailable", ready = false) }; return }

        val ratio = if ((cfg.aspect == "16:9" && cfg.mode != CamMode.PANO) || cfg.mode == CamMode.VIDEO) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3
        val aspectStrategy = AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val video = cfg.mode == CamMode.VIDEO
        val previewCaps = runCatching { Preview.getPreviewCapabilities(info) }.getOrNull()
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy).build())
            .apply { if (video && cfg.stabilization && previewCaps?.isStabilizationSupported == true) setPreviewStabilizationEnabled(true) }
            .build()
        preview.surfaceProvider = previewView.surfaceProvider

        var newState = state.value.copy(modes = modesFor(cfg.front), error = null, hasFlash = info.hasFlashUnit())
        try {
            if (cfg.mode == CamMode.PANO) {
                val target = if (cfg.panoUltra) Size(4080, 3072) else Size(3264, 2448)
                val ia = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
                        .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                    .build()
                ia.setAnalyzer(analysisExecutor) { img -> val a = analyzer; if (a != null) a.analyze(img) else img.close() }
                camera = p.bindToLifecycle(owner, selector, preview, ia)
                analysis = ia
                val res = ia.resolutionInfo?.resolution
                val c2 = Camera2CameraInfo.from(info)
                val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val phys = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val fpx = if (focal != null && phys != null && res != null) focal / phys.width * maxOf(res.width, res.height) else (res?.let { maxOf(it.width, it.height) * 0.78f } ?: 0f)
                newState = newState.copy(analysisFocalPx = fpx, resolution = res?.let { "Panorama · ${minOf(it.width, it.height)} px tall strips" } ?: "")
            } else if (!video) {
                val caps = ImageCapture.getImageCaptureCapabilities(info)
                val ultra = cfg.ultraHdr && !useExt && caps.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                val res = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspectStrategy)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .apply { if (cfg.maxRes) setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) }
                    .build()
                val ic = ImageCapture.Builder()
                    .setCaptureMode(if (cfg.maxRes || useExt) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(cfg.flash)
                    .setResolutionSelector(res)
                    .apply { if (ultra) setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR) }
                    .build()
                camera = try {
                    p.bindToLifecycle(owner, selector, preview, ic)
                } catch (e: Exception) {
                    // Fall back to a plain JPEG pipeline if the combination isn't supported
                    val plain = ImageCapture.Builder().setFlashMode(cfg.flash).setResolutionSelector(res).build()
                    imageCapture = plain
                    newState = newState.copy(ultraHdrActive = false)
                    p.bindToLifecycle(owner, selector, preview, plain)
                }
                if (imageCapture == null) { imageCapture = ic; newState = newState.copy(ultraHdrActive = ultra) }
                newState = newState.copy(resolution = imageCapture?.resolutionInfo?.resolution?.let { "${it.width}×${it.height} · ${"%.1f".format(it.width * it.height / 1_000_000f)} MP" } ?: "")
            } else {
                val vcaps = Recorder.getVideoCapabilities(info)
                val hlg = vcaps.supportedDynamicRanges.any { it == DynamicRange.HLG_10_BIT }
                val dr = if (cfg.hdrVideo && hlg) DynamicRange.HLG_10_BIT else DynamicRange.SDR
                val supported = vcaps.getSupportedQualities(dr)
                val names = supported.mapNotNull { q -> when (q) { Quality.UHD -> "UHD"; Quality.FHD -> "FHD"; Quality.HD -> "HD"; Quality.SD -> "SD"; else -> null } }
                val want = when (cfg.quality) { "UHD" -> Quality.UHD; "FHD" -> Quality.FHD; "HD" -> Quality.HD; else -> Quality.SD }
                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(want, FallbackStrategy.lowerQualityOrHigherThan(want))).build()
                val fpsRanges = info.supportedFrameRateRanges
                val fpsOptions = listOf(24, 30, 60).filter { f -> fpsRanges.any { it.lower <= f && it.upper >= f } }.ifEmpty { listOf(30) }
                val stab = cfg.stabilization && vcaps.isStabilizationSupported
                fun build(withFps: Boolean, range: DynamicRange) = VideoCapture.Builder(recorder)
                    .setDynamicRange(range)
                    .apply {
                        if (stab) setVideoStabilizationEnabled(true)
                        if (withFps && cfg.fps != 30 && cfg.fps in fpsOptions) setTargetFrameRate(Range(cfg.fps, cfg.fps))
                    }.build()
                var vc = build(true, dr)
                camera = try {
                    p.bindToLifecycle(owner, selector, preview, vc)
                } catch (e: Exception) {
                    vc = build(false, DynamicRange.SDR)
                    p.bindToLifecycle(owner, selector, preview, vc)
                }
                videoCapture = vc
                newState = newState.copy(qualities = names, fpsOptions = fpsOptions, hdrVideoSupported = hlg, hdrVideoActive = vc.dynamicRange == DynamicRange.HLG_10_BIT,
                    stabilizationActive = stab, resolution = (names.firstOrNull { it == cfg.quality } ?: names.firstOrNull() ?: "") + if (cfg.fps != 30) " · ${cfg.fps} fps" else "")
            }
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: "Couldn't start camera", ready = false) }
            return
        }
        val cam = camera ?: return
        runCatching {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val iso = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val minF = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val awb = c2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
            newState = newState.copy(
                isoRange = iso?.let { it.lower..it.upper }, shutterRangeNs = exp?.let { it.lower..minOf(it.upper, 30_000_000_000L) },
                minFocusDiopters = minF, awbModes = awb,
            )
        }
        manual = ManualParams(); aeAwbLocked = false
        val es = cam.cameraInfo.exposureState
        newState = newState.copy(
            ready = true,
            exposureIndex = es.exposureCompensationIndex,
            exposureMin = es.exposureCompensationRange.lower,
            exposureMax = es.exposureCompensationRange.upper,
            exposureStep = es.exposureCompensationStep.toFloat(),
        )
        state.value = newState
        cam.cameraInfo.zoomState.observe(owner) { z -> state.update { it.copy(zoom = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) } }
    }

    fun unbind() { provider?.unbindAll(); camera = null; analysisExecutor.shutdown() }

    private fun pushRequestOptions() {
        val cam = camera ?: return
        val b = CaptureRequestOptions.Builder()
        val m = manual
        if (m.manualExposure) {
            val s = state.value
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            val iso = m.iso ?: s.isoRange?.let { (100).coerceIn(it.first, it.last) } ?: 100
            val exp = m.shutterNs ?: 8_333_333L
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
        } else if (aeAwbLocked) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
        }
        m.focusDiopters?.let { d ->
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        }
        if (aeAwbLocked) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
        else if (m.awbMode != CaptureRequest.CONTROL_AWB_MODE_AUTO) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        runCatching { Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build()) }
    }

    fun setManual(p: ManualParams) { manual = p; pushRequestOptions() }

    /** Freezes exposure and white balance (panorama sweeps) so strips match. */
    fun lockAeAwb(lock: Boolean) { aeAwbLocked = lock; pushRequestOptions() }

    suspend fun setExposureAwait(index: Int) {
        val cam = camera ?: return
        runCatching { cam.cameraControl.setExposureCompensationIndex(index).await(context) }
        state.update { it.copy(exposureIndex = index) }
    }

    suspend fun takePhotoAwait(file: File, mirror: Boolean): Pair<File?, String?> = suspendCancellableCoroutine { cont ->
        takePhoto(file, mirror) { f, e -> if (cont.isActive) cont.resume(f to e) }
    }

    fun setZoom(ratio: Float) {
        val s = state.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(s.minZoom, s.maxZoom))
    }

    fun setExposure(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
        state.update { it.copy(exposureIndex = index) }
    }

    fun setTorch(on: Boolean) { camera?.cameraControl?.enableTorch(on) }

    fun setFlash(mode: Int) { imageCapture?.flashMode = mode }

    fun focus(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
            .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun takePhoto(file: File, mirror: Boolean, onDone: (File?, String?) -> Unit) {
        val ic = imageCapture ?: return onDone(null, "Camera not ready")
        val opts = ImageCapture.OutputFileOptions.Builder(file).setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = mirror }).build()
        ic.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) = onDone(file, null)
            override fun onError(exception: ImageCaptureException) = onDone(null, exception.message)
        })
    }

    @SuppressLint("MissingPermission")
    fun startRecording(file: File, withAudio: Boolean, onFinalized: (File?, String?) -> Unit) {
        val vc = videoCapture ?: return onFinalized(null, "Video not ready")
        val pending = vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .apply { if (withAudio) withAudioEnabled() }
        recording = pending.start(executor) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> state.update { it.copy(recording = true, paused = false, recordMs = 0) }
                is VideoRecordEvent.Pause -> state.update { it.copy(paused = true) }
                is VideoRecordEvent.Resume -> state.update { it.copy(paused = false) }
                is VideoRecordEvent.Status -> state.update { it.copy(recordMs = ev.recordingStats.recordedDurationNanos / 1_000_000) }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    state.update { it.copy(recording = false, paused = false) }
                    if (ev.hasError() && ev.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) onFinalized(if (file.exists() && file.length() > 0) file else null, "Recording error ${ev.error}")
                    else onFinalized(file, null)
                }
            }
        }
    }

    fun pauseRecording() = recording?.pause()
    fun resumeRecording() = recording?.resume()
    fun stopRecording() = recording?.stop()
}
