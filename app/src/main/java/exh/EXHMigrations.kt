package exh

import tachiyomi.domain.manga.model.Manga
import java.net.URI
import java.net.URISyntaxException

/**
 * SY-specific file
 */
object EXHMigrations {

    fun migrateBackupEntry(manga: Manga): Manga = manga

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig)
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }
}
