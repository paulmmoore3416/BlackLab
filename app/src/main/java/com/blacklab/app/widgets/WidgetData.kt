package com.blacklab.app.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.appwidget.updateAll
import androidx.room.InvalidationTracker
import com.blacklab.app.AppContainer
import com.blacklab.app.MainActivity
import com.blacklab.app.app
import com.blacklab.app.assistant.Briefing
import com.blacklab.app.brainstorm.NoteItem
import com.blacklab.app.brainstorm.NoteOps
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Time
import com.blacklab.app.data.NoteEntity
import com.blacklab.app.data.TaskEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Pure selection rules for the Today widget (unit tested). */
object TodayLogic {

    /**
     * Tasks that belong on today's list: open tasks due today or earlier, or added to My Day,
     * plus the ones finished today so an accidental tick can be undone from the widget.
     * Open first (overdue, then by time and priority), finished last.
     */
    fun tasks(all: List<TaskEntity>, profile: String, today: Long, startOfToday: Long): List<TaskEntity> {
        fun relevant(t: TaskEntity) = (t.dueDay != null && t.dueDay <= today) || t.myDayDay == today
        val open = all.filter { it.profile == profile && !it.completed && relevant(it) }
            .sortedWith(compareBy<TaskEntity>({ (it.dueDay ?: today) >= today }, { it.dueDay ?: today }, { it.dueMinute ?: Int.MAX_VALUE }, { -it.priority }, { it.sortOrder }))
        val done = all.filter { it.profile == profile && it.completed && (it.completedAt ?: 0) >= startOfToday && relevant(it) }
            .sortedByDescending { it.completedAt }
        return open + done
    }

    /** Checklist rows for the widget: open items in order, then checked ones. */
    fun checklist(items: List<NoteItem>): List<Pair<Int, NoteItem>> {
        val indexed = items.mapIndexed { i, it -> i to it }.filter { it.second.text.isNotBlank() }
        return indexed.filter { !it.second.done } + indexed.filter { it.second.done }
    }

    fun countLabel(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many
}

data class TodayRow(
    val kind: Kind,
    val id: Long,
    val title: String,
    val detail: String,
    val done: Boolean = false,
    val late: Boolean = false,
    val route: String,
) { enum class Kind { EVENT, TASK, REMINDER } }

data class TodayModel(
    val account: String,
    val date: String,
    val agenda: List<TodayRow>,
    val tasks: List<TodayRow>,
    val hideDetails: Boolean,
)

data class ChecklistModel(
    val noteId: Long,
    val title: String,
    /** (index into the note's items, item) */
    val rows: List<Pair<Int, NoteItem>>,
    val doneCount: Int,
    val total: Int,
    val color: Int,
    val isChecklist: Boolean,
    val missing: Boolean,
    val hideDetails: Boolean,
)

object Widgets {
    /** Bumped whenever data a widget shows changes; running widget sessions re-load on it. */
    val version = MutableStateFlow(0L)
    private var job: Job? = null

