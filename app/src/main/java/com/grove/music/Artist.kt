package com.grove.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArtistInfo(val name: String, val image: String?, val bio: String?, val fans: Int, val source: String?)

/** Looks up an artist's photo (Deezer) and biography (Wikipedia) automatically, cached on the phone. */
object ArtistRepository {
    private val mem = HashMap<String, ArtistInfo?>()
    private val musicWords = listOf("singer", "musician", "band", "rapper", "group", "songwriter", "duo", "dj",
        "composer", "vocalist", "artist", "penyanyi", "kumpulan", "muzik", "pemuzik", "idol")

    suspend fun load(ctx: Context, name: String): ArtistInfo? = withContext(Dispatchers.IO) {
        val key = name.trim().lowercase()
        if (key.isEmpty() || key == "unknown artist") return@withContext null
        synchronized(mem) { if (mem.containsKey(key)) return@withContext mem[key] }
        val sp = ctx.getSharedPreferences("artist_cache", Context.MODE_PRIVATE)
        sp.getString(key, null)?.let { j ->
            val info = runCatching { fromJson(JSONObject(j)) }.getOrNull()
            if (info != null) { synchronized(mem) { mem[key] = info }; return@withContext info }
        }
        val info = runCatching { fetch(name.trim()) }.getOrNull()
            ?: primary(name)?.let { p -> runCatching { fetch(p) }.getOrNull() }
        if (info != null) sp.edit().putString(key, toJson(info).toString()).apply()
        synchronized(mem) { mem[key] = info }
        info
    }

    /** "A feat. B", "A, B", "A & B" -> "A" */
    private fun primary(n: String): String? =
        n.split(Regex("""\s*(,|;|/|&| x | feat\.?| ft\.?| featuring )\s*""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && !it.equals(n.trim(), true) }

    private fun fetch(name: String): ArtistInfo? {
        var image: String? = null; var fans = 0; var display = name
        get("https://api.deezer.com/search/artist?limit=5&q=${enc(name)}")?.let { body ->
            val arr = JSONObject(body).optJSONArray("data")
            val list = if (arr == null) emptyList() else (0 until arr.length()).map { arr.getJSONObject(it) }
            val n = norm(name)
            val o = list.firstOrNull { norm(it.optString("name")) == n }
                ?: list.firstOrNull()?.takeIf { val m = norm(it.optString("name")); m.isNotEmpty() && (m.contains(n) || n.contains(m)) }
            if (o != null) {
                image = o.optString("picture_xl").takeIf { it.isNotBlank() && !it.contains("/artist//") }
                fans = o.optInt("nb_fan"); display = o.optString("name", name)
            }
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
        if (image == null && bio == null) return null
        return ArtistInfo(display, image, bio, fans, source)
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun toJson(i: ArtistInfo) = JSONObject().put("n", i.name).put("i", i.image ?: "").put("b", i.bio ?: "")
        .put("f", i.fans).put("s", i.source ?: "")
    private fun fromJson(o: JSONObject) = ArtistInfo(o.getString("n"), o.optString("i").ifBlank { null },
        o.optString("b").ifBlank { null }, o.optInt("f"), o.optString("s").ifBlank { null })

    private fun get(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "GroveMusic/1.0 (Android; github.com/aimanharith19-oss/GroveMusic)")
            try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
        }
    } catch (e: Exception) { null }
}
