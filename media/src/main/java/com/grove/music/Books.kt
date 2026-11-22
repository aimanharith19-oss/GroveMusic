package com.grove.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import java.util.Calendar

/** Set by the books side of the app (Grove Book) so music/video screens can start book backups & restores. */
object BookBridge {
    var backup: ((Context, Uri) -> Unit)? = null
    var restore: ((Context, Uri) -> Unit)? = null
    /** check every comic in the library for new chapters */
    var rescan: ((Context) -> Unit)? = null
    /** bumps when a full rescan is asked for, so the book tab re-reads its folders */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    /** draws a Grove Book screen ("library", "updates", "history", "browse", "more", "book") inside GroveMedia */
    var embed: (@androidx.compose.runtime.Composable (String, androidx.compose.ui.Modifier) -> Unit)? = null
    /** true while a Grove Book screen has opened something on top (a book, settings…) — GroveMedia then hides its headers */
    val deep = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    /** runs a read-only query on Grove Book's own database connection; rows of strings */
    var sql: ((String, Int) -> List<Array<String?>>)? = null
}

data class BookRead(
    val mangaId: Long, val title: String, val cover: Any?, val chapterId: Long, val chapter: String,
    val lastRead: Long, val readMs: Long,
)

/** Reads Grove Book's own database (read-only) for "continue reading" and reading stats. */
object Books {
    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun cover(ctx: Context, url: String?): Any? {
        if (url.isNullOrBlank()) return null
        val dir = ctx.getExternalFilesDir("covers") ?: File(ctx.filesDir, "covers")
        val f = File(dir, md5(url))
        return if (f.exists()) f else url
    }

    private fun rows(q: String, cols: Int): List<Array<String?>> = runCatching { BookBridge.sql?.invoke(q, cols) }.getOrNull().orEmpty()

    /** every history row, newest first */
    fun history(ctx: Context): List<BookRead> = rows("""
        SELECT m.id, COALESCE(m.user_title, m.remote_title), m.remote_cover, c.id, c.remote_name, h.read_at, h.read_duration
        FROM history h JOIN chapter c ON c.id = h.chapter_id JOIN manga m ON m.id = h.manga_id
        ORDER BY h.read_at DESC""".trimIndent(), 7).map { r ->
        BookRead(r[0]?.toLongOrNull() ?: 0, r[1] ?: "", cover(ctx, r[2]), r[3]?.toLongOrNull() ?: 0, r[4] ?: "",
            r[5]?.toLongOrNull() ?: 0, r[6]?.toLongOrNull() ?: 0)
    }

    data class Lib(val id: Long, val title: String, val cover: Any?, val unread: Int)

    /** books in the library (favourites), A–Z, with unread chapter count */
    fun library(ctx: Context): List<Lib> = rows("""
        SELECT m.id, COALESCE(m.user_title, m.remote_title), m.remote_cover,
          (SELECT COUNT(*) FROM chapter c WHERE c.manga_id = m.id AND c.user_read = 0)
        FROM manga m WHERE m.user_favorite_at IS NOT NULL ORDER BY 2 COLLATE NOCASE""".trimIndent(), 4).map { r ->
        Lib(r[0]?.toLongOrNull() ?: 0, r[1] ?: "", cover(ctx, r[2]), r[3]?.toIntOrNull() ?: 0)
    }

    /** unread chapters fetched in the last week for books in the library (the "updates" badge) */
    fun newChapters(ctx: Context): Int = rows("""
        SELECT COUNT(*) FROM chapter c JOIN manga m ON m.id = c.manga_id
        WHERE m.user_favorite_at IS NOT NULL AND c.user_read = 0 AND c.state_date_fetch > ${System.currentTimeMillis() - 7L * 86_400_000}""".trimIndent(), 1)
        .firstOrNull()?.get(0)?.toIntOrNull() ?: 0

    /** last read chapter of each book, newest first */
    fun recent(ctx: Context, n: Int = 15) = history(ctx).filter { it.lastRead > 0 }.distinctBy { it.mangaId }.take(n)

    fun openReader(ctx: Context, b: BookRead) = runCatching {
        ctx.startActivity(Intent().setClassName(ctx.packageName, "eu.kanade.tachiyomi.ui.reader.ReaderActivity")
            .putExtra("manga", b.mangaId).putExtra("chapter", b.chapterId))
    }

