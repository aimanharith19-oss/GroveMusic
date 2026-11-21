package eu.kanade.tachiyomi.ui.grovemedia

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import com.grove.music.BookBridge
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory
import eu.kanade.presentation.components.LocalInPivot
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.offline.OfflineTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import mihon.app.di.appGraph

/** Grove Book's screens shown right inside GroveMedia's comics / book tabs. */
object MediaEmbed {
    fun install() {
        BookBridge.embed = { key, modifier -> Embed(key, modifier) }
    }

    private fun tabFor(key: String): Tab = when (key) {
        "updates" -> UpdatesTab
        "history" -> HistoryTab
        "browse" -> BrowseTab
        "more" -> MoreTab
        "book" -> OfflineTab
        else -> LibraryTab
    }

    @Composable
    private fun Embed(key: String, modifier: Modifier) {
        val ctx = LocalContext.current
        TachiyomiTheme {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodySmall,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalMetroViewModelFactory provides ctx.appGraph.viewModelFactory,
            ) {
                val tab = remember(key) { tabFor(key) }
                if (tab == BrowseTab) {
                    DisposableEffect(Unit) {
                        BrowseTab.onBrowseTabSelected(true)
                        onDispose { BrowseTab.onBrowseTabSelected(false) }
                    }
                }
                Navigator(EmbedRoot(tab), key = "gm_nav_$key") { nav ->
                    LaunchedEffect(nav.size) { BookBridge.deep[key] = nav.size > 1 }
                    DisposableEffect(Unit) { onDispose { BookBridge.deep.remove(key) } }
                    Box(modifier.consumeWindowInsets(WindowInsets.statusBars).consumeWindowInsets(WindowInsets.navigationBars)) {
                        DefaultNavigatorScreenTransition(nav)
                    }
                }
            }
        }
    }

    private class EmbedRoot(val tab: Tab) : Screen {
        override val key = "gm_root_" + tab.key

        @Composable
        override fun Content() {
            val nav = LocalNavigator.currentOrThrow
            val base = MaterialTheme.colorScheme
            val scheme = remember(base) { base.copy(background = Color.Transparent, surface = Color.Transparent) }
            TabNavigator(tab, key = "gm_tabs_" + tab.key) {
                CompositionLocalProvider(LocalNavigator provides nav, LocalInPivot provides true) {
                    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                        tab.Content()
                    }
                }
            }
        }
    }
}
