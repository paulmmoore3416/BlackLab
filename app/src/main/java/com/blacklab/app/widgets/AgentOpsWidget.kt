package com.blacklab.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.blacklab.app.R
import com.blacklab.app.assistant.DesktopAi
import com.blacklab.app.link.AgentOps

/** Desktop AI (5.19): working / idle / offline, today's numbers and the last code review; tap opens Agent Ops. */
class AgentOpsWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        AgentOps.load(context)  // a cold start of the widget process still has the last snapshot
        val lines = AgentOps.widgetLines(AgentOps.snapshot.value, DesktopAi.status.value.state == DesktopAi.State.ONLINE, System.currentTimeMillis())
        provideContent { GlanceTheme(colors = WUi.colors(context)) { Content(lines) } }
    }

    @Composable
    private fun Content(lines: List<String>) {
        val ctx = LocalContext.current
        val open = actionStartActivity(Widgets.openIntent(ctx, "agentops"))
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WUi.corner(ctx)).background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp).clickable(open)) {
            WUi.Header(R.drawable.ic_w_desktop, "Desktop AI", lines[0], open)
            Text(lines[1], maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            Text(lines[2], maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
        }
    }

    companion object {
        suspend fun refresh(c: Context) = AgentOpsWidget().updateAll(c)
    }
}

class AgentOpsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentOpsWidget()
}
