package com.blacklab.app.ui.viewer

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.blacklab.app.files.PptxRender
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.findActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A WebView for our own generated slide HTML: JavaScript for scaling/navigation, no files, no network. */
@SuppressLint("SetJavaScriptEnabled")
private fun slideWebView(ctx: Context, html: String): WebView = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.blockNetworkLoads = true
    setBackgroundColor(android.graphics.Color.BLACK)
    isVerticalScrollBarEnabled = false
    loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
}

/**
 * PowerPoint decks as they look in PowerPoint (see [PptxRender]): scroll through every slide, read the speaker notes,
 * and Present — full screen, tap/swipe or a Bluetooth clicker to advance, presenter view with notes, timer and the
 * next slide, and slides on a TV/projector (HDMI or cast screen) while the phone shows your notes.
 */
@Composable
fun SlidesScreen(file: File) {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    val dark = com.blacklab.app.ui.theme.LocalBlColors.current.isDark
    val deck by produceState<Result<PptxRender.Deck>?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { PptxRender.render(file) } } }
    var presenting by remember { mutableStateOf<Int?>(null) }
    var notesOpen by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val scope = rememberCoroutineScope()

    presenting?.let { start -> deck?.getOrNull()?.let { d -> PresentMode(d, start) { presenting = null }; return } }

    fun currentSlide(then: (Int) -> Unit) {
        val w = web ?: return then(0)
        w.evaluateJavascript("visible()") { r -> then(r?.toIntOrNull() ?: 0) }
    }

    Scaffold(topBar = {
        BlTopBar(file.nameWithoutExtension, subtitle = deck?.getOrNull()?.let { d -> "${d.slides.size} slides" + if (d.slides.any { it.notes.isNotBlank() }) " · speaker notes" else "" } ?: "Presentation",
            onBack = { shell.back() }, actions = {
                IconButton(onClick = { notesOpen = !notesOpen }, enabled = deck?.getOrNull() != null) { Icon(if (notesOpen) Icons.Rounded.SpeakerNotesOff else Icons.Rounded.SpeakerNotes, "Speaker notes") }
                IconButton(onClick = { shell.toast(com.blacklab.app.office.MicrosoftApps.openInMicrosoft(ctx, file)) }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in PowerPoint") }
                FilledTonalButton(onClick = { currentSlide { presenting = it } }, enabled = deck?.getOrNull() != null, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Rounded.Slideshow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Present")
                }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val d = deck) {
                null -> com.blacklab.app.ui.components.SkeletonPage()
                else -> d.fold(onSuccess = { dk ->
                    val html = remember(dk, dark) { PptxRender.page(dk, present = false, dark = dark) }
                    Column(Modifier.fillMaxSize()) {
                        AndroidView({ c -> slideWebView(c, html).also { it.setBackgroundColor(if (dark) 0xFF141414.toInt() else 0xFFEDEDED.toInt()); web = it } }, Modifier.weight(1f).fillMaxWidth())
                        AnimatedVisibility(notesOpen) { NotesStrip(dk) { i -> web?.evaluateJavascript("show($i)", null) } }
                    }
                }, onFailure = { e -> Text("Couldn't open this presentation: ${e.message}", Modifier.padding(16.dp)) })
            }
        }
    }
}

@Composable
private fun NotesStrip(deck: PptxRender.Deck, onJump: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().fillMaxHeight(0.42f)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
            deck.slides.forEach { s ->
                Surface(onClick = { onJump(s.number - 1) }, shape = RoundedCornerShape(8.dp), color = Color.Transparent) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("${s.number}. ${s.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.notes.ifBlank { "No speaker notes" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Slides on a second screen (TV, projector, cast display) while the phone runs presenter view. */
private class SlidePresentation(ctx: Context, display: Display, private val html: String) : Presentation(ctx, display) {
    var web: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = slideWebView(context, html)
        setContentView(web!!)
    }
    fun show(i: Int) { web?.evaluateJavascript("show($i)", null) }
}

@Composable
private fun PresentMode(deck: PptxRender.Deck, start: Int, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val activity = remember { ctx.findActivity() }
    val html = remember(deck) { PptxRender.page(deck, present = true, dark = true) }
    var cur by remember { mutableIntStateOf(start.coerceIn(0, deck.slides.lastIndex.coerceAtLeast(0))) }
    var controls by remember { mutableStateOf(true) }
    var presenterView by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var external by remember { mutableStateOf<SlidePresentation?>(null) }
    val startedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    fun go(i: Int) { cur = i.coerceIn(0, deck.slides.lastIndex); web?.evaluateJavascript("show($cur)", null); external?.show(cur) }

    // Full screen, landscape, screen kept on — all restored on exit
    DisposableEffect(Unit) {
        val w = activity?.window
        val oldOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ic = w?.let { WindowCompat.getInsetsController(it, it.decorView) }
        ic?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ic?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ic?.show(WindowInsetsCompat.Type.systemBars())
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.requestedOrientation = oldOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // A TV/projector (HDMI, USB-C, or a cast screen): slides go there, the phone switches to presenter view
    DisposableEffect(Unit) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        fun attach() {
            val d = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
            if (d != null && external?.display?.displayId != d.displayId) {
                external?.dismiss()
                external = runCatching { SlidePresentation(activity ?: ctx, d, html).also { it.show(); scope.launch { delay(600); it.show(cur) } } }.getOrNull()
                presenterView = external != null
            } else if (d == null && external != null) { external?.dismiss(); external = null }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = attach()
            override fun onDisplayRemoved(id: Int) = attach()
            override fun onDisplayChanged(id: Int) {}
        }
        dm.registerDisplayListener(listener, null)
        attach()
        onDispose { dm.unregisterDisplayListener(listener); external?.dismiss(); external = null }
    }
    LaunchedEffect(Unit) { focus.requestFocus(); while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(controls, cur) { if (controls && !presenterView) { delay(3500); controls = false } }
    BackHandler { onExit() }

    val elapsed = (now - startedAt) / 1000
    val clock = "%d:%02d".format(elapsed / 60, elapsed % 60)
    val slide = deck.slides.getOrNull(cur)

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable().onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (e.key) {
            Key.DirectionRight, Key.DirectionDown, Key.PageDown, Key.Spacebar, Key.Enter, Key.N, Key.VolumeDown -> { go(cur + 1); true }
            Key.DirectionLeft, Key.DirectionUp, Key.PageUp, Key.P, Key.VolumeUp -> { go(cur - 1); true }
            Key.Escape -> { onExit(); true }
            else -> false
        }
    }) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(if (presenterView) 0.58f else 1f).fillMaxHeight()) {
                AndroidView({ c -> slideWebView(c, html).also { web = it; it.postDelayed({ it.evaluateJavascript("show($cur)", null) }, 400) } }, Modifier.fillMaxSize())
                // touch layer over the slide: tap right/left to move, swipe, long-press for controls
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    var dx = 0f
                    detectHorizontalDragGestures(onDragStart = { dx = 0f }, onDragEnd = { if (dx < -60) go(cur + 1) else if (dx > 60) go(cur - 1) }) { _, d -> dx += d }
                }.pointerInput(Unit) {
                    detectTapGestures(onTap = { o -> if (o.x < size.width / 3f) go(cur - 1) else if (o.x > size.width * 2 / 3f) go(cur + 1) else controls = !controls },
                        onLongPress = { controls = true })
                })
                androidx.compose.animation.AnimatedVisibility(controls && !presenterView, enter = fadeIn(), exit = fadeOut()) {
                    Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "End show", tint = Color.White) }
                        Text("${cur + 1} / ${deck.slides.size}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(clock, color = Color.White, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { presenterView = true }) { Icon(Icons.Rounded.SpeakerNotes, "Presenter view", tint = Color.White) }
                    }
                }
            }
            if (presenterView) PresenterPanel(deck, cur, clock, external != null, Modifier.weight(0.42f).fillMaxHeight(),
                onPrev = { go(cur - 1) }, onNext = { go(cur + 1) }, onHide = { presenterView = false }, onExit = onExit)
        }
    }
}

