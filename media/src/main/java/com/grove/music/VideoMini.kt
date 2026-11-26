package com.grove.music

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.roundToInt

/**
 * YouTube-style mini player: swiping the video player down hands the running player (VLC or the
 * hardware engine) over to here, so it keeps playing in a small window on top of GroveMedia.
 * Tap it to go back to full screen, ✕ to stop.
 */
object VideoMini {
    class Held(
        val lib: LibVLC, val vlc: MediaPlayer, val exo: androidx.media3.exoplayer.ExoPlayer?,
        val title: String, val videoId: Long, val src: Uri?, val w: Int, val h: Int,
        val pfd: android.os.ParcelFileDescriptor?,
    )
    val held = mutableStateOf<Held?>(null)

    fun hold(h: Held) {
        held.value = h
        // when it ends in the mini window, just close it
        runCatching { h.vlc.setEventListener { e -> if (e.type == MediaPlayer.Event.EndReached) android.os.Handler(android.os.Looper.getMainLooper()).post { held.value?.let { if (it === h) release(null) } } } }
    }

    /** taken back by the full-screen player (nothing is released) */
    fun take(): Held? = held.value?.also { runCatching { it.vlc.detachViews() }; held.value = null }

    fun playing(h: Held) = runCatching { h.exo?.isPlaying ?: h.vlc.isPlaying }.getOrDefault(false)
    fun toggle(h: Held) = runCatching {
        val e = h.exo
        if (e != null) { if (e.isPlaying) e.pause() else e.play() } else { if (h.vlc.isPlaying) h.vlc.pause() else h.vlc.play() }
    }

    /** stop and free the player, remembering where you were */
    fun release(ctx: Context?) {
        val h = held.value ?: return
        held.value = null
        runCatching {
            val t = h.exo?.currentPosition ?: h.vlc.time
            val len = h.exo?.duration?.takeIf { it > 0 } ?: h.vlc.length
            if (ctx != null && h.videoId != 0L) ctx.getSharedPreferences("video_pos", Context.MODE_PRIVATE).edit().apply {
                if (len > 0 && t > len - 30_000) remove(h.videoId.toString()) else putLong(h.videoId.toString(), t)
            }.apply()
        }
        runCatching { h.exo?.release() }
        runCatching { h.vlc.stop(); h.vlc.detachViews(); h.vlc.release(); h.lib.release() }
        runCatching { h.pfd?.close() }
    }
}

/** Floating mini video window over every GroveMedia screen; drag it anywhere. */
@Composable
fun VideoMiniOverlay(accent: Color, modifier: Modifier = Modifier) {
    val h = VideoMini.held.value ?: return
    val ctx = LocalContext.current
    var playing by remember(h) { mutableStateOf(true) }
    LaunchedEffect(h) { while (true) { playing = VideoMini.playing(h); delay(400) } }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    val ratio = (h.w.toFloat() / h.h.coerceAtLeast(1)).coerceIn(0.6f, 2.4f)
    Column(modifier.offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }.width(200.dp)
        .clip(RoundedCornerShape(10.dp)).background(Color(0xFF111111))
        .pointerInput(h) { detectDragGestures { ch, d -> ch.consume(); ox += d.x; oy += d.y } }) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black)
            .pointerInput(h) { detectTapGestures { expand(ctx, h) } }) {
            if (h.exo != null) AndroidView({ c -> androidx.media3.ui.PlayerView(c).apply { useController = false; player = h.exo } },
                Modifier.fillMaxSize(), onRelease = { it.player = null })
            else AndroidView({ c -> VLCVideoLayout(c).also {
                    runCatching { h.vlc.attachViews(it, null, false, false) }
                    // redraw the picture on this new (small) surface
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (VideoMini.held.value === h) runCatching { h.vlc.setVideoTrackEnabled(false); h.vlc.setVideoTrackEnabled(true) }
                    }, 250)
                } },
                Modifier.fillMaxSize(), onRelease = { if (VideoMini.held.value === h) runCatching { h.vlc.detachViews() } })
        }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(h.title.lowercase(), color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).pointerInput(h) { detectTapGestures { expand(ctx, h) } })
            Box(Modifier.size(38.dp).pointerInput(h) { detectTapGestures { VideoMini.toggle(h); playing = !playing } }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Box(Modifier.size(38.dp).pointerInput(h) { detectTapGestures { VideoMini.release(ctx) } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
    }
}

private fun expand(ctx: Context, h: VideoMini.Held) {
    ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("fromMini", true).putExtra("id", h.videoId)
        .putExtra("title", h.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Frames for the seek preview (like YouTube's thumbnails over the bar). */
class FramePreview(private val ctx: Context, private val uri: Uri) {
    private var r: MediaMetadataRetriever? = null
    private val lock = Mutex()
    private val cache = object : LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, Bitmap>?) = size > 40
    }

    suspend fun at(ms: Long): Bitmap? = lock.withLock {
        val key = ms / 2000 // one frame every 2 seconds is plenty while scrubbing
        cache[key]?.let { return@withLock it }
        runCatching {
            val rr = r ?: MediaMetadataRetriever().also { it.setDataSource(ctx, uri); r = it }
            val us = key * 2_000_000L
            (if (android.os.Build.VERSION.SDK_INT >= 27) rr.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            else rr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
        }.getOrNull()?.also { cache[key] = it }
    }

    fun close() { runCatching { r?.release() }; r = null }
}
