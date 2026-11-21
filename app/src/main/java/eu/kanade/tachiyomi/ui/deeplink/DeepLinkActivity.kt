package eu.kanade.tachiyomi.ui.deeplink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.main.MainActivity

class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Grovebook: build a fresh intent instead of relaunching the untrusted one
        val safe = Intent(applicationContext, MainActivity::class.java).apply {
            action = intent.action
            data = intent.data
            intent.extras?.let { extras -> putExtras(extras) }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(safe)
        finish()
    }
}
