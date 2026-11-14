package com.grove.music

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val track: Int,
    val duration: Long,
    val uri: Uri,
    val path: String?,
    val mime: String?,
    val displayName: String,
    val genre: String,
) {
    val folder: String get() = path?.substringBeforeLast('/', "")?.ifEmpty { null } ?: "unknown folder"
    val artUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    val format: String get() = formatOf(mime, displayName)
}

fun formatOf(mime: String?, name: String?): String {
    val ext = name?.substringAfterLast('.', "")?.uppercase().orEmpty()
    return when {
        mime?.contains("flac") == true || ext == "FLAC" -> "FLAC"
        ext.length in 1..4 -> ext
        else -> mime?.substringAfter('/')?.uppercase().orEmpty()
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title).setArtist(artist).setAlbumTitle(album).setGenre(genre)
            .setArtworkUri(artUri)
            .setExtras(Bundle().apply {
                putString("path", path); putString("name", displayName)
                putString("fmt", format); putLong("dur", duration)
            })
            .build()
    ).build()

object MusicRepository {
    fun query(ctx: Context): List<Song> {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        ) + (if (android.os.Build.VERSION.SDK_INT >= 30) arrayOf("genre") else emptyArray())
        val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.MIME_TYPE} LIKE '%flac%'"
        val out = ArrayList<Song>()
        ctx.contentResolver.query(base, cols, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { c ->
            fun s(col: String): String? = c.getColumnIndex(col).let { if (it < 0) null else c.getString(it) }
            fun l(col: String): Long = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
            while (c.moveToNext()) {
                val id = l(MediaStore.Audio.Media._ID)
                val name = s(MediaStore.Audio.Media.DISPLAY_NAME).orEmpty()
                out += Song(
                    id = id,
                    title = s(MediaStore.Audio.Media.TITLE)?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
                    artist = s(MediaStore.Audio.Media.ARTIST).clean("unknown artist"),
                    album = s(MediaStore.Audio.Media.ALBUM).clean("unknown album"),
                    albumId = l(MediaStore.Audio.Media.ALBUM_ID),
                    track = (l(MediaStore.Audio.Media.TRACK) % 1000).toInt(),
                    duration = l(MediaStore.Audio.Media.DURATION),
                    uri = ContentUris.withAppendedId(base, id),
                    path = s(MediaStore.Audio.Media.DATA),
                    mime = s(MediaStore.Audio.Media.MIME_TYPE),
                    displayName = name,
                    genre = s("genre").clean("unknown genre"),
                )
            }
        }
        return out
    }

    private fun String?.clean(def: String) =
        if (this.isNullOrBlank() || this == "<unknown>") def else this.trim()
}

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    val playlists = PlaylistStore.get(app)
    val mixSalt = androidx.compose.runtime.mutableIntStateOf(prefs.mixSalt)
    /** start a radio from a song (seed first, then a related random mix) */
    var playRadio: ((List<Song>) -> Unit)? = null
    var editTags: ((Long) -> Unit)? = null
    fun radio(seed: Song) { playRadio?.invoke(Radio.build(seed, songs.value)) }
    fun newMixes() { mixSalt.intValue++; prefs.mixSalt = mixSalt.intValue }
    val songs = mutableStateOf<List<Song>>(emptyList())
    /** every folder that contains music, with its song count (hidden ones included) */
    val folders = mutableStateOf<List<Pair<String, Int>>>(emptyList())
    val hidden = mutableStateOf(prefs.hiddenFolders)
    val loading = mutableStateOf(false)
    /** bumps whenever a new artist picture/info lands in the cache */
    val artistTick = androidx.compose.runtime.mutableIntStateOf(0)
    private var all: List<Song> = emptyList()
    private var byId: Map<String, Song> = emptyMap()

    fun song(id: String?): Song? = id?.let { byId[it] }

    fun load() = viewModelScope.launch {
        loading.value = true
        all = withContext(Dispatchers.IO) { runCatching { MusicRepository.query(getApplication()) }.getOrDefault(emptyList()) }
        byId = all.associateBy { it.id.toString() }
        ArtistRepository.learn(all)
        folders.value = all.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
        apply()
        loading.value = false
        syncArtists()
    }

    /** (done, total) while artist info is downloading, null when idle */
    val artistSync = mutableStateOf<Pair<Int, Int>?>(null)
    private var syncing = false

    /** fetch & keep info + pictures for every artist at once, so artist pages work offline */
    fun syncArtists() {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            val names = songs.value.flatMap { ArtistRepository.split(it.artist) }.distinctBy { ArtistRepository.key(it) }
            ArtistRepository.syncAll(getApplication(), names) { d, t ->
                artistSync.value = if (t == 0 || d >= t) null else d to t
                if (d % 5 == 0 || d == t) artistTick.intValue++
            }
            artistSync.value = null; artistTick.intValue++
            syncing = false
        }
    }

    fun artistNames() = songs.value.flatMap { ArtistRepository.split(it.artist) }.distinctBy { ArtistRepository.key(it) }

    /** (done, total, current song title) while converting to FLAC, null when idle */
    val flacJob = mutableStateOf<Triple<Int, Int, String>?>(null)
    val flacSongProgress = androidx.compose.runtime.mutableFloatStateOf(0f)
    val preferFlac = mutableStateOf(prefs.preferFlac)

    private fun sameKey(s: Song) = ArtistRepository.norm(s.title) + "|" + ArtistRepository.norm(s.artist)

    /** Songs that still need a FLAC copy. */
    fun needingFlac(list: List<Song>): List<Song> {
        val have = all.filter { FlacConverter.isFlac(it) }.map { sameKey(it) }.toHashSet()
        return list.filter { !FlacConverter.isFlac(it) && sameKey(it) !in have }
    }

    fun convertToFlac(list: List<Song>) {
        if (flacJob.value != null) return
        val todo = needingFlac(list)
        if (todo.isEmpty()) return
        viewModelScope.launch {
            var done = 0
            withContext(Dispatchers.Default) {
                for (s in todo) {
                    flacJob.value = Triple(done, todo.size, s.title)
                    flacSongProgress.floatValue = 0f
                    FlacConverter.convert(getApplication(), s) { flacSongProgress.floatValue = it }
                    done++
                }
            }
            flacJob.value = null
            delay(1500) // let the media scanner index the new files
            load()
        }
    }

    fun setPreferFlac(on: Boolean) { preferFlac.value = on; prefs.preferFlac = on; apply() }

    fun setHidden(folder: String, hide: Boolean) {
        val h = if (hide) hidden.value + folder else hidden.value - folder
        hidden.value = h; prefs.hiddenFolders = h; apply()
    }

    private fun apply() { songs.value = visibleSongs(all, hidden.value, preferFlac.value) }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("grove", Context.MODE_PRIVATE)
    var accent: Int
        get() = sp.getInt("accent", 0xFF0050EF.toInt())
        set(v) = sp.edit().putInt("accent", v).apply()
    var lyricsFolder: String?
        get() = sp.getString("lyrics_folder", null)
        set(v) = sp.edit().putString("lyrics_folder", v).apply()
    var onlineLyrics: Boolean
        get() = sp.getBoolean("online_lyrics", true)
        set(v) = sp.edit().putBoolean("online_lyrics", v).apply()
    fun saveQueue(ids: List<String>, index: Int, pos: Long) =
        sp.edit().putString("q_ids", ids.joinToString(",")).putInt("q_idx", index).putLong("q_pos", pos).apply()
    fun saveQueuePosition(index: Int, pos: Long) = sp.edit().putInt("q_idx", index).putLong("q_pos", pos).apply()
    fun loadQueue(): Triple<List<String>, Int, Long> = Triple(
        sp.getString("q_ids", "")!!.split(',').filter { it.isNotBlank() }, sp.getInt("q_idx", 0), sp.getLong("q_pos", 0))
    var mixSalt: Int
        get() = sp.getInt("mix_salt", 0)
        set(v) = sp.edit().putInt("mix_salt", v).apply()
    var carLyrics: Boolean
        get() = sp.getBoolean("car_lyrics", true)
        set(v) = sp.edit().putBoolean("car_lyrics", v).apply()
    var preferFlac: Boolean
        get() = sp.getBoolean("prefer_flac", true)
        set(v) = sp.edit().putBoolean("prefer_flac", v).apply()
    var liveTiles: Boolean
        get() = sp.getBoolean("live_tiles", false)
        set(v) = sp.edit().putBoolean("live_tiles", v).apply()
    var hiddenFolders: Set<String>
        get() = sp.getStringSet("hidden_folders", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden_folders", v).apply()
    fun lyricsFor(id: String): String? = sp.getString("lrc_$id", null)
    fun setLyricsFor(id: String, uri: String) = sp.edit().putString("lrc_$id", uri).apply()
}

