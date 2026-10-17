package com.blacklab.app.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
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
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import com.blacklab.app.core.BlackLabStorage
import com.blacklab.app.core.Folder
import com.blacklab.app.core.Keys
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
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
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        camOk = granted(Manifest.permission.CAMERA); micOk = granted(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(Unit) { if (!camOk || !micOk) perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }

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
                Button(onClick = { perms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Allow camera") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { shell.back() }) { Text("Back") }
            }
        } else {
            CameraContent(initialMode, micOk, onScanDoc = docScanner, onScanCode = codeScanner)
        }
    }
    scanResult?.let { r -> ScanDoneDialog(r) { scanResult = null } }
    code?.let { c -> CodeResultDialog(c) { code = null } }
}

@Composable
private fun CameraContent(initialMode: String, micOk: Boolean, onScanDoc: () -> Unit, onScanCode: () -> Unit) {
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
    DisposableEffect(Unit) { onDispose { engine.stopRecording(); engine.unbind(); sound.release() } }

    var cfg by remember {
        mutableStateOf(CamConfig(
            mode = if (initialMode == "video") CamMode.VIDEO else CamMode.PHOTO,
            aspect = s.camAspect, ultraHdr = s.camUltraHdr, maxRes = s.camMaxResolution, quality = s.camVideoQuality,
            fps = s.camVideoFps, stabilization = s.camStabilization, hdrVideo = s.camHdrVideo,
        ))
    }
    var initialized by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; keepScreenOn = true } }
    LaunchedEffect(Unit) { engine.init(); initialized = true }
    LaunchedEffect(initialized, cfg) { if (initialized) engine.bind(owner, previewView, cfg) }

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

    LaunchedEffect(Unit) {
        // show the latest photo/video thumbnail
        val dirs = listOf(app.storage.dir(s.activeProfile, Folder.PHOTOS), app.storage.dir(s.activeProfile, Folder.VIDEOS))
        lastCapture = dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }.filter { it.isFile }.maxByOrNull { it.lastModified() }
    }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(3500); focusPoint = null; showExposure = false } }

    fun capture() {
        if (busy) return
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
            val f = File(app.storage.dir(s.activeProfile, Folder.PHOTOS), "IMG_${BlackLabStorage.timestamp()}.jpg")
            if (s.camShutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flashOverlay = true
            engine.takePhoto(f, cfg.front) { file, err ->
                busy = false
                if (file != null) { app.storage.scan(file); lastCapture = file } else shell.toast("Capture failed: $err")
            }
            delay(120); flashOverlay = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ previewView }, Modifier.fillMaxSize()
            .pointerInput(cfg) {
                detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) engine.setZoom(engine.state.value.zoom * zoom) }
            }
            .pointerInput(cfg) {
                detectTapGestures(onTap = { o -> engine.focus(previewView, o.x, o.y); focusPoint = o; showExposure = true }, onDoubleTap = { cfg = cfg.copy(front = !cfg.front) })
            })

        if (grid) Canvas(Modifier.fillMaxSize()) {
            // Rule-of-thirds over the visible preview frame only (preview is letterboxed with FIT_CENTER)
            val ratio = if (cfg.aspect == "16:9" || cfg.mode == CamMode.VIDEO) 16f / 9f else 4f / 3f
            val frameH = minOf(size.height, size.width * ratio)
            val top = (size.height - frameH) / 2
            val c = Color.White.copy(alpha = 0.35f)
            for (i in 1..2) {
                drawLine(c, Offset(size.width * i / 3, top), Offset(size.width * i / 3, top + frameH), 1.5f)
                drawLine(c, Offset(0f, top + frameH * i / 3), Offset(size.width, top + frameH * i / 3), 1.5f)
            }
        }
        focusPoint?.let { p ->
            val anim by animateFloatAsState(1f, label = "focus")
            Box(Modifier.offset { IntOffset((p.x - with(density) { 36.dp.toPx() }).roundToInt(), (p.y - with(density) { 36.dp.toPx() }).roundToInt()) }
                .size(72.dp * anim).border(2.dp, Color(0xFFFFD54F), CircleShape))
        }
        AnimatedVisibility(flashOverlay, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))) }
        if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center))

        // ---------------- top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CamIcon(Icons.Default.Close, "Close") { shell.back() }
            Spacer(Modifier.weight(1f))
            if (st.recording) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xCCD32F2F)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(8.dp))
                    Text(com.blacklab.app.core.Time.duration(st.recordMs) + if (st.paused) " ❚❚" else "", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.weight(1f))
            }
            if (cfg.mode == CamMode.VIDEO) {
                CamIcon(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, "Torch", active = torch) { torch = !torch; engine.setTorch(torch) }
            } else if (st.hasFlash) {
                CamIcon(when (cfg.flash) { ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn; ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto; else -> Icons.Default.FlashOff }, "Flash",
                    active = cfg.flash != ImageCapture.FLASH_MODE_OFF) {
                    val next = when (cfg.flash) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
                    cfg = cfg.copy(flash = next); engine.setFlash(next)
                }
            }
            if (cfg.mode != CamMode.VIDEO) CamIcon(Icons.Default.Timer, "Timer", label = if (timerSec > 0) "${timerSec}s" else null, active = timerSec > 0) {
                timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            }
            CamIcon(if (grid) Icons.Default.GridOn else Icons.Default.GridOff, "Grid", active = grid) { grid = !grid; app.settings.update { putBoolean(Keys.CAM_GRID, grid) } }
            if (cfg.mode == CamMode.PHOTO) CamIcon(Icons.Default.AspectRatio, "Aspect", label = cfg.aspect) {
                val a = if (cfg.aspect == "4:3") "16:9" else "4:3"; cfg = cfg.copy(aspect = a); app.settings.update { putString(Keys.CAM_ASPECT, a) }
            }
            CamIcon(Icons.Default.Tune, "Settings") { settingsSheet = true }
        }

        // ---------------- bottom controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (showExposure && st.exposureMax > st.exposureMin) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Exposure, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Slider(
                        value = st.exposureIndex.toFloat(), onValueChange = { engine.setExposure(it.roundToInt()) },
                        valueRange = st.exposureMin.toFloat()..st.exposureMax.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(String.format(Locale.US, "%+.1f", st.exposureIndex * st.exposureStep), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
            // status line
            val status = listOfNotNull(
                st.resolution.takeIf { it.isNotBlank() },
                if (st.ultraHdrActive && cfg.mode != CamMode.VIDEO) "Ultra HDR" else null,
                if (st.hdrVideoActive) "HDR10 HLG" else null,
                if (st.stabilizationActive) "Stabilized" else null,
            ).joinToString(" · ")
            if (status.isNotBlank()) Text(status, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
            st.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelSmall) }
            // zoom presets
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                val presets = buildList {
                    if (st.minZoom < 0.95f) add(st.minZoom)
                    add(1f); if (st.maxZoom >= 2f) add(2f); if (st.maxZoom >= 5f) add(5f); if (st.maxZoom >= 10f) add(10f); if (st.maxZoom >= 20f) add(20f)
                }
                presets.forEach { z ->
                    val sel = kotlin.math.abs(st.zoom - z) < 0.05f
                    Box(Modifier.size(if (sel) 44.dp else 36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape).clickable { engine.setZoom(z) }, contentAlignment = Alignment.Center) {
                        Text(if (sel) String.format(Locale.US, "%.1f×", st.zoom).replace(".0×", "×") else fmtZoom(z), color = if (sel) Color(0xFFFFD54F) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (presets.none { kotlin.math.abs(st.zoom - it) < 0.05f }) Text(String.format(Locale.US, "%.1f×", st.zoom), color = Color(0xFFFFD54F), fontWeight = FontWeight.Bold)
            }
            // modes
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                st.modes.forEach { m ->
                    val sel = cfg.mode == m
                    Text(m.label.uppercase(), color = if (sel) Color(0xFFFFD54F) else Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !st.recording) { torch = false; cfg = cfg.copy(mode = m) }
                            .background(if (sel) Color.White.copy(alpha = 0.15f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                Text("SCAN DOC", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onScanDoc).padding(horizontal = 12.dp, vertical = 6.dp))
                Text("QR / BARCODE", color = Color.White, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onScanCode).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            // shutter row
            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.DarkGray).border(2.dp, Color.White, RoundedCornerShape(14.dp))
                    .clickable { lastCapture?.let { shell.go("media?path=" + Uri.encode(app.storage.relative(it))) } }) {
                    lastCapture?.let { f ->
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(f).apply { if (f.extension == "mp4") decoderFactory(VideoFrameDecoder.Factory()) }.crossfade(true).build(),
                            contentDescription = "Last capture", contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                val video = cfg.mode == CamMode.VIDEO
                Box(
                    Modifier.size(80.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
                        .background(if (video) Color(0xFFE53935) else Color.White).clickable(enabled = st.ready) { capture() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (video && st.recording) Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(Color.White))
                    if (busy) Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Gray))
                }
                if (st.recording) {
                    CamIcon(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Pause", big = true) { if (st.paused) engine.resumeRecording() else engine.pauseRecording() }
                } else {
                    CamIcon(Icons.Default.Cameraswitch, "Switch camera", big = true) { torch = false; cfg = cfg.copy(front = !cfg.front) }
                }
            }
        }
    }

    if (settingsSheet) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { settingsSheet = false }, sheetState = sheet) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("Camera settings", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                LabeledSwitchRow("Ultra HDR photos", "Brighter highlights on HDR displays (Pixel)", cfg.ultraHdr) { v -> cfg = cfg.copy(ultraHdr = v); app.settings.update { putBoolean(Keys.CAM_ULTRA_HDR, v) } }
                LabeledSwitchRow("Maximum resolution", "Full-sensor capture (up to 50 MP). Slower.", cfg.maxRes) { v -> cfg = cfg.copy(maxRes = v); app.settings.update { putBoolean(Keys.CAM_MAX_RES, v) } }
                LabeledSwitchRow("Shutter sound", null, s.camShutterSound) { v -> app.settings.update { putBoolean(Keys.CAM_SHUTTER, v) } }
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

@Suppress("unused")
@Composable
private fun IconBtn(onClick: () -> Unit) = IconButton(onClick) {}
