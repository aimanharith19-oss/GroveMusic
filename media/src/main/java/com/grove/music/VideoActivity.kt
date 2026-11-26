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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Full-screen video player on VLC (plays almost any format) with picture-in-picture. */
class VideoActivity : ComponentActivity() {
    private lateinit var lib: LibVLC
    private lateinit var player: MediaPlayer
    /** hardware engine (ExoPlayer) for 4K / 8K — same screen and controls, only the decoder changes */
    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private val hw = mutableStateOf(false)
    // once the player is released (closing, or jumping to the next episode) nothing may touch VLC again:
    // a late timer asking "is it playing?" crashed the app ("can't get VLCObject instance")
    @Volatile private var dead = false
    /** the player was handed to the mini window: don't release it when this screen closes */
    private var handedOff = false
    /** this screen took over a player from the mini window */
    private var adopted = false
    private var openJob: kotlinx.coroutines.Job? = null
    /** the file being played (for seek-bar frame previews) */
    private var src: Uri? = null
    private var preview: FramePreview? = null
    private val exoListener = object : androidx.media3.common.Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) { refresh() }
                            override fun onPlaybackStateChanged(state: Int) {
                                if (state == androidx.media3.common.Player.STATE_ENDED) playNextEpisode()
                            }
                            // the hardware engine can't play this file (e.g. DTS / TrueHD audio, odd codec) → VLC takes over
                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { fallbackToVlc() }
                            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                                val audio = tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                if (audio.isNotEmpty() && audio.none { g -> (0 until g.length).any { g.isTrackSupported(it) } }) fallbackToVlc()
                            }
    }
    private val handler by lazy { android.os.Handler(mainLooper) }
    private inline fun <T> vlc(def: T, f: () -> T): T = if (dead) def else runCatching(f).getOrDefault(def)
    private var pTime: Long
        get() = vlc(0L) { exo?.currentPosition ?: player.time }
        set(v) { vlc(Unit) { val e = exo; if (e != null) e.seekTo(v) else player.time = v } }
    private val pLen: Long get() = vlc(0L) { exo?.let { e -> e.duration.takeIf { it > 0 } ?: 0L } ?: player.length }
    private val pPlaying: Boolean get() = vlc(false) { exo?.isPlaying ?: player.isPlaying }
    private fun pPlay() = vlc(Unit) { val e = exo; if (e != null) e.play() else player.play() }
    private fun pPause() = vlc(Unit) { val e = exo; if (e != null) e.pause() else player.pause() }
    private fun pStop() = vlc(Unit) { val e = exo; if (e != null) e.stop() else player.stop() }
    private var pfd: ParcelFileDescriptor? = null
    // a Compose state, so the episode list under the video follows what is playing
    private var videoId by mutableLongStateOf(0L)
    private var vw = 16; private var vh = 9
    private val inPip = mutableStateOf(false)

    // ── shared with the notification service ──
    private var session: android.media.session.MediaSession? = null
    var thumb: android.graphics.Bitmap? = null
    fun titleText() = title.value
    /** what the user asked for; VLC only reports the real state a moment later */
    private var want: Boolean? = null
    private fun playingNow() = want ?: pPlaying
    fun isPlaying() = ::player.isInitialized && playingNow()
    fun sessionToken() = session?.sessionToken
    fun toggle() {
        val play = !playingNow()
        want = play
        if (play) pPlay() else pPause()
        refresh()
        // once VLC has switched, go back to its real state
        handler.postDelayed({ if (pPlaying == play) { want = null }; refresh() }, 700)
    }
    fun skip(ms: Long) { pTime = (pTime + ms).coerceAtLeast(0); refresh() }

    /** audio only: keep the sound going in the background with no picture */
    private val audioOnly = mutableStateOf(false)

    private fun refresh() {
        val st = android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
                android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SEEK_TO or
                android.media.session.PlaybackState.ACTION_FAST_FORWARD or android.media.session.PlaybackState.ACTION_REWIND or
                android.media.session.PlaybackState.ACTION_STOP)
            .setState(if (playingNow()) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED,
                pTime, if (playingNow()) 1f else 0f).build()
        session?.setPlaybackState(st)
        session?.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title.value)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Grove Media")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, pLen)
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
            ra(if (playingNow()) R.drawable.ic_n_pause else R.drawable.ic_n_play, if (playingNow()) "Pause" else "Play", VideoService.ACT_TOGGLE),
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
        /** 4K and bigger go to the hardware ExoPlayer engine (setting: auto / VLC / ExoPlayer) */
        fun open(ctx: Context, v: Video, title: String) {
            val eng = Prefs(ctx).videoEngine
            val hw = eng == "exo" || (eng == "auto" && maxOf(v.w, v.h) >= 3000)
            if (v.id < 0) { ctx.startActivity(Intent(ctx, VideoActivity::class.java).setData(Uri.fromFile(java.io.File(v.path ?: "")))
                .putExtra("title", title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            ctx.startActivity(Intent(ctx, VideoActivity::class.java).putExtra("id", v.id).putExtra("title", title)
                .putExtra("w", v.w).putExtra("h", v.h).putExtra("hw", hw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun openVlc(ctx: Context, v: Video, title: String) = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
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
        // let VLC drop late frames: forcing every frame made 4K 60fps fall behind and show a black picture
        val mini = if (intent.getBooleanExtra("fromMini", false)) VideoMini.take() else null
        if (mini != null) {
            adopted = true
            lib = mini.lib; player = mini.vlc; exo = mini.exo; pfd = mini.pfd
            exo?.addListener(exoListener)
        } else {
            VideoMini.release(this) // a new video replaces whatever was in the mini window
            lib = LibVLC(this, arrayListOf("--audio-time-stretch", "--codec=mediacodec_ndk,mediacodec_jni,all"))
            player = MediaPlayer(lib)
        }
        session = android.media.session.MediaSession(this, "GroveVideo").apply {
            setCallback(object : android.media.session.MediaSession.Callback() {
                override fun onPlay() { if (!playingNow()) toggle() }
                override fun onPause() { if (playingNow()) toggle() }
                override fun onSeekTo(pos: Long) { pTime = pos; refresh() }
                override fun onFastForward() = skip(10_000)
                override fun onRewind() = skip(-10_000)
                override fun onStop() { finish() }
            })
            isActive = true
        }
        player.setEventListener { e ->
            if (e.type == MediaPlayer.Event.Vout) vouts = e.voutCount
            if (e.type == MediaPlayer.Event.Playing && !retried) handler.postDelayed({ blackCheck() }, 2500)
            if (e.type == MediaPlayer.Event.Playing || e.type == MediaPlayer.Event.Paused) refresh()
            if (e.type == MediaPlayer.Event.EndReached) { refresh(); playNextEpisode() }
        }
        val f = android.content.IntentFilter().apply { addAction(VideoService.ACT_TOGGLE); addAction(ACT_AUDIO) }
        registerReceiver(pipReceiver, f, RECEIVER_NOT_EXPORTED)
        if (mini != null) {
            videoId = mini.videoId; title.value = mini.title; vw = mini.w; vh = mini.h; hw.value = mini.exo != null
            src = mini.src; src?.let { preview = FramePreview(this, it) }
            refresh()
        } else load(intent)
        setContent { Screen() }
        pipParams(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("fromMini", false)) return
        VideoMini.release(this); load(intent)
    }

    /** swipe down: keep playing in GroveMedia's mini window (like YouTube) */
    private fun toMini() {
        savePos()
        exo?.removeListener(exoListener)
        runCatching { player.detachViews() }
        VideoMini.hold(VideoMini.Held(lib, player, exo, title.value, videoId, src, vw, vh, pfd))
        pfd = null
        handedOff = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out)
    }

    private fun load(i: Intent) {
        savePos()
        videoId = i.getLongExtra("id", 0)
        vw = i.getIntExtra("w", 16).coerceAtLeast(1); vh = i.getIntExtra("h", 9).coerceAtLeast(1)
        title.value = i.getStringExtra("title").orEmpty()
        if (i.getBooleanExtra("hw", false) && videoId != 0L) {
            if (exo == null) {
                player.stop()
                exo = androidx.media3.exoplayer.ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
                    .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                    .setHandleAudioBecomingNoisy(true).build().also { e -> e.addListener(exoListener) }
            }
            hw.value = true
            val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
            exo!!.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            src = uri; preview?.close(); preview = FramePreview(this, uri)
            val resume = getSharedPreferences("video_pos", MODE_PRIVATE).getLong(videoId.toString(), 0)
            if (resume > 5000) exo!!.seekTo(resume)
            exo!!.prepare(); exo!!.play()
            lifecycleScope.launch { thumb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri, android.util.Size(512, 288), null) }.getOrNull() } }
            return
        }
        exo?.let { it.release(); exo = null }; hw.value = false
        // opened from a file manager / another app
        if (i.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            src = u; preview?.close(); preview = FramePreview(this, u)
            title.value = runCatching { contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()?.substringBeforeLast('.') ?: (u.lastPathSegment ?: "video")
            if (u.scheme == "content") { pfd?.close(); pfd = contentResolver.openFileDescriptor(u, "r") ?: return }
            retried = false; vouts = 0
            mk = { if (u.scheme == "content") Media(lib, pfd!!.fileDescriptor) else Media(lib, u).apply { addOption(":network-caching=1500") } }
            val m = mk!!()
            player.media = m; m.release()
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            pPlay(); return
        }
        i.getStringExtra("url")?.let { url ->
            val m = Media(lib, Uri.parse(url)).apply { addOption(":network-caching=1500"); setHWDecoderEnabled(true, false) }
            player.media = m; m.release()
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            pPlay(); thumb = null
            return
        }
        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        src = uri; preview?.close(); preview = FramePreview(this, uri)
        val id = videoId
        // opening the file asks Android's media storage, which can be slow: never on the main thread (it froze the app)
        openJob?.cancel()
        openJob = lifecycleScope.launch {
            val fd = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull() } ?: return@launch
            if (dead || id != videoId) { fd.close(); return@launch }
            pfd?.close(); pfd = fd
            retried = false; vouts = 0
            mk = { Media(lib, pfd!!.fileDescriptor).apply { setHWDecoderEnabled(true, false) } }
            val m = mk!!()
            vlc(Unit) { player.media = m }; m.release()
            // pause the music player
            @Suppress("DEPRECATION")
            (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            pPlay()
            val resume = getSharedPreferences("video_pos", MODE_PRIVATE).getLong(id.toString(), 0)
            if (resume > 5000) pTime = resume
            thumb = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri, android.util.Size(512, 288), null) }.getOrNull() }
            refresh()
        }
    }

    private val title = mutableStateOf("")

    /** TV shows: when an episode ends, start the next one (the next season after the last episode). */
    private fun playNextEpisode() {
        // remember finished episodes (home picks the next one you haven't seen)
        if (videoId > 0) getSharedPreferences("grove", MODE_PRIVATE).let { p ->
            p.edit().putStringSet("watched_eps", (p.getStringSet("watched_eps", emptySet()) ?: emptySet()) + videoId.toString()).apply()
        }
        val next = VideoQueue.next(videoId) ?: return
        handler.post {
            if (isFinishing) return@post
            // this episode is finished: don't resume it from the end next time
            getSharedPreferences("video_pos", MODE_PRIVATE).edit().remove(videoId.toString()).apply()
            android.widget.Toast.makeText(this, "next: S%02dE%02d".format(next.parsed.season ?: 0, next.parsed.episode ?: 0), android.widget.Toast.LENGTH_SHORT).show()
            open(this, next, VideoQueue.label(next))
        }
    }

    private fun fallbackToVlc() {
        val id = videoId; if (id == 0L || exo == null) return
        val t = exo?.currentPosition ?: 0
        exo?.release(); exo = null; hw.value = false
        handler.post {
            load(Intent().putExtra("id", id).putExtra("title", title.value).putExtra("w", vw).putExtra("h", vh).putExtra("hw", false))
            if (t > 1000) pTime = t
            android.widget.Toast.makeText(this, "playing with VLC (this file needs it)", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // black-picture guard: some phones can't draw 4K / 10-bit video through the fast path; reopen it the safe way
    private var mk: (() -> Media)? = null
    private var vouts = 0
    private var retried = false
    private fun blackCheck() {
        if (retried || audioOnly.value || !::player.isInitialized || !pPlaying) return
        if (player.videoTracksCount > 0 && vouts == 0) {
            retried = true
            val t = pTime
            val m = mk?.invoke() ?: return
            m.addOption(":no-mediacodec-dr")
            player.media = m; m.release(); pPlay(); pTime = t
            android.widget.Toast.makeText(this, "switched to compatible video mode", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private var watched = 0L
    private var watchStart = System.currentTimeMillis()

    private fun savePos() {
        if (videoId == 0L || !::player.isInitialized) return
        StatsLog.recordWatch(this, watchStart, videoId, watched); watched = 0; watchStart = System.currentTimeMillis()
        val t = pTime; val len = pLen
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
        if (Build.VERSION.SDK_INT in 26..30 && pPlaying) pipParams(true)?.let { enterPictureInPictureMode(it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // the PiP window was closed with its ✕ → stop the video (unless it switched to audio only)
        if (!isInPictureInPictureMode && !audioOnly.value &&
            lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) { pStop(); finish() }
    }

    override fun onResume() { super.onResume(); if (audioOnly.value) { audioOnly.value = false; pipParams(true) } }

    override fun onStop() {
        super.onStop()
        savePos()
        if (!inPip.value && !audioOnly.value && !isFinishing) pPause()
    }

    override fun onDestroy() {
        if (!handedOff) savePos()
        dead = true
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(pipReceiver) }
        VideoService.stop(this); session?.release(); if (current === this) current = null
        preview?.close()
        if (!handedOff) { // (when handed off, the mini window owns the player now)
            runCatching { exo?.release() }; exo = null
            runCatching { player.stop(); player.detachViews(); player.release(); lib.release() }; pfd?.close()
        }
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
        LaunchedEffect(Unit) {
            while (true) {
                if (exo != null) { pos = pTime; len = pLen; playing = pPlaying }
                else {
                    // VLC can wait for the main thread while it resizes the picture: asking it from here would freeze the screen
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Triple(pTime, pLen, pPlaying) }
                    pos = r.first; len = r.second; playing = r.third
                }
                if (playing) watched += 250
                delay(250)
            }
        }
        // scrubbing: by sliding sideways in the middle of the picture, or along the bar (with a frame preview)
        var scrub by remember { mutableStateOf<Long?>(null) }
        var scrubFrac by remember { mutableFloatStateOf(0.5f) }
        var fromBar by remember { mutableStateOf(false) }
        var scrubStart by remember { mutableLongStateOf(0L) }
        var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(scrub?.div(2000)) {
            val t = scrub ?: run { frame = null; return@LaunchedEffect }
            delay(30)
            preview?.let { p -> frame = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { p.at(t) } ?: frame }
        }
        LaunchedEffect(show, playing, scrub) { if (show && playing && scrub == null) { delay(3500); show = false } }
        val swipeMini = remember { Prefs(this@VideoActivity).swipeMini }
        var downY by remember { mutableFloatStateOf(0f) }
        val portrait = androidx.compose.ui.platform.LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
        val eps = remember(title.value) { VideoQueue.all.firstOrNull { it.id == videoId }?.let { VideoQueue.episodes(it) } ?: emptyList() }
        val split = portrait && eps.size > 1 && !pip
        Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box((if (split) Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(16f / 9f) else Modifier.fillMaxSize()).background(Color.Black).graphicsLayer {
            translationY = downY; val f = (downY / size.height).coerceIn(0f, 1f); scaleX = 1f - f * 0.3f; scaleY = 1f - f * 0.3f
        }.pointerInput(swipeMini) {
            if (!swipeMini) return@pointerInput
            // swipe down = GroveMedia's own mini player, like YouTube
            detectVerticalDragGestures(onDragEnd = {
                if (downY > size.height * 0.18f) { downY = 0f; toMini() } else downY = 0f
            }, onDragCancel = { downY = 0f }) { ch, d -> ch.consume(); downY = (downY + d).coerceAtLeast(0f) }
        }.pointerInput(Unit) {
            // slide sideways across the picture to seek (full width ≈ 2 minutes, or the whole video if shorter)
            var acc = 0f
            detectHorizontalDragGestures(
                onDragStart = { acc = 0f; scrubStart = pTime; fromBar = false; scrub = scrubStart; show = true },
                onDragEnd = { scrub?.let { pTime = it }; scrub = null },
                onDragCancel = { scrub = null },
            ) { ch, d ->
                ch.consume(); acc += d
                val len = pLen
                val span = if (len in 1..120_000) len else 120_000L
                val t = (scrubStart + (acc / size.width * span).toLong()).let { if (len > 0) it.coerceIn(0, len) else it.coerceAtLeast(0) }
                scrub = t; scrubFrac = if (len > 0) t.toFloat() / len else 0.5f
            }
        }.pointerInput(Unit) {
            detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                pTime = (pTime + if (o.x > size.width / 2) 10_000 else -10_000).coerceAtLeast(0)
            })
        }) {
            var exoView by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
            if (hw.value) AndroidView({ c -> androidx.media3.ui.PlayerView(c).also { v ->
                    v.useController = false; v.player = exo; v.setBackgroundColor(android.graphics.Color.BLACK); exoView = v } },
                Modifier.fillMaxSize(), update = { v -> v.player = exo })
            else AndroidView({ c -> VLCVideoLayout(c).also { l ->
                    player.attachViews(l, null, true, false)
                    // the picture was drawn somewhere else (mini window): make VLC draw it here again
                    if (adopted) handler.postDelayed({ vlc(Unit) { player.setVideoTrackEnabled(false); player.setVideoTrackEnabled(true) } }, 250)
                } }, Modifier.fillMaxSize())
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
            LaunchedEffect(fill, exoView) {
                if (hw.value) exoView?.resizeMode = if (fill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                else player.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FIT_SCREEN else MediaPlayer.ScaleType.SURFACE_BEST_FIT
            }
            val accent = Color(Prefs(this@VideoActivity).accent)
            val posS = remember { mutableLongStateOf(0L) }; val durS = remember { mutableLongStateOf(0L) }
            LaunchedEffect(pos, len) { posS.longValue = pos; durS.longValue = len }
            // sliding across the middle: big preview frame with how far you've moved
            scrub?.takeIf { !fromBar }?.let { sc ->
                Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                    .padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    frame?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.width(220.dp).height(124.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))) }
                    val dlt = sc - scrubStart
                    Text((if (dlt >= 0) "+" else "−") + t(kotlin.math.abs(dlt)), color = Color.White, fontSize = 22.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    Text("${t(sc)} / ${t(pLen)}", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
            androidx.compose.animation.AnimatedVisibility(show && !pip, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.75f), 0.3f to Color.Transparent, 0.62f to Color.Transparent,
                    1f to androidx.compose.ui.graphics.lerp(accent, Color.Black, 0.6f).copy(alpha = 0.92f)))) {
                    // header: Grove style — small caps label, big light lowercase title
                    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout).then(if (split) Modifier else Modifier.statusBarsPadding())
                        .padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.Top) {
                        Btn(Icons.AutoMirrored.Rounded.ArrowBack, outline = false) { finish() }
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            if (!split) Text("NOW PLAYING · VIDEO", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = 1.sp)
                            Text(title.value.lowercase(), color = Color.White, fontSize = if (split) 15.sp else 26.sp, lineHeight = if (split) 18.sp else 30.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Light, maxLines = if (split) 1 else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = if (split) 10.dp else 0.dp))
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout)
                        .then(if (split) Modifier else Modifier.navigationBarsPadding()).padding(horizontal = if (split) 10.dp else 20.dp, vertical = if (split) 2.dp else 12.dp)) {
                        // frame preview above the bar while dragging it
                        if (scrub != null && fromBar) Box(Modifier.fillMaxWidth().height(120.dp)) {
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                val w = 176.dp
                                val x = (maxWidth * scrubFrac - w / 2).coerceIn(0.dp, maxWidth - w)
                                Column(Modifier.padding(start = x).width(w), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.width(w).height(99.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                        .border(2.dp, Color.White, androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(Color.Black)) {
                                        frame?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    }
                                    Text(t(scrub!!), color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                }
                            }
                        }
                        SeekBar(posS, durS, accent, onDrag = { ms, f -> scrub = ms; scrubFrac = f; fromBar = ms != null }) { pTime = it }
                        if (!split) TimeRow(posS, durS)
                        val k = if (split) 0.62f else 1f
                        Row(Modifier.fillMaxWidth().padding(top = if (split) 0.dp else 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            if (split) Text("${t(posS.longValue)} / ${t(durS.longValue)}", color = Color.White, fontSize = 12.sp)
                            else Btn(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen, 44, outline = false) { fill = !fill }
                            Btn(Icons.Rounded.Replay10, (56 * k).toInt()) { pTime = (pTime - 10_000).coerceAtLeast(0) }
                            // accent play button with a soft halo, like the music player
                            Box(Modifier.size((80 * k).dp).pointerInput(Unit) { detectTapGestures { toggle() } }, contentAlignment = Alignment.Center) {
                                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(accent.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
                                Box(Modifier.size((66 * k).dp).clip(CircleShape).background(accent)
                                    .then(Modifier.border(2.dp, Color.White, CircleShape)), contentAlignment = Alignment.Center) {
                                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size((36 * k).dp))
                                }
                            }
                            Btn(Icons.Rounded.Forward10, (56 * k).toInt()) { pTime = pTime + 10_000 }
                            Row {
                                Btn(Icons.Rounded.Headphones, (44 * k).toInt().coerceAtLeast(34), outline = false) { goAudioOnly() }
                                Btn(Icons.Rounded.PictureInPictureAlt, (44 * k).toInt().coerceAtLeast(34), outline = false) { enterPip() }
                            }
                        }
                    }
                }
            }
        }
        if (split) EpisodeList(eps, Color(Prefs(this@VideoActivity).accent), Modifier.weight(1f))
        }
    }

    /** the show's other episodes under the video (portrait only), like YouTube's "up next" */
    @Composable
    private fun EpisodeList(eps: List<Video>, accent: Color, modifier: Modifier) {
        val cur = eps.indexOfFirst { it.id == videoId }.coerceAtLeast(0)
        val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (cur - 1).coerceAtLeast(0))
        LaunchedEffect(cur) { state.animateScrollToItem((cur - 1).coerceAtLeast(0)) }
        val watched = remember { getSharedPreferences("grove", MODE_PRIVATE).getStringSet("watched_eps", emptySet()) ?: emptySet() }
        Column(modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(eps[cur].parsed.title.lowercase(), color = Color.White, fontSize = 24.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            Text("${eps.size} episodes · the next one plays by itself", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items(eps.size) { i ->
                    val e = eps[i]
                    val now = e.id == videoId
                    Row(Modifier.fillMaxWidth().background(if (now) accent.copy(alpha = 0.25f) else Color.Transparent)
                        .pointerInput(e.id) { detectTapGestures { if (!now) open(this@VideoActivity, e, VideoQueue.label(e)) } }
                        .padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))) {
                            VideoThumb(e, Modifier.fillMaxSize())
                            if (now) Text("playing", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart)
                                .background(accent).padding(horizontal = 4.dp, vertical = 1.dp))
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text("S%02d · E%02d".format(e.parsed.season ?: 0, e.parsed.episode ?: 0), color = if (now) accent else Color.White,
                                fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Text(e.name.substringBeforeLast('.'), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (e.id.toString() in watched && !now) Text("watched", color = Color.White.copy(alpha = 0.4f), fontSize = 11.sp)
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
