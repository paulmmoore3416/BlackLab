package com.blacklab.app.ui.components

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptic vocabulary, so every screen feels the same in the hand:
 * tick (selection), confirm (completed), reject (destructive/failed), toggle, longPress.
 * Turned off by Settings › Haptic feedback; Android's own touch-vibration setting still applies.
 */
object Haptics {
    @Volatile var enabled: Boolean = true
    private fun View.buzz(c: Int) { if (enabled) performHapticFeedback(c) }
    fun tick(v: View) = v.buzz(HapticFeedbackConstants.CLOCK_TICK)
    fun confirm(v: View) = v.buzz(HapticFeedbackConstants.CONFIRM)
    fun reject(v: View) = v.buzz(HapticFeedbackConstants.REJECT)
    fun toggle(v: View, on: Boolean) = v.buzz(if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
    fun longPress(v: View) = v.buzz(HapticFeedbackConstants.LONG_PRESS)
}

class HapticsHandle(private val view: View) {
    fun tick() = Haptics.tick(view)
    fun confirm() = Haptics.confirm(view)
    fun reject() = Haptics.reject(view)
    fun toggle(on: Boolean) = Haptics.toggle(view, on)
    fun longPress() = Haptics.longPress(view)
}

@Composable
fun rememberHaptics(): HapticsHandle {
    val view = LocalView.current
    return remember(view) { HapticsHandle(view) }
}
