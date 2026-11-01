package com.blacklab.app.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The current time, updated on every minute boundary, whenever the screen comes back to the
 * foreground, and when the date, time or time zone changes. Screens that read it re-sort and
 * re-filter on their own: events that ended drop off, overdue flips at midnight, greetings change.
 */
@Composable
fun rememberNow(): LocalDateTime {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)) }
    var bump by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) bump++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { bump++ } }
        val f = IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
    LaunchedEffect(bump) {
        while (true) {
            now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
            val next = now.plusMinutes(1)
            delay(java.time.Duration.between(LocalDateTime.now(), next).toMillis().coerceIn(250, 60_000) + 50)
        }
    }
    return now
}

/**
 * A counter that increases whenever the phone's own calendars change (an event added in Google
 * Calendar, a sync finishing) or the screen resumes, for keys of queries that read the device calendar.
 */
@Composable
fun rememberDeviceCalendarVersion(enabled: Boolean): Int {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, enabled) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && enabled) version++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler = Handler(Looper.getMainLooper())
        var pending = false
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                // Calendar syncs fire bursts of changes; refresh once per burst
                if (pending) return
                pending = true
                handler.postDelayed({ pending = false; version++ }, 800)
            }
        }
        val registered = runCatching { ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }.isSuccess
        onDispose { if (registered) runCatching { ctx.contentResolver.unregisterContentObserver(observer) } }
    }
    return version
}
