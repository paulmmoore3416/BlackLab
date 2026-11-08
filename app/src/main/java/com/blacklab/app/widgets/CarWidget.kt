package com.blacklab.app.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.blacklab.app.R
import com.blacklab.app.app
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.Nhtsa
import com.blacklab.app.vehicle.Telemetry
import com.blacklab.app.vehicle.VehicleLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What the Car widget shows; pure, built from the Garage, the car link and the recall list. */
data class CarModel(
    val name: String, val status: String, val fuel: String?, val fuelPct: Float?, val detail: String, val next: String?, val alert: String?, val hidden: Boolean,
)

object CarLogic {
    fun model(g: Garage.State, t: Telemetry.State, open: Int, driving: Boolean, tripMiles: Double, hide: Boolean, now: Long = System.currentTimeMillis()): CarModel {
        val v = g.vehicle
        val fuelPct = t["fuel_pct"]?.num
        val fuel = listOfNotNull(fuelPct?.let { "%.0f%% fuel".format(it) }, t["range"]?.num?.let { "%,.0f mi range".format(it) }).joinToString(" · ").ifBlank { null }
        val status = when {
            driving -> "Driving · %.1f mi".format(tripMiles)
            hide -> "Parked"
            t.parked != null -> "Parked" + (t.parked.place.substringBefore(",").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + " · " + Telemetry.ago(t.parked.time, now)
            t.lastSession > 0 -> "Last drive " + Telemetry.ago(t.lastSession, now)
            else -> "Not connected yet"
        }
        val detail = listOfNotNull(
            t["locked"]?.let { if (it.bool == true) "Locked" else "Unlocked" },
            v.odometerNow.takeIf { it > 0 }?.let { (if (v.estMiles > 0) "≈ " else "") + Garage.fmtMiles(it) + " mi" },
        ).joinToString(" · ")
        val due = Garage.due(g).firstOrNull { it.status == Garage.Status.OVERDUE || it.status == Garage.Status.DUE_SOON } ?: Garage.due(g).firstOrNull { it.status == Garage.Status.OK }
        val alert = when {
            t.activeDtcs.isNotEmpty() -> "${t.activeDtcs.size} trouble code${if (t.activeDtcs.size > 1) "s" else ""}"
            t["mil"]?.bool == true -> "Check-engine light on"
            open > 0 -> "$open open recall${if (open > 1) "s" else ""} to check"
            t["tire_warning"]?.text?.let { it != "OK" } == true -> "Tire pressure warning"
            t["locked"]?.bool == false -> "Doors unlocked"
            else -> null
        }
        return CarModel(v.name, status, fuel, fuelPct?.toFloat()?.div(100f), detail, due?.let { "${it.item.name} ${it.describe()}" }, alert, hide)
    }
}

/** Your car at a glance: fuel and range, locks, odometer, where it's parked and the next service. */
class CarWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    companion object {
        val version = MutableStateFlow(0L)
        private var job: Job? = null

        /** Debounced: live readings arrive every second, the widget redraws at most every 30 s. */
        fun refreshSoon(context: Context) {
            val app = runCatching { context.app }.getOrNull() ?: return
            if (job?.isActive == true) return
            job = app.appScope.launch {
                delay(30_000)
                version.value = version.value + 1
                runCatching { CarWidget().updateAll(context.applicationContext) }
            }
        }
    }

    private fun build(context: Context, onLock: Boolean): CarModel {
        val app = context.app
        val hide = onLock || app.settings.current.widgetPrivacy == "COUNTS"
        val live = VehicleLink.live.value
        return CarLogic.model(Garage.load(context), VehicleLink.load(context), Nhtsa.load(context).open.size, live.active && !live.sim, live.tripMiles, hide)
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val onLock = WUi.onLockScreen(context, GlanceAppWidgetManager(context).getAppWidgetId(id))
        val initial = build(context, onLock)
        provideContent {
            val v by version.collectAsState()
            val m by produceState(initial, v) { value = build(context, onLock) }
            GlanceTheme(colors = WUi.colors(context)) { Content(m) }
        }
    }

    @Composable
    private fun Content(m: CarModel) {
        val ctx = LocalContext.current
        val open = actionStartActivity(Widgets.openIntent(ctx, "garage"))
        Column(GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(WUi.corner(ctx)).background(GlanceTheme.colors.widgetBackground).padding(12.dp).clickable(open)) {
            WUi.Header(R.drawable.ic_w_car, m.name, m.status, open)
            m.fuel?.let { f ->
                Text(f, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Medium),
                    modifier = GlanceModifier.padding(start = 4.dp))
                m.fuelPct?.let { p ->
                    Spacer(GlanceModifier.height(6.dp))
                    LinearProgressIndicator(p.coerceIn(0f, 1f), GlanceModifier.fillMaxWidth().height(6.dp).padding(horizontal = 4.dp),
                        color = if (p <= 0.12f) GlanceTheme.colors.error else GlanceTheme.colors.primary, backgroundColor = GlanceTheme.colors.secondaryContainer)
                }
                Spacer(GlanceModifier.height(6.dp))
            }
            if (m.detail.isNotBlank()) Text(m.detail, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), modifier = GlanceModifier.padding(start = 4.dp))
            if (!m.hidden) m.alert?.let { a ->
                Row(GlanceModifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp)) {
                    Image(ImageProvider(R.drawable.ic_w_bell), null, GlanceModifier.size(14.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.error))
                    Spacer(GlanceModifier.width(6.dp))
                    Text(a, maxLines = 1, style = TextStyle(color = GlanceTheme.colors.error, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
            if (!m.hidden) m.next?.let { WUi.SectionLabel("Next: $it") }
        }
    }
}

class CarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CarWidget()
}
