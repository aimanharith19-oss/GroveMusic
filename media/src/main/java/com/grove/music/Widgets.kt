package com.grove.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.KeyEvent
import android.widget.RemoteViews

/** Home-screen widgets (pill, wide, square) and the quick-settings tile, all driven by the playback service. */
object Widgets {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("grove_widget", Context.MODE_PRIVATE)
    private var art: Bitmap? = null
    private var artKey: String? = null

    /** media-button press sent to the playback service (works even when it isn't running yet) */
    fun key(ctx: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(ctx, code,
        Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(ComponentName(ctx, androidx.media3.session.MediaButtonReceiver::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun save(ctx: Context, title: String?, artist: String?, artUri: Uri?, playing: Boolean) {
        sp(ctx).edit().putString("t", title).putString("a", artist).putString("u", artUri?.toString()).putBoolean("p", playing).apply()
        val k = artUri?.toString()
        if (k != artKey) {
            artKey = k
            art = k?.let { loadArt(ctx, Uri.parse(it)) }
        }
        refresh(ctx)
    }

    fun refresh(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        listOf(WidgetPill::class.java to R.layout.gm_widget_pill, WidgetWide::class.java to R.layout.gm_widget_wide,
            WidgetSquare::class.java to R.layout.gm_widget_square).forEach { (cls, layout) ->
            val ids = m.getAppWidgetIds(ComponentName(ctx, cls))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(ctx, layout))
        }
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, PlayTile::class.java)) }
    }

    fun views(ctx: Context, layout: Int): RemoteViews {
        val p = sp(ctx)
        if (art == null && p.getString("u", null) != null && artKey == null) {
            artKey = p.getString("u", null)
            art = loadArt(ctx, Uri.parse(artKey))
        }
        return RemoteViews(ctx.packageName, layout).apply {
            setTextViewText(R.id.w_title, p.getString("t", null) ?: "GroveMedia")
            if (layout != R.layout.gm_widget_pill) setTextViewText(R.id.w_artist, p.getString("a", null) ?: "tap play to start")
            art?.let { setImageViewBitmap(R.id.w_art, it) } ?: setImageViewResource(R.id.w_art, R.drawable.gm_fg)
            setImageViewResource(R.id.w_play, if (p.getBoolean("p", false)) R.drawable.ic_n_pause else R.drawable.ic_n_play)
            setOnClickPendingIntent(R.id.w_play, key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            setOnClickPendingIntent(R.id.w_next, key(ctx, KeyEvent.KEYCODE_MEDIA_NEXT))
            if (layout != R.layout.gm_widget_pill) setOnClickPendingIntent(R.id.w_prev, key(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
    }

    private fun loadArt(ctx: Context, u: Uri): Bitmap? = runCatching {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
        var s = 1; while (o.outWidth / (s * 2) >= 300) s *= 2
        ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) }
    }.getOrNull()

    fun playing(ctx: Context) = sp(ctx).getBoolean("p", false)
    fun title(ctx: Context) = sp(ctx).getString("t", null)
}

abstract class GroveWidget(private val layout: Int) : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { m.updateAppWidget(ids, Widgets.views(ctx, layout)) }
}
class WidgetPill : GroveWidget(R.layout.gm_widget_pill)
class WidgetWide : GroveWidget(R.layout.gm_widget_wide)
class WidgetSquare : GroveWidget(R.layout.gm_widget_square)

/** Quick-settings tile: tap to play / pause, shows the song. */
class PlayTile : TileService() {
    override fun onStartListening() { draw() }
    override fun onClick() {
        runCatching { Widgets.key(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE).send() }
    }
    private fun draw() {
        val t = qsTile ?: return
        val on = Widgets.playing(this)
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = if (on) "playing" else "GroveMedia"
        t.subtitle = Widgets.title(this) ?: "music"
        t.icon = Icon.createWithResource(this, if (on) R.drawable.ic_n_pause else R.drawable.ic_n_play)
        t.updateTile()
    }
}
