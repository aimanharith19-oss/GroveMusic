package eu.kanade.presentation.library.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.Metro
import eu.kanade.presentation.components.metroTilt
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

/** A book in the grid. [cover] is anything the image loader understands; null = flat title tile. */
data class RackBook(
    val key: Any,
    val title: String,
    val cover: Any?,
    val badge: String? = null,
    val selected: Boolean = false,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit = {},
)

/** A group of books (a folder), shown under a Windows Phone group header. */
data class RackShelf(
    val key: Any,
    val label: String,
    val books: List<RackBook>,
)

/**
 * Grovebook library in Windows Phone style: each folder is a group with a big lowercase
 * header and an accent letter tile, and all of its books sit in a flat, square grid.
 */
@Composable
fun GroveRack(
    shelves: List<RackShelf>,
    topPadding: Dp,
    bottomPadding: Dp,
    emptyText: String,
    header: LazyListScope.() -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
    storeKey: String = "rack",
) {
    val accent = MaterialTheme.colorScheme.primary
    // Folders whose content is hidden (tap a folder name to hide/show), remembered between launches
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("grove_rack", android.content.Context.MODE_PRIVATE) }
    val collapsed = remember {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("collapsed_$storeKey", emptySet()).orEmpty())
        }
    }
    fun toggle(key: String) {
        if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
        prefs.edit().putStringSet("collapsed_$storeKey", collapsed.toSet()).apply()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val usable = maxWidth - 32.dp
        val columns = max(3, (usable / 108.dp).toInt())

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topPadding + 4.dp, bottom = bottomPadding + 24.dp),
        ) {
            header()

            if (shelves.isEmpty()) {
                item(key = "rack-empty") {
                    Text(
                        text = emptyText.lowercase(),
                        color = Metro.Dim,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }

            var seq = 0
            shelves.forEach { shelf ->
                val headerIndex = seq++
                val hidden = shelf.key.toString() in collapsed
                item(key = "head-${shelf.key}") {
                    GroupHeader(shelf.label, shelf.books.size, accent, headerIndex, hidden) {
                        toggle(shelf.key.toString())
                    }
                }
                if (hidden) {
                    item(key = "gap-${shelf.key}") { Spacer(Modifier.height(6.dp)) }
                    return@forEach
                }
                if (shelf.books.isEmpty()) {
                    item(key = "empty-${shelf.key}") {
                        Text(
                            text = "nothing here yet",
                            color = Metro.Dim,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.padding(start = 74.dp, bottom = 8.dp),
                        )
                    }
                }
                shelf.books.chunked(columns).forEachIndexed { rowIndex, row ->
                    val rowFeather = seq++
                    item(key = "row-${shelf.key}-$rowIndex") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .feather(rowFeather)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            for (i in 0 until columns) {
                                val book = row.getOrNull(i)
                                Box(Modifier.weight(1f)) {
                                    if (book != null) BookTile(book, accent)
                                }
                            }
                        }
                    }
                }
                item(key = "gap-${shelf.key}") { Spacer(Modifier.height(14.dp)) }
            }

            footer()
        }
    }
}

/** Windows Phone "feather": items slide in from the right one after another. */
@Composable
private fun Modifier.feather(index: Int): Modifier {
    if (index > 14) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 35L)
        a.animateTo(1f, tween(420, easing = Metro.Ease))
    }
    return this.graphicsLayer {
        alpha = a.value
        translationX = (1f - a.value) * 90.dp.toPx()
    }
}

@Composable
private fun GroupHeader(
    label: String,
    count: Int,
    accent: Color,
    index: Int,
    hidden: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .feather(index)
            .metroTilt(onClick = onToggle)
            .graphicsLayer { alpha = if (hidden) 0.55f else 1f }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Accent letter tile, like the Windows Phone music and people hubs
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(accent),
            contentAlignment = Alignment.BottomStart,
        ) {
            Text(
                text = label.firstOrNull { it.isLetterOrDigit() }?.lowercase() ?: "#",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        // Full folder name: wraps instead of being cut off
        Column(Modifier.weight(1f)) {
            Text(
                text = label.lowercase(),
                color = Color.White,
                fontSize = 28.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Light,
                softWrap = true,
            )
            Text(
                text = "$count ${if (count == 1) "book" else "books"}" + if (hidden) "  ·  hidden, tap to show" else "",
                color = Metro.Dim,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun BookTile(book: RackBook, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .metroTilt(onLongClick = book.onLongClick, onClick = book.onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (book.selected) Modifier.border(3.dp, accent) else Modifier),
        ) {
            if (book.cover != null) {
                eu.kanade.presentation.manga.components.MangaCover.Book(
                    data = book.cover,
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                )
            } else {
                DrawnCover(title = book.title, seed = book.key.hashCode())
            }
            if (book.badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    // Small reading-progress percentage
                    Text(
                        text = book.badge,
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }
            }
            if (book.selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(accent.copy(alpha = 0.4f)),
                )
            }
        }
        Text(
            text = book.title.lowercase(),
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Light,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, end = 2.dp),
        )
    }
}

/** Flat Windows Phone tile with the title, for books without cover art. */
@Composable
fun DrawnCover(title: String, seed: Int) {
    val tiles = listOf(
        Color(0xFF339966), Color(0xFF00ABA9), Color(0xFF1BA1E2), Color(0xFF6A00FF),
        Color(0xFFD80073), Color(0xFFFA6800), Color(0xFF825A2C), Color(0xFF647687),
    )
    val base = tiles[abs(seed) % tiles.size]
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(lerp(base, Color.Black, 0.1f))
            .padding(8.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = title.lowercase(),
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Light,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
