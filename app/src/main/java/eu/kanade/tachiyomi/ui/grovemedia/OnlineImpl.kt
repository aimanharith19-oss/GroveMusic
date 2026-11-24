package eu.kanade.tachiyomi.ui.grovemedia

import android.content.Context
import com.grove.music.OChapter
import com.grove.music.OManga
import com.grove.music.OSource
import com.grove.music.OnlineBridge
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.app.di.appGraph
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Connects GroveMedia's own comic screens to the installed extension sources. */
object OnlineImpl {
    private val pageCache = ConcurrentHashMap<String, List<Page>>()

    fun install(app: Context) {
        val sm by lazy { app.appGraph.sourceManager }
        suspend fun src(id: Long): CatalogueSource =
            sm.get(id) as? CatalogueSource ?: error("source not installed")

        fun MangasPage.out(id: Long) = mangas.map { it.toO(id) } to hasNextPage

        OnlineBridge.sources = {
            sm.getVisibleOnlineSources().map { OSource(it.id, it.name, it.lang) }.sortedBy { it.name.lowercase() }
        }
        OnlineBridge.popular = { id, page -> src(id).getPopularManga(page).out(id) }
        OnlineBridge.latest = { id, page ->
            val s = src(id)
            if (s.supportsLatest) s.getLatestUpdates(page).out(id) else s.getPopularManga(page).out(id)
        }
        OnlineBridge.search = { id, q, page ->
            val s = src(id)
            s.getSearchManga(page, q, s.getFilterList().takeIf { it.isNotEmpty() } ?: FilterList()).out(id)
        }
        OnlineBridge.details = { m ->
            val s = src(m.source)
            val sm0 = SManga.create().apply { url = m.url; title = m.title; thumbnail_url = m.cover }
            val r = s.getMangaUpdate(sm0, emptyList(), fetchDetails = true, fetchChapters = true)
            val manga = r.manga.toO(m.source).let { if (it.title.isBlank()) it.copy(title = m.title) else it }
            manga to r.chapters.map { OChapter(it.url, it.name, it.chapter_number, it.date_upload, it.scanlator) }
        }
        OnlineBridge.pageCount = { id, url ->
            val pages = src(id).getPageList(SChapter.create().apply { this.url = url; name = "" })
            pageCache["$id|$url"] = pages
            pages.size
        }
        OnlineBridge.page = { ctx, id, url, index ->
            val s = src(id)
            val pages = pageCache["$id|$url"]
                ?: s.getPageList(SChapter.create().apply { this.url = url; name = "" }).also { pageCache["$id|$url"] = it }
            val p = pages.getOrNull(index)
            if (p == null) null else {
                val dir = File(ctx.cacheDir, "online_pages").apply { mkdirs() }
                val f = File(dir, md5("$id|$url|$index"))
                if (f.exists() && f.length() > 0) f else {
                    val hs = s as? HttpSource ?: error("source can't load images")
                    if (p.imageUrl.isNullOrBlank()) p.imageUrl = hs.getImageUrl(p)
                    hs.getImage(p).use { resp ->
                        val body = resp.body
                        f.outputStream().use { out -> body.byteStream().copyTo(out) }
                    }
                    trim(dir)
                    f
                }
            }
        }
        OnlineBridge.legacyLibrary = {
            com.grove.music.BookBridge.sql?.invoke(
                """SELECT source_id, remote_url, COALESCE(user_title, remote_title), COALESCE(user_thumbnail_url, remote_cover), remote_genre
                   FROM manga WHERE user_favorite_at IS NOT NULL""",
                5,
            ).orEmpty().mapNotNull { r ->
                val source = r[0]?.toLongOrNull() ?: return@mapNotNull null
                OManga(source, r[1] ?: return@mapNotNull null, r[2] ?: "", r[3], genre = r[4])
            }
        }
    }

    private fun SManga.toO(source: Long) = OManga(
        source = source,
        url = url,
        title = runCatching { title }.getOrDefault(""),
        cover = thumbnail_url,
        description = description,
        genre = genre,
        author = listOfNotNull(author, artist).distinct().joinToString(", ").ifBlank { null },
    )

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** keeps the page cache under ~300 MB, oldest first out */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 300L * 1024 * 1024) break
            total -= f.length(); f.delete()
        }
    }
}
