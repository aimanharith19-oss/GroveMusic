package com.grove.music

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore

/**
 * Simple overall stats for Grove Launcher's feed card: time spent on music, videos, comics and books for a period
 * ("today", "month" (default), "year", "all" — pass it as the selection), plus the top item of each.
 * Only Grove Launcher may read it.
 */
class MediaStatsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        if (callingPackage != "org.canopy.launcher" && callingPackage != ctx.packageName) throw SecurityException("GroveMedia stats are for Grove Launcher")
        val period = selection?.takeIf { it in setOf("today", "month", "year", "all") } ?: "month"
        val music = Stats(StatsLog.read(ctx))
        val watch = Stats(StatsLog.readWatch(ctx))
        val comics = runCatching { Books.ReadStats(Books.history(ctx)) }.getOrNull()
        val book = runCatching { BookTime(ctx) }.getOrNull()
        fun ms(s: Stats) = when (period) { "today" -> s.todayMs; "month" -> s.monthMs; "year" -> s.yearMs; else -> s.totalMs }
        val topSong = music.top(period, 1).firstOrNull()
        val topVideo = watch.top(period, 1).firstOrNull()
        val c = MatrixCursor(COLUMNS, 1)
        c.addRow(arrayOf<Any?>(
            period,
            ms(music), music.top(period, 9999).sumOf { it.second },
            ms(watch), watch.top(period, 9999).size,
            comics?.of(period) ?: 0L, comics?.chapters ?: 0,
            book?.of(period) ?: 0L, book?.top?.size ?: 0,
            topSong?.let { title(ctx, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.first) }, topSong?.second ?: 0,
            topVideo?.let { title(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it.first) }, topVideo?.third ?: 0L,
            comics?.top?.firstOrNull()?.first?.title,
            book?.top?.firstOrNull()?.first,
        ))
        return c
    }

    private fun title(ctx: android.content.Context, base: Uri, id: Long): String? = runCatching {
        ctx.contentResolver.query(android.content.ContentUris.withAppendedId(base, id), arrayOf(MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) ?: it.getString(1) else null
        }
    }.getOrNull()

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val COLUMNS = arrayOf("period", "music_ms", "music_plays", "videos_ms", "videos_watched", "comics_ms", "comics_chapters",
            "book_ms", "books", "top_song", "top_song_plays", "top_video", "top_video_ms", "top_comic", "top_book")
    }
}
