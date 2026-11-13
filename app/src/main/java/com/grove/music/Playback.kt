package com.grove.music

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
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
        .let { all -> val h = Prefs(this).hiddenFolders; all.filter { it.folder !in h } }.also { library = it }

    private fun resolve(items: List<MediaItem>): MutableList<MediaItem> {
        val byId by lazy { songs().associateBy { it.id.toString() } }
        return items.mapNotNull { item ->
            when {
                item.requestMetadata.mediaUri != null -> item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
                item.localConfiguration != null -> item
                else -> byId[item.mediaId]?.toMediaItem()
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
        val root = MediaItem.Builder().setMediaId("root").setMediaMetadata(
            MediaMetadata.Builder().setTitle("Grove Music").setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()
        val songsFolder = MediaItem.Builder().setMediaId("songs").setMediaMetadata(
            MediaMetadata.Builder().setTitle("All songs").setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
        ).build()

        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onGetLibraryRoot(s: MediaLibrarySession, b: MediaSession.ControllerInfo, params: LibraryParams?) =
                Futures.immediateFuture(LibraryResult.ofItem(root, params))

            override fun onGetChildren(s: MediaLibrarySession, b: MediaSession.ControllerInfo, parentId: String,
                                       page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val list: List<MediaItem> = when (parentId) {
                    "root" -> listOf(songsFolder)
                    "songs" -> songs().drop(page * pageSize).take(pageSize).map { it.toMediaItem() }
                    else -> emptyList()
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list), params))
            }

            override fun onGetItem(s: MediaLibrarySession, b: MediaSession.ControllerInfo, mediaId: String) =
                Futures.immediateFuture(
                    songs().firstOrNull { it.id.toString() == mediaId }?.let { LibraryResult.ofItem(it.toMediaItem(), null) }
                        ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                )

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
                                         mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(resolve(mediaItems))

            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo)
                : ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val (ids, index, pos) = prefs.loadQueue()
                val byId = songs().associateBy { it.id.toString() }
                val items = ids.mapNotNull { byId[it]?.toMediaItem() }
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
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
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
