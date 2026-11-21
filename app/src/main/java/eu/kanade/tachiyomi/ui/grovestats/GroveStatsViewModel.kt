package eu.kanade.tachiyomi.ui.grovestats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Everything the stats page shows. */
data class GroveStats(
    val books: Int,
    val completed: Int,
    val started: Int,
    val chapters: Int,
    val readChapters: Int,
    val bookmarks: Int,
    val downloads: Int,
    val readTimeMs: Long,
    val byStatus: List<Pair<String, Int>>,
    val bySource: List<Pair<String, Int>>,
    val byFolder: List<Pair<String, Int>>,
    val progress: List<Pair<String, Int>>,
    val monthly: List<Pair<String, Int>>,
    val recency: List<Pair<String, Int>>,
    /** Per-title reading info for the combined top 10. */
    val top: List<TopBook>,
    /** Library reading time per day for the last 14 days (date to ms). */
    val daily: List<Pair<java.time.LocalDate, Long>>,
)

data class TopBook(
    val title: String,
    val cover: Any?,
    val timeMs: Long,
    val progress: Int?,
    val detail: String,
    val onPhone: Boolean,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class GroveStatsViewModel(
    private val getLibraryManga: GetLibraryManga,
    private val getCategories: GetCategories,
    private val getTotalReadDuration: GetTotalReadDuration,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val historyRepository: tachiyomi.domain.history.repository.HistoryRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow<GroveStats?>(null)
    val stats: StateFlow<GroveStats?> = _stats.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val library = getLibraryManga.await().distinctBy { it.id }
            val categories = getCategories.await().associateBy { it.id }

            val statusName = mapOf(
                SManga.ONGOING to "ongoing",
                SManga.COMPLETED to "completed",
                SManga.LICENSED to "licensed",
                SManga.PUBLISHING_FINISHED to "publishing done",
                SManga.CANCELLED to "cancelled",
                SManga.ON_HIATUS to "on hiatus",
            )
            val byStatus = library.groupingBy { statusName[it.manga.status.toInt()] ?: "unknown" }
                .eachCount().toList().sortedByDescending { it.second }

            val bySourceRaw = library.groupingBy { it.manga.source }.eachCount()
            val bySource = bySourceRaw.map { (id, n) ->
                (runCatching { sourceManager.getOrStub(id).name }.getOrNull() ?: id.toString()) to n
            }.sortedByDescending { it.second }.take(8)

            val byFolder = library.flatMap { m -> m.categories.ifEmpty { listOf(0L) } }
                .groupingBy { id ->
                    categories[id]?.name?.takeIf { it.isNotBlank() } ?: "default"
                }
                .eachCount().toList().sortedByDescending { it.second }

            val buckets = listOf("not started", "1–25%", "26–50%", "51–75%", "76–99%", "caught up")
            val progress = library.groupingBy { m ->
                val total = m.totalChapters
                val pct = if (total <= 0) 0.0 else m.readCount * 100.0 / total
                when {
                    m.readCount == 0L -> buckets[0]
                    pct >= 100.0 -> buckets[5]
                    pct > 75 -> buckets[4]
                    pct > 50 -> buckets[3]
                    pct > 25 -> buckets[2]
                    else -> buckets[1]
                }
            }.eachCount().let { counts -> buckets.map { it to (counts[it] ?: 0) } }

            // Books last read in each of the past 6 months
            val zone = ZoneId.systemDefault()
            val now = Instant.now().atZone(zone).toLocalDate().withDayOfMonth(1)
            val months = (5 downTo 0).map { now.minusMonths(it.toLong()) }
            val monthly = months.map { month ->
                val label = month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).lowercase()
                label to library.count { m ->
                    val d = m.lastRead?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()) }?.atZone(zone)?.toLocalDate()
                        ?: return@count false
                    d.year == month.year && d.month == month.month
                }
            }

            val nowMs = System.currentTimeMillis()
            fun within(days: Int) = library.count { m ->
                val t = m.lastRead?.toEpochMilliseconds() ?: return@count false
                nowMs - t <= days * 86_400_000L
            }
            val recency = listOf(
                "today" to within(1),
                "this week" to within(7),
                "this month" to within(30),
                "3 months" to within(90),
                "this year" to within(365),
            )

            // Reading time per title and per day, from reading history
            val histories = runCatching { historyRepository.getHistoryByMangaIds(library.map { it.id }) }
                .getOrDefault(emptyMap())
            val timeByManga = histories.mapValues { (_, list) -> list.sumOf { it.readDuration } }
            val today = Instant.now().atZone(zone).toLocalDate()
            val days = (13 downTo 0).map { today.minusDays(it.toLong()) }
            val perDay = mutableMapOf<java.time.LocalDate, Long>()
            histories.values.flatten().forEach { h ->
                val d = h.readAt?.let { Instant.ofEpochMilli(it.toEpochMilliseconds()).atZone(zone).toLocalDate() }
                if (d != null && d >= days.first()) perDay[d] = (perDay[d] ?: 0L) + h.readDuration
            }
            val top = library
                .filter { it.readCount > 0 || (timeByManga[it.id] ?: 0L) > 0 }
                .map { m ->
                    TopBook(
                        title = m.manga.title,
                        cover = tachiyomi.domain.manga.model.MangaCover(
                            mangaId = m.manga.id,
                            sourceId = m.manga.source,
                            isMangaFavorite = m.manga.favorite,
                            ogUrl = m.manga.thumbnailUrl,
                            lastModified = m.manga.coverLastModified,
                        ),
                        timeMs = timeByManga[m.id] ?: 0L,
                        progress = if (m.totalChapters > 0) (m.readCount * 100 / m.totalChapters).toInt().coerceIn(0, 100) else null,
                        detail = "${m.readCount} of ${m.totalChapters} chapters",
                        onPhone = false,
                    )
                }

            _stats.value = GroveStats(
                books = library.size,
                completed = library.count { it.manga.status.toInt() == SManga.COMPLETED && it.unreadCount == 0L },
                started = library.count { it.readCount > 0 },
                chapters = library.sumOf { it.totalChapters }.toInt(),
                readChapters = library.sumOf { it.readCount }.toInt(),
                bookmarks = library.sumOf { it.bookmarkCount }.toInt(),
                downloads = downloadManager.getDownloadCount(),
                readTimeMs = getTotalReadDuration.await(),
                byStatus = byStatus,
                bySource = bySource,
                byFolder = byFolder,
                progress = progress,
                monthly = monthly,
                recency = recency,
                top = top,
                daily = days.map { it to (perDay[it] ?: 0L) },
            )
        }
    }
}
