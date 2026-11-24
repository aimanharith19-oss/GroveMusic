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

/** GroveMedia's own shelf database: comics and books, progress and reading time. */
class ShelfDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "grovemedia_shelf.db", null, 1) {
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            arrayOf(page, pages, System.currentTimeMillis(), addMs, id)
        )
    }

    fun remove(id: Long) {
        writableDatabase.delete("shelf", "id = ?", arrayOf(id.toString()))
    }
}
