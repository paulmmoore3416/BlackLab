package com.blacklab.app.vehicle

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blacklab.app.app
import com.blacklab.app.core.Profile
import com.blacklab.app.data.TaskEntity
import com.blacklab.app.data.TaskOps
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Turns "oil change in 1,200 mi" into a date: your usual miles per day (from trips and fill-ups, or
 * the yearly miles in the profile) predict when each item comes due. A daily check sends one
 * notification a week ahead (and one when something goes overdue), and any item can become a To-Do.
 */
object ServiceReminders {

    /** Your pace in miles per day, from the trip log and fill-ups. */
    fun pace(context: Context): Double? {
        val g = Garage.load(context)
        val trips = VehicleLink.load(context).trips.filter { it.end != null && !it.simulated }.map { it.start to it.miles }
        return Garage.milesPerDay(g.fuel, trips)
    }

    fun due(context: Context): List<Garage.Due> = Garage.due(Garage.load(context), milesPerDay = pace(context))

    /** Items worth a heads-up today: overdue, or expected within [days]. Pure; unit-tested. */
    fun upcoming(due: List<Garage.Due>, today: LocalDate, days: Int = 7): List<Garage.Due> = due.filter { d ->
        val e = d.expectedDay
        d.status == Garage.Status.OVERDUE || (e != null && e - today.toEpochDay() in 0..days.toLong())
    }

    /** A stable key per due occurrence, so each one is announced once. */
    fun key(d: Garage.Due) = "${d.item.id}:${d.dueMiles ?: ""}:${d.dueDay ?: ""}:${if (d.status == Garage.Status.OVERDUE) "late" else "soon"}"

    suspend fun check(context: Context) {
        val prefs = context.getSharedPreferences("garage_reminders", Context.MODE_PRIVATE)
        val told = prefs.getStringSet("told", emptySet()).orEmpty()
        val today = LocalDate.now()
        val fresh = upcoming(due(context), today).filter { key(it) !in told }
        if (fresh.isEmpty()) return
        val g = Garage.load(context).vehicle
        val title = if (fresh.size == 1) "${fresh[0].item.name}: ${if (fresh[0].status == Garage.Status.OVERDUE) "overdue" else "due soon"}" else "${fresh.size} service items coming due"
        VehicleLink.alert(context, "service_due", title, fresh.joinToString("\n") { "${it.item.name} ${it.describe()}" } + "\n${g.name}. Tap to log it or add a To-Do.")
        prefs.edit().putStringSet("told", (told + fresh.map(::key)).toList().takeLast(200).toSet()).apply()
    }

    /** Adds a Personal To-Do for [d] on its expected date (or today if overdue), at 9 AM. Returns the task id. */
    suspend fun addTask(context: Context, d: Garage.Due): Long {
        val app = context.app
        val g = Garage.load(context).vehicle
        val day = (d.expectedDay ?: LocalDate.now().toEpochDay()).coerceAtLeast(LocalDate.now().toEpochDay())
        val notes = listOfNotNull(g.name, d.describe(), d.dueMiles?.let { "Due at ${Garage.fmtMiles(it)} mi" }, g.dealer.ifBlank { null }?.let { "Dealer: $it" }, d.item.note.ifBlank { null })
            .joinToString("\n")
        return TaskOps.save(app, TaskEntity(profile = Profile.PERSONAL.name, title = "Car: ${d.item.name}", notes = notes, dueDay = day, dueMinute = 9 * 60,
            reminderAt = LocalDate.ofEpochDay(day).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), tags = "car"))
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("service_reminders", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ServiceReminderWorker>(1, TimeUnit.DAYS).setInitialDelay(2, TimeUnit.HOURS).build())
    }
}

class ServiceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { runCatching { ServiceReminders.check(applicationContext) }; return Result.success() }
}
