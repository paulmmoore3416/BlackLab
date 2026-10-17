package com.blacklab.app.study

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.blacklab.app.app
import com.blacklab.app.core.Time
import com.blacklab.app.data.StudySessionEntity
import com.blacklab.app.reminders.Notifications
import com.blacklab.app.reminders.ReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pomodoro focus timer whose state survives process death (wall-clock based + exact alarm). */
class StudyTimer private constructor(private val context: Context) {

    enum class Phase { IDLE, FOCUS, BREAK, LONG_BREAK }

    data class State(
        val phase: Phase = Phase.IDLE,
        val running: Boolean = false,
        val endAt: Long = 0,
        val remainingMs: Long = 0,
        val totalMs: Long = 0,
        val courseId: Long? = null,
        val completedFocus: Int = 0,
        val profile: String = "PERSONAL",
    ) {
        fun remaining(now: Long = System.currentTimeMillis()): Long = if (running) (endAt - now).coerceAtLeast(0) else remainingMs
    }

    private val prefs = context.getSharedPreferences("blacklab_study", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    private fun load() = State(
        phase = runCatching { Phase.valueOf(prefs.getString("phase", "IDLE")!!) }.getOrDefault(Phase.IDLE),
        running = prefs.getBoolean("running", false),
        endAt = prefs.getLong("endAt", 0),
        remainingMs = prefs.getLong("remaining", 0),
        totalMs = prefs.getLong("total", 0),
        courseId = prefs.getLong("course", -1).takeIf { it >= 0 },
        completedFocus = prefs.getInt("done", 0),
        profile = prefs.getString("profile", "PERSONAL")!!,
    )

    private fun save(s: State) {
        prefs.edit().putString("phase", s.phase.name).putBoolean("running", s.running).putLong("endAt", s.endAt)
            .putLong("remaining", s.remainingMs).putLong("total", s.totalMs).putLong("course", s.courseId ?: -1)
            .putInt("done", s.completedFocus).putString("profile", s.profile).apply()
        _state.value = s
        rearm()
    }

    private fun minutes(phase: Phase): Int {
        val st = context.app.settings.current
        return when (phase) {
            Phase.FOCUS, Phase.IDLE -> st.pomodoroWork
            Phase.BREAK -> st.pomodoroBreak
            Phase.LONG_BREAK -> st.pomodoroLongBreak
        }
    }

    fun setCourse(courseId: Long?) = save(_state.value.copy(courseId = courseId))

    fun start(profile: String) {
        val s = _state.value
        val phase = if (s.phase == Phase.IDLE) Phase.FOCUS else s.phase
        val total = if (s.phase == Phase.IDLE || s.remainingMs <= 0) minutes(phase) * 60_000L else s.totalMs
        val remaining = if (s.phase == Phase.IDLE || s.remainingMs <= 0) total else s.remainingMs
        save(s.copy(phase = phase, running = true, endAt = System.currentTimeMillis() + remaining, remainingMs = remaining, totalMs = total, profile = profile))
    }

    fun pause() {
        val s = _state.value
        if (!s.running) return
        save(s.copy(running = false, remainingMs = s.remaining()))
    }

    /** Stop; logs a partial focus session if at least one minute was spent. */
    fun reset() {
        val s = _state.value
        if (s.phase == Phase.FOCUS) {
            val spent = ((s.totalMs - s.remaining()) / 60_000L).toInt()
            if (spent >= 1) log(s, spent)
        }
        save(State(courseId = s.courseId, profile = s.profile))
    }

    fun skip() {
        val s = _state.value
        when (s.phase) {
            Phase.FOCUS -> {
                val spent = ((s.totalMs - s.remaining()) / 60_000L).toInt()
                if (spent >= 1) log(s, spent)
                toBreak(s.copy(completedFocus = s.completedFocus + 1), autoStart = false)
            }
            Phase.BREAK, Phase.LONG_BREAK -> toFocusReady(s)
            Phase.IDLE -> Unit
        }
    }

    private fun toBreak(s: State, autoStart: Boolean) {
        val long = s.completedFocus > 0 && s.completedFocus % 4 == 0
        val phase = if (long) Phase.LONG_BREAK else Phase.BREAK
        val total = minutes(phase) * 60_000L
        save(s.copy(phase = phase, running = autoStart, endAt = System.currentTimeMillis() + total, remainingMs = total, totalMs = total))
    }

    private fun toFocusReady(s: State) {
        val total = minutes(Phase.FOCUS) * 60_000L
        save(s.copy(phase = Phase.FOCUS, running = false, remainingMs = total, totalMs = total))
    }

    private fun log(s: State, minutes: Int) {
        val app = context.app
        app.io {
            app.db.education().insertSession(
                StudySessionEntity(profile = s.profile, courseId = s.courseId, startAt = System.currentTimeMillis() - minutes * 60_000L, minutes = minutes, kind = "FOCUS")
            )
        }
    }

    /** Called by the UI ticker and by the alarm. */
    fun checkCompletion(fromAlarm: Boolean = false) {
        val s = _state.value
        if (!s.running || s.remaining() > 0) return
        when (s.phase) {
            Phase.FOCUS -> {
                log(s, (s.totalMs / 60_000L).toInt())
                val next = s.copy(completedFocus = s.completedFocus + 1)
                toBreak(next, autoStart = true)
                Notifications.simple(context, 7001, Notifications.CH_STUDY, "Focus session complete 🎯",
                    "Nice work. Time for a ${if ((next.completedFocus % 4) == 0) "long " else ""}break.", "education")
            }
            Phase.BREAK, Phase.LONG_BREAK -> {
                toFocusReady(s)
                Notifications.simple(context, 7001, Notifications.CH_STUDY, "Break's over", "Ready for the next focus session?", "education")
            }
            Phase.IDLE -> Unit
        }
    }

    @SuppressLint("ScheduleExactAlarm")
    fun rearm() {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 5151, Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_STUDY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val s = _state.value
        if (!s.running) { am.cancel(pi); return }
        try {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.endAt, pi)
        }
    }

    companion object {
        @Volatile private var instance: StudyTimer? = null
        fun get(context: Context): StudyTimer =
            instance ?: synchronized(this) { instance ?: StudyTimer(context.applicationContext).also { instance = it } }
    }
}
