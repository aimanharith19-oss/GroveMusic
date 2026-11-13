package com.grove.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Background playback (ExoPlayer decodes MP3, AAC, OGG, OPUS, WAV and FLAC incl. 24-bit hi-res).
 * Runs as a MediaLibraryService so Android treats Grove as a real music app: system media controls,
 * lock screen, Bluetooth / headset buttons, playback resumption after reboot, Android Auto and other
 * media browsers can all see and control it.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var library: List<Song>? = null

    private fun songs(): List<Song> = library ?: runCatching { MusicRepository.query(this) }.getOrDefault(emptyList())
        .let { val p = Prefs(this); visibleSongs(it, p.hiddenFolders, p.preferFlac) }.also { library = it }

    private fun byId() = songs().associateBy { it.id.toString() }

    // ── browse tree for Android Auto & other media browsers ────────────────
    // root → playlists / albums / artists / songs
    // playable ids are "<parent>::<songId>" so tapping a song plays its whole list from there

    private fun folder(id: String, title: String, art: Uri? = null, sub: String? = null, grid: Boolean = false) =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(sub).setArtworkUri(art)
                .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle().apply {
                    putInt(STYLE_BROWSABLE, if (grid) 2 else 1); putInt(STYLE_PLAYABLE, 1)
                }).build()
        ).build()

    private fun playable(parent: String, s: Song): MediaItem {
        val base = s.toMediaItem()
        return base.buildUpon().setMediaId("$parent::${s.id}")
            .setMediaMetadata(base.mediaMetadata.buildUpon().setArtworkUri(ArtProvider.album(s.albumId)).build()).build()
    }

    private fun artistKey(s: Song) = ArtistRepository.key(ArtistRepository.primary(s.artist))

    /** songs belonging to a browse folder, in play order */
    private fun songsOf(parent: String): List<Song> {
        val all = songs()
        return when {
            parent == "songs" -> all.sortedBy { it.title.lowercase() }
            parent.startsWith("al:") -> all.filter { it.albumId.toString() == parent.drop(3) }.sortedWith(compareBy({ it.track }, { it.title }))
            parent.startsWith("ar:") -> parent.drop(3).let { k ->
                all.filter { s -> ArtistRepository.split(s.artist).any { ArtistRepository.key(it) == k } }
                    .sortedWith(compareBy({ it.album.lowercase() }, { it.track }))
            }
            parent.startsWith("pl:") -> PlaylistStore(this).get(parent.drop(3))?.ids?.let { ids ->
                val m = byId(); ids.mapNotNull { m[it.toString()] }
            } ?: emptyList()
            parent == "search" -> lastSearch
            else -> emptyList()
        }
    }

    private fun children(parent: String): List<MediaItem> = when (parent) {
        ROOT -> listOf(
            folder("playlists", "Playlists"), folder("albums", "Albums", grid = true),
            folder("artists", "Artists", grid = true), folder("songs", "Songs"),
        )
        "playlists" -> PlaylistStore(this).lists.value.map { p ->
            folder("pl:${p.id}", p.name, sub = "${p.ids.size} songs",
                art = p.ids.firstNotNullOfOrNull { byId()[it.toString()] }?.let { ArtProvider.album(it.albumId) })
        }
        "albums" -> songs().groupBy { it.albumId }.values.sortedBy { it[0].album.lowercase() }.map { l ->
            folder("al:${l[0].albumId}", l[0].album, ArtProvider.album(l[0].albumId), l[0].artist, grid = true)
        }
        "artists" -> songs().groupBy { artistKey(it) }.map { (k, l) ->
            val name = l.map { ArtistRepository.primary(it.artist) }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val img = ArtistRepository.cached(this, name)?.image
            folder("ar:$k", name, img?.let { ArtProvider.url(it) } ?: ArtProvider.album(l[0].albumId), "${l.size} songs", grid = true)
        }.sortedBy { it.mediaMetadata.title.toString().lowercase() }
        else -> songsOf(parent).map { playable(parent, it) }
    }

    // ── live lyrics in Android Auto ─────────────────────────────────────────
    // The car can't show custom screens, so while Auto is connected the line being sung
    // replaces the artist line under the song title (restored when the car disconnects).
    private val scope = kotlinx.coroutines.MainScope()
    private var cars = 0
    private var lyricJob: kotlinx.coroutines.Job? = null

    private fun isCar(c: MediaSession.ControllerInfo) =
        c.packageName.contains("gearhead") || c.packageName.contains("android.car") || c.packageName.contains("automotive")

    private fun startCarLyrics(player: Player) {
        if (lyricJob?.isActive == true || !Prefs(this).carLyrics) return
        lyricJob = scope.launch {
            var forId: String? = null
            var lyrics: Lyrics? = null
            var shown: String? = null
            while (true) {
                val item = player.currentMediaItem
                if (item != null) {
                    if (item.mediaId != forId) {
                        forId = item.mediaId; shown = null
                        val song = byId()[item.mediaId]
                        lyrics = LyricsRepository.load(this@PlaybackService, trackKey(item, song, player.duration.coerceAtLeast(0)))
                    }
                    val l = lyrics
                    val line = if (l != null && l.synced) {
                        val i = l.lines.indexOfLast { it.time <= player.currentPosition + 200 }
                        l.lines.getOrNull(i)?.text?.takeIf { it.isNotBlank() }?.let { "♪ $it" }
                    } else null
                    val artist = byId()[item.mediaId]?.artist ?: item.mediaMetadata.artist?.toString()
                    val want = line ?: artist
                    if (want != shown && want != null) {
                        shown = want
                        setArtistLine(player, want)
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun stopCarLyrics(player: Player) {
        lyricJob?.cancel(); lyricJob = null
        val item = player.currentMediaItem ?: return
        byId()[item.mediaId]?.artist?.let { setArtistLine(player, it) }
    }

    private fun setArtistLine(player: Player, text: String) {
        val idx = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artist?.toString() == text) return
        player.replaceMediaItem(idx, item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(text).build()).build())
    }

    private var lastSearch: List<Song> = emptyList()
    private fun search(q: String): List<Song> {
        val t = q.trim().lowercase()
        lastSearch = if (t.isEmpty()) songs().shuffled() else songs().filter {
            it.title.lowercase().contains(t) || it.artist.lowercase().contains(t) || it.album.lowercase().contains(t)
        }
        return lastSearch
    }

    /** turns browse ids ("albums::123") back into playable items */
    private fun resolve(items: List<MediaItem>): MutableList<MediaItem> {
        val m by lazy { byId() }
        return items.mapNotNull { item ->
            when {
                item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                item.localConfiguration != null -> item
                item.requestMetadata.searchQuery != null -> search(item.requestMetadata.searchQuery!!).firstOrNull()?.toMediaItem()
                else -> m[item.mediaId.substringAfterLast("::")]?.toMediaItem()
            }
        }.toMutableList()
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val prefs = Prefs(this)
        // remember the queue so Android can offer "resume" in the media controls later
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                prefs.saveQueue(ids, player.currentMediaItemIndex, 0)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying && player.mediaItemCount > 0) prefs.saveQueuePosition(player.currentMediaItemIndex, player.currentPosition)
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val root = MediaItem.Builder().setMediaId(ROOT).setMediaMetadata(
            MediaMetadata.Builder().setTitle("Grove Music").setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(s: MediaSession, c: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (isCar(c)) { cars++; startCarLyrics(s.player) }
                return super.onConnect(s, c)
            }

            override fun onDisconnected(s: MediaSession, c: MediaSession.ControllerInfo) {
                if (isCar(c)) { cars = (cars - 1).coerceAtLeast(0); if (cars == 0) stopCarLyrics(s.player) }
            }

            override fun onGetLibraryRoot(s: MediaLibrarySession, b: MediaSession.ControllerInfo, params: LibraryParams?)
                : ListenableFuture<LibraryResult<MediaItem>> {
                library = null // pick up new songs / playlists
                val extras = Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putInt(STYLE_BROWSABLE, 1); putInt(STYLE_PLAYABLE, 1)
                }
                return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
            }

            override fun onGetChildren(s: MediaLibrarySession, b: MediaSession.ControllerInfo, parentId: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val all = runCatching { children(parentId) }.getOrDefault(emptyList())
                val list = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            }

            override fun onGetItem(s: MediaLibrarySession, b: MediaSession.ControllerInfo, mediaId: String)
                : ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(
                    byId()[mediaId.substringAfterLast("::")]?.let { LibraryResult.ofItem(it.toMediaItem(), null) }
                        ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )

            override fun onSearch(s: MediaLibrarySession, b: MediaSession.ControllerInfo, query: String, params: LibraryParams?)
                : ListenableFuture<LibraryResult<Void>> {
                val n = search(query).size
                s.notifySearchResultChanged(b, query, n, params)
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(s: MediaLibrarySession, b: MediaSession.ControllerInfo, query: String,
                                           page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val list = search(query).drop(page * pageSize).take(pageSize).map { playable("search", it) }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            }

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(resolve(mediaItems))

            // tapping one song in a car list plays that whole list, starting at the tapped song
            override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val one = mediaItems.singleOrNull()
                if (one != null && one.mediaId.contains("::")) {
                    val parent = one.mediaId.substringBeforeLast("::")
                    val id = one.mediaId.substringAfterLast("::")
                    val list = songsOf(parent)
                    val idx = list.indexOfFirst { it.id.toString() == id }
                    if (idx >= 0) return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, idx, startPositionMs))
                }
                if (one != null && one.requestMetadata.searchQuery != null) {
                    val list = search(one.requestMetadata.searchQuery!!)
                    if (list.isNotEmpty()) return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(list.map { it.toMediaItem() }, 0, 0))
                }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(resolve(mediaItems),
                    if (startIndex == C.INDEX_UNSET) 0 else startIndex, startPositionMs))
            }

            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val (ids, index, pos) = prefs.loadQueue()
                val m = byId()
                val items = ids.mapNotNull { m[it]?.toMediaItem() }
                val start = if (items.isEmpty()) 0 else index.coerceIn(0, items.lastIndex)
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, start, pos))
            }
        }).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    companion object {
        const val ROOT = "root"
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    }
}

