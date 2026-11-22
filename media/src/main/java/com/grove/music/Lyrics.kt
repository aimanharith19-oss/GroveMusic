package com.grove.music

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LyricLine(val time: Long, val text: String)
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String)

/** What we know about the playing track, for lyric lookup. */
data class TrackKey(
    val id: String, val uri: Uri?, val path: String?, val title: String,
    val artist: String, val album: String?, val durationMs: Long, val baseName: String,
)

fun trackKey(item: MediaItem, song: Song?, durationMs: Long): TrackKey {
    val md = item.mediaMetadata
    val name = song?.displayName ?: md.extras?.getString("name") ?: item.requestMetadata.mediaUri?.lastPathSegment.orEmpty()
    return TrackKey(
        id = item.mediaId,
        uri = song?.uri ?: item.requestMetadata.mediaUri ?: item.localConfiguration?.uri,
        path = song?.path ?: md.extras?.getString("path"),
        title = song?.title ?: md.title?.toString() ?: name,
        artist = song?.artist ?: md.artist?.toString().orEmpty(),
        album = song?.album ?: md.albumTitle?.toString(),
        durationMs = song?.duration?.takeIf { it > 0 } ?: durationMs,
        baseName = name.substringBeforeLast('.'),
    )
}

object LrcParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val meta = Regex("""^\[[a-zA-Z#]+:.*]$""")
    private val offsetRe = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private val wordTags = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(text: String?, source: String): Lyrics? {
        if (text.isNullOrBlank()) return null
        val offset = offsetRe.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val timed = ArrayList<LyricLine>()
        val plain = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val tags = generateSequence(tag.matchAt(line, 0)) { prev -> tag.matchAt(line, prev.range.last + 1) }.toList()
            if (tags.isEmpty()) {
                if (!meta.matches(line)) plain += line
                continue
            }
            val body = line.substring(tags.last().range.last + 1).replace(wordTags, "").trim()
            for (m in tags) {
                val (mm, ss, frac) = m.destructured
                val f = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                timed += LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1000 + f - offset, body)
            }
        }
        if (timed.isNotEmpty()) return Lyrics(timed.sortedBy { it.time }, true, source)
        val p = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        return if (p.isEmpty()) null else Lyrics(p.map { LyricLine(-1, it) }, false, source)
    }
}

/** Reads Vorbis comments (e.g. LYRICS=) embedded in FLAC files. */
object FlacTags {
    fun read(ctx: Context, uri: Uri): Map<String, String> = try {
        ctx.contentResolver.openInputStream(uri)?.use { parse(DataInputStream(BufferedInputStream(it))) } ?: emptyMap()
    } catch (e: Exception) { emptyMap() }

    private fun parse(inp: DataInputStream): Map<String, String> {
        val head = ByteArray(4); inp.readFully(head)
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            val rest = ByteArray(6); inp.readFully(rest)
            val size = ((rest[2].toInt() and 0x7F) shl 21) or ((rest[3].toInt() and 0x7F) shl 14) or
                ((rest[4].toInt() and 0x7F) shl 7) or (rest[5].toInt() and 0x7F)
            skip(inp, size.toLong() + if ((rest[1].toInt() and 0x10) != 0) 10 else 0)
            inp.readFully(head)
        }
        if (String(head, Charsets.US_ASCII) != "fLaC") return emptyMap()
        while (true) {
            val h = inp.readUnsignedByte()
            val last = (h and 0x80) != 0
            val type = h and 0x7F
            val len = (inp.readUnsignedByte() shl 16) or (inp.readUnsignedByte() shl 8) or inp.readUnsignedByte()
            if (type == 4) { val b = ByteArray(len); inp.readFully(b); return vorbis(b) }
            skip(inp, len.toLong())
            if (last) return emptyMap()
        }
    }

    private fun skip(inp: DataInputStream, n: Long) {
        var left = n
        while (left > 0) { val s = inp.skip(left); if (s <= 0) { inp.readByte(); left-- } else left -= s }
    }

    private fun vorbis(b: ByteArray): Map<String, String> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(bb.position() + bb.int)
        val out = HashMap<String, String>()
        repeat(bb.int) {
            val l = bb.int
            val s = String(b, bb.position(), l, Charsets.UTF_8)
            bb.position(bb.position() + l)
            val i = s.indexOf('=')
            if (i > 0) out.putIfAbsent(s.substring(0, i).uppercase(), s.substring(i + 1))
        }
        return out
    }
}

