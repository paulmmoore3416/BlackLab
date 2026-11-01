package com.blacklab.app.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
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
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.brainstorm.NoteOps

/** A Brainstorm checklist (or note) pinned to the home screen. Tick items without opening the app. */
class ChecklistWidget : GlanceAppWidget() {
    companion object { val NoteKey = longPreferencesKey("noteId") }

    override val sizeMode = SizeMode.Exact
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val onLock = WUi.onLockScreen(context, appWidgetId)
        val firstId = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[NoteKey] ?: 0L
        val initial = Widgets.checklist(context, firstId, onLock)
        provideContent {
            val noteId = currentState<Preferences>()[NoteKey] ?: 0L
            val v by Widgets.version.collectAsState()
            val model by produceState(initial, noteId, v) { value = Widgets.checklist(context, noteId, onLock) }
            GlanceTheme(colors = WUi.colors(context)) { Content(model, appWidgetId) }
        }
    }

    @Composable
    private fun Content(m: ChecklistModel, appWidgetId: Int) {
        val ctx = LocalContext.current
        val configure = actionStartActivity(
            Intent(ctx, ChecklistConfigActivity::class.java)
                .setData(Uri.parse("blacklab://widget/configure/$appWidgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(24.dp).background(GlanceTheme.colors.widgetBackground)) {
            WUi.noteColor(m.color)?.let { c -> Box(GlanceModifier.fillMaxWidth().height(6.dp).background(c)) {} }
            Column(GlanceModifier.fillMaxSize().padding(12.dp)) {
                if (m.missing) {
                    WUi.Header(R.drawable.ic_w_checklist, "Checklist", null, configure)
                    Column(GlanceModifier.fillMaxSize().clickable(configure), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (m.noteId > 0) "That note was deleted." else "No note chosen yet.", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                        Text("Tap to pick a checklist", style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 13.sp))
                    }
                    return@Column
                }
                val open = actionStartActivity(Widgets.openIntent(ctx, "note/${m.noteId}"))
                WUi.Header(if (m.isChecklist) R.drawable.ic_w_checklist else R.drawable.ic_w_note, if (m.hideDetails) "Note" else m.title,
                    if (m.isChecklist) "${m.doneCount} of ${m.total} done" else null, open,
                    R.drawable.ic_w_add, "Add item", actionStartActivity(Widgets.captureIntent(ctx, "additem", m.noteId)))
                if (m.hideDetails) {
                    WUi.Hidden(listOf(if (m.isChecklist) TodayLogic.countLabel(m.total - m.doneCount, "item") + " left" else "Pinned note"), open)
                    return@Column
                }
                if (m.rows.isEmpty()) {
                    Column(GlanceModifier.fillMaxSize().clickable(actionStartActivity(Widgets.captureIntent(ctx, "additem", m.noteId))), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                        Text("Empty list", style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                        Text("Tap + to add items", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
                    }
                    return@Column
                }
                LazyColumn(GlanceModifier.fillMaxSize()) {
                    if (!m.isChecklist) items(m.rows, itemId = { it.first.toLong() }) { (_, item) ->
                        Text(item.text, modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp).clickable(open),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp))
                    } else items(m.rows, itemId = { it.first.toLong() }) { (index, item) ->
                        Row(GlanceModifier.fillMaxWidth().padding(start = (item.indent * 16).dp), verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(
                                checked = item.done,
                                onCheckedChange = actionRunCallback<ToggleNoteItemAction>(actionParametersOf(
                                    ToggleNoteItemAction.NoteId to m.noteId, ToggleNoteItemAction.Index to index, ToggleNoteItemAction.Text to item.text,
                                )),
                                text = item.text,
                                maxLines = 3,
                                style = TextStyle(
                                    color = if (item.done) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface, fontSize = 14.sp,
                                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

class ChecklistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChecklistWidget()
}

/**
 * Ticks an item of a pinned checklist. The item text is checked too, so a list that was edited in
 * the app since the widget last drew is never ticked in the wrong place.
 */
class ToggleNoteItemAction : ActionCallback {
    companion object {
        val NoteId = ActionParameters.Key<Long>("noteId")
        val Index = ActionParameters.Key<Int>("index")
        val Text = ActionParameters.Key<String>("text")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val app = context.app
        val n = app.db.notes().get(parameters[NoteId] ?: return) ?: return
        val index = parameters[Index] ?: return
        val text = parameters[Text] ?: return
        if (n.checklist) {
            val items = NoteOps.items(n).toMutableList()
            val i = if (items.getOrNull(index)?.text == text) index else items.indexOfFirst { it.text == text }
            if (i < 0) { Widgets.refreshNow(context); return }
            items[i] = items[i].copy(done = !items[i].done)
            NoteOps.save(app, n.copy(items = NoteOps.itemsJson(items)))
        }
        Widgets.refreshNow(context)
    }
}