/** UI-side handle to the playback service, exposing Compose state. */
class PlayerConnection(private val ctx: Context) {
    val current = mutableStateOf<MediaItem?>(null)
    val isPlaying = mutableStateOf(false)
    val shuffle = mutableStateOf(false)
    val repeatMode = mutableIntStateOf(Player.REPEAT_MODE_OFF)
    val upNext = mutableStateOf<List<MediaItem>>(emptyList())
    /** upcoming songs in play order, with their position in the player's list */
    val queue = mutableStateOf<List<Pair<Int, MediaItem>>>(emptyList())

    private var controller: MediaController? = null
    private val pending = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    private val future = MediaController.Builder(
        ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
    ).buildAsync()

    init {
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(listener)
            sync(c)
            pending.forEach { it(c) }
            pending.clear()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun run(block: (MediaController) -> Unit) {
        controller?.let(block) ?: pending.add(block)
    }

    private fun sync(p: Player) {
        current.value = p.currentMediaItem
        isPlaying.value = p.isPlaying
        shuffle.value = p.shuffleModeEnabled
        repeatMode.intValue = p.repeatMode
        val t = p.currentTimeline
        val next = ArrayList<Pair<Int, MediaItem>>()
        if (!t.isEmpty && p.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = p.currentMediaItemIndex
            while (next.size < 500) {
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) break
                next += i to p.getMediaItemAt(i)
            }
        }
        queue.value = next
        upNext.value = next.take(5).map { it.second }
    }

