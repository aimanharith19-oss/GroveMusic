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
        conn = PlayerConnection(this)
        val prefs = Prefs(this)
        setContent { GroveApp(vm, conn, prefs) }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { conn.playUri(it) }
    }

    override fun onDestroy() {
        conn.release()
        super.onDestroy()
    }
}
