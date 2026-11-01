package com.blacklab.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.ColorFilter
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.data.TaskOps

/** Today: the rest of today's schedule and the tasks due, with tick-off boxes. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val onLock = WUi.onLockScreen(context, appWidgetId)
        val initial = Widgets.today(context, onLock)
        provideContent {
            val v by Widgets.version.collectAsState()
            val model by produceState(initial, v) { value = Widgets.today(context, onLock) }
            GlanceTheme { Content(model) }
        }
    }

    @Composable
    private fun Content(m: TodayModel) {
        val ctx = LocalContext.current
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground).padding(12.dp)) {
            WUi.Header(R.drawable.ic_w_today, m.date, "Today · ${m.account}", actionStartActivity(Widgets.openIntent(ctx, "home")),
                R.drawable.ic_w_add, "Add a task", actionStartActivity(Widgets.captureIntent(ctx, "task")))
            if (m.hideDetails) {
                val open = m.tasks.count { !it.done }
                WUi.Hidden(listOf(TodayLogic.countLabel(m.agenda.size, "thing", "things") + " on the schedule", TodayLogic.countLabel(open, "task") + " to do"),
                    actionStartActivity(Widgets.openIntent(ctx, "home")))
                return@Column
            }
            if (m.agenda.isEmpty() && m.tasks.isEmpty()) {
                Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(Widgets.captureIntent(ctx, "task"))), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    Text("Nothing left for today.", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                    Text("Tap + to add a task.", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                }
                return@Column
            }
            LazyColumn(GlanceModifier.fillMaxSize()) {
                if (m.agenda.isNotEmpty()) {
                    item { WUi.SectionLabel("Schedule") }
                    items(m.agenda) { row -> AgendaRow(row) }
                }
                if (m.tasks.isNotEmpty()) {
                    item { WUi.SectionLabel("Tasks · ${m.tasks.count { !it.done }} open") }
                    items(m.tasks, itemId = { it.id }) { row -> TaskRow(row) }
                }
            }
        }
    }

    @Composable
    private fun AgendaRow(r: TodayRow) {
        val ctx = LocalContext.current
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp).clickable(actionStartActivity(Widgets.openIntent(ctx, r.route))), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(if (r.kind == TodayRow.Kind.REMINDER) R.drawable.ic_w_bell else R.drawable.ic_w_event), null, GlanceModifier.size(16.dp),
                colorFilter = ColorFilter.tint(GlanceTheme.colors.secondary))
            Spacer(GlanceModifier.width(10.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(r.title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                Text(r.detail, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }

    @Composable
    private fun TaskRow(r: TodayRow) {
        val ctx = LocalContext.current
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            CheckBox(checked = r.done, onCheckedChange = actionRunCallback<ToggleTaskAction>(actionParametersOf(ToggleTaskAction.TaskId to r.id)))
            Column(GlanceModifier.defaultWeight().clickable(actionStartActivity(Widgets.openIntent(ctx, r.route)))) {
                Text(r.title, maxLines = 2, style = TextStyle(
                    color = if (r.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface, fontSize = 14.sp,
                    textDecoration = if (r.done) TextDecoration.LineThrough else TextDecoration.None,
                ))
                if (!r.done) Text(r.detail, maxLines = 1, style = TextStyle(color = if (r.late) GlanceTheme.colors.error else GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

/** Ticks (or un-ticks) a task from the Today widget. Repeating tasks roll forward as in the app. */
class ToggleTaskAction : ActionCallback {
    companion object { val TaskId = ActionParameters.Key<Long>("taskId") }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskId] ?: return
        val app = context.app
        val t = app.db.tasks().get(id) ?: return
        TaskOps.setCompleted(app, t, !t.completed)
        Widgets.refreshNow(context)
    }
}
