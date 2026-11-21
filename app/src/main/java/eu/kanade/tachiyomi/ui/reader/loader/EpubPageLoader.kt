package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import mihon.core.archive.EpubReader

/**
 * Loader used to load a chapter from a .epub file.
 */
internal class EpubPageLoader(
    private val context: android.content.Context,
    private val reader: EpubReader,
) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> {
        val content = runCatching { reader.getContentInOrder() }.getOrDefault(emptyList())
        val textLength = content.filter { it.first == "text" }
            .sumOf { it.second.replace(Regex("<[^>]+>"), "").trim().length }

        // Comic / picture EPUBs: just the pictures, like before
        if (textLength < 1500) {
            return reader.getImagesFromPages().mapIndexed { i, path ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(path)!! }
                    status = Page.State.Ready
                }
            }
        }

        // Grovebook: novels are laid out as real book pages, chapter by chapter,
        // with their illustrations as full pages in the right place.
        val theme = GroveReadingTheme.get(context)
        val streams = mutableListOf<() -> java.io.InputStream>()
        val chapter = StringBuilder()
        val seenImages = mutableSetOf<String>()

        fun flushChapter() {
            if (chapter.isBlank()) {
                chapter.clear()
                return
            }
            val text = android.text.Html.fromHtml(chapter.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
            chapter.clear()
            if (text.isBlank()) return
            val renderer = TextBookRenderer(text, theme)
            repeat(renderer.pageCount) { i -> streams += { renderer.renderPage(i) } }
        }

        content.forEach { (type, value) ->
            when (type) {
                "text" -> chapter.append(value)
                "image" -> {
                    if (value in seenImages) return@forEach
                    seenImages += value
                    flushChapter()
                    streams += { reader.getInputStream(value) ?: java.io.ByteArrayInputStream(ByteArray(0)) }
                }
                else -> flushChapter()
            }
        }
        flushChapter()

        return streams.mapIndexed { i, open ->
            ReaderPage(i).apply {
                stream = open
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }
}
