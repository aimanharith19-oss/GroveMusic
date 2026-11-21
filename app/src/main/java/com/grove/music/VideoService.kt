package com.grove.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder

/** Keeps a playing video alive in the background and shows it in the phone's media controls. */
class VideoService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = VideoActivity.current
        when (intent?.action) {
            ACT_TOGGLE -> a?.toggle()
            ACT_BACK -> a?.skip(-10_000)
            ACT_FWD -> a?.skip(10_000)
            ACT_STOP -> { a?.finish(); stopSelf(); return START_NOT_STICKY }
        }
        if (a == null) { stopSelf(); return START_NOT_STICKY }
        startForeground(ID, build(a), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    private fun build(a: VideoActivity): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Video playback", NotificationManager.IMPORTANCE_LOW))
        fun act(icon: Int, title: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(this, icon), title,
            PendingIntent.getService(this, action.hashCode(), Intent(this, VideoService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val open = PendingIntent.getActivity(this, 1, Intent(this, VideoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_n_video)
            .setContentTitle(a.titleText()).setContentText("Grove Media · video")
            .setLargeIcon(a.thumb)
            .setContentIntent(open).setOngoing(a.isPlaying())
            .addAction(act(R.drawable.ic_n_replay10, "Back 10s", ACT_BACK))
            .addAction(act(if (a.isPlaying()) R.drawable.ic_n_pause else R.drawable.ic_n_play, "Play/pause", ACT_TOGGLE))
            .addAction(act(R.drawable.ic_n_forward10, "Forward 10s", ACT_FWD))
            .addAction(act(R.drawable.ic_n_close, "Stop", ACT_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(a.sessionToken()).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val CH = "grove_video"; private const val ID = 7701
        const val ACT_TOGGLE = "grove.video.TOGGLE"; const val ACT_BACK = "grove.video.BACK"
        const val ACT_FWD = "grove.video.FWD"; const val ACT_STOP = "grove.video.STOP"
        fun update(ctx: Context) = runCatching { ctx.startForegroundService(Intent(ctx, VideoService::class.java)) }
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, VideoService::class.java))
    }
}