    fun openBook(ctx: Context, mangaId: Long) = runCatching {
        ctx.startActivity(Intent("eu.kanade.tachiyomi.SHOW_MANGA").setClassName(ctx.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
            .putExtra("manga", mangaId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    fun open(ctx: Context, action: String?) = runCatching {
        ctx.startActivity(Intent().setClassName(ctx.packageName, "eu.kanade.tachiyomi.ui.main.MainActivity")
            .apply { if (action != null) setAction(action) }.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** reading time per period (reading time of a chapter counts on the day it was last read) */
    class ReadStats(h: List<BookRead>) {
        private fun k(ts: Long, f: Int): Int = Calendar.getInstance().run { timeInMillis = ts
            when (f) { Calendar.DAY_OF_YEAR -> get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR); Calendar.MONTH -> get(Calendar.YEAR) * 100 + get(Calendar.MONTH); else -> get(Calendar.YEAR) } }
        private val now = System.currentTimeMillis()
        private val r = h.filter { it.lastRead > 0 }
        val total = r.sumOf { it.readMs }
        val today = r.filter { k(it.lastRead, Calendar.DAY_OF_YEAR) == k(now, Calendar.DAY_OF_YEAR) }.sumOf { it.readMs }
        val month = r.filter { k(it.lastRead, Calendar.MONTH) == k(now, Calendar.MONTH) }.sumOf { it.readMs }
        val year = r.filter { k(it.lastRead, Calendar.YEAR) == k(now, Calendar.YEAR) }.sumOf { it.readMs }
        fun of(period: String) = when (period) { "today" -> today; "month" -> month; "year" -> year; else -> total }
        val chapters = r.size
        /** (book, ms, chapters) most read */
        val top = r.groupBy { it.mangaId }.map { (_, l) -> Triple(l.first(), l.sumOf { it.readMs }, l.size) }.sortedByDescending { it.second }.take(5)
    }
}

/** Offline book reading time (kept by the book tab's reader in "grove_offline"). */
class BookTime(ctx: Context) {
    private val p = ctx.getSharedPreferences("grove_offline", Context.MODE_PRIVATE).all
    private val days = p.filterKeys { it.startsWith("day:") }.mapNotNull { (k, v) -> runCatching { java.time.LocalDate.parse(k.drop(4)) to (v as Number).toLong() }.getOrNull() }
    private val now = java.time.LocalDate.now()
    val today = days.filter { it.first == now }.sumOf { it.second }
    val month = days.filter { it.first.year == now.year && it.first.month == now.month }.sumOf { it.second }
    val year = days.filter { it.first.year == now.year }.sumOf { it.second }
    val total = p.filterKeys { it.startsWith("time:") }.values.sumOf { (it as Number).toLong() }
    fun of(period: String) = when (period) { "today" -> today; "month" -> month; "year" -> year; else -> total }
    /** (title, ms, progress%) most read, all time */
    val top = p.filterKeys { it.startsWith("time:") }.map { (k, v) ->
        val uri = k.drop(5)
        val name = Uri.decode(uri).substringAfterLast('/').substringAfterLast(':').substringBeforeLast('.')
        val pages = (p["pages:$uri"] as? Int) ?: 0; val page = (p["page:$uri"] as? Int) ?: 0
        Triple(name, (v as Number).toLong(), if (pages > 0) ((page + 1) * 100 / pages).coerceIn(0, 100) else null)
    }.sortedByDescending { it.second }.take(5)
}

/** Back up / restore GroveMedia's own music & video data (playlists, likes, pins, stats, settings, saved info). */
object MediaBackup {
    private val prefs = listOf("grove", "playlists", "artist_cache_v2", "meta", "album_order", "vmatch", "video_pos")
    private val files = listOf("plays.log", "watch.log")

    fun write(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)!!.use { os ->
            java.util.zip.ZipOutputStream(os).use { z ->
                val sp = File(ctx.applicationInfo.dataDir, "shared_prefs")
                (sp.listFiles() ?: emptyArray()).filter { f -> prefs.any { f.name == "$it.xml" } }.forEach { f ->
                    z.putNextEntry(java.util.zip.ZipEntry("shared_prefs/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
                files.map { File(ctx.filesDir, it) }.filter { it.exists() }.forEach { f ->
                    z.putNextEntry(java.util.zip.ZipEntry("files/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
        }
    }

    fun read(ctx: Context, uri: Uri) {
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            java.util.zip.ZipInputStream(ins).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val name = e.name.substringAfterLast('/')
                    val dest = when {
                        e.name.startsWith("shared_prefs/") && prefs.any { name == "$it.xml" } -> File(File(ctx.applicationInfo.dataDir, "shared_prefs"), name)
                        e.name.startsWith("files/") && name in files -> File(ctx.filesDir, name)
                        else -> null
                    }
                    dest?.outputStream()?.use { z.copyTo(it) }
                }
            }
        }
    }
}
