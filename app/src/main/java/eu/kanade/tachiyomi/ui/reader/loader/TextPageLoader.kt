package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.text.Html
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.jsoup.Jsoup

/**
 * Grovebook: reads .txt / .md / .html / .fb2 books as paginated book pages.
 */
internal class TextPageLoader(
    private val context: Context,
    private val file: UniFile,
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val raw = file.openInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        val ext = file.name.orEmpty().substringAfterLast('.', "").lowercase()
        val text: CharSequence = when (ext) {
            "html", "htm", "xhtml" -> {
                val doc = Jsoup.parse(raw)
                doc.select("img, image, svg, script, style").remove()
                Html.fromHtml(doc.body()?.html().orEmpty(), Html.FROM_HTML_MODE_LEGACY)
            }
            "fb2" -> {
                val doc = Jsoup.parse(raw, "", org.jsoup.parser.Parser.xmlParser())
                val body = doc.select("body").joinToString("\n\n") { b ->
                    b.select("p, subtitle, v").joinToString("\n\n") { it.text() }
                }
                body
            }
            else -> raw.replace("\r\n", "\n")
        }
        val renderer = TextBookRenderer(text, GroveReadingTheme.get(context))
        return List(renderer.pageCount) { i ->
            ReaderPage(i).apply {
                stream = { renderer.renderPage(i) }
                status = Page.State.Ready
            }
        }
    }
}
