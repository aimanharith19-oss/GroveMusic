package eu.kanade.tachiyomi.ui.grovestats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.Metro
import eu.kanade.presentation.components.RegisterPivotMenu
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.offline.OfflineLibrary
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Book
import kotlin.math.max

/** Offline (Books tab) numbers. */
private data class BookStats(
    val total: Int,
    val started: Int,
    val finished: Int,
    val folders: Int,
    val timeMs: Long,
    val byFormat: List<Pair<String, Int>>,
    val byFolder: List<Pair<String, Int>>,
    val top: List<TopBook>,
    val daily: Map<java.time.LocalDate, Long>,
)

/**
 * Grovebook "stats": every number about your library and books, with graphs.
 */
data object GroveStatsTab : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(index = 4u, title = "stats", icon = rememberVectorPainter(MaterialSymbols.Rounded.Book))

    @Composable
    override fun Content() {
        val vm = metroViewModel<GroveStatsViewModel>()
        val stats by vm.stats.collectAsState()
        val context = LocalContext.current
        var books by remember { mutableStateOf<BookStats?>(null) }
        var tick by remember { mutableStateOf(0) }

        LaunchedEffect(tick) {
            val shelves = OfflineLibrary.scan(context)
            val all = shelves.flatMap { it.books }.distinctBy { it.uri }
            val today = java.time.LocalDate.now()
            books = BookStats(
                total = all.size,
                started = all.count { OfflineLibrary.progress(context, it.uri) != null },
                finished = all.count { (OfflineLibrary.progress(context, it.uri) ?: 0) >= 100 },
                folders = OfflineLibrary.collections(context).size,
                timeMs = all.sumOf { OfflineLibrary.readTime(context, it.uri) },
                top = all.mapNotNull { b ->
                    val p = OfflineLibrary.progress(context, b.uri) ?: return@mapNotNull null
                    TopBook(
                        title = OfflineLibrary.title(context, b),
                        cover = OfflineLibrary.cover(context, b),
                        timeMs = OfflineLibrary.readTime(context, b.uri),
                        progress = p,
                        detail = "${b.extension} on your phone",
                        onPhone = true,
                    )
                },
                daily = (0..13).associate { today.minusDays(it.toLong()).let { d -> d to OfflineLibrary.dayTime(context, d) } },
                byFormat = all.groupingBy { it.extension }.eachCount().toList().sortedByDescending { it.second },
                byFolder = shelves.map { it.path.substringAfterLast(" / ") to it.books.size }
                    .sortedByDescending { it.second }.take(8),
            )
        }

        RegisterPivotMenu("stats", listOf("refresh" to { vm.refresh(); tick++; Unit }))

        val s = stats
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            if (s == null) {
                Text("counting…", color = Metro.Dim, fontSize = 22.sp, fontWeight = FontWeight.Light)
                return@Column
            }

            val b0 = books
            // Everything together: online library + books on your phone
            Heading("everything")
            TileRow(
                "titles" to (s.books + (b0?.total ?: 0)).toString(),
                "reading" to (s.started + (b0?.started ?: 0)).toString(),
                "finished" to (s.completed + (b0?.finished ?: 0)).toString(),
            )
            val totalTime = s.readTimeMs + (b0?.timeMs ?: 0L)
            val activeDays = s.daily.count { (d, ms) -> ms + (b0?.daily?.get(d) ?: 0L) > 0 }
            TileRow(
                "total reading" to formatDuration(totalTime),
                "days read (2 wks)" to activeDays.toString(),
                "avg / day" to formatDuration(
                    s.daily.sumOf { (d, ms) -> ms + (b0?.daily?.get(d) ?: 0L) } / 14,
                ),
            )

            Heading("reading time, last 14 days")
            Columns(
                s.daily.map { (d, ms) ->
                    d.dayOfMonth.toString() to (((ms + (b0?.daily?.get(d) ?: 0L)) / 60_000).toInt())
                },
                unit = "m",
            )

            Heading("top 10 most read")
            val top = (s.top + (b0?.top ?: emptyList()))
                .sortedWith(compareByDescending<TopBook> { it.timeMs }.thenByDescending { it.progress ?: 0 })
                .take(10)
            if (top.isEmpty()) {
                Text("start reading to see your top books", color = Metro.Dim, fontSize = 16.sp)
            }
            val maxTime = max(1L, top.maxOfOrNull { it.timeMs } ?: 1L)
            top.forEachIndexed { i, t -> TopRow(i + 1, t, maxTime) }

            Heading("library")
            TileRow(
                "books" to s.books.toString(),
                "started" to s.started.toString(),
                "completed" to s.completed.toString(),
            )
            TileRow(
                "chapters" to s.chapters.toString(),
                "read" to s.readChapters.toString(),
                "unread" to (s.chapters - s.readChapters).coerceAtLeast(0).toString(),
            )
            TileRow(
                "reading time" to formatDuration(s.readTimeMs),
                "downloaded" to s.downloads.toString(),
                "bookmarks" to s.bookmarks.toString(),
            )

            Heading("chapters read")
            Donut(read = s.readChapters, total = s.chapters)

            Heading("reading progress")
            Bars(s.progress)

            Heading("books opened per month")
            Columns(s.monthly)

            Heading("last read")
            Bars(s.recency)

            Heading("by status")
            Bars(s.byStatus)

            Heading("by folder")
            Bars(s.byFolder)

            Heading("top sources")
            Bars(s.bySource)

            val b = books
            Heading("books on your phone")
            if (b == null) {
                Text("counting…", color = Metro.Dim, fontSize = 18.sp, fontWeight = FontWeight.Light)
            } else {
                TileRow(
                    "books" to b.total.toString(),
                    "opened" to b.started.toString(),
                    "finished" to b.finished.toString(),
                )
                TileRow(
                    "reading time" to formatDuration(b.timeMs),
                    "your folders" to b.folders.toString(),
                    "formats" to b.byFormat.size.toString(),
                )
                if (b.total > 0) {
                    Heading("books opened")
                    Donut(read = b.started, total = b.total)
                    Heading("by format")
                    Bars(b.byFormat)
                    Heading("by phone folder")
                    Bars(b.byFolder)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Light,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp),
    )
}