    fun openIntent(context: Context, route: String): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("blacklab://open/" + Uri.encode(route))) // keeps each row's PendingIntent distinct
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun captureIntent(context: Context, mode: String, noteId: Long = 0): Intent =
        Intent(context, com.blacklab.app.capture.QuickCaptureActivity::class.java)
            .setData(Uri.parse("blacklab://capture/$mode/$noteId"))
            .putExtra(com.blacklab.app.capture.QuickCaptureActivity.EXTRA_MODE, mode)
            .putExtra(com.blacklab.app.capture.QuickCaptureActivity.EXTRA_NOTE, noteId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    /** Watches the tables widgets show and refreshes them (debounced) while the app process lives. */
    fun watch(app: AppContainer) {
        app.db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
            arrayOf("tasks", "notes", "events", "courses", "class_meetings", "assignments"),
        ) {
            override fun onInvalidated(tables: Set<String>) = refresh(app.context)
        })
        app.appScope.launch {
            app.settings.state.map { Triple(it.activeProfile, it.widgetPrivacy, it.professionalName + it.personalName) }
                .distinctUntilChanged().drop(1).collect { refresh(app.context) }
        }
    }

    fun refresh(context: Context, delayMs: Long = 800) {
        val app = context.app
        job?.cancel()
        job = app.appScope.launch {
            delay(delayMs)
            refreshNow(context)
        }
    }

    suspend fun refreshNow(context: Context) {
        version.value = version.value + 1
        runCatching { TodayWidget().updateAll(context) }
        runCatching { ChecklistWidget().updateAll(context) }
        runCatching { CaptureWidget().updateAll(context) }
    }

    private fun hide(app: AppContainer, onLockScreen: Boolean) = onLockScreen || app.settings.current.widgetPrivacy == "COUNTS"

    suspend fun today(context: Context, onLockScreen: Boolean): TodayModel {
        val app = context.app
        val s = app.settings.current
        val profile = s.activeProfile
        val today = Time.today()
        val zone = ZoneId.systemDefault()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = LocalDateTime.now()

        val agenda = runCatching { Briefing.agenda(app, today, today) }.getOrDefault(emptyList())
            .filter { it.end >= now || it.kind == "Due" || isAllDay(it.start, it.end, today) }
            .map { a ->
                val time = when {
                    a.kind == "Due" -> "Due " + Time.time(a.start)
                    isAllDay(a.start, a.end, today) -> "All day"
                    else -> Time.time(a.start) + " – " + Time.time(a.end)
                }
                TodayRow(TodayRow.Kind.EVENT, 0, a.title, time + (a.where.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), route = a.route)
            }
        val reminders = app.db.notes().remindersBetween(start - 1, end).filter { it.profile == profile.name && it.trashedAt == null }
            .map { n -> TodayRow(TodayRow.Kind.REMINDER, n.id, n.title.ifBlank { NoteOps.summary(n).take(60).ifBlank { "Note" } }, "Reminder " + Time.time(Time.toLdt(n.reminderAt!!)), route = "note/${n.id}") }
        val tasks = TodayLogic.tasks(app.db.tasks().all(), profile.name, today.toEpochDay(), start).map { t ->
            val late = !t.completed && t.dueDay != null && t.dueDay < today.toEpochDay()
            val detail = when {
                late -> "Overdue · " + Time.relativeDay(LocalDate.ofEpochDay(t.dueDay!!))
                t.dueMinute != null -> Time.time(t.dueMinute)
                t.myDayDay == today.toEpochDay() && t.dueDay == null -> "My Day"
                else -> "Today"
            }
            TodayRow(TodayRow.Kind.TASK, t.id, t.title, detail, t.completed, late, "task/${t.id}")
        }
        return TodayModel(
            account = app.settings.profileName(profile),
            date = Time.longDate(today),
            agenda = (agenda + reminders),
            tasks = tasks,
            hideDetails = hide(app, onLockScreen),
        )
    }

    suspend fun checklist(context: Context, noteId: Long, onLockScreen: Boolean): ChecklistModel {
        val app = context.app
        val n: NoteEntity? = if (noteId > 0) app.db.notes().get(noteId) else null
        if (n == null || n.trashedAt != null) return ChecklistModel(noteId, "", emptyList(), 0, 0, 0, isChecklist = true, missing = true, hideDetails = hide(app, onLockScreen))
        val items = NoteOps.items(n)
        val body = if (n.checklist) items else n.body.lines().filter { it.isNotBlank() }.map { NoteItem(it.trim()) }
        return ChecklistModel(
            noteId = n.id,
            title = n.title.ifBlank { if (n.checklist) "Checklist" else "Note" },
            rows = TodayLogic.checklist(body),
            doneCount = body.count { it.done && it.text.isNotBlank() },
            total = body.count { it.text.isNotBlank() },
            color = n.color,
            isChecklist = n.checklist,
            missing = false,
            hideDetails = hide(app, onLockScreen),
        )
    }

    /** Checklist notes the user can pin to a widget, newest first. */
    suspend fun pinnableNotes(context: Context): List<NoteEntity> {
        val app = context.app
        val profile = app.settings.current.activeProfile.name
        return app.db.notes().allFor(profile).filter { it.trashedAt == null && !it.archived }
            .sortedWith(compareByDescending<NoteEntity> { it.checklist }.thenByDescending { it.pinned }.thenByDescending { it.updatedAt })
    }

    fun accountName(context: Context, profile: Profile) = context.app.settings.profileName(profile)

    private fun isAllDay(start: LocalDateTime, end: LocalDateTime, day: LocalDate) =
        start.toLocalTime().toSecondOfDay() == 0 && (end.toLocalDate() > start.toLocalDate() || end == start) && start.toLocalDate() <= day
}