/** Free synced-lyrics database (lrclib.net). */
object LrcLib {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun fetch(k: TrackKey): String? {
        val q = "artist_name=${enc(k.artist)}&track_name=${enc(k.title)}"
        if (k.album != null && k.durationMs > 0) {
            get("https://lrclib.net/api/get?$q&album_name=${enc(k.album)}&duration=${k.durationMs / 1000}")
                ?.let { pick(JSONObject(it)) }?.let { return it }
        }
        val arr = get("https://lrclib.net/api/search?$q")?.let { JSONArray(it) } ?: return null
        var plain: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.isNull("syncedLyrics") && o.optString("syncedLyrics").isNotBlank()) return o.getString("syncedLyrics")
            if (plain == null && !o.isNull("plainLyrics")) plain = o.optString("plainLyrics").takeIf { it.isNotBlank() }
        }
        return plain
    }

    private fun pick(o: JSONObject): String? =
        (if (!o.isNull("syncedLyrics")) o.optString("syncedLyrics") else null)?.takeIf { it.isNotBlank() }
            ?: (if (!o.isNull("plainLyrics")) o.optString("plainLyrics") else null)?.takeIf { it.isNotBlank() }

    private fun get(url: String): String? = try {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "GroveMusic/1.0 (Android)")
            try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
        }
    } catch (e: Exception) { null }
}

private fun httpGet(url: String, referer: String? = null): String? = try {
    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("User-Agent", "GroveMusic/1.0 (Android)")
        referer?.let { setRequestProperty("Referer", it) }
        try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
    }
} catch (e: Exception) { null }

/** NetEase Cloud Music — large catalogue of synced lyrics, strong for Asian music. */
object NetEase {
    fun fetch(k: TrackKey): String? {
        val q = URLEncoder.encode("${k.title} ${k.artist}", "UTF-8")
        val res = JSONObject(httpGet("https://music.163.com/api/search/get?s=$q&type=1&limit=8", "https://music.163.com") ?: return null)
            .optJSONObject("result")?.optJSONArray("songs") ?: return null
        val t = k.title.lowercase().filter { it.isLetterOrDigit() }
        val songs = (0 until res.length()).map { res.getJSONObject(it) }
        val hit = songs.firstOrNull { o ->
            o.optString("name").lowercase().filter { it.isLetterOrDigit() }.let { it == t || it.startsWith(t) || t.startsWith(it) } &&
                (k.durationMs <= 0 || kotlin.math.abs(o.optLong("duration") - k.durationMs) < 8000)
        } ?: return null
        return JSONObject(httpGet("https://music.163.com/api/song/lyric?id=${hit.optLong("id")}&lv=1&kv=1&tv=-1", "https://music.163.com") ?: return null)
            .optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
    }
}

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
private fun close(a: String, b: String) = norm(a).let { x -> val y = norm(b); x.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x)) }

/** Kugou — big synced catalogue (Asian, Malay and Western pop), matched by title, artist and length. */
object Kugou {
    fun fetch(k: TrackKey): String? {
        val q = URLEncoder.encode("${k.artist} - ${k.title}", "UTF-8")
        val c = JSONObject(httpGet("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$q&duration=${k.durationMs}&hash=") ?: return null)
            .optJSONArray("candidates") ?: return null
        val hit = (0 until c.length()).map { c.getJSONObject(it) }.firstOrNull { o ->
            (k.durationMs <= 0 || kotlin.math.abs(o.optLong("duration") - k.durationMs) < 6000) && close(o.optString("song"), k.title)
        } ?: return null
        val d = JSONObject(httpGet("https://lyrics.kugou.com/download?ver=1&client=pc&id=${hit.optString("id")}&accesskey=${hit.optString("accesskey")}&fmt=lrc&charset=utf8")
            ?: return null).optString("content").takeIf { it.isNotBlank() } ?: return null
        return String(android.util.Base64.decode(d, android.util.Base64.DEFAULT), Charsets.UTF_8).takeIf { it.isNotBlank() }
    }
}

/** QQ Music — synced lyrics, strong for Chinese, K-pop and regional music. */
object QQMusic {
    fun fetch(k: TrackKey): String? {
        val q = URLEncoder.encode("${k.title} ${k.artist}", "UTF-8")
        val list = JSONObject(httpGet("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$q&format=json&p=1&n=8", "https://y.qq.com") ?: return null)
            .optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: return null
        val hit = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { o ->
            close(o.optString("songname"), k.title) && (k.durationMs <= 0 || kotlin.math.abs(o.optLong("interval") * 1000 - k.durationMs) < 6000)
        } ?: return null
        val t = JSONObject(httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${hit.optString("songmid")}&format=json&nobase64=1",
            "https://y.qq.com") ?: return null).optString("lyric")
        return android.text.Html.fromHtml(t, android.text.Html.FROM_HTML_MODE_LEGACY).toString().takeIf { it.isNotBlank() && !it.contains("此歌曲为没有填词的纯音乐") }
    }
}

