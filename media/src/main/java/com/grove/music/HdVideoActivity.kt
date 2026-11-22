package com.grove.music

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Rational
import android.view.ScaleGestureDetector
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 4K / 8K player: Android's own hardware decoders through ExoPlayer (HEVC, AV1, VP9, 10-bit, HDR).
 * Used for very large videos where VLC's renderer showed a black picture on some phones.
 */
@OptIn(UnstableApi::class)
class HdVideoActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private var videoId = 0L
    private var w = 16; private var h = 9
    private var watchStart = System.currentTimeMillis()
    private var playedMs = 0L
    private var lastTick = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        videoId = intent.getLongExtra("id", 0)
        w = intent.getIntExtra("w", 16).coerceAtLeast(1); h = intent.getIntExtra("h", 9).coerceAtLeast(1)
        requestedOrientation = if (w >= h) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT

        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
        view = PlayerView(this).apply {
            player = this@HdVideoActivity.player
            setBackgroundColor(android.graphics.Color.BLACK)
            setShowNextButton(false); setShowPreviousButton(false)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            keepScreenOn = true
        }
        // pinch out = zoom to fill, pinch in = fit
        val pinch = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleEnd(d: ScaleGestureDetector) {
                view.resizeMode = if (d.scaleFactor > 1f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        })
        view.setOnTouchListener { v, e -> pinch.onTouchEvent(e); if (e.pointerCount > 1) true else { v.performClick(); false } }
        setContentView(view)

        val uri = intent.data ?: android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)
        player.setMediaItem(MediaItem.fromUri(uri))
        val resume = if (videoId != 0L) getSharedPreferences("video_pos", MODE_PRIVATE).getLong(videoId.toString(), 0) else 0
        if (resume > 5000) player.seekTo(resume)
        player.prepare(); player.play()
        setPip()
    }

    private fun setPip() = runCatching {
        val r = (w.toFloat() / h).coerceIn(0.42f, 2.38f)
        setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000).toInt(), 1000))
            .setAutoEnterEnabled(true).setSeamlessResizeEnabled(true).build())
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, cfg: Configuration) {
        super.onPictureInPictureModeChanged(inPip, cfg)
        view.useController = !inPip
        if (!inPip && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) { player.pause(); finish() }
    }

    private fun save() {
        if (videoId == 0L) return
        val t = player.currentPosition; val len = player.duration
        getSharedPreferences("video_pos", MODE_PRIVATE).edit().apply {
            if (len > 0 && t > len - 30_000) remove(videoId.toString()) else putLong(videoId.toString(), t)
        }.apply()
        StatsLog.recordWatch(this, watchStart, videoId, playedMs); playedMs = 0; watchStart = System.currentTimeMillis()
    }

    override fun onPause() {
        super.onPause()
        if (player.isPlaying && lastTick > 0) playedMs += System.currentTimeMillis() - lastTick
        lastTick = 0
        save()
    }
    override fun onResume() { super.onResume(); lastTick = System.currentTimeMillis() }
    override fun onStop() { super.onStop(); if (!isInPictureInPictureMode) player.pause() }
    override fun onDestroy() { save(); player.release(); super.onDestroy() }

    companion object {
        fun open(ctx: Context, v: Video) = ctx.startActivity(Intent(ctx, HdVideoActivity::class.java)
            .putExtra("id", v.id).putExtra("w", v.w).putExtra("h", v.h).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
