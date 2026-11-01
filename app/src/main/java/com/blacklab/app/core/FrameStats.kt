package com.blacklab.app.core

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentHashMap

/**
 * Smoothness meter for this session: counts rendered frames per screen and how many missed the
 * display's frame deadline (by more than half a frame). Kept in memory only and shown in
 * Settings › Problem reports, so slow screens can be found and fixed.
 */
object FrameStats {
    data class Screen(val route: String, val frames: Long, val janky: Long) {
        val smoothPct: Double get() = if (frames == 0L) 100.0 else 100.0 * (frames - janky) / frames
    }

    private class Counter { @Volatile var frames = 0L; @Volatile var janky = 0L }
    private val byRoute = ConcurrentHashMap<String, Counter>()
    @Volatile var route: String = "start"
    private var thread: HandlerThread? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    // ----------------------------------------------------------------- pure (unit tested)

    /** A frame is late when its work took longer than 1.5 × its deadline (the display's frame budget). */
    fun isLate(workNanos: Long, deadlineNanos: Long): Boolean = workNanos > deadlineNanos * 1.5

    fun isJanky(totalNanos: Long, refreshHz: Float): Boolean = isLate(totalNanos, (1_000_000_000.0 / refreshHz.coerceAtLeast(30f)).toLong())

    fun record(route: String, totalNanos: Long, refreshHz: Float) = recordFrame(route, totalNanos, (1_000_000_000.0 / refreshHz.coerceAtLeast(30f)).toLong())

    fun recordFrame(route: String, workNanos: Long, deadlineNanos: Long) {
        val c = byRoute.getOrPut(route) { Counter() }
        synchronized(c) { c.frames++; if (isLate(workNanos, deadlineNanos)) c.janky++ }
    }

    fun snapshot(): List<Screen> = byRoute.entries.map { (r, c) -> Screen(r, c.frames, c.janky) }.filter { it.frames >= 30 }.sortedBy { it.smoothPct }

    fun overall(): Screen = byRoute.values.fold(Screen("all", 0, 0)) { a, c -> Screen("all", a.frames + c.frames, a.janky + c.janky) }

    fun reset() = byRoute.clear()

    // ----------------------------------------------------------------- device

    fun attach(activity: Activity) {
        if (listener != null) return
        val t = HandlerThread("frame-stats").also { it.start() }
        thread = t
        val l = Window.OnFrameMetricsAvailableListener { w, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val hz = w.decorView.display?.refreshRate ?: 60f
            // Work = total minus the idle wait before the frame began; deadline = what the display allowed this frame
            val work = metrics.getMetric(FrameMetrics.TOTAL_DURATION) - metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)
            val deadline = metrics.getMetric(FrameMetrics.DEADLINE).takeIf { it > 0 } ?: (1_000_000_000.0 / hz).toLong()
            recordFrame(route.substringBefore('?').substringBefore('/'), work, deadline)
        }
        listener = l
        runCatching { activity.window.addOnFrameMetricsAvailableListener(l, Handler(t.looper)) }
    }

    fun detach(activity: Activity) {
        listener?.let { runCatching { activity.window.removeOnFrameMetricsAvailableListener(it) } }
        listener = null
        thread?.quitSafely(); thread = null
    }
}
