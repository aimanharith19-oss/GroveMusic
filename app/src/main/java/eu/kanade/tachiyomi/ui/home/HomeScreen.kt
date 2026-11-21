package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.metroTilt
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabNavigator
import eu.kanade.core.preference.asState
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryTab
import eu.kanade.tachiyomi.ui.library.LibraryTab
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import soup.compose.material.motion.animation.materialFadeThroughIn
import soup.compose.material.motion.animation.materialFadeThroughOut
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource

object HomeScreen : Screen() {

    private val librarySearchEvent = Channel<String>()
    private val openTabEvent = Channel<Tab>()
    private val showBottomNavEvent = Channel<Boolean>()

    @Suppress("ConstPropertyName")
    private const val TabFadeDuration = 200

    @Suppress("ConstPropertyName")
    private const val TabNavigatorKey = "HomeTabs"

    private val TABS = listOf(
        LibraryTab,
        eu.kanade.tachiyomi.ui.offline.OfflineTab,
        UpdatesTab,
        BrowseTab,
        eu.kanade.tachiyomi.ui.grovestats.GroveStatsTab,
        MoreTab,
    )

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        // SY -->
        val scope = rememberCoroutineScope()
        val uiPreferences = LocalContext.current.appGraph.uiPreferences
        val alwaysShowLabel by remember {
            uiPreferences.bottomBarLabels.asState(scope)
        }
        // SY <--

