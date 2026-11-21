package com.grove.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Genre (per album, from Deezer) and country (per main artist, from MusicBrainz) looked up online
 * and kept on the phone. Falls back to the file's own genre tag.
 */
object Meta {
    private val genre = HashMap<String, String>()     // album key -> genre ("" = not found)
    private val country = HashMap<String, String>()   // artist key -> ISO code ("" = not found)
    @Volatile private var loaded = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)
    private fun albumKey(s: Song) = ArtistRepository.norm(s.album) + "|" + ArtistRepository.norm(ArtistRepository.primary(s.artist))
    private fun artistKey(s: Song) = ArtistRepository.key(ArtistRepository.primary(s.artist))

    private fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            sp(ctx).all.forEach { (k, v) ->
                val s = v as? String ?: return@forEach
                when { k.startsWith("g|") -> genre[k.drop(2)] = s; k.startsWith("c|") -> country[k.drop(2)] = s }
            }
            loaded = true
        }
    }

    private fun tagGenre(s: Song) = s.genre.takeIf { it.isNotBlank() && it != "unknown genre" }

    /** best genre for a song: online album genre, else the file tag, else "other" */
    fun genreOf(ctx: Context, s: Song): String {
        load(ctx)
        return (synchronized(this) { genre[albumKey(s)] }?.takeIf { it.isNotBlank() } ?: tagGenre(s) ?: "other").lowercase()
    }

    /** country name of the song's main artist, or null if unknown */
    fun countryOf(ctx: Context, s: Song): String? {
        load(ctx)
        val code = synchronized(this) { country[artistKey(s)] }?.takeIf { it.isNotBlank() } ?: return null
        return Locale("", code).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: code
    }

    /** look up everything not cached yet; genres 4 at a time, countries 1/second (MusicBrainz rule) */
    suspend fun sync(ctx: Context, songs: List<Song>, onProgress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        load(ctx)
        val albums = songs.distinctBy { albumKey(it) }.filter { it.album != "unknown album" && synchronized(this@Meta) { !genre.containsKey(albumKey(it)) } }
        val artists = songs.distinctBy { artistKey(it) }.filter { synchronized(this@Meta) { !country.containsKey(artistKey(it)) } }
        val total = albums.size + artists.size
        if (total == 0) return@withContext
        var done = 0
        val sem = Semaphore(4)
        coroutineScope {
            albums.map { s -> async { sem.withPermit {
                runCatching { fetchGenre(s) }.onSuccess { g -> put(ctx, "g|", albumKey(s), g ?: "", genre) }
                synchronized(this@Meta) { done++ }; onProgress(done, total)
            } } }.awaitAll()
        }
        for (s in artists) {
            val r = runCatching { fetchCountry(ArtistRepository.primary(s.artist)) }
            if (r.exceptionOrNull() is IOException) break // offline: try next scan
            r.onSuccess { c -> put(ctx, "c|", artistKey(s), c ?: "", country) }
            done++; onProgress(done, total)
            delay(1100)
        }
    }

    private fun put(ctx: Context, prefix: String, k: String, v: String, map: HashMap<String, String>) {
        synchronized(this) { map[k] = v }
        sp(ctx).edit().putString(prefix + k, v).apply()
    }

    private fun fetchGenre(s: Song): String? {
        val q = URLEncoder.encode("artist:\"${ArtistRepository.primary(s.artist)}\" album:\"${s.album}\"", "UTF-8")
        val arr = JSONObject(get("https://api.deezer.com/search/album?limit=5&q=$q") ?: return null).optJSONArray("data") ?: return null
        val na = ArtistRepository.norm(s.album)
        val hit = (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull {
            val t = ArtistRepository.norm(it.optString("title")); t == na || t.contains(na) || na.contains(t)
        } ?: return null
        val a = JSONObject(get("https://api.deezer.com/album/${hit.optLong("id")}") ?: return null)
        return a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }
    }

    private fun fetchCountry(name: String): String? {
        val q = URLEncoder.encode("artist:\"$name\"", "UTF-8")
        val arr = JSONObject(get("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=3") ?: return null)
            .optJSONArray("artists") ?: return null
        val n = ArtistRepository.norm(name)
        val a = (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { ArtistRepository.norm(it.optString("name")) == n && it.optInt("score") >= 80 } ?: return null
        a.optString("country").takeIf { it.length == 2 }?.let { return it }
        val codes = a.optJSONObject("area")?.optJSONArray("iso-3166-1-codes")
            ?: a.optJSONObject("begin-area")?.optJSONArray("iso-3166-1-codes")
        return codes?.optString(0)?.takeIf { it.length == 2 }
    }

    /** null = not found; throws IOException when offline */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GroveMusic/1.0 ( github.com/aimanharith19-oss/GroveMusic )")
        try { return if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }
}
