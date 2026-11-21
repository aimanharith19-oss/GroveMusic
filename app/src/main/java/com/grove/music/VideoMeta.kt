package com.grove.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CastMember(val personId: String, val name: String, val role: String, val photo: String?)
data class Title(
    val id: String, val tv: Boolean, val name: String, val year: Int?, val overview: String, val poster: String?,
    val backdrop: String?, val rating: Double, val genres: List<String>, val cast: List<CastMember>,
)
data class Episode(val season: Int, val number: Int, val name: String, val overview: String, val still: String?, val air: String)
data class Credit(val title: String, val year: Int?, val tv: Boolean, val poster: String?, val role: String)
data class YtInfo(val title: String, val channel: String, val thumb: String, val published: String, val views: String)
data class Person(val id: String, val name: String, val photo: String?, val bio: String, val born: String, val credits: List<Credit>)

/**
 * Movie & TV info. TMDB (needs a free API key in settings) for movies, TV and actors;
 * TVmaze (no key) for TV shows and actors when there's no TMDB key. Every answer is cached on the phone.
 */
object VideoMeta {
    private const val IMG = "https://image.tmdb.org/t/p/"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun stripHtml(s: String) = s.replace(Regex("<[^>]+>"), "").trim()
    private fun key(ctx: Context) = Prefs(ctx).tmdbKey.trim()

