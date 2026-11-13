package com.grove.music

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Playlist(val id: String, val name: String, val ids: List<Long>)

/** Playlists (incl. the built-in "liked songs") saved on the phone. */
class PlaylistStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("playlists", Context.MODE_PRIVATE)
    val lists = mutableStateOf(load())

    val liked: Playlist get() = lists.value.first { it.id == LIKED }
    fun get(id: String) = lists.value.firstOrNull { it.id == id }
    fun isLiked(songId: Long) = songId in liked.ids

    fun toggleLike(songId: Long): Boolean {
        val now = !isLiked(songId)
        update(LIKED) { if (now) listOf(songId) + it.ids else it.ids - songId }
        return now
    }

    fun create(name: String, songs: List<Long> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "new playlist" }, songs.distinct())
        save(lists.value + p); return p
    }
    fun add(id: String, songs: List<Long>) = update(id) { it.ids + songs.filter { s -> s !in it.ids } }
    fun remove(id: String, songId: Long) = update(id) { it.ids - songId }
    fun rename(id: String, name: String) { if (id != LIKED) save(lists.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }) }
    fun delete(id: String) { if (id != LIKED) save(lists.value.filter { it.id != id }) }

    private fun update(id: String, f: (Playlist) -> List<Long>) = save(lists.value.map { if (it.id == id) it.copy(ids = f(it)) else it })

    private fun save(l: List<Playlist>) {
        lists.value = l
        sp.edit().putString("all", JSONArray().apply {
            l.forEach { p -> put(JSONObject().put("id", p.id).put("n", p.name).put("s", JSONArray(p.ids))) }
        }.toString()).apply()
    }

    private fun load(): List<Playlist> {
        val l = runCatching {
            val a = JSONArray(sp.getString("all", "[]"))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val s = o.getJSONArray("s")
                Playlist(o.getString("id"), o.getString("n"), (0 until s.length()).map { s.getLong(it) })
            }
        }.getOrDefault(emptyList())
        return if (l.any { it.id == LIKED }) l else listOf(Playlist(LIKED, "liked songs", emptyList())) + l
    }

    companion object { const val LIKED = "liked" }
}

/** Opens the "add to playlist" sheet from anywhere in the UI. */
val LocalAddToPlaylist = staticCompositionLocalOf<(List<Long>) -> Unit> { {} }
