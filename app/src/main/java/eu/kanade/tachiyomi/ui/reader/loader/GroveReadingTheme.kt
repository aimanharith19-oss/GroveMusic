package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Color

/** Grovebook: page colours for books laid out as text, and the page background in the offline reader. */
enum class GroveReadingTheme(val label: String, val background: Int, val text: Int, val muted: Int) {
    PAPER("Paper", Color.rgb(0xF6, 0xF1, 0xE7), Color.rgb(0x2B, 0x2A, 0x26), Color.rgb(0x8A, 0x84, 0x78)),
    SEPIA("Sepia", Color.rgb(0xE9, 0xDC, 0xC2), Color.rgb(0x3A, 0x2E, 0x1E), Color.rgb(0x8B, 0x77, 0x5A)),
    GROVE("Grove", Color.rgb(0x0E, 0x1D, 0x16), Color.rgb(0xCF, 0xE3, 0xD6), Color.rgb(0x6E, 0x8C, 0x7C)),
    BLACK("Black", Color.BLACK, Color.rgb(0xC8, 0xC8, 0xC2), Color.rgb(0x6A, 0x6A, 0x66)),
    ;

    val isDark: Boolean get() = this == BLACK || this == GROVE

    companion object {
        private const val PREFS = "grove_offline"
        private const val KEY = "reading_theme"

        fun get(context: Context): GroveReadingTheme {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, BLACK.name)
            return entries.firstOrNull { it.name == name } ?: BLACK
        }

        fun set(context: Context, theme: GroveReadingTheme) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.name).apply()
        }
    }
}
