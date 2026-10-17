package com.blacklab.app.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.blacklab.app.app
import com.blacklab.app.data.TaskOps
import com.blacklab.app.study.StudyTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_FIRE = "com.blacklab.app.action.REMINDER_FIRE"
        const val ACTION_COMPLETE_TASK = "com.blacklab.app.action.COMPLETE_TASK"
        const val ACTION_SNOOZE_TASK = "com.blacklab.app.action.SNOOZE_TASK"
        const val ACTION_STUDY = "com.blacklab.app.action.STUDY_PHASE_END"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.app
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> ReminderScheduler.onAlarm(context)
                    ACTION_STUDY -> StudyTimer.get(context).checkCompletion(fromAlarm = true)
                    ACTION_COMPLETE_TASK, ACTION_SNOOZE_TASK -> {
                        val id = intent.getLongExtra(EXTRA_TASK_ID, 0)
                        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                        val task = app.db.tasks().get(id)
                        if (task != null) {
                            if (intent.action == ACTION_COMPLETE_TASK) TaskOps.setCompleted(app, task, true)
                            else TaskOps.save(app, task.copy(reminderAt = System.currentTimeMillis() + 10 * 60_000L))
                        }
                        ReminderScheduler.reschedule(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderScheduler.reschedule(context)
                StudyTimer.get(context).rearm()
            } finally {
                pending.finish()
            }
        }
    }
}
