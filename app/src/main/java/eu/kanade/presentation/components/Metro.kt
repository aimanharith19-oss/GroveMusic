package eu.kanade.presentation.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Grovebook Metro (Windows Phone) building blocks, matching Grove Music.
 */
object Metro {
    /** Grove accent (Windows Phone emerald family). */
    val Accent = Color(0xFF339966)
    val White = Color.White
    val Dim = Color(0xFF8A8A8A)
    val Ease = CubicBezierEasing(0.1f, 0.9f, 0.2f, 1f)
}

/** True for content shown inside the home panorama: page titles there come from the pivot headers. */
val LocalInPivot = staticCompositionLocalOf { false }

/** Accent glow at the top fading into black, as in Grove Music. */
@Composable
fun MetroTintBackground(accent: Color = Metro.Accent, strength: Float = 0.38f) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to accent.copy(alpha = strength),
                    0.45f to accent.copy(alpha = strength * 0.25f),
                    1f to Color.Black,
                ),
            ),
    )
}

/** Huge, light, lowercase panorama title that slides slower than the content (parallax). */
@Composable
fun MetroPanoramaTitle(title: String, pos: () -> Float) {
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Text(
            text = title,
            color = Metro.White,
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-3).sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -pos() * 60.dp.toPx() }
                .padding(start = 12.dp, top = 2.dp),
        )
    }
}

/** Pivot headers that glide with the finger, in step with the pager. */
@Composable
fun MetroPivotHeaders(pages: List<String>, pos: () -> Float, onSelect: (Int) -> Unit) {
    val widths = remember(pages.size) { mutableStateListOf<Float>().apply { repeat(pages.size) { add(0f) } } }
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer {
                    val p = pos()
                    val i = p.toInt().coerceIn(0, pages.lastIndex)
                    val f = p - i
                    translationX = -(widths.take(i).sum() + widths.getOrElse(i) { 0f } * f)
                }
                .padding(start = 18.dp),
        ) {
            pages.forEachIndexed { i, name ->
                Text(
                    text = name,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    softWrap = false,
                    color = Metro.White,
                    modifier = Modifier
                        .onSizeChanged { widths[i] = it.width.toFloat() }
                        .graphicsLayer { alpha = 0.35f + 0.65f * (1f - abs(pos() - i)).coerceIn(0f, 1f) }
                        .clickable(
                            interactionSource = null,
                            indication = null,
                        ) { onSelect(i) }
                        .padding(end = 22.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Windows Phone "tilt": the item tips toward the finger. */
fun Modifier.metroTilt(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val p by animateFloatAsState(
        if (pressed) 1f else 0f,
        tween(if (pressed) 90 else 260, easing = Metro.Ease),
        label = "metroTilt",
    )
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    this
        .onSizeChanged { sz = it }
        .graphicsLayer {
            val nx = if (sz.width > 0) (touch.x / sz.width - 0.5f) * 2f else 0f
            val ny = if (sz.height > 0) (touch.y / sz.height - 0.5f) * 2f else 0f
            val maxDeg = if (sz.width > 600) 4f else 10f
            rotationY = -nx * maxDeg * p
            rotationX = ny * maxDeg * p
            val sc = 1f - 0.035f * p
            scaleX = sc
            scaleY = sc
            cameraDistance = 14f * density
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { o ->
                    touch = o
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                },
                onLongPress = long?.let { l -> { _: Offset -> l() } },
                onTap = { click() },
            )
        }
}

/**
 * Page-specific commands for the Windows Phone "•••" menu. Each panorama page registers
 * its own items (search, filter, update…) under its key; the menu shows them on top.
 */
object PivotMenu {
    val items = androidx.compose.runtime.mutableStateMapOf<String, List<Pair<String, () -> Unit>>>()
}

/** Registers [items] for [key] in the "•••" menu while this page is on screen. */
@Composable
fun RegisterPivotMenu(key: String, items: List<Pair<String, () -> Unit>>) {
    val latest by rememberUpdatedState(items)
    androidx.compose.runtime.DisposableEffect(key, items.map { it.first }) {
        PivotMenu.items[key] = latest.map { (label, _) ->
            label to { latest.firstOrNull { it.first == label }?.second?.invoke(); Unit }
        }
        onDispose { PivotMenu.items.remove(key) }
    }
}
