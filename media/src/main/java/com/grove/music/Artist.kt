package com.grove.music

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Release(val title: String, val type: String, val year: String, val cover: String?)

data class ArtistInfo(
    val name: String, val image: String?, val bio: String?, val fans: Int, val source: String?,
    val releases: List<Release> = emptyList(),
)

/**
 * Artist photo + discography (Deezer) and biography (Wikipedia), fetched automatically and kept on the
 * phone (text in prefs, pictures in the image disk cache) so artist pages work offline.
 */
object ArtistRepository {
    private val mem = HashMap<String, ArtistInfo?>()
    private val musicWords = listOf("singer", "musician", "band", "rapper", "group", "songwriter", "duo", "dj",
        "composer", "vocalist", "artist", "penyanyi", "kumpulan", "muzik", "pemuzik", "idol")
    // always a collaboration: "A feat. B", "A ft B", "A featuring B", "A; B"
    private val hard = Regex("""\s*(;|\s\(?feat\.?\s|\s\(?ft\.?\s|\sfeaturing\s)\s*""", RegexOption.IGNORE_CASE)
    // only a collaboration when the parts are singers you already have on their own:
    // keeps "AC/DC", "Simon & Garfunkel", "Earth, Wind & Fire" whole
    private val soft = Regex("""\s*(,|/|&|\+|\sx\s|\swith\s|\sand\s)\s*""", RegexOption.IGNORE_CASE)

    /** keys of every full artist credit in the library (set after each scan) */
    @Volatile var known: Set<String> = emptySet()
    fun learn(songs: List<Song>) { known = songs.map { key(it.artist) }.toHashSet() }

    /** "A feat. B" -> ["A", "B"]; "AC/DC" stays "AC/DC" */
    fun split(n: String): List<String> {
        val out = ArrayList<String>()
        for (part in n.split(hard).map { it.trim().trimEnd(')').trim() }.filter { it.isNotEmpty() }) {
            if (key(part) in known && part != n.trim()) { out += part; continue }
            val pieces = part.split(soft).map { it.trim() }.filter { it.isNotEmpty() }
            if (pieces.size > 1 && pieces.any { key(it) in known }) out += pieces else out += part
        }
        return out.ifEmpty { listOf(n.trim()) }
    }
    fun primary(n: String): String = split(n).first()
    fun key(n: String) = n.trim().lowercase()

    /** Cached info only (no network) — for lists. */
    fun cached(ctx: Context, name: String): ArtistInfo? {
        val k = key(name)
        synchronized(mem) { if (mem.containsKey(k)) return mem[k] }
        val j = prefs(ctx).getString(k, null) ?: return null
        val info = runCatching { JSONObject(j).let { if (it.optBoolean("none")) null else fromJson(it) } }.getOrNull()
        synchronized(mem) { mem[k] = info }
        return info
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("artist_cache_v2", Context.MODE_PRIVATE)
    private fun isCached(ctx: Context, name: String) = prefs(ctx).contains(key(name))

    suspend fun load(ctx: Context, name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        val k = key(name)
        if (k.isEmpty() || k == "unknown artist" || k == "<unknown>") return@withContext null
        if (isCached(ctx, name)) return@withContext cached(ctx, name)
        try {
            val info = fetch(name.trim()) ?: split(name).takeIf { it.size > 1 }?.let { fetch(it.first()) }
            prefs(ctx).edit().putString(k, (info?.let { toJson(it) } ?: JSONObject().put("none", true)).toString()).apply()
            synchronized(mem) { mem[k] = info }
            info?.let { prefetchImages(ctx, it) }
            info
        } catch (e: IOException) { null } // offline: don't remember the miss, try again next time
        catch (e: Exception) { prefs(ctx).edit().putString(k, JSONObject().put("none", true).toString()).apply(); null }
    }

    /** Download info + pictures for every artist not cached yet (runs after each library scan). */
    /** Download info + pictures for every artist not cached yet, 4 at a time. */
    suspend fun syncAll(ctx: Context, artists: Collection<String>, onProgress: (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val todo = artists.filter { !isCached(ctx, it) }
        if (todo.isEmpty()) { onProgress(0, 0); return@withContext }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val sem = Semaphore(4)
        onProgress(0, todo.size)
        coroutineScope {
            todo.map { a -> async { sem.withPermit { load(ctx, a); onProgress(done.incrementAndGet(), todo.size) } } }.awaitAll()
        }
    }

    fun cachedCount(ctx: Context, artists: Collection<String>) = artists.count { isCached(ctx, it) }

    private fun prefetchImages(ctx: Context, info: ArtistInfo) {
        val urls = listOfNotNull(info.image) + info.releases.mapNotNull { it.cover }
        urls.forEach { u ->
            ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(u)
                .diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.DISABLED).build())
        }
    }

