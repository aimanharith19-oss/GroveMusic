package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import tachiyomi.i18n.MR

/** Grovebook: Extensions screen opened from More. */
class ExtensionsScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val vm = metroViewModel<ExtensionsViewModel>()
        val query by vm.searchQuery.collectAsStateWithLifecycle()
        TabbedScreen(
            titleRes = MR.strings.label_extensions,
            tabs = listOf(extensionsTab(vm)),
            searchQuery = query,
            onChangeSearchQuery = vm::search,
            navigateUp = navigator::pop,
        )
    }
}

/** Grovebook: Migrate screen opened from More. */
class MigrateSourcesScreen : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        TabbedScreen(
            titleRes = MR.strings.label_migration,
            tabs = listOf(migrateSourceTab()),
            navigateUp = navigator::pop,
        )
    }
}
