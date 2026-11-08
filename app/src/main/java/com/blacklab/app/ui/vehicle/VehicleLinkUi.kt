package com.blacklab.app.ui.vehicle

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blacklab.app.app
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LabeledSwitchRow
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.Shell
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.ui.theme.readable
import com.blacklab.app.vehicle.Bluelink
import com.blacklab.app.vehicle.CarCompanion
import com.blacklab.app.vehicle.Dtcs
import com.blacklab.app.vehicle.Garage
import com.blacklab.app.vehicle.ObdReader
import com.blacklab.app.vehicle.Telemetry
import com.blacklab.app.vehicle.VehicleLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private fun Context.activity(): Activity? { var c: Context? = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }
private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

/** Live car data, diagnostics and where you parked — shown near the top of the Garage. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun LazyListScope.liveCarSection(st: Telemetry.State, live: VehicleLink.Live, expanded: Boolean, onExpand: () -> Unit, onSetup: () -> Unit, onRefresh: () -> Unit, onCodes: () -> Unit,
                                 next: Garage.Due? = null, severe: Boolean = false, onLogFill: (Telemetry.PendingFill) -> Unit = {}, onDismissFill: () -> Unit = {}) {
    item { SectionHeader("From the car", action = "Car connection", onAction = onSetup) }
    st.pendingFill?.let { f ->
        item(key = "pending-fill") {
            BlCard(Modifier.fillMaxWidth(), accent = Mint) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalGasStation, null, tint = Mint.readable())
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Filled up? About %.1f gal".format(f.gallons), style = MaterialTheme.typography.titleSmall)
                        Text("Fuel went from %.0f%% to %.0f%% · %s".format(f.fromPct, f.toPct, Telemetry.ago(f.time)) + (f.station.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { onLogFill(f) }) { Text("Log fill-up") }
                    TextButton(onClick = onDismissFill) { Text("Not a fill-up") }
                }
            }
        }
    }
    item {
        val ctx = LocalContext.current
        BlCard(Modifier.fillMaxWidth(), accent = if (live.active) Mint else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (live.active) Icons.Rounded.BluetoothConnected else Icons.Rounded.Bluetooth, null, tint = if (live.active) Mint.readable() else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    val car = st.link.carName.ifBlank { "your car" }
                    Text(when {
                        live.sim && live.active -> "Simulated drive · %.1f mi".format(live.tripMiles) + (live.mph?.let { " · %.0f mph".format(it) } ?: "")
                        live.ending -> "Drive ending…"
                        live.active -> "Driving · %.1f mi".format(live.tripMiles) + (live.mph?.let { " · %.0f mph".format(it) } ?: "")
                        st.link.carAddress.isBlank() -> "Not connected to a car yet"
                        else -> "Parked · connects to $car"
                    }, style = MaterialTheme.typography.titleSmall)
                    val sub = listOfNotNull(
                        live.via.takeIf { live.active && it.isNotBlank() }?.let { "via $it" },
                        live.obd.ifBlank { null }, live.bluelink.ifBlank { null },
                        st.lastSession.takeIf { !live.active && it > 0 }?.let { "last drive ${Telemetry.ago(it)}" },
                    ).joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.link.carAddress.isBlank()) Text("Connect the phone to the Santa Fe's Bluetooth once (or pick it under Car connection) and BlackLab will log every drive and read the car automatically.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.link.bluelink) androidx.compose.material3.IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh from Bluelink") }
                if (live.active) TextButton(onClick = { VehicleLink.stop(ctx, "manual", now = true) }) { Text("End trip") }
            }
        }
    }
    // Gauges: fuel and how much of the next service interval is used
    val fuel = st["fuel_pct"]?.num
    val used = next?.let { d ->
        val miles = if (severe) d.item.severeMiles ?: d.item.miles else d.item.miles
        val months = if (severe) d.item.severeMonths ?: d.item.months else d.item.months
        when {
            miles != null && d.milesLeft != null -> 1.0 - d.milesLeft.toDouble() / miles
            months != null && d.daysLeft != null -> 1.0 - d.daysLeft / (months * 30.44)
            else -> null
        }
    }
    if (fuel != null || used != null) item(key = "gauges") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (fuel != null) FuelRing(fuel, "fuel", st["range"]?.let { "${Telemetry.format(it)} range" })
            if (used != null && next != null) ServiceRing(used, next.item.name.substringBefore(" (").substringBefore(" &"), next.describe().substringBefore(" ("),
                warn = next.status == Garage.Status.OVERDUE || next.status == Garage.Status.DUE_SOON)
        }
    }
    // Headline values, then everything else grouped
    val headline = listOf("fuel_pct", "range", "battery_v", "coolant", "inst_mpg", "speed", "locked", "tire_warning").filter { fuel == null || (it != "fuel_pct" && it != "range") }.mapNotNull { st[it] }
    if (headline.isNotEmpty()) item {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            headline.forEach { m -> MetricChip(m) }
        }
    }
    val codes = st.activeDtcs
    if (codes.isNotEmpty() || st["mil"]?.bool == true) item {
        BlCard(Modifier.fillMaxWidth(), onClick = onCodes, accent = Danger) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Warning, null, tint = Danger.readable())
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (codes.isEmpty()) "Check-engine light is on" else "${codes.size} trouble code${if (codes.size > 1) "s" else ""}", style = MaterialTheme.typography.titleSmall)
                    codes.take(3).forEach { d -> Text("${d.code} — ${Dtcs.describe(d.code)}" + if (d.kind != "stored") " (${d.kind})" else "", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    st.parked?.let { p ->
        item {
            val ctx = LocalContext.current
            BlCard(Modifier.fillMaxWidth(), onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${p.lat},${p.lon}(Car)")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalParking, null, tint = Cobalt.readable())
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (live.active) "Last parked" else "Parked", style = MaterialTheme.typography.titleSmall)
                        Text(listOf(p.place.ifBlank { "%.5f, %.5f".format(p.lat, p.lon) }, Telemetry.ago(p.time), p.source.label).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.Directions, "Directions to the car")
                }
            }
        }
    }
    val rest = st.snapshot.values.filter { it.key !in headline.map { h -> h.key } && it.key != "fuel_pct" && it.key != "range" }
    if (rest.isNotEmpty()) {
        item { TextButton(onClick = onExpand) { Text(if (expanded) "Hide details" else "All vehicle data (${st.snapshot.size})") } }
        if (expanded) Telemetry.Group.entries.forEach { g ->
            val ms = st.snapshot.values.filter { Telemetry.def(it.key)?.group == g }.sortedBy { m -> Telemetry.DEFS.indexOfFirst { it.key == m.key } }
            if (ms.isNotEmpty()) item(key = "grp-${g.name}") {
                BlCard(Modifier.fillMaxWidth()) {
                    Text(g.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    ms.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Telemetry.def(m.key)?.label ?: m.key, style = MaterialTheme.typography.bodyMedium)
                                Text("${m.source.label} · ${Telemetry.ago(m.time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(Telemetry.format(m), style = MaterialTheme.typography.titleSmall, color = if (Telemetry.isAlert(m)) Danger.readable() else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricChip(m: Telemetry.Metric) {
    val alert = Telemetry.isAlert(m)
    BlCard(accent = if (alert) Danger else null, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(Telemetry.def(m.key)?.label ?: m.key, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Telemetry.format(m), style = MaterialTheme.typography.titleSmall, color = if (alert) Danger.readable() else MaterialTheme.colorScheme.onSurface)
    }
}

/** Trip log with totals for the week, month and year, and business miles for taxes or reimbursement. */
fun LazyListScope.tripsSection(st: Telemetry.State, tank: Double, onTrip: (Telemetry.Trip) -> Unit, onExport: () -> Unit) {
    item { SectionHeader("Trips", action = if (st.trips.any { it.end != null }) "Export CSV" else null, onAction = onExport) }
    val done = st.trips.filter { it.end != null }
    val real = done.filter { !it.simulated }
    if (done.isEmpty()) {
        item { Text("Drives are logged automatically while the phone is connected to the car: distance, time, speed, fuel used, where you started and parked.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    item {
        val now = System.currentTimeMillis()
        val rate = st.link.ratePerMile
        val week = Telemetry.totals(done, Telemetry.weekStart(), now + 1, rate)
        val month = Telemetry.totals(done, Telemetry.monthStart(), now + 1, rate)
        val year = Telemetry.totals(done, Telemetry.yearStart(), now + 1, rate)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            com.blacklab.app.ui.components.StatTile("This week", "%.0f mi".format(week.miles), Icons.Rounded.Route, Cobalt, Modifier.weight(1f))
            com.blacklab.app.ui.components.StatTile("This month", "%.0f mi".format(month.miles), Icons.Rounded.CalendarMonth, Mint, Modifier.weight(1f))
            com.blacklab.app.ui.components.StatTile("Business ${java.time.Year.now()}", "%.0f mi".format(year.byPurpose[Telemetry.Purpose.BUSINESS] ?: 0.0), Icons.Rounded.Work, Amber, Modifier.weight(1f))
        }
        if ((year.byPurpose[Telemetry.Purpose.BUSINESS] ?: 0.0) > 0) Text("About $%,.0f at $%.3f/mi this year — set your rate under Car connection.".format(year.deductible, rate),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
    if (real.isNotEmpty()) item(key = "weekly-chart") { WeeklyMilesChart(remember(st.trips) { Telemetry.weeklyMiles(st.trips) }) }
    items(done.take(25), key = { "trip-" + it.id }) { t ->
        BlCard(Modifier.fillMaxWidth(), onClick = { onTrip(t) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("%.1f mi · %s".format(t.miles, Telemetry.duration(t.durationSec)) + (t.mpg(tank)?.let { " · %.1f mpg".format(it) } ?: ""), style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(Garage.timeLabel(t.start) + " " + clock(t.start),
                        listOf(t.startPlace, t.endPlace).filter { it.isNotBlank() }.map { it.substringBefore(",") }.joinToString(" → ").ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.note.isNotBlank()) Text(t.note, style = MaterialTheme.typography.bodySmall)
                }
                if (t.simulated) Pill("Simulated", Cobalt) else Pill(t.purpose.label + if (t.rule.isNotBlank()) " · auto" else "", if (t.purpose == Telemetry.Purpose.BUSINESS) Amber else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { z -> "%d:%02d %s".format(if (z.hour % 12 == 0) 12 else z.hour % 12, z.minute, if (z.hour < 12) "AM" else "PM") }

@Composable
fun TripDialog(t: Telemetry.Trip, tank: Double, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var purpose by remember { mutableStateOf(t.purpose) }
    var note by remember { mutableStateOf(t.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("%.1f mi · %s".format(t.miles, Garage.timeLabel(t.start))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val facts = listOfNotNull(
                "${clock(t.start)} – ${t.end?.let(::clock) ?: "now"} (${Telemetry.duration(t.durationSec)})",
                t.startPlace.ifBlank { null }?.let { "From: $it" }, t.endPlace.ifBlank { null }?.let { "To: $it" },
                t.avgMph?.let { "Average %.0f mph moving · top %.0f mph".format(it, t.maxMph) },
                "Moving ${Telemetry.duration(t.movingSec)} · idle ${Telemetry.duration(t.idleSec)}".takeIf { t.movingSec + t.idleSec > 60 },
                t.mpg(tank)?.let { "%.1f mpg".format(it) + (t.fuelGal?.let { g -> " · %.2f gal used".format(g) } ?: " (from the fuel gauge)") },
                if (t.odoStart != null && t.odoEnd != null && t.odoEnd > t.odoStart) "Odometer ${Garage.fmtMiles(t.odoStart)} → ${Garage.fmtMiles(t.odoEnd)}" else null,
                (t.hardBrakes + t.hardAccels).takeIf { it > 0 }?.let { "${t.hardBrakes} hard brake${if (t.hardBrakes == 1) "" else "s"}, ${t.hardAccels} hard acceleration${if (t.hardAccels == 1) "" else "s"}" },
                "Measured by " + t.sources.joinToString(", ") { it.label }.ifBlank { "the phone" },
            )
            facts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            DropdownField("Purpose", Telemetry.Purpose.entries.map { it to it.label }, purpose, { purpose = it }, Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (who / why)") })
            if (t.endLat != null && t.endLon != null) TextButton(onClick = {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${t.endLat},${t.endLon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Show where it ended") }
            if (t.endLat != null && t.endLon != null && !t.simulated) {
                var weekdays by remember { mutableStateOf(purpose == Telemetry.Purpose.COMMUTE || purpose == Telemetry.Purpose.BUSINESS) }
                var made by remember { mutableStateOf(false) }
                HorizontalDivider()
                Text("Tag automatically", style = MaterialTheme.typography.labelLarge)
                Text("Future trips that start or end within about 250 m of ${t.endPlace.substringBefore(",").ifBlank { "this spot" }} get the purpose above.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LabeledSwitchRow("Weekdays only", checked = weekdays) { weekdays = it }
                OutlinedButton(enabled = !made, onClick = {
                    VehicleLink.addRule(ctx, Telemetry.Rule(java.util.UUID.randomUUID().toString().take(8), t.endPlace.substringBefore(",").ifBlank { "Place %.4f, %.4f".format(t.endLat, t.endLon) },
                        t.endLat, t.endLon, 250.0, purpose, weekdaysOnly = weekdays))
                    made = true
                }) { Text(if (made) "Rule added — see Car connection" else "Always tag trips here ${purpose.label}") }
            }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete this trip", color = Danger.readable()) }
        }
    }, confirmButton = {
        TextButton(onClick = { VehicleLink.setTrip(ctx, t.id) { it.copy(purpose = purpose, note = note.trim()) }; onDismiss() }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (confirmDelete) ConfirmDialog("Delete trip?", "%.1f mi on %s".format(t.miles, Garage.timeLabel(t.start)), onDismiss = { confirmDelete = false }) { VehicleLink.deleteTrip(ctx, t.id); onDismiss() }
}

@Composable
fun CodesDialog(st: Telemetry.State, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Trouble codes") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.dtcs.isEmpty()) Text("No codes read yet. They're read from the OBD-II adapter at the start of every drive.")
            st.dtcs.forEach { d ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.code, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Pill(if (!d.active) "Cleared" else d.kind.replaceFirstChar { it.uppercase() }, if (!d.active) Success else if (Dtcs.severe(d.code)) Danger else Amber)
                    }
                    Text(Dtcs.describe(d.code), style = MaterialTheme.typography.bodySmall)
                    Text("First seen ${Garage.timeLabel(d.firstSeen)} · last ${Garage.timeLabel(d.lastSeen)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Descriptions are the generic SAE meaning. A flashing check-engine light or a misfire (P03xx) means stop driving hard and get it looked at soon. P1326 on a Hyundai is a knock-sensor/engine bearing warning covered by Hyundai's extended warranty campaign — call the dealer.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }, dismissButton = {
        if (st.activeDtcs.isNotEmpty()) TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("2023 Hyundai Santa Fe " + st.activeDtcs.joinToString(" ") { it.code }))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Look up") }
    })
}

/** Car connection: which Bluetooth device is the car, the OBD-II adapter, Bluelink, trip settings. */
@SuppressLint("MissingPermission")
@Composable
fun CarConnectionDialog(shell: Shell, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.app
    val st by VehicleLink.state.collectAsStateWithLifecycle()
    val liveNow by VehicleLink.live.collectAsStateWithLifecycle()
    val link = st.link
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val btOk = remember(refresh) { ctx.granted(Manifest.permission.BLUETOOTH_CONNECT) }
    val locOk = remember(refresh) { ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) }
    val bgOk = remember(refresh) { ctx.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val bonded = remember(refresh, btOk) { VehicleLink.bonded(ctx) }
    val paired = remember(refresh, link.associationId) { CarCompanion.paired(ctx) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val bgPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val cdmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        r.data?.getParcelableExtra(android.companion.CompanionDeviceManager.EXTRA_ASSOCIATION, android.companion.AssociationInfo::class.java)?.let { CarCompanion.observe(ctx, it) }
        refresh++
    }
    val scanned = remember { mutableStateListOf<Pair<String, String>>() }
    var scanning by remember { mutableStateOf(false) }
    var obdTest by remember { mutableStateOf("") }
    var blUser by remember { mutableStateOf(app.secrets.get(Bluelink.USER).orEmpty()) }
    var blPass by remember { mutableStateOf("") }
    var blPin by remember { mutableStateOf("") }
    var blStatus by remember { mutableStateOf(if (Bluelink.configured(app.secrets)) "Signed in" + (link.bluelinkVin.takeIf { it.isNotBlank() }?.let { " · VIN …${it.takeLast(6)}" } ?: "") else "") }
    var rate by remember { mutableStateOf("%.3f".format(link.ratePerMile)) }

    DisposableEffect(Unit) { onDispose { rate.toDoubleOrNull()?.takeIf { it in 0.0..5.0 }?.let { r -> VehicleLink.setLink(ctx) { it.copy(ratePerMile = r) } } } }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Car connection") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- the car's Bluetooth
            Text("Car Bluetooth", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("When the phone connects to this, BlackLab starts a drive: trip log, Android Auto data, the OBD-II adapter and Bluelink.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!btOk) OutlinedButton(onClick = { perms.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)) }) {
                Icon(Icons.Rounded.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Allow Nearby devices")
            } else {
                val options = listOf("" to "None") + bonded.map { it.address to (it.name + if (it.isCar) "  (car)" else "") }
                DropdownField("Car", options, link.carAddress, { addr ->
                    val name = bonded.firstOrNull { it.address == addr }?.name.orEmpty()
                    if (addr != link.carAddress) CarCompanion.unpair(ctx)
                    VehicleLink.setLink(ctx) { it.copy(carAddress = addr, carName = name) }
                }, Modifier.fillMaxWidth())
                if (link.carAddress.isNotBlank()) {
                    if (paired) Text("✓ Paired as a companion device — drives start in the background, even after a restart.", style = MaterialTheme.typography.bodySmall, color = Success.readable())
                    else OutlinedButton(onClick = {
                        val act = ctx.activity() ?: return@OutlinedButton
                        CarCompanion.associate(act, link.carAddress, launch = { cdmLauncher.launch(IntentSenderRequest.Builder(it).build()) }, onError = { shell.toast(it) })
                    }) { Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Pair for automatic start") }
                }
            }
            LabeledSwitchRow("Log drives automatically", checked = link.autoTrips) { v -> VehicleLink.setLink(ctx) { it.copy(autoTrips = v) } }
            LabeledSwitchRow("Save start and end places", "Addresses for the mileage log and where you parked. Only the endpoints are kept, not the route.", checked = link.recordPlaces) { v -> VehicleLink.setLink(ctx) { it.copy(recordPlaces = v) } }
            LabeledSwitchRow("Trip summary when you park", checked = link.tripSummary) { v -> VehicleLink.setLink(ctx) { it.copy(tripSummary = v) } }
            LabeledSwitchRow("Notice fill-ups", "When the fuel level jumps, offer to log the fill-up with the gallons filled in.", checked = link.detectFills) { v -> VehicleLink.setLink(ctx) { it.copy(detectFills = v) } }
            when {
                !locOk -> OutlinedButton(onClick = { perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location for trip distance") }
                !bgOk -> OutlinedButton(onClick = { bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) { Text("Allow location all the time") }
                else -> {}
            }
            if (locOk && !bgOk) Text("“All the time” lets trips log when BlackLab starts in the background; otherwise only the car's own speed is used for distance.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(rate, { rate = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.weight(1f), label = { Text("Mileage rate ($/mi)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(Modifier.size(8.dp))
                DropdownField("New trips", Telemetry.Purpose.entries.map { it to it.label }, link.defaultPurpose, { p -> VehicleLink.setLink(ctx) { it.copy(defaultPurpose = p) } }, Modifier.weight(1f))
            }
            // ---- automatic trip tags
            Text("Trip rules", style = MaterialTheme.typography.labelLarge)
            if (link.rules.isEmpty()) Text("Open any trip and use “Always tag trips here” to tag trips to work, clients or the doctor automatically.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            link.rules.forEach { rule ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${rule.purpose.label}${if (rule.weekdaysOnly) " · weekdays" else ""} · within ${rule.radiusM.toInt()} m", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { VehicleLink.deleteRule(ctx, rule.id) }) { Text("Remove", color = Danger.readable()) }
                }
            }
            if (link.rules.isNotEmpty()) TextButton(onClick = { shell.toast("Tagged ${VehicleLink.applyRules(ctx)} earlier trip(s)") }) { Text("Apply rules to earlier trips") }
            HorizontalDivider()

            // ---- OBD-II
            Text("OBD-II adapter (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("A Bluetooth ELM327-type plug in the port under the dashboard (OBDLink, Veepeak, Vgate…). Adds RPM, coolant and oil temperature, battery voltage, fuel flow and real MPG, the VIN and check-engine codes.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (btOk) {
                val classic = bonded.filter { it.address != link.carAddress }.map { it.address to (it.name + if (it.looksObd) "  (OBD)" else "") }
                val ble = scanned.map { it.first to "${it.second}  (BLE)" }
                DropdownField("Adapter", listOf("" to "None") + classic + ble.filter { b -> classic.none { it.first == b.first } } +
                    (if (link.obdAddress.isNotBlank() && (classic + ble).none { it.first == link.obdAddress }) listOf(link.obdAddress to link.obdName.ifBlank { link.obdAddress }) else emptyList()),
                    link.obdAddress, { addr ->
                        val isBle = scanned.any { it.first == addr } && bonded.none { it.address == addr }
                        val name = (bonded.firstOrNull { it.address == addr }?.name ?: scanned.firstOrNull { it.first == addr }?.second).orEmpty()
                        VehicleLink.setLink(ctx) { it.copy(obdAddress = addr, obdName = name, obdBle = if (addr == it.obdAddress) it.obdBle else isBle) }
                    }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !scanning, onClick = {
                        if (!ctx.granted(Manifest.permission.BLUETOOTH_SCAN)) { perms.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN)); return@OutlinedButton }
                        scanning = true
                        scope.launch { scanBle(ctx) { addr, name -> if (scanned.none { it.first == addr }) scanned += addr to name }; scanning = false }
                    }) { Text(if (scanning) "Scanning…" else "Find BLE adapters") }
                    if (link.obdAddress.isNotBlank()) OutlinedButton(enabled = !liveNow.active, onClick = {
                        obdTest = "Connecting… (engine on, adapter plugged in)"
                        scope.launch(Dispatchers.IO) {
                            val r = ObdReader(ctx, VehicleLink.state.value.link, onStatus = { s -> obdTest = s }, onMetrics = { VehicleLink.put(ctx, it) }, onCodes = { a, b, c -> VehicleLink.putCodes(ctx, a, b, c) })
                            val ok = runCatching { r.connect().also { if (it) r.readOnce() } }.getOrDefault(false)
                            r.close()
                            val s = VehicleLink.state.value
                            obdTest = if (ok) "Works — " + listOfNotNull(s["vin"]?.text?.let { "VIN $it" }, s["battery_v"]?.let { "battery ${Telemetry.format(it)}" }, "${s.activeDtcs.size} codes").joinToString(", ") else obdTest
                        }
                    }) { Text("Test") }
                }
                if (obdTest.isNotBlank()) Text(obdTest, style = MaterialTheme.typography.bodySmall)
                if (link.obdAddress.isNotBlank() && !link.obdBle) Text("Classic adapters must be paired in Android's Bluetooth settings first (PIN is usually 1234 or 0000).",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()

            // ---- Bluelink
            Text("Hyundai Bluelink (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("What the car last reported to Hyundai: odometer, fuel and range, doors, windows and locks, tire-pressure and fluid warnings, 12V battery and where it's parked. " +
                "Your sign-in is encrypted on this phone and only sent to Hyundai. Hyundai doesn't publish this API, so it can stop working when they change it. BlackLab only reads saved status and never wakes the car.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!Bluelink.configured(app.secrets) || !link.bluelink) {
                OutlinedTextField(blUser, { blUser = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Bluelink email") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(blPass, { blPass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(blPin, { blPin = it.filter { c -> c.isDigit() }.take(4) }, Modifier.fillMaxWidth(), label = { Text("Bluelink PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Button(enabled = blUser.contains("@") && blPass.isNotBlank(), onClick = {
                    blStatus = "Signing in…"
                    scope.launch {
                        app.secrets.put(Bluelink.USER, blUser); app.secrets.put(Bluelink.PASS, blPass); app.secrets.put(Bluelink.PIN, blPin)
                        val r = runCatching { Bluelink.cars(app.secrets) }
                        r.onSuccess { cars ->
                            val v = Garage.load(ctx).vehicle
                            val car = cars.firstOrNull { it.vin.equals(v.vin, true) } ?: cars.firstOrNull()
                            if (car == null) { blStatus = "Signed in, but there are no vehicles on this account"; return@onSuccess }
                            VehicleLink.setLink(ctx) { it.copy(bluelink = true, bluelinkVin = car.vin) }
                            blPass = ""
                            blStatus = "Signed in · ${listOf(car.year, car.name.ifBlank { car.model }).joinToString(" ").trim()}"
                            blStatus = VehicleLink.refreshBluelink(ctx)
                        }.onFailure { e -> Bluelink.signOut(app.secrets); blStatus = e.message ?: "Couldn't sign in" }
                    }
                }) { Text("Sign in to Bluelink") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { blStatus = "Checking…"; scope.launch { blStatus = VehicleLink.refreshBluelink(ctx) } }) { Text("Refresh now") }
                    TextButton(onClick = { Bluelink.signOut(app.secrets); VehicleLink.setLink(ctx) { it.copy(bluelink = false) }; blStatus = "Signed out"; blPass = "" }) { Text("Sign out", color = Danger.readable()) }
                }
            }
            if (blStatus.isNotBlank()) Text(blStatus, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Practice drive (simulator)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("A four-minute pretend drive through a pretend OBD-II adapter, to see trips, live values, MPG, alerts and the drive notification work without the car. " +
                "It's marked Simulated, left out of totals and exports, and never changes your odometer or the car's real values.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var simCode by remember { mutableStateOf(false) }
            LabeledSwitchRow("Include a check-engine code", "Adds a pretend P0420 so you can see the alert.", checked = simCode) { simCode = it }
            OutlinedButton(enabled = !liveNow.active, onClick = {
                if (!btOk && !locOk) { shell.toast("Allow Nearby devices or location first (Android needs one to run a drive)"); return@OutlinedButton }
                runCatching { VehicleLink.simulate(ctx, simCode) }.onSuccess { shell.toast("Simulated drive started — watch the notification and From the car") }
                    .onFailure { shell.toast("Couldn't start: ${it.message}") }
            }) { Text(if (liveNow.active && liveNow.sim) "Simulating…" else "Start a practice drive") }
            HorizontalDivider()
            Text("Android Auto", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("While BlackLab is open on the car screen, it also reads what the head unit shares — model, fuel level, range and odometer on most Hyundai units, and speed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/** Looks for Bluetooth LE OBD adapters for 8 seconds. */
@SuppressLint("MissingPermission")
private suspend fun scanBle(ctx: Context, found: (String, String) -> Unit) {
    val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
    val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
            if (Regex("obd|elm|v-?link|vgate|icar|veepeak|obdlink|ios-vlink|android-vlink|konnwei|scan", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                result.scanRecord?.serviceUuids?.any { it.uuid.toString().startsWith("0000fff0") || it.uuid.toString().startsWith("0000ffe0") || it.uuid.toString().startsWith("000018f0") } == true)
                found(result.device.address, name)
        }
    }
    withContext(Dispatchers.Main) { runCatching { scanner.startScan(cb) } }
    delay(8000)
    withContext(Dispatchers.Main) { runCatching { scanner.stopScan(cb) } }
}
