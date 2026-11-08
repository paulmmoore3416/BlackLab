package com.blacklab.app.ui.components

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.draw.drawBehind
import com.blacklab.app.ui.theme.readable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.blacklab.app.AppContainer
import com.blacklab.app.app
import com.blacklab.app.core.Profile
import com.blacklab.app.core.Settings
import com.blacklab.app.core.Time
import com.blacklab.app.ui.theme.LocalBlColors
import com.blacklab.app.ui.theme.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate

// ------------------------------------------------------------------ shell & app access

class Shell(
    val nav: NavHostController,
    val openDrawer: () -> Unit,
    val snackbar: SnackbarHostState,
    val scope: CoroutineScope,
    val switchProfile: (Profile) -> Unit,
) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun back() { if (!nav.popBackStack()) nav.navigate("home") }

    fun toast(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(message, actionLabel = action, withDismissAction = action == null, duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalShell = staticCompositionLocalOf<Shell> { error("Shell not provided") }

@Composable
fun rememberApp(): AppContainer {
    val ctx = LocalContext.current
    return remember { ctx.app }
}

@Composable
fun settingsState(): Settings {
    val app = rememberApp()
    val s by app.settings.state.collectAsStateWithLifecycle()
    return s
}

@Composable
fun currentProfile(): Profile = settingsState().activeProfile

// ------------------------------------------------------------------ bars & headers

@Composable
fun BlTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    showMenu: Boolean = onBack == null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    large: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val shell = LocalShell.current
    if (large && subtitle == null) {
        // Big title that shrinks into the bar as the list scrolls (pass an exitUntilCollapsed scroll behavior)
        androidx.compose.material3.LargeTopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                when {
                    onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    showMenu -> IconButton(onClick = shell.openDrawer) { Icon(Icons.Rounded.Menu, "Menu") }
                }
            },
            actions = actions,
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.largeTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                scrolledContainerColor = barColor(),
            ),
        )
        return
    }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                showMenu -> IconButton(onClick = shell.openDrawer) { Icon(Icons.Rounded.Menu, "Menu") }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = barColor(),
        ),
    )
}