        TabNavigator(
            tab = LibraryTab,
            key = TabNavigatorKey,
        ) { tabNavigator ->
            // Provide usable navigator to content screen
            CompositionLocalProvider(LocalNavigator provides navigator) {
                // Grovebook: Windows Phone panorama — big title, pivot headers, swipe between sections
                GrovePanorama(tabNavigator, navigator)
            }

            val goToLibraryTab = { tabNavigator.current = LibraryTab }

            BackHandler(enabled = tabNavigator.current != LibraryTab, onBack = goToLibraryTab)

            // SY -->
            LaunchedEffect(tabNavigator.current) {
                BrowseTab.onBrowseTabSelected(tabNavigator.current == BrowseTab)
            }
            // SY <--

            LaunchedEffect(Unit) {
                launch {
                    librarySearchEvent.receiveAsFlow().collectLatest {
                        goToLibraryTab()
                        LibraryTab.search(it)
                    }
                }
                launch {
                    openTabEvent.receiveAsFlow().collectLatest {
                        tabNavigator.current = when (it) {
                            is Tab.Library -> LibraryTab
                            Tab.Updates -> UpdatesTab
                            Tab.History -> MoreTab
                            is Tab.Browse -> {
                                if (it.toExtensions) {
                                    BrowseTab.showExtension()
                                }
                                BrowseTab
                            }

                            is Tab.More -> MoreTab
                        }

                        if (it is Tab.Library && it.mangaIdToOpen != null) {
                            navigator.push(MangaScreen(it.mangaIdToOpen))
                        }
                        if (it is Tab.History) {
                            navigator.push(HistoryTab)
                        }
                        if (it is Tab.More && it.toDownloads) {
                            navigator.push(DownloadQueueScreen)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun GrovePanorama(
        tabNavigator: cafe.adriel.voyager.navigator.tab.TabNavigator,
        navigator: cafe.adriel.voyager.navigator.Navigator,
    ) {
        val pager = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = TABS.indexOfFirst { it == tabNavigator.current }.coerceAtLeast(0),
        ) { TABS.size }
        val pos: () -> Float = { pager.currentPage + pager.currentPageOffsetFraction }
        val scope = rememberCoroutineScope()
        var menu by remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.activity.compose.BackHandler(menu) { menu = false }
        // Nothing shows a bottom bar any more; drain requests so senders never wait
        LaunchedEffect(Unit) { showBottomNavEvent.receiveAsFlow().collect {} }

        // Keep the tab navigator and the pager in step
        LaunchedEffect(tabNavigator.current) {
            val i = TABS.indexOfFirst { it == tabNavigator.current }
            if (i >= 0 && i != pager.currentPage) {
                pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.tween(450, easing = eu.kanade.presentation.components.Metro.Ease))
            }
        }
        LaunchedEffect(pager.settledPage) {
            val t = TABS[pager.settledPage]
            if (tabNavigator.current != t) tabNavigator.current = t
        }

        val titles = TABS.map { tab ->
            when (tab) {
                is LibraryTab -> "library"
                is eu.kanade.tachiyomi.ui.offline.OfflineTab -> "books"
                is UpdatesTab -> "updates"
                is BrowseTab -> "source"
                is eu.kanade.tachiyomi.ui.grovestats.GroveStatsTab -> "stats"
                else -> "more"
            }
        }
        val baseScheme = MaterialTheme.colorScheme
        val pivotScheme = remember(baseScheme) {
            baseScheme.copy(
                background = androidx.compose.ui.graphics.Color.Transparent,
                surface = androidx.compose.ui.graphics.Color.Transparent,
            )
        }

        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            eu.kanade.presentation.components.MetroTintBackground()
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().statusBarsPadding()) {
                eu.kanade.presentation.components.MetroPanoramaTitle("grovebook", pos)
                eu.kanade.presentation.components.MetroPivotHeaders(titles, pos) { i ->
                    scope.launch {
                        pager.animateScrollToPage(i, animationSpec = androidx.compose.animation.core.tween(450, easing = eu.kanade.presentation.components.Metro.Ease))
                    }
                }
                CompositionLocalProvider(eu.kanade.presentation.components.LocalInPivot provides true) {
                    MaterialTheme(
                        colorScheme = pivotScheme,
                        typography = MaterialTheme.typography,
                        shapes = MaterialTheme.shapes,
                    ) {
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pager,
                            modifier = Modifier
                                .weight(1f)
                                .consumeWindowInsets(WindowInsets.statusBars)
                                .consumeWindowInsets(WindowInsets.navigationBars),
                            key = { it },
                        ) { p ->
                            tabNavigator.saveableState(key = "pivot", TABS[p]) {
                                TABS[p].Content()
                            }
                        }
                    }
                }
                MetroAppBar { menu = !menu }
            }

            // Windows Phone app-bar menu sliding up from the bottom
            androidx.compose.animation.AnimatedVisibility(
                menu,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
                        .clickable(interactionSource = null, indication = null) { menu = false },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                menu,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(380, easing = eu.kanade.presentation.components.Metro.Ease)) { it },
                exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(260, easing = eu.kanade.presentation.components.Metro.Ease)) { it },
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.lerp(eu.kanade.presentation.components.Metro.Accent, androidx.compose.ui.graphics.Color.Black, 0.62f))
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 18.dp),
                ) {
                    val pageKey = when (TABS.getOrNull(pager.currentPage)) {
                        is LibraryTab -> "library"
                        is eu.kanade.tachiyomi.ui.offline.OfflineTab -> "books"
                        is eu.kanade.tachiyomi.ui.grovestats.GroveStatsTab -> "stats"
                        else -> null
                    }
                    val pageItems = pageKey?.let { eu.kanade.presentation.components.PivotMenu.items[it] }.orEmpty()
                    (pageItems + listOf<Pair<String, () -> Unit>>(
                        "history" to { navigator.push(HistoryTab) },
                        "downloads" to { navigator.push(eu.kanade.tachiyomi.ui.download.DownloadQueueScreen) },
                        "extensions" to { navigator.push(eu.kanade.tachiyomi.ui.browse.ExtensionsScreen()) },
                        "migrate" to { navigator.push(eu.kanade.tachiyomi.ui.browse.MigrateSourcesScreen()) },
                        "folders" to { navigator.push(eu.kanade.tachiyomi.ui.category.CategoryScreen()) },
                        "settings" to { navigator.push(eu.kanade.tachiyomi.ui.setting.SettingsScreen()) },
                    )).forEachIndexed { index, (label, act) ->
                        if (index == pageItems.size && pageItems.isNotEmpty()) {
                            androidx.compose.foundation.layout.Spacer(Modifier.height(10.dp))
                        }
                        Text(
                            text = label,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .metroTilt {
                                    menu = false
                                    act()
                                }
                                .padding(vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }

    /** Minimised Windows Phone application bar: just "•••" on the right. */
    @Composable
    private fun MetroAppBar(onMore: () -> Unit) {
        androidx.compose.foundation.layout.Row(
            Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .background(eu.kanade.presentation.components.Metro.Accent.copy(alpha = 0.12f))
                .navigationBarsPadding()
                .height(48.dp)
                .clickable(onClick = onMore),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "•••",
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 22.sp,
                modifier = Modifier.padding(end = 22.dp),
            )
        }
    }

    @Composable
    private fun NavigationSuiteItem(
        tab: eu.kanade.presentation.util.Tab,
        navigationSuiteType: NavigationSuiteType,
        // SY -->
        alwaysShowLabel: Boolean,
        // SY <--
    ) {
        val tabNavigator = LocalTabNavigator.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val selected = tabNavigator.current::class == tab::class
        NavigationSuiteItem(
            navigationSuiteType = navigationSuiteType,
            selected = selected,
            onClick = {
                if (!selected) {
                    tabNavigator.current = tab
                } else {
                    scope.launch { tab.onReselect(navigator) }
                }
            },
            icon = {
                Icon(
                    painter = tab.options.icon!!,
                    contentDescription = tab.options.title,
                )
            },
            label = {
                // SY -->
                if (alwaysShowLabel || selected) {
                    // SY <--
                    Text(
                        text = tab.options.title,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // SY -->
                }
                // SY <--
            },
            badge = tabBadge(tab),
        )
    }

    @Composable
    private fun tabBadge(tab: eu.kanade.presentation.util.Tab): (@Composable () -> Unit)? {
        val context = LocalContext.current
        val count by produceState(initialValue = 0, tab) {
            val graph = context.appGraph
            when (tab) {
                is UpdatesTab -> {
                    combine(
                        graph.libraryPreferences.newShowUpdatesCount.changes(),
                        graph.libraryPreferences.newUpdatesCount.changes(),
                    ) { show, count ->
                        if (show) count else 0
                    }
                        .collectLatest { value = it }
                }

                is BrowseTab -> {
                    graph.sourcePreferences.extensionUpdatesCount.changes()
                        .collectLatest { value = it }
                }

                else -> value = 0
            }
        }
        if (count <= 0) return null
        return {
            Badge {
                val desc = when (tab) {
                    is UpdatesTab -> pluralStringResource(
                        MR.plurals.notification_chapters_generic,
                        count = count,
                        count,
                    )

                    is BrowseTab -> pluralStringResource(
                        MR.plurals.update_check_notification_ext_updates,
                        count = count,
                        count,
                    )

                    else -> null
                }
                Text(
                    text = count.toString(),
                    modifier = Modifier.semantics {
                        if (desc != null) contentDescription = desc
                    },
                )
            }
        }
    }

    suspend fun search(query: String) {
        librarySearchEvent.send(query)
    }

    suspend fun openTab(tab: Tab) {
        openTabEvent.send(tab)
    }

    suspend fun showBottomNav(show: Boolean) {
        showBottomNavEvent.send(show)
    }

    sealed interface Tab {
        data class Library(val mangaIdToOpen: Long? = null) : Tab
        data object Updates : Tab
        data object History : Tab
        data class Browse(val toExtensions: Boolean = false) : Tab
        data class More(val toDownloads: Boolean) : Tab
    }
}
