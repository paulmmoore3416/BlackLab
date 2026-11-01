package com.blacklab.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Profile
import com.blacklab.app.ui.components.BlCard
import com.blacklab.app.ui.components.rememberApp
import com.blacklab.app.ui.components.settingsState
import com.blacklab.app.ui.theme.AccentPalette
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.ThemeChoice

/**
 * Account colors: pick a palette (main color + complementary secondary + tertiary) per account,
 * optionally override the secondary color, and see the result live.
 */
@Composable
fun ColorChoices() {
    val app = rememberApp()
    val s = settingsState()
    val dark = LocalBlColors.current.isDark
    var which by remember { mutableStateOf(s.activeProfile) }
    val choice = ThemeChoice.from(s)
    val palette = choice.palette(which)
    val secondaryKey = if (which == Profile.PERSONAL) s.secondaryPersonal else s.secondaryProfessional
    val accentKey = if (which == Profile.PERSONAL) Keys.ACCENT_PERSONAL else Keys.ACCENT_PROFESSIONAL
    val secKey = if (which == Profile.PERSONAL) Keys.SECONDARY_PERSONAL else Keys.SECONDARY_PROFESSIONAL

    BlCard(Modifier.fillMaxWidth()) {
        Text("Colors", style = MaterialTheme.typography.titleSmall)
        Text(
            if (s.dynamicColor) "Material You is on, so wallpaper colors are used. Turn it off to use these."
            else "Each palette pairs a main color with a complementary secondary color. Both are tuned for light and dark mode.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Profile.entries.forEachIndexed { i, p ->
                SegmentedButton(which == p, { which = p }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(app.settings.profileName(p)) }
            }
        }

        Text("Main color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentPalette.entries.forEach { p ->
                PaletteSwatch(p, dark, selected = p == palette) { app.settings.update { putString(accentKey, p.name) } }
            }
        }

        Text("Secondary color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                Dot(palette.secondary(dark), selected = secondaryKey == "AUTO", label = "Auto (complementary)") { app.settings.update { putString(secKey, "AUTO") } }
                Text("Auto", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
            }
            AccentPalette.entries.filter { it != palette }.forEach { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                    Dot(p.accent(dark), selected = secondaryKey == p.name, label = p.label) { app.settings.update { putString(secKey, p.name) } }
                    Text(p.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
                }
            }
        }

        Preview(choice, which, dark)
    }
}

/** Main color as the circle, complementary secondary as the lower-right quarter. */
@Composable
private fun PaletteSwatch(p: AccentPalette, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onClick)
                .semantics { contentDescription = "${p.label} palette"; this.selected = selected },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(44.dp)) {
                drawCircle(p.accent(dark))
                drawArc(p.secondary(dark), 0f, 90f, useCenter = true, topLeft = Offset.Zero, size = Size(size.width, size.height))
                drawArc(p.tertiary(dark), 90f, 45f, useCenter = true, topLeft = Offset.Zero, size = Size(size.width, size.height))
            }
            if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(2.dp))
        }
        Text(p.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
    }
}

@Composable
private fun Dot(c: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

/** A small mock of a screen in the chosen colors, in both light and dark. */
@Composable
private fun Preview(choice: ThemeChoice, p: Profile, currentDark: Boolean) {
    Text("Preview", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false, true).forEach { dark ->
            val pal = choice.palette(p)
            val accent = pal.accent(dark); val ter = pal.tertiary(dark)
            // matches the theme: dark-mode selections use the main color unless a secondary was picked
            val sec = if (dark && !choice.secondaryChosen(p)) accent else choice.secondary(p, dark)
            val bg = if (dark) Color(0xFF0E1014) else Color(0xFFF7F8FA)
            val fg = if (dark) Color(0xFFE9EBEF) else Color(0xFF15171B)
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = bg, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Text(if (dark) "Dark" else "Light", style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(50)).background(accent), contentAlignment = Alignment.Center) {
                        Text("Button", style = MaterialTheme.typography.labelMedium, color = if (dark) Color.Black else Color.White)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(sec.copy(alpha = if (dark) 0.22f else 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                            Text("Selected", style = MaterialTheme.typography.labelSmall, color = if (dark) sec else sec.copy(red = sec.red * 0.6f, green = sec.green * 0.6f, blue = sec.blue * 0.6f))
                        }
                        Box(Modifier.size(18.dp).clip(CircleShape).background(ter))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("Link text", style = MaterialTheme.typography.labelMedium, color = accent)
                }
            }
        }
    }
    if (!currentDark) Text("Tip: the light theme also tints its backgrounds very slightly with your main color.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

/** Under Appearance › Auto: sunset-to-sunrise (with the sun times for today) or custom hours. */
@Composable
fun AutoThemeOptions(s: com.blacklab.app.core.Settings) {
    val app = rememberApp()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pick by remember { mutableStateOf<String?>(null) }
    val sun = androidx.compose.runtime.produceState<com.blacklab.app.core.AutoTheme.SunTimes?>(null, s.themeMode) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.blacklab.app.core.AutoTheme.location(ctx)?.let { (lat, lon) -> com.blacklab.app.core.AutoTheme.sunTimes(java.time.LocalDate.now(), lat, lon, java.time.ZoneId.systemDefault()) }
        }
    }.value
    fun hm(m: Int) = com.blacklab.app.core.Time.time(m)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(s.themeMode == com.blacklab.app.core.AutoTheme.AUTO, { app.settings.update { putString(Keys.THEME_MODE, com.blacklab.app.core.AutoTheme.AUTO) } }, label = { Text("Sunset to sunrise") })
            androidx.compose.material3.FilterChip(s.themeMode == com.blacklab.app.core.AutoTheme.SCHEDULE, { app.settings.update { putString(Keys.THEME_MODE, com.blacklab.app.core.AutoTheme.SCHEDULE) } }, label = { Text("Custom hours") })
        }
        val custom = s.themeMode == com.blacklab.app.core.AutoTheme.SCHEDULE || sun == null
        Text(
            when {
                !custom -> "Dark from sunset (${com.blacklab.app.core.Time.time(sun!!.sunset.hour * 60 + sun.sunset.minute)}) to sunrise (${com.blacklab.app.core.Time.time(sun.sunrise.hour * 60 + sun.sunrise.minute)}) today, worked out on this phone from your approximate location."
                s.themeMode == com.blacklab.app.core.AutoTheme.AUTO -> "Your location isn't available (BlackLab only uses the phone's last known position, and only if location access is on), so these hours are used instead:"
                else -> "Dark between these times:"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        if (custom) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.OutlinedButton(onClick = { pick = "from" }) { Text("Dark from ${hm(s.themeDarkFrom)}") }
            androidx.compose.material3.OutlinedButton(onClick = { pick = "to" }) { Text("until ${hm(s.themeDarkTo)}") }
        }
    }
    pick?.let { which ->
        com.blacklab.app.ui.components.TimePickerDialogBox(if (which == "from") s.themeDarkFrom else s.themeDarkTo, onDismiss = { pick = null }) { m ->
            app.settings.update { putInt(if (which == "from") Keys.THEME_DARK_FROM else Keys.THEME_DARK_TO, m) }; pick = null
        }
    }
}
