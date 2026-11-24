package com.grove.music

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Home-screen comics widget in GroveMedia's style: comics with new chapters first, then what you're reading. */
class WidgetComics : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) {
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ComicWidget.refreshNow(ctx) }
            done.finish()
        }
    }
}

object ComicWidget {
    private data class Tile(val title: String, val cover: String?, val badge: String?)

    fun refresh(ctx: Context) {
        CoroutineScope(Dispatchers.IO).launch { runCatching { refreshNow(ctx) } }
    }

    suspend fun refreshNow(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        val ids = m.getAppWidgetIds(ComponentName(ctx, WidgetComics::class.java))
        if (ids.isEmpty()) return
        // the comics library: comics with unread chapters first, then what you read last
        val lib = Books.library(ctx)
        val fresh = lib.filter { it.unread > 0 }
        val reading = Books.recent(ctx, 8)
        val tiles = (fresh.map { Tile(it.title, (it.cover as? java.io.File)?.path ?: it.cover?.toString(), "${it.unread} new") } +
            reading.map { Tile(it.title, (it.cover as? java.io.File)?.path ?: it.cover?.toString(), null) })
            .distinctBy { it.title }.take(4)
        val newTotal = fresh.sumOf { it.unread }

        val v = RemoteViews(ctx.packageName, R.layout.gm_widget_comics)
        v.setTextViewText(R.id.c_sub, when {
            newTotal > 0 -> "$newTotal unread"
            tiles.isEmpty() -> "add comics in GroveMedia"
            else -> "keep reading"
        })
        val slots = listOf(
            Triple(R.id.c_i0, R.id.c_c0, R.id.c_t0) to R.id.c_b0, Triple(R.id.c_i1, R.id.c_c1, R.id.c_t1) to R.id.c_b1,
            Triple(R.id.c_i2, R.id.c_c2, R.id.c_t2) to R.id.c_b2, Triple(R.id.c_i3, R.id.c_c3, R.id.c_t3) to R.id.c_b3,
        )
        slots.forEachIndexed { i, (ids3, badge) ->
            val (box, img, txt) = ids3
            val t = tiles.getOrNull(i)
            if (t == null) { v.setViewVisibility(box, View.INVISIBLE); return@forEachIndexed }
            v.setViewVisibility(box, View.VISIBLE)
            v.setTextViewText(txt, t.title)
            if (t.badge != null) { v.setViewVisibility(badge, View.VISIBLE); v.setTextViewText(badge, t.badge) } else v.setViewVisibility(badge, View.GONE)
            val bmp = t.cover?.let { cover(ctx, it) }
            if (bmp != null) v.setImageViewBitmap(img, bmp) else v.setImageViewResource(img, R.drawable.gm_comic)
        }
        v.setOnClickPendingIntent(R.id.c_root, openComics(ctx))
        m.updateAppWidget(ids, v)
    }

    private suspend fun cover(ctx: Context, url: String): Bitmap? = runCatching {
        val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(240, 340).allowHardware(false).build())
        (r.drawable as? BitmapDrawable)?.bitmap
    }.getOrNull()

    fun openComics(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 71, Intent(ctx, MainActivity::class.java).putExtra("tab", "comics").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Checks library comics for new chapters every few hours and posts a notification with the comic icon. */
class ComicUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        ComicWidget.refreshNow(applicationContext)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "grovemedia_comics"
        private const val NAME = "grovemedia_comic_updates"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ComicUpdateWorker>(1, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

    }
}
