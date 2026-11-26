package eu.kanade.presentation.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.metroTilt
import cafe.adriel.voyager.navigator.LocalNavigator
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.category.visualName
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.material.PullRefresh
import kotlin.time.Duration.Companion.seconds

/**
 * Grovebook online library: one big rack. Every folder is a level of the rack,
 * labelled with a sticky note, with all of its books shown in a grid.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun LibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: Set<Long>,
    contentPadding: PaddingValues,
    currentPage: Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onClickManga: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (Category, LibraryManga) -> Unit,
    onToggleRangeSelection: (Category, LibraryManga) -> Unit,
    onRefresh: suspend () -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
) {
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val inPivot = eu.kanade.presentation.components.LocalInPivot.current
    val peekCtx = androidx.compose.ui.platform.LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }

    val shown = if (searchQuery.isNullOrEmpty()) {
        categories
    } else {
        categories.filter { getItemsForCategory(it).isNotEmpty() }
    }
    val shelves = shown.map { category ->
        RackShelf(
            key = category.id,
            label = category.visualName,
            books = getItemsForCategory(category).map { item ->
                val manga = item.libraryManga.manga
                RackBook(
                    key = manga.id,
                    title = manga.title,
                    cover = MangaCover(
                        mangaId = manga.id,
                        sourceId = manga.source,
                        isMangaFavorite = manga.favorite,
                        ogUrl = manga.thumbnailUrl,
                        lastModified = manga.coverLastModified,
                    ),
                    badge = item.libraryManga.totalChapters.takeIf { it > 0 }?.let { total ->
                        "${(item.libraryManga.readCount * 100 / total).coerceIn(0L, 100L)}%"
                    },
                    selected = manga.id in selection,
                    onClick = {
                        if (selection.isNotEmpty()) {
                            onToggleSelection(category, item.libraryManga)
                        } else {
                            onClickManga(manga.id)
                        }
                    },
                    // inside GroveMedia: long press = small info card (selecting is one of its buttons)
                    onLongClick = {
                        if (inPivot && selection.isEmpty()) {
                            val lm = item.libraryManga
                            val status = when (manga.status) { 1L -> "ongoing"; 2L -> "completed"; 3L -> "licensed"; 4L -> "finished"; 5L -> "cancelled"; 6L -> "on hiatus"; else -> null }
                            com.grove.music.Peek.show(com.grove.music.PeekData(
                                kind = "COMIC", title = manga.title, sub = manga.author.orEmpty(),
                                image = manga.thumbnailUrl, ratio = 0.7f,
                                rows = listOfNotNull(
                                    manga.author?.takeIf { it.isNotBlank() }?.let { "author" to it },
                                    manga.artist?.takeIf { it.isNotBlank() && it != manga.author }?.let { "artist" to it },
                                    manga.genre?.takeIf { it.isNotEmpty() }?.let { "genre" to it.take(4).joinToString(", ") },
                                    status?.let { "status" to it },
                                    ("chapters" to "${lm.readCount} of ${lm.totalChapters} read").takeIf { lm.totalChapters > 0 },
                                    ("unread" to lm.unreadCount.toString()).takeIf { lm.unreadCount > 0 },
                                ),
                                more = { com.grove.music.PeekMore(about = manga.description?.takeIf { it.isNotBlank() }) },
                                actions = listOf(
                                    "open" to { onClickManga(manga.id) },
                                    "select" to { onToggleSelection(category, lm) },
                                ),
                            ))
                        } else {
                            onToggleRangeSelection(category, item.libraryManga)
                        }
                    },
                )
            },
        )
    }

    PullRefresh(
        refreshing = isRefreshing,
        enabled = selection.isEmpty(),
        onRefresh = {
            scope.launch {
                if (!onRefresh()) return@launch
                isRefreshing = true
                delay(1.seconds)
                isRefreshing = false
            }
        },
    ) {
        GroveRack(
            storeKey = "library",
            shelves = shelves,
            topPadding = contentPadding.calculateTopPadding(),
            bottomPadding = contentPadding.calculateBottomPadding(),
            emptyText = "your library is empty",
            header = {
                if (!searchQuery.isNullOrEmpty()) {
                    item(key = "global-search") {
                        Text(
                            text = "search \"$searchQuery\" in all sources",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .metroTilt(onClick = onGlobalSearchClicked)
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            },
            footer = {
                item(key = "new-folder") {
                    Text(
                        text = "add a folder",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .metroTilt { navigator?.push(CategoryScreen()) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            },
        )
    }
}
