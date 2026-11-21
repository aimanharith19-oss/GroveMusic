package eu.kanade.presentation.util

import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.ScreenTransitionContent
import soup.compose.material.motion.animation.materialSharedAxisX
import soup.compose.material.motion.animation.rememberSlideDistance

/**
 * For invoking back press to the parent activity
 */
val LocalBackPress: ProvidableCompositionLocal<(() -> Unit)?> = staticCompositionLocalOf { null }

interface Tab : cafe.adriel.voyager.navigator.tab.Tab {
    suspend fun onReselect(navigator: Navigator) {}

    // SY -->
    @Composable
    fun isEnabled(): Boolean = true
    // SY <--
}

abstract class Screen : Screen {

    override val key: ScreenKey = uniqueScreenKey
}

interface AssistContentScreen {
    fun onProvideAssistUrl(): String?
}

@Composable
fun DefaultNavigatorScreenTransition(
    navigator: Navigator,
    modifier: Modifier = Modifier,
) {
    val slideDistance = rememberSlideDistance()
    ScreenTransition(
        navigator = navigator,
        transition = {
            // Grovebook: Windows Phone page transition — new page swings/slides in, old one drops away
            val ease = eu.kanade.presentation.components.Metro.Ease
            val forward = navigator.lastEvent != StackEvent.Pop
            if (forward) {
                (
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 90)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(420, easing = ease)) { it / 3 }
                    ) togetherWith (
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { -it / 6 }
                    )
            } else {
                (
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 90)) +
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(420, easing = ease)) { -it / 3 }
                    ) togetherWith (
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { it / 6 }
                    )
            }
        },
        modifier = modifier,
    )
}

@Composable
fun ScreenTransition(
    navigator: Navigator,
    transition: AnimatedContentTransitionScope<Screen>.() -> ContentTransform,
    modifier: Modifier = Modifier,
    content: ScreenTransitionContent = { it.Content() },
) {
    AnimatedContent(
        targetState = navigator.lastItem,
        transitionSpec = transition,
        modifier = modifier,
        label = "transition",
    ) { screen ->
        navigator.saveableState("transition", screen) {
            content(screen)
        }
    }

    BackHandler(enabled = navigator.canPop, onBack = navigator::pop)
}
