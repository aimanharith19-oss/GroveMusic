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
    val songs = mutableStateOf<List<Song>>(emptyList())
    /** every folder that contains music, with its song count (hidden ones included) */
    val folders = mutableStateOf<List<Pair<String, Int>>>(emptyList())
    val hidden = mutableStateOf(prefs.hiddenFolders)
    val loading = mutableStateOf(false)
    private var all: List<Song> = emptyList()
    private var byId: Map<String, Song> = emptyMap()

    fun song(id: String?): Song? = id?.let { byId[it] }

    fun load() = viewModelScope.launch {
        loading.value = true
        all = withContext(Dispatchers.IO) { runCatching { MusicRepository.query(getApplication()) }.getOrDefault(emptyList()) }
        byId = all.associateBy { it.id.toString() }
        folders.value = all.groupingBy { it.folder }.eachCount().toList().sortedBy { it.first.lowercase() }
        apply()
        loading.value = false
    }

    fun setHidden(folder: String, hide: Boolean) {
        val h = if (hide) hidden.value + folder else hidden.value - folder
        hidden.value = h; prefs.hiddenFolders = h; apply()
    }

    private fun apply() {
        val h = hidden.value
        songs.value = if (h.isEmpty()) all else all.filter { it.folder !in h }
    }
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
    var liveTiles: Boolean
        get() = sp.getBoolean("live_tiles", false)
        set(v) = sp.edit().putBoolean("live_tiles", v).apply()
    var hiddenFolders: Set<String>
        get() = sp.getStringSet("hidden_folders", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden_folders", v).apply()
    fun lyricsFor(id: String): String? = sp.getString("lrc_$id", null)
    fun setLyricsFor(id: String, uri: String) = sp.edit().putString("lrc_$id", uri).apply()
}
