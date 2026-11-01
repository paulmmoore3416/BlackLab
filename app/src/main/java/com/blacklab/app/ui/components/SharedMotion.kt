package com.blacklab.app.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.blacklab.app.ui.theme.Motion

/** Set around the NavHost and per destination, so items and their detail screens can morph into each other. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Container transform: the card with this [key] grows into the screen that uses the same key
 * (and shrinks back on return, following the back gesture). No-op with reduced motion.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCard(key: String): Modifier {
    val shared = LocalSharedScope.current
    val anim = LocalNavAnimScope.current
    if (shared == null || anim == null || Motion.reduced) return this
    return with(shared) {
        this@sharedCard.sharedBounds(
            rememberSharedContentState(key), anim,
            enter = fadeIn(tween(260, delayMillis = 60)), exit = fadeOut(tween(160)),
            boundsTransform = { _, _ -> tween(420, easing = Motion.emphasized) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        )
    }
}
