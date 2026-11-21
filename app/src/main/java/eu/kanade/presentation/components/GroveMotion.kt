package eu.kanade.presentation.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged

/** Grove motion curve shared by all Grove apps. */
val GroveEasing = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)

/**
 * Grove "tilt" press effect: the item tips toward the finger and springs back,
 * then fires [onClick].
 */
fun Modifier.groveTilt(onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var touchX by remember { mutableFloatStateOf(0.5f) }
    var touchY by remember { mutableFloatStateOf(0.5f) }
    var width by remember { mutableFloatStateOf(1f) }
    var height by remember { mutableFloatStateOf(1f) }
    val progress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = GroveEasing),
        label = "groveTilt",
    )
    this
        .onSizeChanged {
            width = it.width.toFloat().coerceAtLeast(1f)
            height = it.height.toFloat().coerceAtLeast(1f)
        }
        .graphicsLayer {
            val dx = (touchX - 0.5f) * 2f
            val dy = (touchY - 0.5f) * 2f
            rotationY = dx * 6f * progress
            rotationX = -dy * 6f * progress
            val s = 1f - 0.03f * progress
            scaleX = s
            scaleY = s
            cameraDistance = 12f * density
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touchX = (down.position.x / width).coerceIn(0f, 1f)
                touchY = (down.position.y / height).coerceIn(0f, 1f)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
}