@Composable
private fun PresenterPanel(deck: PptxRender.Deck, cur: Int, clock: String, onTv: Boolean, modifier: Modifier, onPrev: () -> Unit, onNext: () -> Unit, onHide: () -> Unit, onExit: () -> Unit) {
    val s = deck.slides.getOrNull(cur)
    val next = deck.slides.getOrNull(cur + 1)
    Column(modifier.background(Color(0xFF1C1C1C)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${cur + 1} / ${deck.slides.size}", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Timer, null, tint = Color(0xFFB0B0B0), modifier = Modifier.size(18.dp))
            Text(" $clock", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (onTv) Icon(Icons.Rounded.Tv, "Showing on the big screen", tint = Color(0xFF7FBA00), modifier = Modifier.size(18.dp))
            if (!onTv) IconButton(onClick = onHide) { Icon(Icons.Rounded.Fullscreen, "Slide only", tint = Color.White) }
            IconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "End show", tint = Color.White) }
        }
        Text(s?.title.orEmpty(), color = Color(0xFFDADADA), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Column(Modifier.weight(1f).padding(vertical = 8.dp).verticalScroll(rememberScrollState())) {
            Text(s?.notes?.ifBlank { "No speaker notes for this slide." } ?: "", color = Color.White, fontSize = 19.sp, lineHeight = 27.sp)
        }
        Text(if (next != null) "Next: ${next.title}" else "Last slide", color = Color(0xFFB0B0B0), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onPrev, enabled = cur > 0, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null); Spacer(Modifier.width(6.dp)); Text("Back") }
            FilledTonalButton(onClick = onNext, enabled = next != null, modifier = Modifier.weight(1.4f)) { Text("Next"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.ArrowForward, null) }
        }
    }
}
