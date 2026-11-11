package com.blacklab.app.facility

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.core.Profile
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import com.blacklab.app.reminders.Notifications
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** A daily heads-up for inspections and PMs coming due or overdue, and turning them (or failures) into To-Dos. */
object FacilityReminders {

    /** Schedules worth a notification today: overdue or inside their due-soon window. Pure; unit-tested. */
    fun upcoming(due: List<Facility.Due>): List<Facility.Due> = due.filter { it.status != Facility.Status.OK }

    /** One notification per due occurrence (and again once it goes overdue). */
    fun key(d: Facility.Due) = "${d.pm.id}:${d.dueDay}:${if (d.status == Facility.Status.OVERDUE) "late" else "soon"}"

    suspend fun check(context: Context) {
        val prefs = context.getSharedPreferences("facility_reminders", Context.MODE_PRIVATE)
        val told = prefs.getStringSet("told", emptySet()).orEmpty()
        val fresh = upcoming(Facility.due(Facility.load(context))).filter { key(it) !in told }
        if (fresh.isEmpty()) return
        val late = fresh.count { it.status == Facility.Status.OVERDUE }
        val title = if (fresh.size == 1) "${fresh[0].asset.name}: ${fresh[0].pm.title} ${fresh[0].describe()}"
        else "${fresh.size} facility inspections coming due" + if (late > 0) " ($late overdue)" else ""
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            val n = NotificationCompat.Builder(context, Notifications.CH_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.take(8).joinToString("\n") { "${it.asset.name} · ${it.pm.title} — ${it.describe()}" }))
                .setContentText("Tap to log them or add To-Dos").setAutoCancel(true)
                .setContentIntent(Notifications.openAppIntent(context, "facility", 7801)).build()
            runCatching { nm.notify("facility_due", 1, n) }
        }
        prefs.edit().putStringSet("told", (told + fresh.map(::key)).toList().takeLast(300).toSet()).apply()
    }

    private suspend fun listId(context: Context, name: String): Long? =
        context.app.db.tasks().lists(Profile.PROFESSIONAL.name).firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    /** A Professional To-Do in Inspections, on the due date (or today when overdue) at 8 AM. */
    suspend fun addTask(context: Context, d: Facility.Due): Long {
        val today = LocalDate.now().toEpochDay()
        val day = d.dueDay.coerceAtLeast(today)
        val notes = listOfNotNull(d.asset.location.ifBlank { null }, d.pm.standard.ifBlank { null }?.let { "Standard: $it" },
            d.asset.tag.ifBlank { null }?.let { "Tag: $it" }, d.pm.checklist.takeIf { it.isNotEmpty() }?.joinToString("\n") { "☐ $it" }).joinToString("\n")
        return TaskOps.save(context.app, TaskEntity(profile = Profile.PROFESSIONAL.name, listId = listId(context, "Inspections"),
            title = "${d.pm.title}: ${d.asset.name}", notes = notes, dueDay = day, dueMinute = 8 * 60,
            reminderAt = LocalDate.ofEpochDay(day).atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), tags = "facility,inspection"))
    }

    /** A Professional work order in Work Orders for a failed inspection or finding. */
    suspend fun addWorkOrder(context: Context, asset: Facility.Asset, what: String): Long =
        TaskOps.save(context.app, TaskEntity(profile = Profile.PROFESSIONAL.name, listId = listId(context, "Work Orders"),
            title = "WO: ${asset.name} — ${what.lineSequence().first().take(80)}", priority = 3,
            notes = listOfNotNull(what, asset.location.ifBlank { null }, asset.tag.ifBlank { null }?.let { "Tag: $it" },
                listOf(asset.make, asset.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }).joinToString("\n"),
            dueDay = LocalDate.now().toEpochDay(), tags = "facility,work-order"))

    /** Files a work order from plain words (Intake): a task in Work Orders, plus the asset's log when one matched. */
    suspend fun addIntake(context: Context, d: Intake.Draft, by: String = ""): Long {
        val today = LocalDate.now()
        val due = when (d.priority) { Intake.Priority.P1, Intake.Priority.P2 -> today; Intake.Priority.P3 -> today.plusDays(7); Intake.Priority.P4 -> null }
        val remindAt = if (d.priority == Intake.Priority.P1) System.currentTimeMillis() + 60 * 60_000L else null
        val id = TaskOps.save(context.app, TaskEntity(profile = Profile.PROFESSIONAL.name, listId = listId(context, "Work Orders"),
            title = "WO ${d.priority.name}: ${d.title}", priority = d.priority.taskPriority, important = d.priority == Intake.Priority.P1,
            notes = d.notes(), dueDay = due?.toEpochDay(), reminderAt = remindAt,
            tags = listOf("facility", "work-order", d.trade.lowercase().replace(' ', '-')).joinToString(",")))
        d.asset?.let { a ->
            Facility.update(context) { s -> Facility.addEntry(s, Facility.Entry(assetId = a.id, day = today.toEpochDay(), kind = Facility.Kind.WORK_ORDER,
                text = "${d.priority.name} · ${d.title}", by = by, taskId = id)) }
        }
        return id
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("facility_reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FacilityReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(3, TimeUnit.HOURS).build())
    }
}

class FacilityReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { runCatching { FacilityReminders.check(applicationContext) }; return Result.success() }
}