/** Library as the user sees it: hidden folders removed and, when [dedupe] is on, one copy per song
 *  (same title + artist and length within a few seconds) — the best-quality one is kept. */
fun visibleSongs(all: List<Song>, hidden: Set<String>, dedupe: Boolean): List<Song> {
    val list = if (hidden.isEmpty()) all else all.filter { it.folder !in hidden }
    if (!dedupe) return list
    val keep = ArrayList<Song>(list.size)
    list.groupBy { ArtistRepository.norm(it.title) + "|" + ArtistRepository.norm(ArtistRepository.primary(it.artist)) }
        .forEach { (_, group) ->
            if (group.size == 1) { keep += group[0]; return@forEach }
            // split by length so a live/extended version with the same name isn't hidden
            val clusters = ArrayList<MutableList<Song>>()
            for (s in group.sortedBy { it.duration }) {
                val c = clusters.lastOrNull()
                if (c != null && s.duration - c.first().duration <= 5000) c += s else clusters += mutableListOf(s)
            }
            clusters.forEach { c -> keep += c.maxBy { quality(it) } }
        }
    val ids = keep.map { it.id }.toHashSet()
    return list.filter { it.id in ids } // keep original order
}

/** lossless first, then the highest bitrate (file size ÷ length) */
fun quality(s: Song): Double {
    val m = (s.mime.orEmpty() + " " + s.displayName.substringAfterLast('.', "")).lowercase()
    val tier = when {
        listOf("flac", "wav", "alac", "ape", "aiff", "dsd", "dsf").any { m.contains(it) } -> 3
        listOf("opus", "ogg", "vorbis", "m4a", "aac", "mp4").any { m.contains(it) } -> 2
        else -> 1
    }
    val size = runCatching { s.path?.let { java.io.File(it).length() } ?: 0L }.getOrDefault(0L)
    val kbps = if (s.duration > 0) size * 8.0 / s.duration else 0.0
    return tier * 1_000_000.0 + kbps
}
