package com.grove.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Video(
    val id: Long, val title: String, val name: String, val path: String?, val duration: Long,
    val size: Long, val w: Int, val h: Int, val added: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
    val ext: String get() = name.substringAfterLast('.', "mp4")
    val parsed: ParsedName by lazy { ParsedName.of(name) }
}

/** What a file name says: "Breaking.Bad.S02E05.720p.mkv" → show Breaking Bad, season 2, episode 5. */
data class ParsedName(val title: String, val year: Int?, val season: Int?, val episode: Int?) {
    val isEpisode get() = season != null && episode != null

    companion object {
        private val junk = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr|x264|x265|h264|h265|hevc|bluray|blu-ray|brrip|bdrip|web-?dl|webrip|hdrip|dvdrip|hdtv|aac|ac3|dts|ddp?5\.1|10bit|proper|repack|extended|unrated|remastered|yify|rarbg)\b.*""")
        private val tv1 = Regex("""(?i)^(.*?)[\s._-]*s(\d{1,2})[\s._-]*e(\d{1,3})""")
        private val tv2 = Regex("""(?i)^(.*?)[\s._-]+(\d{1,2})x(\d{2,3})\b""")
        private val yearR = Regex("""^(.*?)[\s._(\[-]+((?:19|20)\d{2})(?:[)\]\s._-]|$)""")

        private fun clean(s: String) = s.replace(Regex("""[._]+"""), " ").replace(Regex("""\s*[\[(].*?[])]\s*"""), " ")
            .replace(junk, "").replace(Regex("""\s+"""), " ").trim(' ', '-')

        fun of(file: String): ParsedName {
            val base = file.substringBeforeLast('.')
            (tv1.find(base) ?: tv2.find(base))?.let { m ->
                val t = clean(m.groupValues[1]).replace(Regex("""\s(19|20)\d{2}$"""), "")
                if (t.isNotBlank()) return ParsedName(t, null, m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }
            yearR.find(base.replace('_', ' '))?.let { m ->
                val t = clean(m.groupValues[1])
                if (t.isNotBlank()) return ParsedName(t, m.groupValues[2].toInt(), null, null)
            }
            return ParsedName(clean(base).ifBlank { base }, null, null, null)
        }
    }
}

object VideoRepository {
    fun query(ctx: Context): List<Video> {
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED,
        )
        val out = ArrayList<Video>()
        ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null,
            "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE")?.use { c ->
            fun i(n: String) = c.getColumnIndexOrThrow(n)
            while (c.moveToNext()) {
                val name = c.getString(i(MediaStore.Video.Media.DISPLAY_NAME)) ?: continue
                out += Video(c.getLong(i(MediaStore.Video.Media._ID)), c.getString(i(MediaStore.Video.Media.TITLE)) ?: name, name,
                    c.getString(i(MediaStore.Video.Media.DATA)), c.getLong(i(MediaStore.Video.Media.DURATION)),
                    c.getLong(i(MediaStore.Video.Media.SIZE)), c.getInt(i(MediaStore.Video.Media.WIDTH)),
                    c.getInt(i(MediaStore.Video.Media.HEIGHT)), c.getLong(i(MediaStore.Video.Media.DATE_ADDED)))
            }
        }
        return out
    }
}
