package com.grove.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Official track order of an album from Deezer, cached on the phone; used to sort album songs. */
object AlbumOrder {
    private val mem = HashMap<String, List<String>>()

    private fun key(album: String, artist: String) =
        ArtistRepository.norm(album) + "|" + ArtistRepository.norm(ArtistRepository.primary(artist))

    /** Cached order only (no network). Normalised titles in official order (disc by disc). */
    fun cached(ctx: Context, album: String, artist: String): List<String>? {
        val k = key(album, artist)
        synchronized(mem) { mem[k]?.let { return it } }
        val j = ctx.getSharedPreferences("album_order", Context.MODE_PRIVATE).getString(k, null) ?: return null
        val l = runCatching { JSONArray(j).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull() ?: return null
        synchronized(mem) { mem[k] = l }
        return l
    }

    suspend fun load(ctx: Context, album: String, artist: String): List<String>? = withContext(Dispatchers.IO) {
        cached(ctx, album, artist)?.let { return@withContext it }
        if (album.isBlank() || album == "unknown album") return@withContext null
        val l = runCatching { fetch(album, ArtistRepository.primary(artist)) }.getOrNull() ?: return@withContext null
        ctx.getSharedPreferences("album_order", Context.MODE_PRIVATE).edit()
            .putString(key(album, artist), JSONArray(l).toString()).apply()
        synchronized(mem) { mem[key(album, artist)] = l }
        l
    }

    private fun fetch(album: String, artist: String): List<String>? {
        val q = URLEncoder.encode("artist:\"$artist\" album:\"$album\"", "UTF-8")
        val arr = JSONObject(get("https://api.deezer.com/search/album?limit=10&q=$q") ?: return null).optJSONArray("data") ?: return null
        val na = ArtistRepository.norm(album)
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
        val hit = list.firstOrNull { ArtistRepository.norm(it.optString("title")) == na }
            ?: list.firstOrNull { val t = ArtistRepository.norm(it.optString("title")); t.contains(na) || na.contains(t) } ?: return null
        val tr = JSONObject(get("https://api.deezer.com/album/${hit.optLong("id")}/tracks?limit=300") ?: return null)
            .optJSONArray("data") ?: return null
        return (0 until tr.length()).map { tr.getJSONObject(it) }
            .sortedWith(compareBy({ it.optInt("disk_number", 1) }, { it.optInt("track_position") }))
            .map { ArtistRepository.norm(it.optString("title")) }
            .takeIf { it.isNotEmpty() }
    }

    /** Sort songs by the official order; songs not found online keep their own order at the end. */
    fun sort(songs: List<Song>, order: List<String>?): List<Song> {
        val local = songs.sortedWith(compareBy({ it.track }, { it.title.lowercase() }))
        if (order.isNullOrEmpty()) return local
        fun pos(s: Song): Int {
            val t = ArtistRepository.norm(s.title)
            order.indexOf(t).takeIf { it >= 0 }?.let { return it }
            // tolerate "(remastered)", "- live" etc.
            return order.indexOfFirst { it.isNotEmpty() && (t.startsWith(it) || it.startsWith(t)) }.takeIf { it >= 0 } ?: Int.MAX_VALUE
        }
        return local.sortedWith(compareBy({ pos(it) }, { local.indexOf(it) }))
    }

    private fun get(url: String): String? = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000; readTimeout = 8000
        try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
    }
}