/** lyrics.ovh — plain (unsynced) lyrics as a last resort. */
object LyricsOvh {
    fun fetch(k: TrackKey): String? {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        return JSONObject(httpGet("https://api.lyrics.ovh/v1/${e(k.artist)}/${e(k.title)}") ?: return null)
            .optString("lyrics").takeIf { it.isNotBlank() }
    }
}

object LyricsRepository {
    private var indexFor: String? = null
    private var index: Map<String, Uri> = emptyMap()

    @Synchronized fun invalidateFolder() { indexFor = null }

    suspend fun load(ctx: Context, k: TrackKey): Lyrics? = withContext(Dispatchers.IO) { runCatching { find(ctx, k) }.getOrNull() }

    private fun read(ctx: Context, uri: Uri): String? =
        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } }.getOrNull()

    private fun find(ctx: Context, k: TrackKey): Lyrics? {
        val prefs = Prefs(ctx)
        // 1. a .lrc the user picked for this song
        prefs.lyricsFor(k.id)?.let { LrcParser.parse(read(ctx, Uri.parse(it)), "your .lrc file") }?.let { return it }
        // 2. song.lrc next to the audio file
        k.path?.let { p ->
            val f = File(p.substringBeforeLast('.') + ".lrc")
            runCatching { if (f.canRead()) LrcParser.parse(f.readText(), "lrc next to song") else null }.getOrNull()
        }?.let { return it }
        // 3. lyrics folder chosen in settings
        prefs.lyricsFolder?.let { tree ->
            val idx = folderIndex(ctx, tree)
            listOf(k.baseName, "${k.artist} - ${k.title}", k.title)
                .firstNotNullOfOrNull { idx[it.lowercase()] }
                ?.let { LrcParser.parse(read(ctx, it), "lyrics folder") }
        }?.let { return it }
        // 4. lyrics embedded in FLAC tags
        k.uri?.let { u ->
            val t = FlacTags.read(ctx, u)
            LrcParser.parse(t["LYRICS"] ?: t["SYNCEDLYRICS"] ?: t["UNSYNCEDLYRICS"] ?: t["UNSYNCED LYRICS"], "embedded in file")
        }?.let { return it }
        // 4b. lyrics tag in MP3 / M4A / OGG / OPUS / WMA / DSF files
        k.path?.let { p -> runCatching {
            val f = File(p); if (!f.canRead()) null
            else org.jaudiotagger.audio.AudioFileIO.read(f).tag?.getFirst(org.jaudiotagger.tag.FieldKey.LYRICS)?.takeIf { it.isNotBlank() }
        }.getOrNull() }?.let { LrcParser.parse(it, "embedded in file") }?.let { return it }
        // 5. previously downloaded / 6. lrclib.net
        if (k.artist.isBlank() || k.artist == "unknown artist") return null
        val cache = File(ctx.filesDir, "lyrics/" + "${k.artist} - ${k.title}".replace(Regex("""[^\w\- ]"""), "_").take(120) + ".lrc")
        if (cache.exists()) return LrcParser.parse(cache.readText(), "saved")
        if (!prefs.onlineLyrics) return null
        // online sources, best first: synced lyrics from LRCLIB or NetEase, then plain text from lyrics.ovh
        var best: Pair<String, String>? = null
        for ((name, src) in listOf<Pair<String, (TrackKey) -> String?>>(
            "lrclib.net" to LrcLib::fetch, "NetEase" to NetEase::fetch, "Kugou" to Kugou::fetch, "QQ Music" to QQMusic::fetch,
            "lyrics.ovh" to LyricsOvh::fetch)) {
            val t = runCatching { src(k) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val parsed = LrcParser.parse(t, name) ?: continue
            if (best == null) best = t to name
            if (parsed.synced) { best = t to name; break }
        }
        val (text, name) = best ?: return null
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(text) }
        return LrcParser.parse(text, name)
    }

    @Synchronized private fun folderIndex(ctx: Context, tree: String): Map<String, Uri> {
        if (indexFor == tree) return index
        val m = HashMap<String, Uri>()
        DocumentFile.fromTreeUri(ctx, Uri.parse(tree))?.let { walk(it, m, 0) }
        index = m; indexFor = tree
        return m
    }

    private fun walk(dir: DocumentFile, m: MutableMap<String, Uri>, depth: Int) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) { if (depth < 3) walk(f, m, depth + 1); continue }
            val n = f.name ?: continue
            if (n.endsWith(".lrc", true)) m[n.dropLast(4).lowercase()] = f.uri
        }
    }
}
