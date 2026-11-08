package com.blacklab.app.ui.vehicle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.theme.LocalBlColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max

/*
 * Garage charts. Marks are thin and quiet: 4 dp rounded data-ends on bars, a 2 dp gap between
 * stacked segments, 2 dp lines with 8 dp markers, one recessive gridline. Text stays in text colors
 * (never the series color); a legend appears for two series. Tap a bar or point to read its value.
 */

private fun shortDay(d: LocalDate) = "${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${d.dayOfMonth}"
private fun shortMonth(m: YearMonth) = m.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

/** A 270° ring gauge: fuel level with the range underneath. */
@Composable
fun FuelRing(pct: Double, label: String, sub: String?, modifier: Modifier = Modifier) {
    val bl = LocalBlColors.current
    val low = pct <= 12
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (low) bl.danger else MaterialTheme.colorScheme.primary
    Column(modifier.semantics { contentDescription = "$label ${pct.toInt()} percent" + (sub?.let { ", $it" } ?: "") }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = (270f * (pct / 100.0).coerceIn(0.0, 1.0)).toFloat()
                if (sweep > 0.5f) drawArc(fill, 135f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${pct.toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = if (low) bl.danger else MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        sub?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** How much of the next service interval is used up (0–1), as a ring. */
@Composable
fun ServiceRing(used: Double, title: String, sub: String, warn: Boolean, modifier: Modifier = Modifier) {
    val bl = LocalBlColors.current
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (warn) bl.warning else MaterialTheme.colorScheme.tertiary
    Column(modifier.semantics { contentDescription = "$title, ${(used * 100).toInt()} percent of the interval used, $sub" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val stroke = 10.dp.toPx(); val inset = stroke / 2
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = (270f * used.coerceIn(0.0, 1.0)).toFloat()
                if (sweep > 0.5f) drawArc(fill, 135f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 14.dp)) {
                Text("${(used * 100).toInt().coerceAtMost(999)}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun ChartCard(title: String, readout: String?, legend: List<Pair<Color, String>> = emptyList(), content: @Composable () -> Unit) {
    BlCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            readout?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (legend.size >= 2) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            legend.forEach { (c, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                    Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** A bar with 4 dp rounded top corners and a square base. */
private fun DrawScope.bar(left: Float, top: Float, width: Float, bottom: Float, color: Color, roundTop: Boolean) {
    if (bottom - top < 0.5f) return
    val r = if (roundTop) minOf(4.dp.toPx(), (bottom - top), width / 2) else 0f
    val path = Path().apply { addRoundRect(RoundRect(left, top, left + width, bottom, CornerRadius(r, r), CornerRadius(r, r), CornerRadius.Zero, CornerRadius.Zero)) }
    drawPath(path, color)
}

/** Miles per week, business stacked on everything else. */
@Composable
fun WeeklyMilesChart(weeks: List<Triple<LocalDate, Double, Double>>) {
    val other = MaterialTheme.colorScheme.primary
    val business = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    var sel by remember(weeks) { mutableStateOf(weeks.lastIndex) }
    val max = max(1.0, weeks.maxOf { it.second + it.third })
    val s = weeks.getOrNull(sel)
    val hasBusiness = weeks.any { it.second > 0 }
    ChartCard("Miles per week",
        s?.let { "Week of ${shortDay(it.first)}: %.0f mi".format(it.second + it.third) + if (it.second > 0) " · %.0f business".format(it.second) else "" },
        if (hasBusiness) listOf(other to "Personal & other", business to "Business") else emptyList()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)
            .semantics { contentDescription = "Miles per week for the last ${weeks.size} weeks: " + weeks.joinToString { "%.0f".format(it.second + it.third) } }
            .pointerInput(weeks) { detectTapGestures { p -> sel = (p.x / (size.width / weeks.size)).toInt().coerceIn(0, weeks.lastIndex) } }) {
            val slot = size.width / weeks.size
            val w = slot * 0.56f
            val gap = 2.dp.toPx()
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            weeks.forEachIndexed { i, (_, b, o) ->
                val left = i * slot + (slot - w) / 2
                val alpha = if (i == sel) 1f else 0.72f
                val oTop = size.height - (o / max * size.height).toFloat()
                bar(left, oTop, w, size.height, other.copy(alpha = alpha), roundTop = b <= 0)
                if (b > 0) {
                    val bTop = oTop - gap - (b / max * size.height).toFloat()
                    bar(left, bTop, w, oTop - gap, business.copy(alpha = alpha), roundTop = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            weeks.forEachIndexed { i, (d, _, _) ->
                Text(if (i % 2 == weeks.lastIndex % 2) shortDay(d) else "", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ink, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Text("Top line: %.0f mi".format(max), style = MaterialTheme.typography.labelSmall, color = ink)
    }
}

/** MPG of each full tank, with your average as a dashed reference line. */
@Composable
fun MpgTrendChart(points: List<Pair<Long, Double>>) {
    val line = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    var sel by remember(points) { mutableStateOf(points.lastIndex) }
    val lo = points.minOf { it.second } - 2; val hi = points.maxOf { it.second } + 2
    val avg = points.map { it.second }.average()
    val zone = ZoneId.systemDefault()
    val p = points.getOrNull(sel)
    ChartCard("MPG per tank", p?.let { "${shortDay(Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate())}: %.1f mpg".format(it.second) }) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)
            .semantics { contentDescription = "MPG for the last ${points.size} tanks, average %.1f: ".format(avg) + points.joinToString { "%.1f".format(it.second) } }
            .pointerInput(points) {
                detectTapGestures { t ->
                    val step = if (points.size > 1) size.width / (points.size - 1f) else 0f
                    sel = if (step == 0f) 0 else Math.round(t.x / step).coerceIn(0, points.lastIndex)
                }
            }) {
            fun x(i: Int) = if (points.size == 1) size.width / 2 else i * (size.width - 16.dp.toPx()) / (points.size - 1) + 8.dp.toPx()
            fun y(v: Double) = (size.height - 8.dp.toPx()) - ((v - lo) / (hi - lo) * (size.height - 16.dp.toPx())).toFloat()
            drawLine(grid, Offset(0f, y(avg)), Offset(size.width, y(avg)), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val path = Path().apply { points.forEachIndexed { i, pt -> if (i == 0) moveTo(x(i), y(pt.second)) else lineTo(x(i), y(pt.second)) } }
            drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            points.forEachIndexed { i, pt ->
                val r = if (i == sel) 5.dp.toPx() else 4.dp.toPx()
                drawCircle(surface, r + 2.dp.toPx(), Offset(x(i), y(pt.second)))
                drawCircle(line, r, Offset(x(i), y(pt.second)))
            }
        }
        Text("Average %.1f mpg (dashed) · best %.1f · lowest %.1f".format(avg, points.maxOf { it.second }, points.minOf { it.second }),
            style = MaterialTheme.typography.labelSmall, color = ink, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Fuel spend per month. */
@Composable
fun MonthlySpendChart(months: List<Pair<YearMonth, Double>>) {
    val color = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    var sel by remember(months) { mutableStateOf(months.lastIndex) }
    val max = max(1.0, months.maxOf { it.second })
    val m = months.getOrNull(sel)
    ChartCard("Fuel spend per month", m?.let { "${shortMonth(it.first)} ${it.first.year}: $%,.0f".format(it.second) }) {
        Canvas(Modifier.fillMaxWidth().height(100.dp)
            .semantics { contentDescription = "Fuel spend for the last ${months.size} months: " + months.joinToString { "${shortMonth(it.first)} $%,.0f".format(it.second) } }
            .pointerInput(months) { detectTapGestures { p -> sel = (p.x / (size.width / months.size)).toInt().coerceIn(0, months.lastIndex) } }) {
            val slot = size.width / months.size; val w = slot * 0.5f
            drawLine(grid, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            months.forEachIndexed { i, (_, v) ->
                bar(i * slot + (slot - w) / 2, size.height - (v / max * size.height).toFloat(), w, size.height, color.copy(alpha = if (i == sel) 1f else 0.72f), true)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            months.forEach { (ym, _) -> Text(shortMonth(ym), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Text("Top line: $%,.0f".format(max), style = MaterialTheme.typography.labelSmall, color = ink)
    }
}
