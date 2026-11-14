package com.grove.music

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.images.ArtworkFactory
import org.jaudiotagger.tag.reference.PictureTypes
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

data class Tags(
    val title: String, val artist: String, val album: String, val albumArtist: String,
    val track: String, val year: String, val genre: String,
)

data class OnlineMatch(val tags: Tags, val coverUrl: String?)

/** Reads/writes a song's tags and cover in the file itself, and finds tags + cover online (Deezer). */
object TagEditor {
    init { TagOptionSingleton.getInstance().isAndroid = true }

    fun current(s: Song) = Tags(s.title, s.artist, s.album, "", if (s.track > 0) (s.track % 1000).toString() else "", "", s.genre)

    // ── online ──────────────────────────────────────────────────────────────

    suspend fun search(query: String): List<OnlineMatch> = withContext(Dispatchers.IO) {
        val body = get("https://api.deezer.com/search?limit=8&q=${URLEncoder.encode(query, "UTF-8")}") ?: return@withContext emptyList()
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val al = o.optJSONObject("album"); val ar = o.optJSONObject("artist")
            OnlineMatch(Tags(o.optString("title"), ar?.optString("name").orEmpty(), al?.optString("title").orEmpty(),
                ar?.optString("name").orEmpty(), "", "", ""), al?.optString("cover_xl")?.ifBlank { null }) to o.optLong("id")
        }.let { list ->
            // fill track number, year and genre for the top results
            list.mapIndexed { i, (m, id) -> if (i < 3) runCatching { details(m, id) }.getOrDefault(m) else m }
        }
    }

    private fun details(m: OnlineMatch, trackId: Long): OnlineMatch {
        val t = JSONObject(get("https://api.deezer.com/track/$trackId") ?: return m)
        var genre = ""
        t.optJSONObject("album")?.optLong("id")?.takeIf { it > 0 }?.let { aid ->
            get("https://api.deezer.com/album/$aid")?.let { JSONObject(it).optJSONObject("genres")?.optJSONArray("data")
                ?.optJSONObject(0)?.optString("name").orEmpty().let { g -> genre = g } }
        }
        return m.copy(tags = m.tags.copy(
            track = t.optInt("track_position").takeIf { it > 0 }?.toString().orEmpty(),
            year = t.optString("release_date").take(4), genre = genre))
    }

    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 10000; readTimeout = 15000
            try { if (responseCode == 200) inputStream.readBytes() else null } finally { disconnect() }
        } }.getOrNull()
    }

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000
            try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
        }
    }.getOrNull()

    // ── write ───────────────────────────────────────────────────────────────

    /** Copies the file, edits the copy, writes it back over the original. Caller must hold write access. */
    suspend fun write(ctx: Context, song: Song, t: Tags, cover: ByteArray?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = song.displayName.substringAfterLast('.', "mp3").lowercase()
            val dir = File(ctx.cacheDir, "tagedit").apply { deleteRecursively(); mkdirs() }
            val tmp = File(dir, "song.$ext")
            ctx.contentResolver.openInputStream(song.uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }

            val af = AudioFileIO.read(tmp)
            val tag = af.tagOrCreateAndSetDefault
            fun set(k: FieldKey, v: String) { if (v.isBlank()) runCatching { tag.deleteField(k) } else tag.setField(k, v.trim()) }
            set(FieldKey.TITLE, t.title); set(FieldKey.ARTIST, t.artist); set(FieldKey.ALBUM, t.album)
            set(FieldKey.ALBUM_ARTIST, t.albumArtist); set(FieldKey.GENRE, t.genre); set(FieldKey.YEAR, t.year)
            set(FieldKey.TRACK, t.track.filter { it.isDigit() })
            if (cover != null) runCatching {
                val mime = if (cover.size > 3 && cover[0] == 0x89.toByte()) "image/png" else "image/jpeg"
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(cover, 0, cover.size, o)
                runCatching { tag.deleteArtworkField() }
                when (tag) {
                    // FLAC / Vorbis need an explicit picture block (the generic path uses desktop-only image code)
                    is FlacTag -> tag.setField(MetadataBlockDataPicture(cover, PictureTypes.DEFAULT_ID, mime, "", o.outWidth, o.outHeight, 24, 0))
                    else -> tag.setField(ArtworkFactory.getNew().apply {
                        binaryData = cover; mimeType = mime; pictureType = PictureTypes.DEFAULT_ID
                        width = o.outWidth; height = o.outHeight
                    })
                }
            }
            af.commit()

            ctx.contentResolver.openOutputStream(song.uri, "wt")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
            dir.deleteRecursively()
            // let Android re-read the file so the library shows the new tags
            song.path?.let { p ->
                suspendCancellableCoroutine { c -> MediaScannerConnection.scanFile(ctx, arrayOf(p), null) { _, _ -> c.resume(Unit) } }
            }
            Unit
        }
    }
}
