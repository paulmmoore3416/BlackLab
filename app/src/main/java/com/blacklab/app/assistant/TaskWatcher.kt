package com.blacklab.app.assistant

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blacklab.app.AppContainer
import com.blacklab.app.R
import com.blacklab.app.link.AgentOps
import com.blacklab.app.reminders.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The live task notification (5.19): while the computer runs a job this phone started ("In the background: …"), an
 * ongoing notification shows its latest step, refreshed every 3 s from /phone/agentops. It goes away when the job
 * finishes (the usual result notification follows) or after 30 minutes. Nothing shows on the lock screen, and a
 * private job shows only a step count even when the phone is unlocked.
 */
object TaskWatcher {
    private const val POLL_MS = 3_000L
    private const val MAX_MS = 30 * 60_000L
    private const val PUBLIC_TITLE = "Your computer is working"
    @Volatile private var loop: Job? = null
    private val timedOut = java.util.Collections.synchronizedSet(HashSet<String>())

    /** The jobs worth a live notification: started from the phone and running now. */
    fun watched(jobs: List<DesktopJobs.Job>): List<DesktopJobs.Job> = jobs.filter { it.status == "running" && it.origin.startsWith("Phone") }

    /** Watched jobs that haven't already used up their 30 minutes (a stuck job must not restart the polling forever). */
    fun startable(jobs: List<DesktopJobs.Job>, timedOut: Set<String>): List<DesktopJobs.Job> = watched(jobs).filter { it.id !in timedOut }

    /** (title, text) for a job, from its live task log when the computer has one. */
    fun text(job: DesktopJobs.Job, live: AgentOps.LiveTask?): Pair<String, String> {
        if (job.private) return PUBLIC_TITLE to ("Working… (private)" + (live?.let { " · step ${it.stepCount}" } ?: ""))
        val title = "Computer: " + if (job.title.length > 50) job.title.take(50) + "…" else job.title
        return title to (live?.steps?.lastOrNull()?.title ?: job.progress.ifBlank { "Working…" })
    }

    /** Done when the computer says it isn't running any more, or when it has dropped off both the snapshot and the phone's list. */
    fun finished(job: DesktopJobs.Job, row: AgentOps.JobRow?, active: List<DesktopJobs.Job>): Boolean =
        if (row != null) row.status != "running" else active.none { it.id == job.id }

    /** 8400–8655: clear of the job results (7300 + 0x3ff), "sent to your computer" (7200 + 0x3ff), rounds and updater alerts. */
    fun id(job: DesktopJobs.Job) = 8400 + (job.id.hashCode() and 0xff)

    /** Called after each jobs check-in: starts the watcher when a phone job is running (one loop at a time). */
    fun ensure(app: AppContainer, jobs: List<DesktopJobs.Job>) {
        val start = startable(jobs, timedOut.toSet())
        if (start.isEmpty() || loop?.isActive == true) return
        loop = app.appScope.launch {
            val tracked = start.associateBy { it.id }.toMutableMap()
            val t0 = System.currentTimeMillis()
            try {
                while (tracked.isNotEmpty() && System.currentTimeMillis() - t0 < MAX_MS) {
                    AgentOps.refresh(app)
                    val s = AgentOps.snapshot.value
                    // newly started phone jobs join; finished ones leave
                    startable(DesktopJobs.jobs.value, timedOut.toSet()).forEach { tracked.putIfAbsent(it.id, it) }
                    for (job in tracked.values.toList()) {
                        val row = s?.jobs?.firstOrNull { it.id == job.id }
                        if (finished(job, row, DesktopJobs.jobs.value)) { cancel(app.context, job); tracked.remove(job.id); continue }
                        val fresh = if (row != null && !job.private && row.progress.isNotBlank()) job.copy(progress = row.progress) else job
                        post(app.context, fresh, s?.live?.firstOrNull { it.job == job.id })
                    }
                    delay(POLL_MS)
                }
                if (tracked.isNotEmpty()) timedOut.addAll(tracked.keys)  // hit the 30-minute cap: stay quiet for these jobs
            } finally {
                tracked.values.forEach { cancel(app.context, it) }
            }
        }
    }

    private fun post(c: Context, job: DesktopJobs.Job, live: AgentOps.LiveTask?) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val (title, body) = text(job, live)
        val open = Notifications.openAppIntent(c, "agentops", id(job))
        val public = NotificationCompat.Builder(c, Notifications.CH_WORKING).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PUBLIC_TITLE).setContentIntent(open).build()
        val n = NotificationCompat.Builder(c, Notifications.CH_WORKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body)
            .setColor(0xFF2BD9A8.toInt())
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setProgress(0, 0, true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(open)
            .build()
        try { nm.notify(id(job), n) } catch (_: SecurityException) {}
    }

    private fun cancel(c: Context, job: DesktopJobs.Job) = NotificationManagerCompat.from(c).cancel(id(job))
}
