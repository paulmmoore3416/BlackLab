package com.blacklab.app.ui.settings

import android.content.SharedPreferences
import com.blacklab.app.core.Keys
import com.blacklab.app.core.Settings
import com.blacklab.app.ui.theme.AccentPalette

/**
 * Quick looks: one tap sets the visual style, backdrop, corners, light/dark styles and both account colors together.
 * [dark] = null keeps your Theme (light/dark/auto) as it is.
 */
object ThemePresets {
    data class Preset(
        val id: String, val label: String,
        val visualStyle: String, val backdrop: String, val intensity: Int, val frost: Int, val corners: String,
        val lightStyle: String, val darkStyle: String,
        val personal: AccentPalette, val professional: AccentPalette,
        val dark: Boolean? = null,
    ) {
        val palette get() = professional
    }

    val all = listOf(
        Preset("m365", "Microsoft 365", "FLUENT", "MICA", 55, 60, "FLUENT", "FLUENT", "FLUENT", AccentPalette.MICROSOFT, AccentPalette.MICROSOFT),
        Preset("teams", "Teams", "FLUENT", "MICA", 60, 60, "FLUENT", "FLUENT", "FLUENT", AccentPalette.TEAMS, AccentPalette.TEAMS),
        Preset("office", "Office trio", "FLUENT", "MICA", 50, 65, "FLUENT", "FLUENT", "FLUENT", AccentPalette.EXCEL, AccentPalette.WORD),
        Preset("deck", "Presenter", "FLUENT", "GRADIENT", 45, 70, "FLUENT", "PURE", "FLUENT", AccentPalette.POWERPOINT, AccentPalette.POWERPOINT),
        Preset("aurora", "Aurora glass", "GLASS", "AURORA", 60, 50, "ROUND", "CLEAN", "GRAPHITE", AccentPalette.MINT, AccentPalette.COBALT),
        Preset("night", "OLED night", "GLASS", "PLAIN", 0, 35, "SOFT", "CLEAN", "OLED", AccentPalette.MIDNIGHT, AccentPalette.SKY, dark = true),
        Preset("paper", "Paper", "SOLID", "PLAIN", 0, 100, "SOFT", "WARM", "GRAPHITE", AccentPalette.COPPER, AccentPalette.SAGE, dark = false),
        Preset("classic", "Classic solid", "SOLID", "PLAIN", 0, 100, "ROUND", "CLEAN", "GRAPHITE", AccentPalette.MINT, AccentPalette.COBALT),
    )

    fun apply(p: Preset, e: SharedPreferences.Editor) {
        e.putString(Keys.VISUAL_STYLE, p.visualStyle).putString(Keys.BACKDROP, p.backdrop).putInt(Keys.BACKDROP_INTENSITY, p.intensity)
            .putInt(Keys.CARD_FROST, p.frost).putString(Keys.CORNER_STYLE, p.corners).putString(Keys.LIGHT_STYLE, p.lightStyle)
            .putString(Keys.DARK_STYLE, p.darkStyle).putString(Keys.ACCENT_PERSONAL, p.personal.name).putString(Keys.ACCENT_PROFESSIONAL, p.professional.name)
            .putString(Keys.SECONDARY_PERSONAL, "AUTO").putString(Keys.SECONDARY_PROFESSIONAL, "AUTO").putBoolean(Keys.DYNAMIC_COLOR, false)
        p.dark?.let { e.putString(Keys.THEME_MODE, if (it) "DARK" else "LIGHT") }
    }

    fun matches(p: Preset, s: Settings): Boolean =
        s.visualStyle == p.visualStyle && s.backdrop == p.backdrop && s.cornerStyle == p.corners && s.accentPersonal == p.personal.name &&
            s.accentProfessional == p.professional.name && s.lightStyle == p.lightStyle && s.darkStyle == p.darkStyle && !s.dynamicColor
}
