package com.blacklab.app.bh

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blacklab.app.R
import com.blacklab.app.reminders.Notifications

/**
 * Observation rounds (Q5 / Q15 / Q30 / Q60, line of sight, 1:1). Each person on the census is due
 * one interval after their last check. While rounds are on, a single exact alarm fires at the next
 * due time and again every grace period while anyone is overdue. Notifications carry counts only —
 * never initials or rooms — and stay hidden on the lock screen.
 */
object Rounds {
    const val CH_ROUNDS = "bh_rounds"
    const val ACTION = "com.blacklab.app.action.BH_ROUND_DUE"
    private const val NOTIFY_ID = 7301
    private const val REQ = 7302

    val LEVELS = listOf(
        "Q5" to "Every 5 minutes",
        "Q10" to "Every 10 minutes",
        "Q15" to "Every 15 minutes",
        "Q30" to "Every 30 minutes",
        "Q60" to "Every hour",
        "LOS" to "Line of sight (continuous)",
        "1:1 eyesight" to "1:1 within eyesight (continuous)",
        "1:1 arm's length" to "1:1 within arm's length (continuous)",
        "Routine" to "Routine (unit rounds only)",
    )

    /** Minutes between checks, or null for continuous / routine observation (no timer). */
    fun interval(obs: String): Int? = when (obs) {
        "Q5" -> 5; "Q10" -> 10; "Q15" -> 15; "Q30" -> 30; "Q60" -> 60
        else -> null
    }

    fun continuous(obs: String) = obs == "LOS" || obs.startsWith("1:1")

    data class Due(val patient: Bh.Patient, val last: Bh.Check?, val dueAt: Long?, val overdueMs: Long)

    /** Every person on the census with when they're next due (null = no timer), most urgent first. */
    fun due(s: Bh.State, now: Long): List<Due> {
        val lastBy = s.checks.groupBy { it.patientId }.mapValues { (_, v) -> v.maxByOrNull { it.time } }
        return s.census.map { p ->
            val last = lastBy[p.id]
            val iv = interval(p.obs)
            // Never checked: the clock starts when rounds began or when they joined the census, whichever is later
            val base = last?.time ?: maxOf(s.roundsSince ?: 0L, p.added)
            val at = iv?.let { base + it * 60_000L }
            Due(p, last, at, if (at != null && now > at) now - at else 0)
        }.sortedWith(compareBy<Due> { it.dueAt == null }.thenBy { it.dueAt ?: Long.MAX_VALUE })
    }

    fun nextDue(s: Bh.State, now: Long): Long? = if (!s.roundsOn) null else due(s, now).mapNotNull { it.dueAt }.minOrNull()

    fun overdue(s: Bh.State, now: Long): List<Due> = due(s, now).filter { it.dueAt != null && now > it.dueAt + s.prefs.graceMinutes * 60_000L }

    /** Late checks in the retained log: (checks on time, checks late) — for the round sheet summary. */
    fun compliance(s: Bh.State, patientId: String? = null): Pair<Int, Int> {
        var ok = 0; var late = 0
        s.checks.filter { patientId == null || it.patientId == patientId }.groupBy { it.patientId }.forEach { (pid, list) ->
            val p = s.patients.firstOrNull { it.id == pid } ?: return@forEach
            val iv = interval(p.obs) ?: return@forEach
            list.sortedBy { it.time }.zipWithNext().forEach { (a, b) ->
                if (b.time - a.time <= (iv + s.prefs.graceMinutes) * 60_000L) ok++ else late++
            }
        }
        return ok to late
    }

    // ---------------------------------------------------------------- alarm + notification

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ROUNDS, "Observation rounds", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When observation checks are due or overdue (counts only, never names)"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    private fun pi(context: Context) = PendingIntent.getBroadcast(context, REQ, Intent(context, RoundsReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("ScheduleExactAlarm")
    fun rearm(context: Context) {
        val s = BhStore.load(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val next = nextDue(s, now)
        if (next == null || !s.prefs.roundAlerts) {
            am.cancel(pi(context)); NotificationManagerCompat.from(context).cancel(NOTIFY_ID); return
        }
        // Already past due: nudge again after the grace period instead of firing in a loop
        val at = if (next <= now) now + s.prefs.graceMinutes.coerceAtLeast(1) * 60_000L else next
        if (next > now) NotificationManagerCompat.from(context).cancel(NOTIFY_ID)
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context))
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi(context))
        }
    }

    fun onAlarm(context: Context) {
        val s = BhStore.load(context)
        val now = System.currentTimeMillis()
        if (!s.roundsOn || !s.prefs.roundAlerts) return
        val d = due(s, now).filter { it.dueAt != null && it.dueAt <= now + 30_000 }
        if (d.isNotEmpty()) {
            val late = d.count { now > it.dueAt!! + s.prefs.graceMinutes * 60_000L }
            val title = if (late > 0) "Rounds overdue — $late past due" else "Rounds due now"
            val text = "${d.size} of ${s.census.size} on the census due for a check" + (s.prefs.unit.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            notify(context, title, text, late > 0, s.prefs.roundSound)
        }
        rearm(context)
    }

    private fun notify(context: Context, title: String, text: String, urgent: Boolean, sound: Boolean) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val public = NotificationCompat.Builder(context, CH_ROUNDS).setSmallIcon(R.drawable.ic_notification).setContentTitle("Rounds due").build()
        val n = NotificationCompat.Builder(context, CH_ROUNDS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(if (urgent) 0xFFEF5350.toInt() else 0xFF2BD9A8.toInt())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(false)
            .setSilent(!sound)
            .setAutoCancel(true)
            .setContentIntent(Notifications.openAppIntent(context, "bh/rounds", NOTIFY_ID))
            .build()
        try { nm.notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
    }
}

class RoundsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Rounds.ACTION) return
        val pending = goAsync()
        Thread {
            try { Rounds.onAlarm(context.applicationContext) } finally { pending.finish() }
        }.start()
    }
}
