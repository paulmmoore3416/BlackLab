package com.blacklab.app.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blacklab.app.MainActivity
import com.blacklab.app.R

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_AGENDA = "agenda"
    const val CH_RECORDING = "recording"
    const val CH_STUDY = "study"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Task, event, class and deadline reminders"
                    enableVibration(true)
                },
                NotificationChannel(CH_AGENDA, "Daily agenda", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Morning summary of your day"
                },
                NotificationChannel(CH_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while BlackLab is recording audio"
                    setShowBadge(false)
                },
                NotificationChannel(CH_STUDY, "Study timer", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Focus session and break alerts"
                },
            )
        )
    }

    fun openAppIntent(context: Context, route: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(MainActivity.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, due: ReminderScheduler.Due) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val id = due.key.hashCode()
        val channel = if (due.kind == ReminderScheduler.Kind.AGENDA) CH_AGENDA else CH_REMINDERS
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(due.title)
            .setContentText(due.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(due.text))
            .setAutoCancel(true)
            .setColor(0xFF2BD9A8.toInt())
            .setCategory(if (due.kind == ReminderScheduler.Kind.EVENT || due.kind == ReminderScheduler.Kind.CLASS) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context, due.route, id))
            .setWhen(due.at)
            .setShowWhen(true)
            .setSubText(due.profileLabel)
        if (due.kind == ReminderScheduler.Kind.TASK) {
            b.addAction(0, "Complete", actionIntent(context, ReminderReceiver.ACTION_COMPLETE_TASK, due.refId, id))
            b.addAction(0, "Snooze 10 min", actionIntent(context, ReminderReceiver.ACTION_SNOOZE_TASK, due.refId, id))
        }
        try {
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }

    fun simple(context: Context, id: Int, channel: String, title: String, text: String, route: String?) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setColor(0xFF2BD9A8.toInt())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(context, route, id))
            .build()
        try {
            nm.notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun actionIntent(context: Context, action: String, taskId: Long, notificationId: Int): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderReceiver.EXTRA_TASK_ID, taskId)
            putExtra(ReminderReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(context, (action + taskId).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
