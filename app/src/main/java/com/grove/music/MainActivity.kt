package com.grove.music

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

class MainActivity : ComponentActivity() {
    private val vm: MusicViewModel by viewModels()
    private lateinit var conn: PlayerConnection

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        useHighestRefreshRate()
        conn = PlayerConnection(this)
        val prefs = Prefs(this)
        setContent { GroveApp(vm, conn, prefs) }
        if (savedInstanceState == null) handle(intent)
    }

    /** Ask the display for its fastest mode (90/120/144 Hz) at the current resolution. */
    @Suppress("DEPRECATION")
    private fun useHighestRefreshRate() = runCatching {
        val d = if (android.os.Build.VERSION.SDK_INT >= 30) display!! else windowManager.defaultDisplay
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return@runCatching
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId; preferredRefreshRate = best.refreshRate }
    }

    override fun onResume() {
        super.onResume()
        useHighestRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { conn.playUri(it) }
        // "Play <song/artist> on Grove Music" from Google Assistant / system search
        if (i?.action == android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val q = i.getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim().lowercase()
            Thread {
                val all = runCatching { MusicRepository.query(this) }.getOrDefault(emptyList())
                val hit = if (q.isEmpty()) all else all.filter {
                    it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) || it.album.lowercase().contains(q)
                }
                runOnUiThread { if (hit.isNotEmpty()) conn.playSongs(hit, 0, shuffled = q.isEmpty()) }
            }.start()
        }
    }

    override fun onDestroy() {
        conn.release()
        super.onDestroy()
    }
}
