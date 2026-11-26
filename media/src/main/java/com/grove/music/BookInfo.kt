package com.grove.music

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Book details from the internet (Google Books, then Open Library), saved so each book is looked up once. */
object BookInfo {
    class Info(
        val title: String, val authors: String?, val year: String?, val publisher: String?, val pages: Int?,
        val genres: String?, val rating: String?, val about: String?, val cover: String?, val adult: Boolean,
    )

    /** "Some_Book (z-lib.org) [EPUB] - Author" → "Some Book - Author" */
    fun clean(name: String): String = name.substringBeforeLast('.').replace('_', ' ')
        .replace(Regex("""\((z-?lib[^)]*|libgen[^)]*|epub|pdf|mobi|azw3?|retail|ebook)\)|\[[^]]*]""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun get(url: String): String? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "GroveMedia")
            if (responseCode != 200) null else inputStream.bufferedReader().use { it.readText() }
        }
    }.getOrNull()

    suspend fun lookup(ctx: Context, name: String): Info? = withContext(Dispatchers.IO) {
        val q = clean(name).ifBlank { return@withContext null }
        val sp = ctx.getSharedPreferences("book_info", Context.MODE_PRIVATE)
        val raw = sp.getString(q, null) ?: run {
            val enc = URLEncoder.encode(q, "UTF-8")
            val r = get("https://www.googleapis.com/books/v1/volumes?q=$enc&maxResults=1&printType=books")
                ?.let { runCatching { JSONObject(it).optJSONArray("items")?.optJSONObject(0)?.optJSONObject("volumeInfo") }.getOrNull() }
                ?.let { JSONObject().put("src", "g").put("v", it).toString() }
                ?: get("https://openlibrary.org/search.json?q=$enc&limit=1&fields=title,author_name,first_publish_year,publisher,number_of_pages_median,subject,cover_i,key")
                    ?.let { runCatching { JSONObject(it).optJSONArray("docs")?.optJSONObject(0) }.getOrNull() }
                    ?.let { JSONObject().put("src", "o").put("v", it).toString() }
                ?: "{}"
            sp.edit().putString(q, r).apply(); r
        }
        runCatching {
            val o = JSONObject(raw); val v = o.optJSONObject("v") ?: return@runCatching null
            fun arr(k: String, n: Int = 4) = v.optJSONArray(k)?.let { a -> (0 until minOf(n, a.length())).map { a.optString(it) } }?.filter { it.isNotBlank() }?.joinToString(", ")
            if (o.optString("src") == "g") Info(
                title = v.optString("title"), authors = arr("authors"), year = v.optString("publishedDate").take(4).ifBlank { null },
                publisher = v.optString("publisher").ifBlank { null }, pages = v.optInt("pageCount").takeIf { it > 0 },
                genres = arr("categories"),
                rating = v.optDouble("averageRating", 0.0).takeIf { it > 0 }?.let { "%.1f / 5 (${v.optInt("ratingsCount")} ratings)".format(it) },
                about = v.optString("description").ifBlank { null }?.let { android.text.Html.fromHtml(it, 0).toString().trim() },
                cover = v.optJSONObject("imageLinks")?.optString("thumbnail")?.replace("http://", "https://"),
                adult = v.optString("maturityRating") == "MATURE",
            ) else Info(
                title = v.optString("title"), authors = arr("author_name"), year = v.optInt("first_publish_year").takeIf { it > 0 }?.toString(),
                publisher = arr("publisher", 1), pages = v.optInt("number_of_pages_median").takeIf { it > 0 },
                genres = arr("subject", 3), rating = null, about = null,
                cover = v.optInt("cover_i").takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                adult = Adult.isAdult(arr("subject", 20)),
            )
        }.getOrNull()
    }

    /** extra rows + description for the long-press card */
    suspend fun peek(ctx: Context, name: String): PeekMore {
        val i = lookup(ctx, name) ?: return PeekMore(listOf("online" to "no details found"))
        if (i.adult) return PeekMore(listOf("online" to "18+ hidden"))
        return PeekMore(
            rows = listOfNotNull(
                i.authors?.let { "author" to it },
                i.year?.let { "published" to it },
                i.publisher?.let { "publisher" to it },
                i.pages?.let { "pages" to it.toString() },
                i.genres?.let { "genre" to it },
                i.rating?.let { "rating" to it },
            ),
            about = i.about,
        )
    }
}