/** Bar color: frosted over the glass backdrop, solid otherwise. */
@Composable
fun barColor(): Color {
    val glass = com.blacklab.app.ui.theme.LocalGlass.current
    return if (glass.enabled) glass.barFill(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surfaceContainer
}

/** Scroll behavior for a large title that collapses as you scroll; use with BlTopBar(large = true) and Modifier.nestedScroll. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun rememberCollapsingTitle(): TopAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun BlCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardRadius = com.blacklab.app.ui.theme.LocalCorners.current.card.dp
    val shape = RoundedCornerShape(cardRadius)
    val glass = com.blacklab.app.ui.theme.LocalGlass.current
    val light = !com.blacklab.app.ui.theme.LocalBlColors.current.isDark
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Press glow: a soft light that blooms from where your finger touched (glass) or a gentle sink (solid)
    var touch by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    androidx.compose.runtime.LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is androidx.compose.foundation.interaction.PressInteraction.Press) touch = it.pressPosition }
    }
    val glow by animateFloatAsState(if (pressed) 1f else 0f, androidx.compose.animation.core.tween(if (pressed) 120 else 520), label = "glow")
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f), label = "press")
    val glowColor = (accent ?: MaterialTheme.colorScheme.primary)
    val colors: androidx.compose.material3.CardColors
    val border: BorderStroke
    val elevation: androidx.compose.material3.CardElevation
    if (glass.enabled) {
        colors = CardDefaults.cardColors(containerColor = glass.cardFill(accent))
        border = BorderStroke(1.dp, glass.edge(accent))
        elevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp)
    } else {
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        // Light mode: brighter cards on a calmer page, a hairline border and a soft shadow so they read as raised
        border = BorderStroke(1.dp, accent?.copy(alpha = if (light) 0.55f else 0.4f) ?: MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (light) 1f else 0.7f))
        elevation = CardDefaults.cardElevation(defaultElevation = if (light) 1.dp else 0.dp, pressedElevation = if (light) 2.dp else 0.dp)
    }
    // Sheen and press glow are painted behind the frosted fill as rounded shapes, so they cover the whole card
    val radius = with(androidx.compose.ui.platform.LocalDensity.current) { cardRadius.toPx() }
    val glass2 = glass.enabled
    val surfaceFx = Modifier.drawBehind {
        if (!glass2) return@drawBehind
        val corner = androidx.compose.ui.geometry.CornerRadius(radius, radius)
        drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (light) 0.55f else 0.10f), Color.White.copy(alpha = 0f)), endY = size.height * 0.6f), cornerRadius = corner)
        if (glow > 0.01f) {
            val c = if (touch.isSpecified) touch else center
            drawRoundRect(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(glowColor.copy(alpha = (if (light) 0.55f else 0.30f) * glow), glowColor.copy(alpha = 0f)), c, size.maxDimension * 0.8f), cornerRadius = corner)
        }
    }
    val inner: @Composable ColumnScope.() -> Unit = { Column(Modifier.padding(contentPadding), content = content) }
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale }.then(surfaceFx), shape = shape, colors = colors, border = border, elevation = elevation, interactionSource = interaction, content = inner)
    } else {
        Card(modifier = modifier.then(surfaceFx), shape = shape, colors = colors, border = border, elevation = elevation, content = inner)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    // A small illustration in the palette's three colors; settles in once (instant with reduced motion)
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (com.blacklab.app.ui.theme.Motion.reduced) 1f else 0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown.animateTo(1f, com.blacklab.app.ui.theme.Motion.emphasized(520)) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val tertiary = MaterialTheme.colorScheme.tertiary
    Column(modifier.fillMaxWidth().padding(32.dp).graphicsLayer { alpha = shown.value; translationY = (1 - shown.value) * 24f }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).graphicsLayer { scaleX = 0.9f + 0.1f * shown.value; scaleY = scaleX }, contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val r = size.minDimension / 2
                drawCircle(primary.copy(alpha = 0.10f), r, center)
                drawCircle(secondary.copy(alpha = 0.18f), r * 0.34f, center + androidx.compose.ui.geometry.Offset(r * 0.62f, -r * 0.48f))
                drawCircle(tertiary.copy(alpha = 0.20f), r * 0.22f, center + androidx.compose.ui.geometry.Offset(-r * 0.66f, r * 0.52f))
                drawCircle(primary.copy(alpha = 0.16f), r * 0.62f, center)
            }
            Icon(icon, null, tint = primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val ink = color.readable()
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = ink, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = ink, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun ColorDot(color: Color, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size / 3.2f)).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color.readable(), modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun ProgressRing(progress: Float, size: Dp, stroke: Dp = 8.dp, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = Stroke(stroke.toPx(), cap = StrokeCap.Round)
            val inset = stroke.toPx() / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = s)
            drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = s)
        }
        content()
    }
}

@Composable
fun StatTile(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    BlCard(modifier, onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        IconBadge(icon, color, 32.dp)
        Spacer(Modifier.height(8.dp))
        CountUpText(value, MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ------------------------------------------------------------------ pickers & dialogs

@Composable
fun DatePickerDialogBox(initial: LocalDate?, onDismiss: () -> Unit, allowClear: Boolean = false, onPick: (LocalDate?) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = Time.pickerMillis(initial ?: Time.today()))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(state.selectedDateMillis?.let(Time::fromPickerMillis)); onDismiss() }) { Text("OK") }
        },
        dismissButton = {
            Row {
                if (allowClear) TextButton(onClick = { onPick(null); onDismiss() }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) { DatePicker(state = state) }
}

@Composable
fun TimePickerDialogBox(initialMinute: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = (initialMinute / 60) % 24, initialMinute = initialMinute % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour * 60 + state.minute); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state = state) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Delete", destructive: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { if (destructive) haptics.reject() else haptics.confirm(); onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) LocalBlColors.current.danger else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String = "",
    label: String = "",
    confirmLabel: String = "Save",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { if (label.isNotEmpty()) Text(label) }, singleLine = singleLine,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ColorPickerRow(selected: Int?, onPick: (Int) -> Unit, colors: List<Int> = Palette, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            val isSel = selected == c
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(Color(c))
                    .border(if (isSel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .clickable { onPick(c) },
                contentAlignment = Alignment.Center,
            ) { if (isSel) Icon(Icons.Rounded.Check, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Read-only text field that opens a menu; works with any value type. */
@Composable
fun <T> DropdownField(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Box(Modifier.matchParentSize().clip(RoundedCornerShape(4.dp)).clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { (v, l) ->
                DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false },
                    trailingIcon = { if (v == selected) Icon(Icons.Rounded.Check, null) })
            }
        }
    }
}

