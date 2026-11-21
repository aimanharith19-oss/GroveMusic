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
import androidx.compose.foundation.gestures.detectTapGestures
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

    companion object {
        fun open(ctx: Context, v: Video, title: String) = ctx.startActivity(Intent(ctx, VideoActivity::class.java)
            .putExtra("id", v.id).putExtra("title", title).putExtra("w", v.w).putExtra("h", v.h)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lib = LibVLC(this, arrayListOf("--audio-time-stretch", "--no-drop-late-frames", "--no-skip-frames"))
        player = MediaPlayer(lib)
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
        val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        pfd?.close()
        pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
        val m = Media(lib, pfd!!.fileDescriptor)
        player.media = m; m.release()
        // pause the music player
        @Suppress("DEPRECATION")
        (getSystemService(AUDIO_SERVICE) as AudioManager).requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        player.play()
        val resume = getSharedPreferences("video_pos", MODE_PRIVATE).getLong(videoId.toString(), 0)
        if (resume > 5000) player.time = resume
    }

    private val title = mutableStateOf("")

    private fun savePos() {
        if (videoId == 0L || !::player.isInitialized) return
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
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(auto).setSeamlessResizeEnabled(true)
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
        if (Build.VERSION.SDK_INT in 26..30 && player.isPlaying) pipParams(true)?.let { enterPictureInPictureMode(it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        savePos()
        if (!inPip.value) player.pause()
    }

    override fun onDestroy() {
        savePos()
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
        LaunchedEffect(Unit) { while (true) { pos = player.time; len = player.length; playing = player.isPlaying; delay(250) } }
        LaunchedEffect(show, playing) { if (show && playing) { delay(3500); show = false } }
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onTap = { show = !show }, onDoubleTap = { o ->
                player.time = (player.time + if (o.x > size.width / 2) 10_000 else -10_000).coerceAtLeast(0)
            })
        }) {
            AndroidView({ c -> VLCVideoLayout(c).also { player.attachViews(it, null, true, false) } }, Modifier.fillMaxSize())
            LaunchedEffect(fill) { player.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT }
            AnimatedVisibility(show && !pip, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) {
                    // top bar — the PiP button is always here
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn(Icons.AutoMirrored.Rounded.ArrowBack) { finish() }
                        Text(title.value, color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        Btn(if (fill) Icons.Rounded.FitScreen else Icons.Rounded.Fullscreen) { fill = !fill }
                        if (Build.VERSION.SDK_INT >= 26) Btn(Icons.Rounded.PictureInPictureAlt) { enterPip() }
                    }
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn(Icons.Rounded.Replay10, 56) { player.time = (player.time - 10_000).coerceAtLeast(0) }
                        Btn(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 76) { if (player.isPlaying) player.pause() else player.play() }
                        Btn(Icons.Rounded.Forward10, 56) { player.time = player.time + 10_000 }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Slider(drag ?: if (len > 0) pos.toFloat() / len else 0f, { drag = it },
                            onValueChangeFinished = { drag?.let { player.time = (it * len).toLong() }; drag = null },
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(Prefs(this@VideoActivity).accent)))
                        Row { Text(t(pos), color = Color.White, fontSize = 13.sp); Spacer(Modifier.weight(1f)); Text(t(len), color = Color.White, fontSize = 13.sp) }
                    }
                }
            }
        }
    }

    private fun t(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

    @Composable
    private fun Btn(icon: ImageVector, size: Int = 44, onClick: () -> Unit) {
        Box(Modifier.size(size.dp).clip(CircleShape).pointerInput(Unit) { detectTapGestures { onClick() } }, contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}