    private fun fetch(name: String): ArtistInfo? {
        var image: String? = null; var fans = 0; var display = name; var id: Long = 0
        get("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}")?.let { body ->
            val arr = JSONObject(body).optJSONArray("data")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            val n = norm(name)
            val o = list.firstOrNull { norm(it.optString("name")) == n }
                ?: list.firstOrNull()?.takeIf { val m = norm(it.optString("name")); m.isNotEmpty() && (m.contains(n) || n.contains(m)) }
            if (o != null) {
                image = o.optString("picture_xl").takeIf { it.isNotBlank() && !it.contains("/artist//") }
                fans = o.optInt("nb_fan"); display = o.optString("name", name); id = o.optLong("id")
            }
        }
        val releases = ArrayList<Release>()
        if (id > 0) get("https://api.deezer.com/artist/$id/albums?limit=200")?.let { body ->
            val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
            val seen = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optString("title"); if (t.isBlank() || !seen.add(norm(t))) continue
                releases += Release(t, o.optString("record_type", "album"), o.optString("release_date").take(4),
                    o.optString("cover_xl").ifBlank { o.optString("cover_big") }.ifBlank { null })
            }
            releases.sortByDescending { it.year }
        }
        var bio: String? = null; var source: String? = null
        loop@ for (lang in listOf("en", "ms")) {
            for (t in listOf("$name (singer)", "$name (band)", "$name (musician)", "$name (rapper)", "$name (penyanyi)", name)) {
                val o = get("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(t.replace(' ', '_')).replace("+", "%20")}")
                    ?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                if (o.optString("type") != "standard") continue
                val desc = (o.optString("description") + " " + o.optString("extract").take(300)).lowercase()
                if (musicWords.none { desc.contains(it) }) continue
                bio = o.optString("extract").takeIf { it.isNotBlank() }
                source = o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
                if (image == null) image = o.optJSONObject("originalimage")?.optString("source")
                if (bio != null) break@loop
            }
        }
        if (image == null && bio == null && releases.isEmpty()) return null
        return ArtistInfo(display, image, bio, fans, source, releases)
    }

    fun norm(s: String) = s.lowercase().replace(Regex("""\(.*?\)|\[.*?]"""), "").filter { it.isLetterOrDigit() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun toJson(i: ArtistInfo) = JSONObject().put("n", i.name).put("i", i.image ?: "").put("b", i.bio ?: "")
        .put("f", i.fans).put("s", i.source ?: "")
        .put("r", JSONArray().apply { i.releases.forEach { put(JSONObject().put("t", it.title).put("y", it.type).put("d", it.year).put("c", it.cover ?: "")) } })
    private fun fromJson(o: JSONObject) = ArtistInfo(o.getString("n"), o.optString("i").ifBlank { null },
        o.optString("b").ifBlank { null }, o.optInt("f"), o.optString("s").ifBlank { null },
        (o.optJSONArray("r") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { Release(it.getString("t"), it.optString("y"), it.optString("d"), it.optString("c").ifBlank { null }) } })

    /** null = not found (404 etc.); throws IOException when offline so the miss isn't cached */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "GroveMusic/1.0 (Android; github.com/aimanharith19-oss/GroveMusic)")
        try { return if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }
}
