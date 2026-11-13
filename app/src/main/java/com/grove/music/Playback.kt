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
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Background playback (ExoPlayer decodes MP3, AAC, OGG, OPUS, WAV and FLAC incl. 24-bit hi-res). */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
                    mediaItems.map { item ->
                        item.requestMetadata.mediaUri?.let { item.buildUpon().setUri(it).build() } ?: item
                    }.toMutableList()
                )
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
        val next = ArrayList<MediaItem>()
        if (!t.isEmpty && p.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = p.currentMediaItemIndex
            while (next.size < 5) {
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                if (i == C.INDEX_UNSET) break
                next += p.getMediaItemAt(i)
            }
        }
        upNext.value = next
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
