package com.blacklab.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.blacklab.app.R

/**
 * Quick capture: one tap to a new note, checklist, voice note, photo note or task. Capturing never
 * shows existing content, so it works without unlocking BlackLab.
 */
class CaptureWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    private data class Btn(val mode: String, val icon: Int, val label: String)

    private val buttons = listOf(
        Btn("checklist", R.drawable.ic_w_checklist, "New checklist"),
        Btn("voice", R.drawable.ic_w_mic, "Voice note"),
        Btn("photo", R.drawable.ic_w_camera, "Photo note"),
        Btn("task", R.drawable.ic_w_task, "New task"),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme(colors = WUi.colors(context)) { Content() } }
    }

    @Composable
    private fun Content() {
        val ctx = LocalContext.current
        val size = LocalSize.current
        val tall = size.height >= 96.dp
        // Each round button needs ~48dp; the "Take a note" bar keeps at least ~110dp when it shares the row.
        val room = ((size.width.value - if (tall) 16f else 126f) / 48f).toInt().coerceIn(0, buttons.size)
        val shown = buttons.take(room)
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WUi.corner(androidx.glance.LocalContext.current) + 4.dp).background(GlanceTheme.colors.widgetBackground).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (tall) {
                NoteBar(GlanceModifier.fillMaxWidth())
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    shown.forEachIndexed { i, b ->
                        if (i > 0) Spacer(GlanceModifier.width(8.dp))
                        WUi.RoundButton(b.icon, b.label, actionStartActivity(Widgets.captureIntent(ctx, b.mode)), 44.dp, filled = i == 0)
                    }
                }
            } else {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NoteBar(GlanceModifier.defaultWeight())
                    shown.forEach { b ->
                        Spacer(GlanceModifier.width(6.dp))
                        WUi.RoundButton(b.icon, b.label, actionStartActivity(Widgets.captureIntent(ctx, b.mode)), 42.dp, filled = false)
                    }
                }
            }
        }
    }

    @Composable
    private fun NoteBar(modifier: GlanceModifier) {
        val ctx = LocalContext.current
        Box(modifier.height(42.dp).cornerRadius(21.dp).background(GlanceTheme.colors.secondaryContainer)
            .clickable(actionStartActivity(Widgets.captureIntent(ctx, "note"))), contentAlignment = Alignment.CenterStart) {
            Row(GlanceModifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(R.drawable.ic_w_note), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer))
                Spacer(GlanceModifier.width(10.dp))
                Text("Take a note…", maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer, fontSize = 14.sp))
            }
        }
    }
}

class CaptureWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaptureWidget()
}