/** A tappable "field" row showing a value, used for date/time/repeat pickers in editors. */
@Composable
fun PickerRow(icon: ImageVector, label: String, value: String?, modifier: Modifier = Modifier, valueColor: Color? = null, onClear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (value != null) (valueColor ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(value ?: label, style = MaterialTheme.typography.bodyLarge, color = if (value != null) (valueColor ?: MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant)
            if (value != null) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value != null && onClear != null) {
            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun ProfileBadge(profile: Profile, name: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val c = com.blacklab.app.ui.theme.profileAccent(profile)
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(50),
        color = c.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, c.copy(alpha = 0.5f)),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (profile == Profile.PERSONAL) Icons.Rounded.Person else Icons.Rounded.Work,
                null, tint = c, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(name, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LabeledSwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    val haptics = rememberHaptics()
    val change: (Boolean) -> Unit = { v -> haptics.toggle(v); onChange(v) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled) { change(!checked) }.padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = change, enabled = enabled)
    }
}

@Composable
fun SettingRow(title: String, value: String? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ChipFlow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

/** Simple vertical bar chart for small series (study minutes, etc.). */
@Composable
fun MiniBars(values: List<Float>, labels: List<String>, color: Color, modifier: Modifier = Modifier, highlightLast: Boolean = true) {
    val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier.padding(horizontal = 5.dp).fillMaxWidth().height((80 * (v / max)).dp.coerceAtLeast(3.dp))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (highlightLast && i == values.lastIndex) color else color.copy(alpha = 0.45f))
                )
                Spacer(Modifier.height(4.dp))
                Text(labels.getOrElse(i) { "" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

fun Modifier.screenPadding(): Modifier = this.padding(horizontal = 16.dp)

/**
 * Text whose leading number counts up to its value when it first appears and glides when it
 * changes ("12", "3.5 h", "$48", "85%"). Anything that isn't a number shows as is.
 */
@Composable
fun CountUpText(value: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val m = remember(value) { Regex("^([^\\d-]*)(-?[\\d,]*\\.?\\d+)(.*)$").find(value) }
    if (m == null) { Text(value, modifier, color = color, style = style, maxLines = 1); return }
    val (pre, num, post) = m.destructured
    val target = num.replace(",", "").toDoubleOrNull()
    if (target == null) { Text(value, modifier, color = color, style = style, maxLines = 1); return }
    val decimals = num.substringAfter('.', "").length
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(target) {
        anim.animateTo(target.toFloat(), if (com.blacklab.app.ui.theme.Motion.reduced) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(700, easing = com.blacklab.app.ui.theme.Motion.ease))
    }
    val shown = if (decimals > 0) "%.${decimals}f".format(anim.value) else "%,d".format(anim.value.roundToLong()).let { if (num.contains(',')) it else it.replace(",", "") }
    Text(pre + shown + post, modifier, color = color, style = style.merge(com.blacklab.app.ui.theme.MonoDigits), maxLines = 1)
}

private fun Float.roundToLong(): Long = kotlin.math.round(this.toDouble()).toLong()
