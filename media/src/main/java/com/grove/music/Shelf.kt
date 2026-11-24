package com.grove.music

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One comic or book in GroveMedia's own library (replaces Grove Book's database). */
data class ShelfItem(
    val id: Long,
    val kind: String,        // "comic" or "book"
    val title: String,
    val path: String,        // file or folder the user picked
    val cover: String?,
    val page: Int,           // last page read (0-based)
    val pages: Int,          // total pages, 0 if unknown yet
    val lastRead: Long,      // epoch millis, 0 = never
    val readMs: Long,        // total reading time
)

data class OnlineFav(val source: Long, val url: String, val title: String, val cover: String?, val chapters: Int, val newCount: Int, val updatedAt: Long)

data class OnlineRead(
    val source: Long, val mangaUrl: String, val chapterUrl: String, val chapter: String,
    val page: Int, val pages: Int, val readAt: Long, val readMs: Long, val title: String, val cover: String?,
)

/** GroveMedia's own shelf database: comics and books, progress and reading time. */
class ShelfDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "grovemedia_shelf.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE shelf (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                path TEXT NOT NULL UNIQUE,
                cover TEXT,
                page INTEGER NOT NULL DEFAULT 0,
                pages INTEGER NOT NULL DEFAULT 0,
                last_read INTEGER NOT NULL DEFAULT 0,
                read_ms INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        createOnline(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createOnline(db)
    }

    private fun createOnline(db: SQLiteDatabase) {
        // online comics added to the library from a source
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS online (
                source INTEGER NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                cover TEXT,
                chapters INTEGER NOT NULL DEFAULT 0,
                new_count INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                added_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (source, url)
            )""".trimIndent()
        )
        // chapters read (and the page reached) for online comics
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS online_read (
                source INTEGER NOT NULL,
                manga_url TEXT NOT NULL,
                chapter_url TEXT NOT NULL,
                chapter_name TEXT NOT NULL,
                page INTEGER NOT NULL DEFAULT 0,
                pages INTEGER NOT NULL DEFAULT 0,
                read_at INTEGER NOT NULL DEFAULT 0,
                read_ms INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (source, chapter_url)
            )""".trimIndent()
        )
    }

    // ── online library ──

    fun onlineAll(): List<OnlineFav> = readableDatabase.rawQuery(
        "SELECT source, url, title, cover, chapters, new_count, updated_at FROM online ORDER BY title COLLATE NOCASE", null,
    ).use { c ->
        val out = ArrayList<OnlineFav>()
        while (c.moveToNext()) out += OnlineFav(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5), c.getLong(6))
        out
    }

    fun isFav(source: Long, url: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM online WHERE source = ? AND url = ?", arrayOf(source.toString(), url),
    ).use { it.moveToFirst() }

    fun addFav(m: OManga, chapters: Int) {
        val v = ContentValues().apply {
            put("source", m.source); put("url", m.url); put("title", m.title); put("cover", m.cover)
            put("chapters", chapters); put("added_at", System.currentTimeMillis()); put("updated_at", 0L)
        }
        writableDatabase.insertWithOnConflict("online", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun removeFav(source: Long, url: String) {
        writableDatabase.delete("online", "source = ? AND url = ?", arrayOf(source.toString(), url))
    }

    /** Records a fresh chapter count; any increase is added to the "new" badge. */
    fun setChapters(source: Long, url: String, total: Int) {
        writableDatabase.execSQL(
            "UPDATE online SET new_count = new_count + CASE WHEN chapters > 0 THEN MAX(0, ? - chapters) ELSE 0 END, updated_at = CASE WHEN chapters > 0 AND ? > chapters THEN ? ELSE updated_at END, chapters = ? WHERE source = ? AND url = ?",
            arrayOf<Any>(total, total, System.currentTimeMillis(), total, source, url),
        )
    }

    fun clearNew(source: Long, url: String) {
        writableDatabase.execSQL("UPDATE online SET new_count = 0 WHERE source = ? AND url = ?", arrayOf<Any>(source, url))
    }

    fun readChapters(source: Long, mangaUrl: String): Map<String, Pair<Int, Int>> = readableDatabase.rawQuery(
        "SELECT chapter_url, page, pages FROM online_read WHERE source = ? AND manga_url = ?", arrayOf(source.toString(), mangaUrl),
    ).use { c ->
        val out = HashMap<String, Pair<Int, Int>>()
        while (c.moveToNext()) out[c.getString(0)] = c.getInt(1) to c.getInt(2)
        out
    }

    fun saveRead(source: Long, mangaUrl: String, chapterUrl: String, name: String, page: Int, pages: Int, addMs: Long) {
        writableDatabase.execSQL(
            """INSERT INTO online_read (source, manga_url, chapter_url, chapter_name, page, pages, read_at, read_ms)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(source, chapter_url) DO UPDATE SET page = excluded.page, pages = excluded.pages,
               read_at = excluded.read_at, read_ms = read_ms + excluded.read_ms""",
            arrayOf<Any>(source, mangaUrl, chapterUrl, name, page, pages, System.currentTimeMillis(), addMs),
        )
    }

    /** Last read chapter of each online comic, newest first (for history / continue reading). */
    fun onlineHistory(limit: Int = 50): List<OnlineRead> = readableDatabase.rawQuery(
        """SELECT r.source, r.manga_url, r.chapter_url, r.chapter_name, r.page, r.pages, r.read_at, r.read_ms,
                  COALESCE(o.title, ''), o.cover
           FROM online_read r LEFT JOIN online o ON o.source = r.source AND o.url = r.manga_url
           WHERE r.read_at = (SELECT MAX(read_at) FROM online_read x WHERE x.source = r.source AND x.manga_url = r.manga_url)
           ORDER BY r.read_at DESC LIMIT ?""", arrayOf(limit.toString()),
    ).use { c ->
        val out = ArrayList<OnlineRead>()
        while (c.moveToNext()) out += OnlineRead(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getInt(5),
            c.getLong(6), c.getLong(7), c.getString(8), c.getString(9))
        out
    }

    fun all(kind: String? = null): List<ShelfItem> {
        val where = if (kind == null) null else "kind = ?"
        val args = if (kind == null) null else arrayOf(kind)
        readableDatabase.query("shelf", null, where, args, null, null, "title COLLATE NOCASE").use { c ->
            val out = ArrayList<ShelfItem>()
            while (c.moveToNext()) out += ShelfItem(
                id = c.getLong(0), kind = c.getString(1), title = c.getString(2), path = c.getString(3),
                cover = c.getString(4), page = c.getInt(5), pages = c.getInt(6),
                lastRead = c.getLong(7), readMs = c.getLong(8),
            )
            return out
        }
    }

    /** Adds a file or folder to the shelf. Returns the row id, or -1 if it is already there. */
    fun add(kind: String, title: String, path: String, cover: String? = null): Long {
        val v = ContentValues().apply {
            put("kind", kind); put("title", title); put("path", path); put("cover", cover)
        }
        return writableDatabase.insertWithOnConflict("shelf", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Saves reading progress and adds the time spent since the last save. */
    fun progress(id: Long, page: Int, pages: Int, addMs: Long) {
        writableDatabase.execSQL(
            "UPDATE shelf SET page = ?, pages = ?, last_read = ?, read_ms = read_ms + ? WHERE id = ?",
            arrayOf<Any>(page, pages, System.currentTimeMillis(), addMs, id)
        )
    }

    fun remove(id: Long) {
        writableDatabase.delete("shelf", "id = ?", arrayOf(id.toString()))
    }
}