/** Three flat Metro tiles with a big light number each. */
@Composable
private fun TileRow(vararg items: Pair<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEachIndexed { i, (label, value) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (i == 0) Metro.Accent else Color(0xFF1F1F1F))
                    .padding(10.dp),
            ) {
                Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Light, maxLines = 1)
                Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

/** Grows from 0 to 1 when shown, so charts animate in. */
@Composable
private fun growIn(): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(700, easing = Metro.Ease)) }
    return a.value
}

/** Horizontal bar chart: label, bar, count. */
@Composable
private fun Bars(data: List<Pair<String, Int>>) {
    if (data.isEmpty()) {
        Text("nothing yet", color = Metro.Dim, fontSize = 16.sp)
        return
    }
    val maxValue = max(1, data.maxOf { it.second })
    val grow = growIn()
    val accent = Metro.Accent
    data.forEach { (label, value) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label.lowercase(),
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(110.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(18.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFF1F1F1F), size = size)
                    drawRect(accent, size = Size(size.width * value / maxValue * grow, size.height))
                }
            }
            Text(
                text = value.toString(),
                color = Metro.Dim,
                fontSize = 14.sp,
                modifier = Modifier
                    .width(48.dp)
                    .padding(start = 8.dp),
            )
        }
    }
}

/** Vertical columns, used for the monthly graph. */
@Composable
private fun Columns(data: List<Pair<String, Int>>, unit: String = "") {
    val maxValue = max(1, data.maxOfOrNull { it.second } ?: 1)
    val grow = growIn()
    val accent = Metro.Accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(if (data.size > 8) 3.dp else 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEach { (label, value) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (value > 0) "$value$unit" else "", color = Metro.Dim, fontSize = 10.sp, maxLines = 1)
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                ) {
                    val h = size.height * value / maxValue * grow
                    drawRect(accent, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                }
                Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

/** Ring showing how much is done. */
@Composable
private fun Donut(read: Int, total: Int) {
    val grow = growIn()
    val accent = Metro.Accent
    val fraction = if (total <= 0) 0f else read.toFloat() / total
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(120.dp)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0xFF1F1F1F), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(accent, -90f, 360f * fraction * grow, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text("${(fraction * 100).toInt()}%", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Light)
            Text("$read of $total", color = Metro.Dim, fontSize = 15.sp)
        }
    }
}

/** One line of the top 10: rank, cover, title, time and progress. */
@Composable
private fun TopRow(rank: Int, book: TopBook, maxTime: Long) {
    val grow = growIn()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = rank.toString(),
            color = if (rank <= 3) Metro.Accent else Metro.Dim,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.width(36.dp),
        )
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(60.dp)
                .background(Color(0xFF1F1F1F)),
        ) {
            if (book.cover != null) {
                eu.kanade.presentation.manga.components.MangaCover.Book(
                    data = book.cover,
                    modifier = Modifier.fillMaxSize(),
                    shape = androidx.compose.ui.graphics.RectangleShape,
                )
            } else {
                eu.kanade.presentation.library.components.DrawnCover(book.title, book.title.hashCode())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title.lowercase(),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(formatDuration(book.timeMs))
                    book.progress?.let { append("  ·  $it%") }
                    append("  ·  ")
                    append(if (book.onPhone) "book" else "library")
                },
                color = Metro.Dim,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(top = 2.dp),
            ) {
                drawRect(Color(0xFF1F1F1F), size = size)
                drawRect(Metro.Accent, size = Size(size.width * book.timeMs / maxTime * grow, size.height))
            }
        }
    }
}
