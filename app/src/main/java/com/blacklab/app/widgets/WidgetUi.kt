package com.blacklab.app.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.blacklab.app.R

/** Small building blocks shared by the BlackLab widgets. */
internal object WUi {

    fun onLockScreen(context: Context, appWidgetId: Int): Boolean = runCatching {
        AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) ==
            AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD
    }.getOrDefault(false)

    @Composable
    fun Header(icon: Int, title: String, subtitle: String?, onTitle: Action?, actionIcon: Int? = null, actionLabel: String = "", onAction: Action? = null, accent: ColorProvider = GlanceTheme.colors.primary) {
        Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, end = 0.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(icon), null, GlanceModifier.size(20.dp), colorFilter = ColorFilter.tint(accent))
            Spacer(GlanceModifier.width(8.dp))
            Column(GlanceModifier.defaultWeight().let { if (onTitle != null) it.clickable(onTitle) else it }) {
                Text(title, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                if (!subtitle.isNullOrBlank()) Text(subtitle, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp))
            }
            if (actionIcon != null && onAction != null) RoundButton(actionIcon, actionLabel, onAction, 36.dp)
        }
    }

    @Composable
    fun RoundButton(icon: Int, label: String, onClick: Action, size: Dp = 40.dp, filled: Boolean = true) {
        Box(
            GlanceModifier.size(size).cornerRadius(size / 2)
                .background(if (filled) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.secondaryContainer)
                .clickable(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(icon), label, GlanceModifier.size(size * 0.5f),
                colorFilter = ColorFilter.tint(if (filled) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSecondaryContainer))
        }
    }

    @Composable
    fun SectionLabel(text: String) {
        Text(text.uppercase(), modifier = GlanceModifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp),
            style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
    }

    @Composable
    fun Hidden(lines: List<String>, onOpen: Action) {
        Column(GlanceModifier.fillMaxWidth().padding(8.dp).clickable(onOpen), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(ImageProvider(R.drawable.ic_w_lock), null, GlanceModifier.size(22.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant))
            Spacer(GlanceModifier.size(6.dp))
            lines.forEach { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp)) }
            Text("Details hidden · tap to open BlackLab", style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp))
        }
    }

    fun noteColor(index: Int): ColorProvider? {
        val c = com.blacklab.app.brainstorm.NoteStyle.colors.getOrNull(index) ?: return null
        if (index == 0) return null
        return androidx.glance.color.ColorProvider(day = Color(c.light), night = Color(c.dark))
    }
}
