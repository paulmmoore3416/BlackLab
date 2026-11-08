package com.blacklab.app.ui.vehicle

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blacklab.app.ui.theme.readable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.BlTopBar
import com.blacklab.app.ui.components.ConfirmDialog
import com.blacklab.app.ui.components.DropdownField
import com.blacklab.app.ui.components.LocalShell
import com.blacklab.app.ui.components.Pill
import com.blacklab.app.ui.components.SectionHeader
import com.blacklab.app.ui.components.StatTile
import com.blacklab.app.ui.components.TextInputDialog
import com.blacklab.app.ui.theme.Amber
import com.blacklab.app.ui.theme.Cobalt
import com.blacklab.app.ui.theme.Danger
import com.blacklab.app.ui.theme.Mint
import com.blacklab.app.ui.theme.Success
import com.blacklab.app.vehicle.Garage
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.util.UUID

/** Your 2023 Hyundai Santa Fe: what's due, fuel economy, service history and the vehicle profile. */
@Composable
fun GarageScreen() {
    val shell = LocalShell.current
    val ctx = LocalContext.current
    remember { Garage.load(ctx) }
    val st by Garage.state.collectAsStateWithLifecycle()
    val v = st.vehicle
    remember { com.blacklab.app.vehicle.Nhtsa.load(ctx) }
    val nhtsa by com.blacklab.app.vehicle.Nhtsa.state.collectAsStateWithLifecycle()
    val pace = remember(st, com.blacklab.app.vehicle.VehicleLink.state.collectAsStateWithLifecycle().value.trips.size) { com.blacklab.app.vehicle.ServiceReminders.pace(ctx) }
    val due = remember(st, pace) { Garage.due(st, milesPerDay = pace) }
    var fillPrefill by remember { mutableStateOf<com.blacklab.app.vehicle.Telemetry.PendingFill?>(null) }
    var recall by remember { mutableStateOf<com.blacklab.app.vehicle.Nhtsa.Recall?>(null) }
    var showSpecs by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(v.vin) {
        // First look (or a week on): check NHTSA for recalls and decode the VIN
        if (System.currentTimeMillis() - com.blacklab.app.vehicle.Nhtsa.load(ctx).checkedAt > 7 * 86_400_000L) com.blacklab.app.vehicle.Nhtsa.refresh(ctx)
    }
    val stats = remember(st.fuel) { Garage.fuelStats(st.fuel) }
    var editOdo by remember { mutableStateOf(false) }
    var editVehicle by remember { mutableStateOf(false) }
    var fuelDialog by remember { mutableStateOf(false) }
    var serviceFor by remember { mutableStateOf<Garage.ServiceItem?>(null) }
    var serviceAny by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<Garage.ServiceItem?>(null) }
    var deleteFuel by remember { mutableStateOf<Garage.Fuel?>(null) }
    var showFacts by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    remember { com.blacklab.app.vehicle.VehicleLink.load(ctx) }
    val car by com.blacklab.app.vehicle.VehicleLink.state.collectAsStateWithLifecycle()
    val live by com.blacklab.app.vehicle.VehicleLink.live.collectAsStateWithLifecycle()
    var setup by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    var codes by remember { mutableStateOf(false) }
    var trip by remember { mutableStateOf<com.blacklab.app.vehicle.Telemetry.Trip?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Scaffold(topBar = {
        BlTopBar(v.name, subtitle = if (v.odometer > 0) "${if (v.estMiles > 0) "≈ " else ""}${Garage.fmtMiles(v.odometerNow)} mi" + (if (v.odometerAt > 0) " · updated ${Garage.timeLabel(v.odometerAt)}" else "") else "Set your odometer to start tracking",
            onBack = { shell.back() }) {
            IconButton(onClick = { editVehicle = true }) { Icon(Icons.Rounded.Edit, "Edit vehicle") }
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Car connection") }, leadingIcon = { Icon(Icons.Rounded.Bluetooth, null) }, onClick = { menu = false; setup = true })
                    DropdownMenuItem(text = { Text("Add a custom service item") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = {
                        menu = false; editItem = Garage.ServiceItem("custom-" + UUID.randomUUID().toString().take(8), "", 10_000, 12)
                    })
                    DropdownMenuItem(text = { Text("Restore default schedule") }, leadingIcon = { Icon(Icons.Rounded.Restore, null) }, onClick = {
                        menu = false; Garage.resetSchedule(ctx); shell.toast("Default intervals restored (your history is kept)")
                    })
                }
            }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(if (v.estMiles > 0) "Odometer (est.)" else "Odometer", if (v.odometer > 0) Garage.fmtMiles(v.odometerNow) else "—", Icons.Rounded.Speed, Cobalt, Modifier.weight(1f)) { editOdo = true }
                    StatTile("Avg MPG", stats.avgMpg?.let { "%.1f".format(it) } ?: "—", Icons.Rounded.LocalGasStation, Mint, Modifier.weight(1f)) { fuelDialog = true }
                    StatTile("Fuel · 30 days", if (stats.monthCost > 0) "$" + "%.0f".format(stats.monthCost) else "—", Icons.Rounded.Payments, Amber, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { fuelDialog = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.LocalGasStation, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Log fuel") }
                    OutlinedButton(onClick = { serviceAny = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Build, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Log service") }
                }
            }
            liveCarSection(car, live, showAll, onExpand = { showAll = !showAll }, onSetup = { setup = true },
                onRefresh = { scope.launch { shell.toast(com.blacklab.app.vehicle.VehicleLink.refreshBluelink(ctx)) } }, onCodes = { codes = true },
                next = due.firstOrNull { it.status != Garage.Status.UNKNOWN }, severe = v.severeService,
                onLogFill = { fillPrefill = it }, onDismissFill = { com.blacklab.app.vehicle.VehicleLink.clearPendingFill(ctx) })
            // ---- recalls & build
            item { SectionHeader("Recalls & safety", action = "Check now") { scope.launch { shell.toast(com.blacklab.app.vehicle.Nhtsa.refresh(ctx, notify = false)) } } }
            item {
                val open = nhtsa.open
                BlCard(Modifier.fillMaxWidth(), accent = if (open.isNotEmpty()) Amber else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (open.isEmpty()) Icons.Rounded.VerifiedUser else Icons.Rounded.ReportProblem, null, tint = if (open.isEmpty()) Success.readable() else Amber.readable())
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(when {
                                nhtsa.checkedAt == 0L -> "Not checked yet"
                                open.isEmpty() && nhtsa.recalls.isNotEmpty() -> "All ${nhtsa.recalls.size} listed recalls marked done or not for your car"
                                open.isEmpty() -> "No recalls listed for the ${v.year} ${v.model}"
                                else -> "${open.size} recall${if (open.size > 1) "s" else ""} to check"
                            }, style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(nhtsa.checkedAt.takeIf { it > 0 }?.let { "NHTSA · checked ${com.blacklab.app.vehicle.Telemetry.ago(it)}" }, nhtsa.error.ifBlank { null }).joinToString(" · ")
                                .ifBlank { "Weekly check of NHTSA's public recall list" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (v.vin.length == 17) TextButton(onClick = { com.blacklab.app.core.Files.openUrl(ctx, com.blacklab.app.vehicle.Nhtsa.vinCheckUrl(v.vin)) }) { Text("Check open recalls for my VIN on nhtsa.gov") }
                }
            }
            items(nhtsa.recalls, key = { "recall-" + it.campaign }) { rc ->
                val done = rc.campaign in nhtsa.resolved
                BlCard(Modifier.fillMaxWidth(), onClick = { recall = rc }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rc.component.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                            Text("${rc.campaign} · ${rc.date}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(if (done) "Done / n.a." else if (rc.parkIt || rc.parkOutside) "Park it" else "Open", if (done) Success else if (rc.parkIt || rc.parkOutside) Danger else Amber)
                    }
                }
            }
            nhtsa.decoded?.takeIf { it.specs.isNotEmpty() }?.let { d ->
                item { SectionHeader("From your VIN", action = if (showSpecs) "Hide" else "Show") { showSpecs = !showSpecs } }
                if (showSpecs) item {
                    BlCard(Modifier.fillMaxWidth()) {
                        d.specs.forEach { (k, value) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
                                Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f))
                            }
                        }
                        Text("Decoded by NHTSA from VIN …${d.vin.takeLast(6)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item { SectionHeader("Maintenance") }
            items(due, key = { it.item.id }) { d ->
                val (tag, color) = when (d.status) {
                    Garage.Status.OVERDUE -> "Overdue" to Danger
                    Garage.Status.DUE_SOON -> "Due soon" to Amber
                    Garage.Status.OK -> "OK" to Success
                    Garage.Status.UNKNOWN -> "Not logged" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                BlCard(Modifier.fillMaxWidth(), onClick = { serviceFor = d.item }, accent = if (d.status == Garage.Status.OVERDUE || d.status == Garage.Status.DUE_SOON) color else null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.item.name, style = MaterialTheme.typography.titleSmall)
                            Text(d.describe(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(intervalLabel(d.item, v.severeService) + (d.item.lastDay?.let { " · last ${Garage.dayLabel(it)}" + (d.item.lastMiles?.let { m -> " at ${Garage.fmtMiles(m)} mi" } ?: "") } ?: ""),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(tag, color)
                        if (d.status == Garage.Status.OVERDUE || d.status == Garage.Status.DUE_SOON || d.expectedDay != null)
                            IconButton(onClick = { scope.launch { com.blacklab.app.vehicle.ServiceReminders.addTask(ctx, d); shell.toast("Added to To-Do" + (d.expectedDay?.let { " for ${Garage.dayLabel(it.coerceAtLeast(LocalDate.now().toEpochDay()))}" } ?: "")) } }) {
                                Icon(Icons.Rounded.AddTask, "Add to To-Do")
                            }
                        IconButton(onClick = { editItem = d.item }) { Icon(Icons.Rounded.Tune, "Edit interval") }
                    }
                }
            }
            item { SectionHeader("Fuel log") }
            if (st.fuel.isEmpty()) item { Text("No fill-ups yet. Log one here or say “log fuel 13 gallons for 48 dollars at 31,250 miles” — in the car too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(st.fuel.take(15), key = { it.id }) { f ->
                BlCard(Modifier.fillMaxWidth(), onClick = { deleteFuel = f }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${"%.2f".format(f.gallons)} gal" + (f.cost?.let { " · $${"%.2f".format(it)}" } ?: "") + (if (!f.full) " · partial" else ""), style = MaterialTheme.typography.titleSmall)
                            Text(listOfNotNull(Garage.timeLabel(f.time), f.miles?.let { "${Garage.fmtMiles(it)} mi" }, f.station.ifBlank { null }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        f.cost?.let { c -> Text("$" + "%.3f".format(c / f.gallons) + "/gal", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            val mpgPts = Garage.mpgSeries(st.fuel)
            if (mpgPts.size >= 2) item(key = "mpg-chart") { MpgTrendChart(mpgPts.takeLast(12)) }
            val spend = Garage.monthlySpend(st.fuel)
            if (spend.any { it.second > 0 }) item(key = "spend-chart") { MonthlySpendChart(spend) }
            if (pace != null) item { Text("Your pace: about %.0f mi a day (%,.0f a year) — used to predict service dates.".format(pace, pace * 365), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (stats.costPerMile != null) item { Text("About ${"%.1f".format(stats.costPerMile * 100)}¢ per mile in fuel" + (stats.lastMpg?.let { " · last tank ${"%.1f".format(it)} mpg" } ?: ""), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            tripsSection(car, v.tankGallons, onTrip = { trip = it }, onExport = {
                runCatching { com.blacklab.app.core.Files.share(ctx, listOf(com.blacklab.app.vehicle.VehicleLink.exportCsv(ctx)), "Mileage log") }
                    .onFailure { shell.toast("Couldn't export: ${it.message}") }
            })
            item { SectionHeader("Service history") }
            if (st.services.isEmpty()) item { Text("Nothing logged yet. Tap a maintenance item above to mark it done.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(st.services.take(20), key = { it.id }) { r ->
                BlCard(Modifier.fillMaxWidth()) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(Garage.dayLabel(r.day), r.miles?.let { "${Garage.fmtMiles(it)} mi" }, r.cost?.let { "$" + "%.2f".format(it) }, r.shop.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { SectionHeader("2023 Santa Fe", action = if (showFacts) "Hide" else "Show") { showFacts = !showFacts } }
            if (showFacts) {
                items(Garage.FACTS) { (k, text) ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Text("Maintenance intervals are a typical Hyundai schedule to start from — confirm them in your Owner's Manual (or MyHyundai) and adjust any item with its tune button. Turn on severe service in the profile if you tow, do lots of short trips, or drive in extreme heat or dust.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { SectionHeader("Android Auto") }
            item {
                BlCard(Modifier.fillMaxWidth()) {
                    Text("BlackLab on the Santa Fe display", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Ask by voice, hear your briefing and summaries, check the agenda and tasks, talk through any knowledge base hands-free, take voice notes, log fuel and service, and play your music and recordings.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "On the phone: Settings › Connected devices › Connection preferences › Android Auto.",
                        "Tap Version about ten times to unlock developer settings, then ⋮ › Developer settings › turn on “Unknown sources” (needed because BlackLab isn't from the Play Store).",
                        "In Android Auto › Customize launcher, make sure BlackLab is checked.",
                        "Connect to the car — wirelessly on the 8-inch screen, or with a USB data cable on the 10.25-inch navigation screen — and open BlackLab from the app launcher.",
                    ).forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }

    if (editOdo) TextInputDialog("Odometer (miles)", if (v.odometer > 0) v.odometer.toString() else "", keyboardType = KeyboardType.Number, onDismiss = { editOdo = false }) { t ->
        t.filter { it.isDigit() }.toIntOrNull()?.let { m ->
            if (m < v.odometer) shell.toast("That's lower than the last reading (${Garage.fmtMiles(v.odometer)})") else Garage.setOdometer(ctx, m)
        }
    }
    fillPrefill?.let { pf -> FuelDialog(pf.odometer ?: v.odometerNow, onDismiss = { fillPrefill = null }, gallons0 = "%.1f".format(pf.gallons), station0 = pf.station, full0 = pf.toPct >= 92, time = pf.time) { f ->
        Garage.addFuel(ctx, f)
        com.blacklab.app.vehicle.VehicleLink.clearPendingFill(ctx)
        shell.toast("Fill-up logged" + (Garage.fuelStats(Garage.state.value.fuel).lastMpg?.let { " · ${"%.1f".format(it)} mpg" } ?: ""))
    } }
    recall?.let { rc -> RecallDialog(rc, rc.campaign in nhtsa.resolved, onDismiss = { recall = null }) { done -> com.blacklab.app.vehicle.Nhtsa.setResolved(ctx, rc.campaign, done) } }
    if (fuelDialog) FuelDialog(v.odometer, onDismiss = { fuelDialog = false }) { f ->
        Garage.addFuel(ctx, f)
        val last = Garage.fuelStats(Garage.state.value.fuel).lastMpg
        shell.toast("Fill-up logged" + (last?.let { " · ${"%.1f".format(it)} mpg" } ?: ""))
    }
    serviceFor?.let { item -> ServiceDialog(listOf(item), v.odometer, preselected = setOf(item.id), onDismiss = { serviceFor = null }) { ids, miles, day, cost, shop, notes ->
        Garage.logService(ctx, ids, miles, day, cost = cost, shop = shop, notes = notes); shell.toast("Logged")
    } }
    if (serviceAny) ServiceDialog(st.items, v.odometer, preselected = emptySet(), onDismiss = { serviceAny = false }) { ids, miles, day, cost, shop, notes ->
        Garage.logService(ctx, ids, miles, day, cost = cost, shop = shop, notes = notes); shell.toast("Logged")
    }
    editItem?.let { item -> ItemDialog(item, isCustom = st.items.none { it.id == item.id } || item.id.startsWith("custom-"), onDismiss = { editItem = null },
        onDelete = { Garage.deleteItem(ctx, item.id) }) { Garage.upsertItem(ctx, it) } }
    deleteFuel?.let { f -> ConfirmDialog("Delete fill-up?", "${"%.2f".format(f.gallons)} gal on ${Garage.timeLabel(f.time)}", onDismiss = { deleteFuel = null }) { Garage.deleteFuel(ctx, f.id) } }
    if (editVehicle) VehicleDialog(v, onDismiss = { editVehicle = false }) { Garage.setVehicle(ctx, it) }
    if (setup) CarConnectionDialog(shell, onDismiss = { setup = false })
    if (codes) CodesDialog(car, onDismiss = { codes = false })
    trip?.let { t -> TripDialog(t, v.tankGallons, onDismiss = { trip = null }) }
}

private fun intervalLabel(i: Garage.ServiceItem, severe: Boolean): String {
    val miles = if (severe) i.severeMiles ?: i.miles else i.miles
    val months = if (severe) i.severeMonths ?: i.months else i.months
    return listOfNotNull(miles?.let { "every ${Garage.fmtMiles(it)} mi" }, months?.let { if (it % 12 == 0) "${it / 12} yr" else "$it mo" }).joinToString(" / ").ifBlank { "as needed" }
}

@Composable
private fun num(label: String, value: String, onChange: (String) -> Unit, decimal: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) =
    OutlinedTextField(value, { t -> onChange(t.filter { it.isDigit() || (decimal && it == '.') }) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number))

@Composable
private fun FuelDialog(odometer: Int, onDismiss: () -> Unit, gallons0: String = "", station0: String = "", full0: Boolean = true, time: Long? = null, onSave: (Garage.Fuel) -> Unit) {
    var gallons by remember { mutableStateOf(gallons0) }
    var cost by remember { mutableStateOf("") }
    var miles by remember { mutableStateOf(if (odometer > 0) odometer.toString() else "") }
    var station by remember { mutableStateOf(station0) }
    var full by remember { mutableStateOf(full0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log fuel") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                num("Gallons", gallons, { gallons = it }, decimal = true, modifier = Modifier.weight(1f))
                num("Total $", cost, { cost = it }, decimal = true, modifier = Modifier.weight(1f))
            }
            num("Odometer", miles, { miles = it })
            OutlinedTextField(station, { station = it }, Modifier.fillMaxWidth(), label = { Text("Station (optional)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filled the tank", Modifier.weight(1f)); Switch(full, { full = it })
            }
            Text("Full fill-ups give accurate MPG; partial ones roll into the next full tank.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = {
        TextButton(enabled = gallons.toDoubleOrNull()?.let { it > 0 } == true, onClick = {
            onSave(Garage.Fuel(UUID.randomUUID().toString(), time ?: System.currentTimeMillis(), miles.toIntOrNull(), gallons.toDouble(), cost.toDoubleOrNull(), full, station.trim()))
            onDismiss()
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ServiceDialog(items: List<Garage.ServiceItem>, odometer: Int, preselected: Set<String>, onDismiss: () -> Unit,
                          onSave: (List<String>, Int?, Long, Double?, String, String) -> Unit) {
    var chosen by remember { mutableStateOf(preselected) }
    var miles by remember { mutableStateOf(if (odometer > 0) odometer.toString() else "") }
    var cost by remember { mutableStateOf("") }
    var shop by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var daysAgo by remember { mutableStateOf("0") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (items.size == 1) items.first().name else "Log service") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (items.size > 1) {
                Text("What was done?", style = MaterialTheme.typography.labelLarge)
                items.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(i.id in chosen, { c -> chosen = if (c) chosen + i.id else chosen - i.id })
                        Text(i.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
            } else if (items.first().note.isNotBlank()) Text(items.first().note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            num("Odometer", miles, { miles = it })
            num("Days ago (0 = today)", daysAgo, { daysAgo = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                num("Cost $", cost, { cost = it }, decimal = true, modifier = Modifier.weight(1f))
                OutlinedTextField(shop, { shop = it }, Modifier.weight(1f), label = { Text("Shop") }, singleLine = true)
            }
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Notes") })
        }
    }, confirmButton = {
        TextButton(enabled = chosen.isNotEmpty(), onClick = {
            onSave(chosen.toList(), miles.toIntOrNull(), LocalDate.now().minusDays(daysAgo.toLongOrNull() ?: 0).toEpochDay(), cost.toDoubleOrNull(), shop.trim(), notes.trim())
            onDismiss()
        }) { Text("Mark done") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ItemDialog(item: Garage.ServiceItem, isCustom: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (Garage.ServiceItem) -> Unit) {
    var name by remember { mutableStateOf(item.name) }
    var miles by remember { mutableStateOf(item.miles?.toString() ?: "") }
    var months by remember { mutableStateOf(item.months?.toString() ?: "") }
    var sMiles by remember { mutableStateOf(item.severeMiles?.toString() ?: "") }
    var sMonths by remember { mutableStateOf(item.severeMonths?.toString() ?: "") }
    var note by remember { mutableStateOf(item.note) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (item.name.isBlank()) "New service item" else "Interval") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                num("Every … miles", miles, { miles = it }, modifier = Modifier.weight(1f))
                num("or … months", months, { months = it }, modifier = Modifier.weight(1f))
            }
            Text("Severe service", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                num("Miles", sMiles, { sMiles = it }, modifier = Modifier.weight(1f))
                num("Months", sMonths, { sMonths = it }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") })
            if (isCustom || item.name.isNotBlank()) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Remove this item", color = Danger.readable()) }
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank(), onClick = {
            onSave(item.copy(name = name.trim(), miles = miles.toIntOrNull(), months = months.toIntOrNull(), severeMiles = sMiles.toIntOrNull(), severeMonths = sMonths.toIntOrNull(), note = note.trim()))
            onDismiss()
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun VehicleDialog(v: Garage.Vehicle, onDismiss: () -> Unit, onSave: (Garage.Vehicle) -> Unit) {
    var x by remember { mutableStateOf(v) }
    var tank by remember { mutableStateOf(v.tankGallons.toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Vehicle") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${x.year} ${x.make} ${x.model}", style = MaterialTheme.typography.titleSmall)
            DropdownField("Trim", listOf("" to "Not set") + Garage.TRIMS.map { it to it }, x.trim, { x = x.copy(trim = it) })
            DropdownField("Engine", Garage.ENGINES.map { it to it }, x.engine, { x = x.copy(engine = it) })
            DropdownField("Drive", listOf("AWD" to "HTRAC AWD", "FWD" to "Front-wheel drive"), x.drive, { x = x.copy(drive = it) })
            OutlinedTextField(x.nickname, { x = x.copy(nickname = it) }, Modifier.fillMaxWidth(), label = { Text("Nickname (optional)") }, singleLine = true)
            OutlinedTextField(x.vin, { x = x.copy(vin = it.uppercase().take(17)) }, Modifier.fillMaxWidth(), label = { Text("VIN") }, singleLine = true)
            OutlinedTextField(x.plate, { x = x.copy(plate = it.uppercase()) }, Modifier.fillMaxWidth(), label = { Text("Plate") }, singleLine = true)
            num("Fuel tank (gal)", tank, { tank = it }, decimal = true)
            num("Miles per year (blank = learn from trips)", if (x.milesPerYear > 0) x.milesPerYear.toString() else "", { x = x.copy(milesPerYear = it.toIntOrNull() ?: 0) })
            OutlinedTextField(x.tirePsi, { x = x.copy(tirePsi = it) }, Modifier.fillMaxWidth(), label = { Text("Tire pressure (door placard)") }, placeholder = { Text("e.g. 35 front / 35 rear") }, singleLine = true)
            OutlinedTextField(x.oil, { x = x.copy(oil = it) }, Modifier.fillMaxWidth(), label = { Text("Oil spec & capacity (manual)") }, singleLine = true)
            OutlinedTextField(x.dealer, { x = x.copy(dealer = it) }, Modifier.fillMaxWidth(), label = { Text("Service dealer (for navigation)") }, singleLine = true)
            OutlinedTextField(x.insurance, { x = x.copy(insurance = it) }, Modifier.fillMaxWidth(), label = { Text("Insurance / roadside number") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Severe service schedule")
                    Text("Towing, short trips, extreme heat/cold, dusty roads", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(x.severeService, { x = x.copy(severeService = it) })
            }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(x.copy(tankGallons = tank.toDoubleOrNull() ?: x.tankGallons)); onDismiss() }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun RecallDialog(r: com.blacklab.app.vehicle.Nhtsa.Recall, done: Boolean, onDismiss: () -> Unit, onDone: (Boolean) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(r.component.lowercase().replaceFirstChar { it.uppercase() }) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NHTSA campaign ${r.campaign} · reported ${r.date}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.parkIt) Text("NHTSA says: park it until it's fixed.", color = Danger.readable(), fontWeight = FontWeight.SemiBold)
            if (r.parkOutside) Text("NHTSA says: park outside, away from buildings, until it's fixed.", color = Danger.readable(), fontWeight = FontWeight.SemiBold)
            listOf("What" to r.summary, "Risk" to r.consequence, "Fix" to r.remedy).filter { it.second.isNotBlank() }.forEach { (k, t) ->
                Text(k, style = MaterialTheme.typography.labelLarge); Text(t, style = MaterialTheme.typography.bodySmall)
            }
            if (r.overTheAir) Text("This one can be fixed with an over-the-air update.", style = MaterialTheme.typography.bodySmall)
            Text("Recalls are listed by year, make and model, so some won't apply to your car (for example, ones for accessories you don't have). The nhtsa.gov VIN check or your Hyundai dealer can confirm.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = { onDone(!done); onDismiss() }) { Text(if (done) "Mark as open" else "Done or not for my car") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
