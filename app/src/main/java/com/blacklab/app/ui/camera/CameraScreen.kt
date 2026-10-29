package com.blacklab.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaActionSound
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Geo
import com.blacklab.app.core.Keys
import com.blacklab.app.core.LocationTracker
import com.blacklab.app.core.OrientationTracker
import com.blacklab.app.imaging.BitmapIO
import com.blacklab.app.imaging.PanoramaStitcher
import com.blacklab.app.imaging.PhotoFilter
import com.blacklab.app.imaging.PhotoInfo
import com.blacklab.app.imaging.PhotoMeta
import com.blacklab.app.ui.components.CodeResultDialog
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.ScanDoneDialog
import com.blacklab.app.ui.components.ScanResult
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.rememberCodeScanner
import com.blacklab.app.ui.components.rememberDocumentScanner
import com.blacklab.app.ui.components.settingsState
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

@Composable
fun CameraScreen(initialMode: String) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val profile = s.activeProfile
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var camOk by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var micOk by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    var locOk by remember { mutableStateOf(Geo.hasPermission(ctx)) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camOk = granted(Manifest.permission.CAMERA); micOk = granted(Manifest.permission.RECORD_AUDIO); locOk = Geo.hasPermission(ctx)
    }
    LaunchedEffect(Unit) {
        if (!camOk || !micOk || (!locOk && s.camGeotag)) perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + if (s.camGeotag) Geo.permissions else emptyArray())
    }

    var scanResult by remember { mutableStateOf<ScanResult?>(null) }
    var code by remember { mutableStateOf<Barcode?>(null) }
    val docScanner = rememberDocumentScanner(app, profile) { scanResult = it }
    val codeScanner = rememberCodeScanner { code = it }
    LaunchedEffect(initialMode) {
        when (initialMode) { "scan" -> docScanner(); "qr" -> codeScanner() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!camOk) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.NoPhotography, null, tint = Color.White, modifier = Modifier.size(56.dp))
                Text("Camera permission is needed", color = Color.White, modifier = Modifier.padding(16.dp))
                Button(onClick = { perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) + Geo.permissions) }) { Text("Allow camera") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { shell.back() }) { Text("Back") }
            }
        } else {
            CameraContent(initialMode, micOk, locOk, onScanDoc = docScanner, onScanCode = codeScanner)
        }
    }
    scanResult?.let { r -> ScanDoneDialog(r) { scanResult = null } }
    code?.let { c -> CodeResultDialog(c) { code = null } }
}

private val ShutterSpeeds: List<Long> = listOf(
    125_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_666_667L, 33_333_333L, 66_666_667L,
    125_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L,
)

private fun shutterLabel(ns: Long): String = if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}″" else "1/${(1_000_000_000.0 / ns).roundToInt()}"

private val WbModes = listOf(
    CaptureRequest.CONTROL_AWB_MODE_AUTO to "Auto",
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT to "Daylight",
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT to "Cloudy",
    CaptureRequest.CONTROL_AWB_MODE_SHADE to "Shade",
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT to "Tungsten",
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT to "Fluorescent",
)

/** Exposure-bracketing presets (EV offsets). */
private val Brackets = listOf(emptyList(), listOf(-1f, 0f, 1f), listOf(-2f, 0f, 2f), listOf(-2f, -1f, 0f, 1f, 2f))

