package com.grove.music

import android.content.Context
import java.io.File

/** An online comic source (from an installed extension). */
data class OSource(val id: Long, val name: String, val lang: String)

/** A comic from an online source. */
data class OManga(
    val source: Long,
    val url: String,
    val title: String,
    val cover: String?,
    val description: String? = null,
    val genre: String? = null,
    val author: String? = null,
)

data class OChapter(val url: String, val name: String, val number: Float, val date: Long, val scanlator: String? = null)

/**
 * Online comic sources, filled in by the app module from the installed extensions.
 * GroveMedia's own screens call these; no Grove Book screens are involved.
 */
object OnlineBridge {
    var sources: (suspend () -> List<OSource>)? = null
    var popular: (suspend (source: Long, page: Int) -> Pair<List<OManga>, Boolean>)? = null
    var latest: (suspend (source: Long, page: Int) -> Pair<List<OManga>, Boolean>)? = null
    var search: (suspend (source: Long, query: String, page: Int) -> Pair<List<OManga>, Boolean>)? = null
    /** full details and chapter list, newest chapter first */
    var details: (suspend (OManga) -> Pair<OManga, List<OChapter>>)? = null
    /** number of pages in a chapter */
    var pageCount: (suspend (source: Long, chapterUrl: String) -> Int)? = null
    /** downloads one page image to the cache and returns the file */
    var page: (suspend (ctx: Context, source: Long, chapterUrl: String, index: Int) -> File?)? = null
    /** Grove Book library entries to bring across once: (source, url, title, cover) */
    var legacyLibrary: (() -> List<OManga>)? = null
}

/** Adult content is hidden everywhere, with no way to show it. */
object Adult {
    private val words = listOf("adult", "hentai", "smut", "erotica", "erotic", "pornographic", "porn", "mature 18+", "18+", "ecchi", "yaoi", "yuri", "nsfw")

    fun isAdult(genre: String?, title: String? = null): Boolean {
        val g = genre?.lowercase() ?: return false
        return g.split(',', ';', '|').map { it.trim() }.any { tag -> words.any { tag == it || tag.startsWith("$it ") } }
    }
}

/** Checks every online comic in the library for new chapters. */
object OnlineUpdates {
    suspend fun check(ctx: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val db = ShelfDb(ctx)
        val favs = db.onlineAll()
        val details = OnlineBridge.details ?: return 0
        var found = 0
        favs.forEachIndexed { i, f ->
            onProgress(i + 1, favs.size)
            runCatching {
                val (_, chapters) = details(OManga(f.source, f.url, f.title, f.cover))
                if (chapters.size > f.chapters) found += chapters.size - f.chapters
                db.setChapters(f.source, f.url, chapters.size)
            }
        }
        return found
    }

    /** Copies the old Grove Book library into GroveMedia's own, once. */
    fun importLegacy(ctx: Context) {
        val p = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
        if (p.getBoolean("online_imported", false)) return
        val list = runCatching { OnlineBridge.legacyLibrary?.invoke() }.getOrNull() ?: return
        val db = ShelfDb(ctx)
        list.filterNot { Adult.isAdult(it.genre) }.forEach { db.addFav(it, 0) }
        p.edit().putBoolean("online_imported", true).apply()
    }
}
