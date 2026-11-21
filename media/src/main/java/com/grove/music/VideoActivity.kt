package com.grove.music

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Full-screen video player on VLC (plays almost any format) with picture-in-picture. */
class VideoActivity : ComponentActivity() {
    private lateinit var lib: LibVLC
    private lateinit var player: MediaPlayer
    private var pfd: ParcelFileDescriptor? = null
    private var videoId = 0L
    private var vw = 16; private var vh = 9
    private val inPip = mutableStateOf(false)

    // ── shared with the notification service ──
    private var session: android.media.session.MediaSession? = null
    var thumb: android.graphics.Bitmap? = null
    fun titleText() = title.value
    fun isPlaying() = ::player.isInitialized && player.isPlaying
    fun sessionToken() = session?.sessionToken
    fun toggle() { if (player.isPlaying) player.pause() else player.play(); refresh() }
    fun skip(ms: Long) { player.time = (player.time + ms).coerceAtLeast(0); refresh() }

    /** audio only: keep the sound going in the background with no picture */
    private val audioOnly = mutableStateOf(false)

    private fun refresh() {
        val st = android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SEEK_TO or
                android.media.session.PlaybackState.ACTION_FAST_FORWARD or android.media.session.PlaybackState.ACTION_REWIND or
                android.media.session.PlaybackState.ACTION_STOP)
            .setState(if (player.isPlaying) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                player.time, if (player.isPlaying) 1f else 0f).build()
        session?.setPlaybackState(st)
        session?.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title.value)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Grove Media")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, player.length)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, thumb).build())
        VideoService.update(this)
        pipActions()
    }

    // picture-in-picture buttons: play/pause and "audio only"
    private val pipReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            when (i.action) {
                VideoService.ACT_TOGGLE -> toggle()
                ACT_AUDIO -> goAudioOnly()
            }
        }
    }

    private fun pipActions() {
        if (Build.VERSION.SDK_INT < 26) return
        fun ra(icon: Int, t: String, action: String) = android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, icon), t, t,
            android.app.PendingIntent.getBroadcast(this, action.hashCode(), Intent(action).setPackage(packageName), android.app.PendingIntent.FLAG_IMMUTABLE))
        val b = PictureInPictureParams.Builder().setActions(listOf(
            ra(if (player.isPlaying) R.drawable.ic_n_pause else R.drawable.ic_n_play, "Play/pause", VideoService.ACT_TOGGLE),
            ra(R.drawable.ic_n_headphones, "Audio only", ACT_AUDIO)))
        runCatching { setPictureInPictureParams(b.build()) }
    }

    /** close the picture, keep listening (controls stay in the notification) */
    private fun goAudioOnly() {
        audioOnly.value = true
        refresh()
        moveTaskToBack(true)
    }

    companion object {
        const val ACT_AUDIO = "grove.video.AUDIO"
        var current: VideoActivity? = null
        /** play anything VLC can open from the internet: http(s) files, HLS (.m3u8), DASH, RTSP, RTMP, IPTV… */
        fun openUrl(ctx: Context, url: String) = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra("url", url).putExtra("title", Uri.parse(url).let { (it.lastPathSegment ?: it.host ?: url) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun open(ctx: Context, v: Video, title: String) = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra("id", v.id).putExtra("title", title).putExtra("w", v.w).putExtra("h", v.h)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // true full screen: draw under the camera cut-out, hide status & navigation bars (swipe from an edge to peek)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        current = this
        lib = LibVLC(this, arrayListOf("--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(lib)
        session = android.media.session.MediaSession(this, "GroveVideo").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { player.play(); refresh() }
                override fun onPause() { player.pause(); refresh() }
                override fun onSeekTo(pos: Long) { player.time = pos; refresh() }
                override fun onFastForward() = skip(10_000)
                override fun onRewind() = skip(-10_000)
                override fun onStop() { finish() }
            })
            isActive = true
        }
        player.setEventListener { e ->
            if (e.type == MediaPlayer.Event.Playing || e.type == MediaPlayer.Event.Paused) refresh()
            if (e.type == MediaPlayer.Event.EndReached) refresh()
        }
        val f = android.content.IntentFilter().apply { addAction(VideoService.ACT_TOGGLE); addAction(ACT_AUDIO) }
        registerReceiver(pipReceiver, f, RECEIVER_NOT_EXPORTED)
        load(intent)
        setContent { Screen() }
        pipParams(true)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); load(intent) }

    private fun load(i: Intent) {
        savePos()
        videoId = i.getLongExtra("id", 0)
        vw = i.getIntExtra("w", 16).coerceAtLeast(1); vh = i.getIntExtra("h", 9).coerceAtLeast(1)
        title.value = i.getStringExtra("title").orEmpty()
        // opened from a file manager / another app
        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            title.value = runCatching { contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()?.substringBeforeLast('.') ?: (u.lastPathSegment ?: "video")
            val m = if (u.scheme == "content") {
                pfd?.close(); pfd = contentResolver.openFileDescriptor(u, "r") ?: return
                Media(lib, pfd!!.fileDescriptor)
            } else Media(lib, u).apply { addOption(":network-caching=1500") }
            player.media = m; m.release()
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            player.play(); return
        }
        i.getStringExtra("url")?.let { url ->
            val m = Media(lib, Uri.parse(url)).apply { addOption(":network-caching=1500"); setHWDecoderEnabled(true, false) }
            player.media = m; m.release()
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            player.play(); thumb = null
            return
        }
        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        pfd?.close()
        pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
        val m = Media(lib, pfd!!.fileDescriptor)
        player.media = m; m.release()
        // pause the music player
        @Suppress("DEPRECATION")
        (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        player.play()
        thumb = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(512, 288), null) }.getOrNull()
        val resume = getSharedPreferences("video_pos", MODE_PRIVATE).getLong(videoId.toString(), 0)
        if (resume > 5000) player.time = resume
    }

    private val title = mutableStateOf("")

    private var watched = 0L
    private var watchStart = System.currentTimeMillis()

    private fun savePos() {
        if (videoId == 0L || !::player.isInitialized) return
        StatsLog.recordWatch(this, watchStart, videoId, watched); watched = 0; watchStart = System.currentTimeMillis()
        val t = player.time; val len = player.length
        getSharedPreferences("video_pos", MODE_PRIVATE).edit().apply {
            if (len > 0 && t > len - 30_000) remove(videoId.toString()) else putLong(videoId.toString(), t)
        }.apply()
    }

    private fun pipParams(auto: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val r = (vw.toFloat() / vh).coerceIn(0.42f, 2.38f)
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000))
        // swiping home with the gesture bar drops the video into picture-in-picture
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(auto && !audioOnly.value).setSeamlessResizeEnabled(true)
        return b.build().also { setPictureInPictureParams(it) }
    }

    /** PiP button: shrink the video and go back to Grove's own home screen (not the phone's) */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        pipParams(true)?.let { enterPictureInPictureMode(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (audioOnly.value) return
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying) pipParams(true)?.let { enterPictureInPictureMode(it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // the PiP window was closed with its ✕ → stop the video (unless it switched to audio only)
        if (!isInPictureInPictureMode && !audioOnly.value &&
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) { player.stop(); finish() }
    }

    override fun onResume() { super.onResume(); if (audioOnly.value) { audioOnly.value = false; pipParams(true) } }

    override fun onStop() {
        super.onStop()
        savePos()
        if (!inPip.value && !audioOnly.value && !isFinishing) player.pause()
    }

    override fun onDestroy() {
        savePos()
        runCatching { unregisterReceiver(pipReceiver) }
        VideoService.stop(this); session?.release(); if (current === this) current = null
        player.stop(); player.detachViews(); player.release(); lib.release(); pfd?.close()
        super.onDestroy()
    }

    @Composable
    private fun Screen() {
        var show by remember { mutableStateOf(true) }
        var playing by remember { mutableStateOf(true) }
        var pos by remember { mutableLongStateOf(0L) }
        var len by remember { mutableLongStateOf(0L) }
        var drag by remember { mutableStateOf<Float?>(null) }
        var fill by remember { mutableStateOf(false) }
        val pip by inPip
        LaunchedEffect(Unit) { while (true) { pos = player.time; len = player.length; playing = player.isPlaying; if (playing) watched += 250; delay(250) } }
        LaunchedEffect(show, playing) { if (show && playing) { delay(3500); show = false } }
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                player.time = (player.time + if (o.x > size.width / 2) 10_000 else -10_000).coerceAtLeast(0)
            })
        }) {
            AndroidView({ c -> VLCVideoLayout(c).also { player.attachViews(it, null, true, false) } }, Modifier.fillMaxSize())
            // pinch out = zoom to fill the screen, pinch in = whole picture (like the YouTube app)
            var zoomMsg by remember { mutableStateOf<String?>(null) }
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var scale = 1f
                    do {
                        val ev = awaitPointerEvent()
                        if (ev.changes.count { it.pressed } >= 2) {
                            scale *= ev.calculateZoom()
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale > 1.15f && !fill) { fill = true; zoomMsg = "zoomed to fill" }
                    else if (scale < 0.87f && fill) { fill = false; zoomMsg = "original" }
                }
            })
            LaunchedEffect(zoomMsg) { if (zoomMsg != null) { delay(1200); zoomMsg = null } }
            zoomMsg?.let { Text(it, color = Color.White, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 8.dp)) }
            LaunchedEffect(fill) { player.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN else MediaPlayer.ScaleType.SURFACE_BEST_FIT }
            val accent = Color(Prefs(this@VideoActivity).accent)
            val posS = remember { mutableLongStateOf(0L) }; val durS = remember { mutableLongStateOf(0L) }
            LaunchedEffect(pos, len) { posS.longValue = pos; durS.longValue = len }
            AnimatedVisibility(show && !pip, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.75f), 0.3f to Color.Transparent, 0.62f to Color.Transparent,
                    1f to androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.6f).copy(alpha = 0.92f)))) {
                    // header: Grove style — small caps label, big light lowercase title
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).statusBarsPadding()
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                        Btn(Icons.AutoMirrored.Rounded.ArrowBack, outline = false) { finish() }
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            Text("NOW PLAYING · VIDEO", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.sp)
                            Text(title.value.lowercase(), color = Color.White, fontSize = 26.sp, lineHeight = 30.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Light, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)
                        .navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                        SeekBar(posS, durS, accent) { player.time = it }
                        TimeRow(posS, durS)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Btn(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen, 44, outline = false) { fill = !fill }
                            Btn(Icons.Rounded.Replay10, 56) { player.time = (player.time - 10_000).coerceAtLeast(0) }
                            // accent play button with a soft halo, like the music player
                            Box(Modifier.size(80.dp).pointerInput(Unit) { detectTapGestures { toggle() } }, contentAlignment = Alignment.Center) {
                                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
                                Box(Modifier.size(66.dp).clip(CircleShape).background(accent)
                                    .then(Modifier.border(2.dp, Color.White, CircleShape)), contentAlignment = Alignment.Center) {
                                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                                }
                            }
                            Btn(Icons.Rounded.Forward10, 56) { player.time = player.time + 10_000 }
                            Row {
                                Btn(Icons.Rounded.Headphones, 44, outline = false) { goAudioOnly() }
                                Btn(Icons.Rounded.PictureInPictureAlt, 44, outline = false) { enterPip() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun t(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    @Composable
    private fun Btn(icon: ImageVector, size: Int = 44, outline: Boolean = true, onClick: () -> Unit) {
        Box(Modifier.size(size.dp).clip(CircleShape).then(if (outline) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
            .pointerInput(Unit) { detectTapGestures { onClick() } }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}