@Composable
private fun CameraContent(initialMode: String, micOk: Boolean, locOk: Boolean, onScanDoc: () -> Unit, onScanCode: () -> Unit) {
    val ctx = LocalContext.current
    val app = rememberApp()
    val shell = LocalShell.current
    val s = settingsState()
    val owner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val engine = remember { CameraEngine(ctx) }
    val st by engine.state.collectAsState()
    val sound = remember { MediaActionSound() }
    val location = remember { LocationTracker(ctx) }
    val orientation = remember { OrientationTracker(ctx) }
    DisposableEffect(Unit) {
        orientation.start()
        onDispose { engine.analyzer = null; engine.stopRecording(); engine.unbind(); sound.release(); location.stop(); orientation.stop() }
    }
    LaunchedEffect(locOk, s.camGeotag) { if (locOk && s.camGeotag) location.start(app.appScope) }

    var cfg by remember {
        mutableStateOf(CamConfig(
            mode = when (initialMode) { "video" -> CamMode.VIDEO; "pano" -> CamMode.PANO; "pro" -> CamMode.PRO; else -> CamMode.PHOTO },
            aspect = s.camAspect, ultraHdr = s.camUltraHdr, maxRes = s.camMaxResolution, quality = s.camVideoQuality,
            fps = s.camVideoFps, stabilization = s.camStabilization, hdrVideo = s.camHdrVideo, panoUltra = s.camPanoQuality == "ULTRA",
        ))
    }
    var initialized by remember { mutableStateOf(false) }
    // TextureView-backed preview so colour looks can be applied live with a RenderEffect
    val previewView = remember {
        PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER; keepScreenOn = true }
    }
    LaunchedEffect(Unit) { engine.init(); initialized = true }
    LaunchedEffect(initialized, cfg) { if (initialized) engine.bind(owner, previewView, cfg) }

    var filter by remember { mutableStateOf(PhotoFilter.from(s.camFilter)) }
    val filterApplies = cfg.mode != CamMode.VIDEO
    LaunchedEffect(filter, filterApplies) { previewView.setRenderEffect(if (filterApplies) filter.renderEffect() else null) }
    var filterBar by remember { mutableStateOf(false) }

    var grid by remember { mutableStateOf(s.camGrid) }
    var timerSec by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var torch by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var showExposure by remember { mutableStateOf(false) }
    var flashOverlay by remember { mutableStateOf(false) }
    var lastCapture by remember { mutableStateOf<File?>(null) }
    var settingsSheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var bracket by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(ManualParams()) }
    var proDial by remember { mutableStateOf<String?>(null) }
    var roll by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(cfg.mode, cfg.front) { manual = ManualParams(); proDial = null }
    LaunchedEffect(Unit) { while (true) { roll = orientation.rollDeg; delay(120) } }

    LaunchedEffect(Unit) {
        // show the latest photo/video thumbnail
        val dirs = listOf(app.storage.dir(s.activeProfile, Folder.PHOTOS), app.storage.dir(s.activeProfile, Folder.VIDEOS))
        lastCapture = withContext(Dispatchers.IO) { dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { it.isFile && !it.name.startsWith(".") }.maxByOrNull { it.lastModified() } }
    }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(3500); focusPoint = null; showExposure = false } }

    // ---------------- panorama
    val stitcher = remember(st.analysisFocalPx) { if (st.analysisFocalPx > 0f) PanoramaStitcher(ctx, st.analysisFocalPx) else null }
    val idlePano = remember { kotlinx.coroutines.flow.MutableStateFlow(com.blacklab.app.imaging.PanoProgress()) }
    val pano by (stitcher?.progress ?: idlePano).collectAsState()
    var panoResult by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var panoProcessing by remember { mutableStateOf(false) }
    LaunchedEffect(cfg.mode, stitcher) { engine.analyzer = if (cfg.mode == CamMode.PANO) stitcher else null }
    LaunchedEffect(cfg.mode) { if (cfg.mode == CamMode.PANO && cfg.front) cfg = cfg.copy(front = false) }

    fun photoInfo(): PhotoInfo {
        val geo = if (s.camGeotag) location.latest else null
        orientation.setLocation(geo)
        val who = s.userName.ifBlank { s.organization }
        return PhotoInfo(
            geo = geo, headingDeg = orientation.headingDeg, author = s.userName,
            copyright = if (who.isNotBlank()) "© ${LocalDate.now().year} $who" else "",
            account = app.settings.profileName(s.activeProfile), filter = filter,
        )
    }

    /** Applies the look / info stamp and writes location + details into the photo's EXIF. */
    suspend fun finishPhoto(f: File, info: PhotoInfo, look: PhotoFilter = filter) = withContext(Dispatchers.Default) {
        runCatching { BitmapIO.bake(f, look, s.camVintageDate, if (s.camInfoStamp) info else null) }
        PhotoMeta.write(f, info.copy(filter = look), includeExtras = s.camInfoTags)
        app.storage.scan(f)
    }

    fun finishPano() {
        val sti = stitcher ?: return
        panoProcessing = true
        scope.launch {
            val (bmp, err) = withContext(Dispatchers.Default) { sti.finish() }
            engine.lockAeAwb(false)
            panoProcessing = false
            if (bmp != null) panoResult = bmp else err?.let { shell.toast(it) }
        }
    }
    LaunchedEffect(pano.running, pano.started) { if (!pano.running && pano.started && !panoProcessing && panoResult == null) finishPano() }

    fun capture() {
        if (busy || panoProcessing) return
        if (cfg.mode == CamMode.PANO) {
            val sti = stitcher ?: return
            if (pano.running) { if (s.camShutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING); finishPano() }
            else {
                engine.lockAeAwb(true); sti.start()
                if (s.camShutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            return
        }
        val video = cfg.mode == CamMode.VIDEO
        if (video) {
            if (st.recording) { engine.stopRecording(); if (s.camShutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING); return }
            val f = File(app.storage.dir(s.activeProfile, Folder.VIDEOS), "VID_${BlackLabStorage.timestamp()}.mp4")
            if (s.camShutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            engine.startRecording(f, micOk) { file, err ->
                if (file != null) { app.storage.scan(file); lastCapture = file; shell.toast("Video saved · Videos/${file.name}") }
                err?.let { if (file == null) shell.toast(it) }
            }
            return
        }
        scope.launch {
            if (timerSec > 0) { for (i in timerSec downTo 1) { countdown = i; delay(1000) }; countdown = 0 }
            busy = true
            val info = photoInfo()
            val stamp = BlackLabStorage.timestamp()
            val evs = if (cfg.mode == CamMode.PHOTO || cfg.mode == CamMode.PRO) Brackets[bracket] else emptyList()
            if (evs.isEmpty()) {
                val f = File(app.storage.dir(s.activeProfile, Folder.PHOTOS), "IMG_$stamp.jpg")
                if (s.camShutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                flashOverlay = true
                launch { delay(120); flashOverlay = false }
                val (file, err) = engine.takePhotoAwait(f, cfg.front)
                busy = false
                if (file != null) { lastCapture = file; app.appScope.launch { finishPhoto(file, info) } } else shell.toast("Capture failed: $err")
            } else {
                // exposure bracketing: same framing at several EV offsets
                val base = st.exposureIndex
                val stepEv = st.exposureStep.takeIf { it > 0f } ?: (1f / 3f)
                val saved = mutableListOf<File>()
                for (ev in evs) {
                    val idx = (base + (ev / stepEv).roundToInt()).coerceIn(st.exposureMin, st.exposureMax)
                    engine.setExposureAwait(idx)
                    delay(380)
                    if (s.camShutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                    flashOverlay = true; launch { delay(90); flashOverlay = false }
                    val tag = (if (ev >= 0) "+" else "") + String.format(Locale.US, "%.0f", ev)
                    val (file, _) = engine.takePhotoAwait(File(app.storage.dir(s.activeProfile, Folder.PHOTOS), "IMG_${stamp}_EV$tag.jpg"), cfg.front)
                    file?.let { saved += it }
                }
                engine.setExposureAwait(base)
                busy = false
                saved.lastOrNull()?.let { lastCapture = it }
                app.appScope.launch { saved.forEach { finishPhoto(it, info.copy(caption = "Exposure bracket ${it.nameWithoutExtension.substringAfterLast('_')}")) } }
                shell.toast("Bracket saved · ${saved.size} exposures")
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize()
            .pointerInput(cfg) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f && cfg.mode != CamMode.PANO) engine.setZoom(engine.state.value.zoom * zoom) }
            }
            .pointerInput(cfg) {
                detectTapGestures(onTap = { o -> engine.focus(previewView, o.x, o.y); focusPoint = o; showExposure = cfg.mode != CamMode.PRO }, onDoubleTap = { if (cfg.mode != CamMode.PANO) cfg = cfg.copy(front = !cfg.front) })
            })

        // film texture preview for looks that have it (grain/vignette/leak are baked at capture)
        if (filterApplies && (filter.vignette > 0f || filter.lightLeak)) Canvas(Modifier.fillMaxSize()) {
            val ratio = if (cfg.aspect == "16:9" && cfg.mode != CamMode.PANO) 16f / 9f else 4f / 3f
            val frameH = minOf(size.height, size.width * ratio)
            val top = (size.height - frameH) / 2
            if (filter.lightLeak) drawRect(Brush.horizontalGradient(listOf(Color(0x66FF5A1F), Color(0x22FF9A3C), Color.Transparent), 0f, size.width * 0.45f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, frameH))
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = filter.vignette * 0.9f)), Offset(size.width / 2, top + frameH / 2), radius = kotlin.math.hypot(size.width, frameH) * 0.62f),
                Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, frameH))
        }

        if (grid && cfg.mode != CamMode.PANO) Canvas(Modifier.fillMaxSize()) {
            // Rule-of-thirds over the visible preview frame only (preview is letterboxed with FIT_CENTER)
            val ratio = if (cfg.aspect == "16:9" || cfg.mode == CamMode.VIDEO) 16f / 9f else 4f / 3f
            val frameH = minOf(size.height, size.width * ratio)
            val top = (size.height - frameH) / 2
            val c = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(c, Offset(size.width * i / 3, top), Offset(size.width * i / 3, top + frameH), 1.5f)
                drawLine(c, Offset(0f, top + frameH * i / 3), Offset(size.width, top + frameH * i / 3), 1.5f)
            }
            // horizon level: turns gold when the phone is level
            val level = abs(roll) < 1.2f
            val cx = size.width / 2; val cy = top + frameH / 2; val len = size.width * 0.16f
            val rad = Math.toRadians(-roll.toDouble()).toFloat()
            val dx = kotlin.math.cos(rad) * len; val dy = kotlin.math.sin(rad) * len
            drawLine(if (level) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.7f), Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), if (level) 4f else 2.5f)
        }
        focusPoint?.let { p ->
            val anim by animateFloatAsState(1f, label = "focus")
            Box(Modifier.offset { IntOffset((p.x - with(density) { 36.dp.toPx() }).roundToInt(), (p.y - with(density) { 36.dp.toPx() }).roundToInt()) }
                .size(72.dp * anim).border(2.dp, Color(0xFFFFD54F), CircleShape))
        }
        AnimatedVisibility(flashOverlay, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) }
        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))

        if (cfg.mode == CamMode.PANO) PanoGuide(pano, panoProcessing, Modifier.fillMaxSize())

        // ---------------- top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CamIcon(Icons.Default.Close, "Close") { shell.back() }
            Spacer(Modifier.width(12.dp))
            if (st.recording) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCCD32F2F)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(8.dp))
                    Text(com.blacklab.app.core.Time.duration(st.recordMs) + if (st.paused) " ❚❚" else "", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
            }
            if (cfg.mode == CamMode.VIDEO) {
                CamIcon(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, "Torch", active = torch) { torch = !torch; engine.setTorch(torch) }
            } else if (st.hasFlash && cfg.mode != CamMode.PANO) {
                CamIcon(when (cfg.flash) { ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn; ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto; else -> Icons.Default.FlashOff }, "Flash",
                    active = cfg.flash != ImageCapture.FLASH_MODE_OFF) {
                    val next = when (cfg.flash) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
                    cfg = cfg.copy(flash = next); engine.setFlash(next)
                }
            }
            if (cfg.mode != CamMode.VIDEO && cfg.mode != CamMode.PANO) CamIcon(Icons.Default.Timer, "Timer", label = if (timerSec > 0) "${timerSec}s" else null, active = timerSec > 0) {
                timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            }
            if (cfg.mode == CamMode.PHOTO || cfg.mode == CamMode.PRO) CamIcon(Icons.Default.Exposure, "Exposure bracketing",
                label = when (bracket) { 1 -> "±1"; 2 -> "±2"; 3 -> "×5"; else -> null }, active = bracket > 0) {
                bracket = (bracket + 1) % Brackets.size
                shell.toast(when (bracket) { 1 -> "Bracketing: 3 shots at −1, 0, +1 EV"; 2 -> "Bracketing: 3 shots at −2, 0, +2 EV"; 3 -> "Bracketing: 5 shots from −2 to +2 EV"; else -> "Bracketing off" })
            }
            if (filterApplies) CamIcon(Icons.Default.AutoAwesome, "Looks", active = !filter.isIdentity) { filterBar = !filterBar }
            CamIcon(if (grid) Icons.Default.GridOn else Icons.Default.GridOff, "Grid & level", active = grid) { grid = !grid; app.settings.update { putBoolean(Keys.CAM_GRID, grid) } }
            if (cfg.mode == CamMode.PHOTO) CamIcon(Icons.Default.AspectRatio, "Aspect", label = cfg.aspect) {
                val a = if (cfg.aspect == "4:3") "16:9" else "4:3"; cfg = cfg.copy(aspect = a); app.settings.update { putString(Keys.CAM_ASPECT, a) }
            }
            if (s.camGeotag) CamIcon(if (locOk && location.latest != null) Icons.Default.LocationOn else Icons.Default.LocationOff, "Location", active = locOk && location.latest != null) {
                shell.toast(location.latest?.let { "Tagging photos at ${it.label}" } ?: if (locOk) "Waiting for a location fix…" else "Location permission is off — photos won't be geotagged")
            }
            CamIcon(Icons.Default.Tune, "Settings") { settingsSheet = true }
        }

        // ---------------- bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))).navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (showExposure && st.exposureMax > st.exposureMin && cfg.mode != CamMode.PRO) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Exposure, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Slider(
                        value = st.exposureIndex.toFloat(), onValueChange = { engine.setExposure(it.roundToInt()) },
                        valueRange = st.exposureMin.toFloat()..st.exposureMax.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(String.format(Locale.US, "%+.1f", st.exposureIndex * st.exposureStep), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            AnimatedVisibility(filterBar && filterApplies, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                FilterStrip(filter, lastCapture) { f -> filter = f; app.settings.update { putString(Keys.CAM_FILTER, f.name) } }
            }
            if (cfg.mode == CamMode.PRO) ProPanel(st, manual, proDial, onDial = { proDial = if (proDial == it) null else it }) { m -> manual = m; engine.setManual(m) }
            // status line
            val status = listOfNotNull(
                st.resolution.takeIf { it.isNotBlank() },
                if (st.ultraHdrActive && cfg.mode != CamMode.VIDEO && filter.isIdentity && !s.camInfoStamp) "Ultra HDR" else null,
                if (st.hdrVideoActive) "HDR10 HLG" else null,
                if (st.stabilizationActive) "Stabilized" else null,
                if (!filter.isIdentity && filterApplies) filter.label else null,
                if (s.camGeotag && location.latest != null && cfg.mode != CamMode.VIDEO) "GPS" else null,
            ).joinToString(" · ")
            if (status.isNotBlank()) Text(status, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
            st.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelSmall) }
            // zoom presets
            if (cfg.mode != CamMode.PANO) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                val presets = buildList {
                    if (st.minZoom < 0.95f) add(st.minZoom)
                    add(1f); if (st.maxZoom >= 2f) add(2f); if (st.maxZoom >= 5f) add(5f); if (st.maxZoom >= 10f) add(10f); if (st.maxZoom >= 20f) add(20f)
                }
                presets.forEach { z ->
                    val sel = abs(st.zoom - z) < 0.05f
                    Box(Modifier.size(if (sel) 44.dp else 36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape).clickable { engine.setZoom(z) }, contentAlignment = Alignment.Center) {
                        Text(if (sel) String.format(Locale.US, "%.1f×", st.zoom).replace(".0×", "×") else fmtZoom(z), color = if (sel) Color(0xFFFFD54F) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (presets.none { abs(st.zoom - it) < 0.05f }) Text(String.format(Locale.US, "%.1f×", st.zoom), color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
            }
            // modes
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                st.modes.forEach { m ->
                    val sel = cfg.mode == m
                    Text(m.label.uppercase(), color = if (sel) Color(0xFFFFD54F) else Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !st.recording && !pano.running) { torch = false; cfg = cfg.copy(mode = m) }
                            .background(if (sel) Color.White.copy(alpha = 0.15f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Text("SCAN DOC", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onScanDoc).padding(horizontal = 12.dp, vertical = 6.dp))
                Text("QR / BARCODE", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onScanCode).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // shutter row
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(Color.DarkGray).border(2.dp, Color.White, RoundedCornerShape(16.dp))
                    .clickable { lastCapture?.let { shell.go("media?path=" + Uri.encode(app.storage.relative(it))) } }) {
                    lastCapture?.let { f ->
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(f).memoryCacheKey(f.path + f.lastModified()).apply { if (f.extension == "mp4") decoderFactory(VideoFrameDecoder.Factory()) }.crossfade(true).build(),
                            contentDescription = "Last capture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val video = cfg.mode == CamMode.VIDEO
                val panoMode = cfg.mode == CamMode.PANO
                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (video || (panoMode && pano.running)) Color(0xFFE53935) else Color.White).clickable(enabled = st.ready) { capture() },
                    contentAlignment = Alignment.Center,
                ) {
                    if ((video && st.recording) || (panoMode && pano.running)) Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                    else if (panoMode) Icon(Icons.Default.Panorama, null, tint = Color.Black, modifier = Modifier.size(30.dp))
                    if (busy) Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Gray))
                }
                if (st.recording) {
                    CamIcon(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause", big = true) { if (st.paused) engine.resumeRecording() else engine.pauseRecording() }
                } else if (panoMode) {
                    Spacer(Modifier.size(56.dp))
                } else {
                    CamIcon(Icons.Default.Cameraswitch, "Switch camera", big = true) { torch = false; cfg = cfg.copy(front = !cfg.front) }
                }
            }
        }

        panoResult?.let { bmp ->
            PanoramaReview(bmp, onDiscard = { panoResult = null }) { saved ->
                panoResult = null
                saved.firstOrNull()?.let { lastCapture = it }
                app.appScope.launch { saved.forEach { PhotoMeta.write(it, photoInfo().copy(caption = "Panorama"), s.camInfoTags); app.storage.scan(it) } }
                shell.toast(if (saved.size > 1) "Panorama + tiny planet saved to Photos" else "Panorama saved to Photos")
            }
        }
    }

    if (settingsSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { settingsSheet = false }, sheetState = sheet) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Camera settings", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text("Photo information", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                LabeledSwitchRow("Geotag photos", if (locOk) "GPS location saved in every picture" else "Needs location permission", s.camGeotag) { v -> app.settings.update { putBoolean(Keys.CAM_GEOTAG, v) } }
                LabeledSwitchRow("Photo details", "Street address, compass heading, altitude, author & copyright", s.camInfoTags) { v -> app.settings.update { putBoolean(Keys.CAM_INFO_TAGS, v) } }
                LabeledSwitchRow("Info stamp on picture", "Burn date, address & GPS into the image (inspections)", s.camInfoStamp) { v -> app.settings.update { putBoolean(Keys.CAM_INFO_STAMP, v) } }
                LabeledSwitchRow("1970s date imprint", "Orange film-camera date on the 1970s look", s.camVintageDate) { v -> app.settings.update { putBoolean(Keys.CAM_VINTAGE_DATE, v) } }
                Text("Capture", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                LabeledSwitchRow("Ultra HDR photos", "Brighter highlights on HDR displays (Pixel)", cfg.ultraHdr) { v -> cfg = cfg.copy(ultraHdr = v); app.settings.update { putBoolean(Keys.CAM_ULTRA_HDR, v) } }
                LabeledSwitchRow("Maximum resolution", "Full-sensor capture (up to 50 MP). Slower.", cfg.maxRes) { v -> cfg = cfg.copy(maxRes = v); app.settings.update { putBoolean(Keys.CAM_MAX_RES, v) } }
                LabeledSwitchRow("Shutter sound", null, s.camShutterSound) { v -> app.settings.update { putBoolean(Keys.CAM_SHUTTER, v) } }
                Text("Panorama quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(!cfg.panoUltra, { cfg = cfg.copy(panoUltra = false); app.settings.update { putString(Keys.CAM_PANO_QUALITY, "HIGH") } }, label = { Text("High · ~3,300 px tall") })
                    FilterChip(cfg.panoUltra, { cfg = cfg.copy(panoUltra = true); app.settings.update { putString(Keys.CAM_PANO_QUALITY, "ULTRA") } }, label = { Text("Ultra · ~4,000 px tall") })
                }
                Text("Video quality", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (st.qualities.ifEmpty { listOf("UHD", "FHD", "HD") }).forEach { q ->
                        FilterChip(cfg.quality == q, { cfg = cfg.copy(quality = q); app.settings.update { putString(Keys.CAM_VIDEO_QUALITY, q) } },
                            label = { Text(when (q) { "UHD" -> "4K"; "FHD" -> "1080p"; "HD" -> "720p"; else -> "480p" }) })
                    }
                }
                Text("Frame rate", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.fpsOptions.forEach { f -> FilterChip(cfg.fps == f, { cfg = cfg.copy(fps = f); app.settings.update { putInt(Keys.CAM_FPS, f) } }, label = { Text("$f fps") }) }
                }
                LabeledSwitchRow("Video stabilization", null, cfg.stabilization) { v -> cfg = cfg.copy(stabilization = v); app.settings.update { putBoolean(Keys.CAM_STABILIZATION, v) } }
                LabeledSwitchRow("10-bit HDR video (HLG)", if (st.hdrVideoSupported || cfg.mode != CamMode.VIDEO) "Vivid HDR video for HDR displays" else "Not supported on this camera", cfg.hdrVideo) { v ->
                    cfg = cfg.copy(hdrVideo = v); app.settings.update { putBoolean(Keys.CAM_HDR_VIDEO, v) }
                }
                Text("Photos save to BlackLab/${app.settings.profileName(s.activeProfile)}/Photos · Videos to …/Videos", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** Horizontal strip of looks, each previewed on the last photo. */
@Composable
private fun FilterStrip(selected: PhotoFilter, sample: File?, onPick: (PhotoFilter) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhotoFilter.entries.forEach { f ->
            val sel = f == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onPick(f) }.padding(2.dp)) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(Color.DarkGray).border(if (sel) 2.dp else 0.dp, Color(0xFFFFD54F), RoundedCornerShape(14.dp))) {
                    if (sample != null && sample.extension != "mp4") AsyncImage(
                        ImageRequest.Builder(ctx).data(sample).size(160).build(), f.label, contentScale = ContentScale.Crop,
                        colorFilter = if (f.isIdentity) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(f.composeMatrix()), modifier = Modifier.fillMaxSize(),
                    ) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF4F8CFF), Color(0xFFFFB74D)))))
                }
                Text(f.label, color = if (sel) Color(0xFFFFD54F) else Color.White, fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Pro controls: ISO, shutter speed, manual focus and white balance. */
@Composable
private fun ProPanel(st: CamState, m: ManualParams, dial: String?, onDial: (String) -> Unit, onChange: (ManualParams) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (dial) {
            "ISO" -> st.isoRange?.let { r ->
                val lo = ln(r.first.toFloat()); val hi = ln(r.last.toFloat())
                DialSlider(m.iso?.let { (ln(it.toFloat()) - lo) / (hi - lo) }, "ISO ${m.iso ?: "auto"}", onAuto = { onChange(m.copy(iso = null)) }) { t ->
                    onChange(m.copy(iso = (exp(lo + t * (hi - lo)) / 10f).roundToInt().times(10).coerceIn(r.first, r.last)))
                }
            }
            "S" -> st.shutterRangeNs?.let { r ->
                val speeds = ShutterSpeeds.filter { it in r }
                if (speeds.isNotEmpty()) DialSlider(m.shutterNs?.let { speeds.indexOf(it).coerceAtLeast(0).toFloat() / (speeds.size - 1).coerceAtLeast(1) },
                    "Shutter ${m.shutterNs?.let(::shutterLabel) ?: "auto"}" + if ((m.shutterNs ?: 0) >= 250_000_000L) " · use a tripod" else "", onAuto = { onChange(m.copy(shutterNs = null)) }) { t ->
                    onChange(m.copy(shutterNs = speeds[(t * (speeds.size - 1)).roundToInt()]))
                }
            }
            "F" -> if (st.minFocusDiopters > 0f) DialSlider(m.focusDiopters?.let { it / st.minFocusDiopters },
                "Focus ${m.focusDiopters?.let { if (it < 0.05f) "∞" else String.format(Locale.US, "%.2f m", 1f / it) } ?: "auto"}", onAuto = { onChange(m.copy(focusDiopters = null)) }) { t ->
                onChange(m.copy(focusDiopters = t * st.minFocusDiopters))
            }
            "WB" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WbModes.filter { it.first in st.awbModes || it.first == CaptureRequest.CONTROL_AWB_MODE_AUTO }.forEach { (mode, label) ->
                    val sel = m.awbMode == mode
                    Text(label, color = if (sel) Color.Black else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.14f)).clickable { onChange(m.copy(awbMode = mode)) }.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                "ISO" to (m.iso?.toString() ?: "AUTO"),
                "S" to (m.shutterNs?.let(::shutterLabel) ?: "AUTO"),
                "F" to (m.focusDiopters?.let { if (it < 0.05f) "∞" else String.format(Locale.US, "%.1fm", 1f / it) } ?: "AF"),
                "WB" to (WbModes.firstOrNull { it.first == m.awbMode }?.second?.uppercase() ?: "AUTO"),
            ).forEach { (k, v) ->
                val sel = dial == k
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDial(k) }
                    .background(if (sel) Color.White.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(k, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(v, color = if (v == "AUTO" || v == "AF") Color.White else Color(0xFFFFD54F), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DialSlider(value: Float?, label: String, onAuto: () -> Unit, onValue: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("AUTO", color = if (value == null) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (value == null) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.14f)).clickable(onClick = onAuto).padding(horizontal = 10.dp, vertical = 5.dp))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Slider(value ?: 0.5f, { onValue(it) })
        }
    }
}

/** On-screen guidance while sweeping a panorama. */
@Composable
private fun PanoGuide(p: com.blacklab.app.imaging.PanoProgress, processing: Boolean, modifier: Modifier) {
    Box(modifier) {
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(12.dp))) {
                p.preview?.let { b ->
                    // the stitcher works in sweep order; mirror for right-to-left sweeps so it reads naturally
                    Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.fillMaxSize().graphicsLayerFlip(p.direction < 0))
                }
                if (!p.started) Text(if (p.running) "Move steadily left or right" else "Tap the shutter, then sweep slowly", color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.Center))
            }
            val msg = when {
                processing -> "Stitching panorama…"
                p.tooFast -> "Slow down"
                abs(p.drift) > 0.55f -> if (p.drift > 0) "Tilt up — keep the arrow on the line" else "Tilt down — keep the arrow on the line"
                abs(p.tilt) > 8f && p.running -> "Hold the phone upright"
                p.started -> "${p.degrees.roundToInt()}° · keep going or tap to finish"
                else -> null
            }
            msg?.let {
                Text(it, color = if (p.tooFast || abs(p.drift) > 0.55f) Color(0xFFFFD54F) else Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 6.dp))
            }
        }
        // centre guide line + arrow that rides the vertical drift
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2
            drawLine(Color.White.copy(alpha = 0.6f), Offset(size.width * 0.08f, cy), Offset(size.width * 0.92f, cy), 2.5f)
        }
        if (p.running || !p.started) {
            val off by animateFloatAsState(p.drift.coerceIn(-1.5f, 1.5f) * 140f, tween(120), label = "drift")
            Icon(if (p.direction < 0) Icons.AutoMirrored.Filled.ArrowBack else Icons.AutoMirrored.Filled.ArrowForward, null,
                tint = if (abs(p.drift) > 0.55f || p.tooFast) Color(0xFFFFD54F) else Color.White,
                modifier = Modifier.align(Alignment.Center).offset(y = off.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).padding(6.dp))
        }
    }
}

private fun Modifier.graphicsLayerFlip(flip: Boolean) = if (flip) this.then(Modifier.graphicsLayer(scaleX = -1f)) else this

private fun fmtZoom(z: Float): String = if (z < 1f) String.format(Locale.US, "%.1f", z).removePrefix("0") else "${z.roundToInt()}×"

@Composable
private fun CamIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean = false, label: String? = null, big: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(2.dp).size(if (big) 56.dp else 42.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (big) 0.45f else 0.3f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, color = if (active) Color(0xFFFFD54F) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        else Icon(icon, desc, tint = if (active) Color(0xFFFFD54F) else Color.White, modifier = Modifier.size(if (big) 28.dp else 22.dp))
    }
}