    fun playSongs(songs: List<Song>, start: Int, shuffled: Boolean = false) = run {
        if (songs.isEmpty()) return@run
        it.shuffleModeEnabled = shuffled
        it.setMediaItems(songs.map { s -> s.toMediaItem() }, if (shuffled) songs.indices.random() else start, 0)
        it.prepare(); it.play()
    }

    fun playUri(uri: Uri) {
        var name = uri.lastPathSegment ?: "track"
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        }
        val item = MediaItem.Builder().setMediaId(uri.toString()).setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(name.substringBeforeLast('.'))
                    .setExtras(android.os.Bundle().apply {
                        putString("name", name); putString("fmt", formatOf(null, name))
                    }).build()
            ).build()
        run { it.setMediaItem(item); it.prepare(); it.play() }
    }

    fun playAt(index: Int) = run { it.seekTo(index, 0); it.play() }
    fun removeAt(index: Int) = run { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) }
    fun playPause() = run { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun next() = run { it.seekToNext() }
    fun prev() = run { it.seekToPrevious() }
    fun seekTo(ms: Long) = run { it.seekTo(ms.coerceAtLeast(0)) }
    fun toggleShuffle() = run { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    fun cycleRepeat() = run {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun position(): Long = controller?.currentPosition ?: 0L
    fun duration(): Long = controller?.duration?.takeIf { it > 0 } ?: 0L

    fun release() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(future)
    }
}