    /** GET with an on-disk cache so pages work offline after the first visit */
    private fun get(ctx: Context, url: String): String? {
        val f = File(ctx.filesDir, "vmeta/" + Integer.toHexString(url.hashCode()) + "_" + url.length + ".json")
        if (f.exists()) return f.readText()
        val body = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10000; readTimeout = 10000
                setRequestProperty("User-Agent", if (url.contains("youtube.com")) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36" else "GroveMedia/1.0 (Android)")
                setRequestProperty("Accept-Language", "en")
                try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
            }
        }.getOrNull() ?: return null
        runCatching { f.parentFile?.mkdirs(); f.writeText(body) }
        return body
    }
    private fun json(ctx: Context, url: String) = get(ctx, url)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun jsonArr(ctx: Context, url: String) = get(ctx, url)?.let { runCatching { JSONArray(it) }.getOrNull() }
    private fun JSONArray?.objs() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    // ── YouTube (for clips that aren't movies or TV): title, channel, thumbnail from the public search page ──
    suspend fun youtube(ctx: Context, query: String): YtInfo? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        val html = get(ctx, "https://www.youtube.com/results?hl=en&search_query=${enc(query)}") ?: return@withContext null
        val i = html.indexOf("\"videoRenderer\":{"); if (i < 0) return@withContext null
        var depth = 0; var j = html.indexOf('{', i); val start = j; var inStr = false; var esc = false
        while (j < html.length) {
            val c = html[j]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else if (c == '"') inStr = true else if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }
            j++
        }
        val o = runCatching { JSONObject(html.substring(start, j + 1)) }.getOrNull() ?: return@withContext null
        fun runs(k: String) = o.optJSONObject(k)?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: o.optJSONObject(k)?.optString("simpleText")
        val id = o.optString("videoId").ifBlank { return@withContext null }
        YtInfo(runs("title").orEmpty(), runs("ownerText").orEmpty(), "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            runs("publishedTimeText").orEmpty(), runs("viewCountText").orEmpty())
    }

    // ── your own pick for a video, when the file name isn't enough ──
    private fun picks(ctx: Context) = ctx.getSharedPreferences("vmatch", Context.MODE_PRIVATE)
    fun pick(ctx: Context, v: Video): String? = picks(ctx).getString(v.id.toString(), null)
    fun setPick(ctx: Context, v: Video, p: String?) = picks(ctx).edit().apply { if (p == null) remove(v.id.toString()) else putString(v.id.toString(), p) }.apply()

    /** movie info for a video: your pick first ("tmdb:<id>" or "q:<search words>"), else from the file name */
    suspend fun movieFor(ctx: Context, v: Video): Title? = withContext(Dispatchers.IO) {
        val p = pick(ctx, v)
        when {
            p == null -> movie(ctx, v.parsed)
            p.startsWith("tmdb:") -> tmdbTitle(ctx, p.substringAfter(':').toLong(), false)
            p.startsWith("q:") -> movie(ctx, ParsedName.of(p.substringAfter(':') + ".x"))
            else -> null
        }
    }

    data class Hit(val id: Long, val name: String, val year: Int?, val poster: String?, val overview: String)

    /** list of possible movies for words you type */
    suspend fun searchMovies(ctx: Context, q: String): List<Hit> = withContext(Dispatchers.IO) {
        val k = key(ctx); if (k.isEmpty() || q.isBlank()) return@withContext emptyList()
        json(ctx, "https://api.themoviedb.org/3/search/movie?api_key=$k&query=${enc(q)}")?.optJSONArray("results").objs().take(10).map {
            Hit(it.optLong("id"), it.optString("title"), it.optString("release_date").take(4).toIntOrNull(),
                it.optString("poster_path").takeIf { p -> p.isNotBlank() && p != "null" }?.let { p -> IMG + "w185" + p }, it.optString("overview"))
        }
    }

    // ── find ───────────────────────────────────────────────────────────────

    suspend fun movie(ctx: Context, p: ParsedName): Title? = withContext(Dispatchers.IO) {
        val k = key(ctx); if (k.isEmpty()) return@withContext null
        fun find(q: String, year: Int?) = json(ctx, "https://api.themoviedb.org/3/search/movie?api_key=$k&query=${enc(q)}" + (year?.let { "&year=$it" } ?: ""))
            ?.optJSONArray("results").objs().firstOrNull()
        // 1. file name (+year)  2. without the year  3. fewer words  4. whatever YouTube calls it (concerts, specials…)
        val words = p.title.split(' ').filter { it.isNotBlank() }
        val r = find(p.title, p.year) ?: p.year?.let { find(p.title, null) }
            ?: (words.size - 1 downTo 2).firstNotNullOfOrNull { n -> find(words.take(n).joinToString(" "), null) }
            ?: youtube(ctx, p.title)?.title?.let { yt ->
                val clean = yt.replace(Regex("""(?i)\(.*?\)|\[.*?]|official|full (concert|movie|show)|\bhd\b|\b4k\b"""), " ").replace(Regex("""\s+"""), " ").trim()
                find(clean, null) ?: find(clean.substringBefore(" - ").substringBefore(" | "), null)
            }
            ?: return@withContext null
        tmdbTitle(ctx, r.optLong("id"), false)
    }

    suspend fun show(ctx: Context, name: String): Title? = withContext(Dispatchers.IO) {
        val k = key(ctx)
        if (k.isNotEmpty()) {
            json(ctx, "https://api.themoviedb.org/3/search/tv?api_key=$k&query=${enc(name)}")?.optJSONArray("results").objs()
                .firstOrNull()?.let { return@withContext tmdbTitle(ctx, it.optLong("id"), true) }
        }
        val s = json(ctx, "https://api.tvmaze.com/singlesearch/shows?q=${enc(name)}&embed=cast") ?: return@withContext null
        Title("tvmaze:${s.optLong("id")}", true, s.optString("name"), s.optString("premiered").take(4).toIntOrNull(),
            stripHtml(s.optString("summary")), s.optJSONObject("image")?.optString("original"), s.optJSONObject("image")?.optString("original"),
            s.optJSONObject("rating")?.optDouble("average", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
            s.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            s.optJSONObject("_embedded")?.optJSONArray("cast").objs().take(20).map { c ->
                val pe = c.getJSONObject("person")
                CastMember("tvmaze:${pe.optLong("id")}", pe.optString("name"), c.optJSONObject("character")?.optString("name").orEmpty(),
                    pe.optJSONObject("image")?.optString("medium"))
            })
    }

    private fun tmdbTitle(ctx: Context, id: Long, tv: Boolean): Title? {
        val k = key(ctx)
        val o = json(ctx, "https://api.themoviedb.org/3/${if (tv) "tv" else "movie"}/$id?api_key=$k&append_to_response=credits") ?: return null
        return Title("tmdb:${if (tv) "tv" else "movie"}:$id", tv, o.optString(if (tv) "name" else "title"),
            o.optString(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull(), o.optString("overview"),
            o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w500" + it },
            o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w1280" + it },
            o.optDouble("vote_average", 0.0), o.optJSONArray("genres").objs().map { it.optString("name") },
            o.optJSONObject("credits")?.optJSONArray("cast").objs().take(20).map { c ->
                CastMember("tmdb:${c.optLong("id")}", c.optString("name"), c.optString("character"),
                    c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w185" + it })
            })
    }

    /** every episode of a show, in the official order */
    suspend fun episodes(ctx: Context, t: Title): List<Episode> = withContext(Dispatchers.IO) {
        if (t.id.startsWith("tvmaze:")) {
            return@withContext jsonArr(ctx, "https://api.tvmaze.com/shows/${t.id.substringAfter(':')}/episodes").objs().map {
                Episode(it.optInt("season"), it.optInt("number"), it.optString("name"), stripHtml(it.optString("summary")),
                    it.optJSONObject("image")?.optString("medium"), it.optString("airdate"))
            }
        }
        val id = t.id.substringAfterLast(':'); val k = key(ctx)
        val show = json(ctx, "https://api.themoviedb.org/3/tv/$id?api_key=$k") ?: return@withContext emptyList()
        show.optJSONArray("seasons").objs().map { it.optInt("season_number") }.filter { it > 0 }.flatMap { sn ->
            json(ctx, "https://api.themoviedb.org/3/tv/$id/season/$sn?api_key=$k")?.optJSONArray("episodes").objs().map {
                Episode(sn, it.optInt("episode_number"), it.optString("name"), it.optString("overview"),
                    it.optString("still_path").takeIf { p -> p.isNotBlank() && p != "null" }?.let { p -> IMG + "w300" + p }, it.optString("air_date"))
            }
        }
    }

    /** an actor and everything they've been in */
    suspend fun person(ctx: Context, id: String): Person? = withContext(Dispatchers.IO) {
        if (id.startsWith("tvmaze:")) {
            val n = id.substringAfter(':')
            val p = json(ctx, "https://api.tvmaze.com/people/$n") ?: return@withContext null
            val credits = jsonArr(ctx, "https://api.tvmaze.com/people/$n/castcredits?embed=show").objs().mapNotNull { c ->
                val s = c.optJSONObject("_embedded")?.optJSONObject("show") ?: return@mapNotNull null
                Credit(s.optString("name"), s.optString("premiered").take(4).toIntOrNull(), true, s.optJSONObject("image")?.optString("medium"), "")
            }
            return@withContext Person(id, p.optString("name"), p.optJSONObject("image")?.optString("original"), "",
                p.optString("birthday"), credits.sortedByDescending { it.year ?: 0 })
        }
        val n = id.substringAfter(':'); val k = key(ctx)
        val p = json(ctx, "https://api.themoviedb.org/3/person/$n?api_key=$k&append_to_response=combined_credits") ?: return@withContext null
        val credits = p.optJSONObject("combined_credits")?.optJSONArray("cast").objs().map { c ->
            val tv = c.optString("media_type") == "tv"
            Credit(c.optString(if (tv) "name" else "title"), c.optString(if (tv) "first_air_date" else "release_date").take(4).toIntOrNull(), tv,
                c.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "w342" + it }, c.optString("character"))
        }.distinctBy { it.title + it.tv }.sortedByDescending { it.year ?: 0 }
        Person(id, p.optString("name"), p.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }?.let { IMG + "h632" + it },
            p.optString("biography"), p.optString("birthday"), credits)
    }
}
