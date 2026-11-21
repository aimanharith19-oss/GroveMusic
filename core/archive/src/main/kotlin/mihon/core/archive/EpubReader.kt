package mihon.core.archive

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.io.InputStream

/**
 * Wrapper over ArchiveReader to load files in epub format.
 */
class EpubReader(private val reader: ArchiveReader) : Closeable by reader {

    /**
     * Path separator used by this epub.
     */
    private val pathSeparator = getPathSeparator()

    /**
     * Returns an input stream for reading the contents of the specified zip file entry.
     */
    fun getInputStream(entryName: String): InputStream? {
        return reader.getInputStream(entryName)
    }

    /**
     * Returns the path of all the images found in the epub file.
     */
    fun getImagesFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val pages = getPagesFromDocument(doc)
        return getImagesFromPages(pages, ref)
    }

    /**
     * Grovebook: the whole book in reading order, as chapters of HTML text with the
     * pictures kept in place. Each item is either ("text", html) or ("image", zip path).
     */
    fun getContentInOrder(): List<Pair<String, String>> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val basePath = getParentDirectory(ref)
        val out = mutableListOf<Pair<String, String>>()
        val imgTag = Regex("""<(img|image)\b[^>]*>""", RegexOption.IGNORE_CASE)
        val srcAttr = Regex("""(?:src|xlink:href|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        getPagesFromDocument(doc).forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            val pageDir = getParentDirectory(entryPath)
            val html = getInputStream(entryPath)?.use { Jsoup.parse(it, null, "") } ?: return@forEach
            html.select("script, style, svg title").remove()
            // Keep <image> inside <svg> as a plain picture
            html.select("svg").forEach { svg ->
                val image = svg.selectFirst("image")
                if (image != null) svg.replaceWith(image) else svg.remove()
            }
            val body = html.body()?.html() ?: return@forEach
            var last = 0
            imgTag.findAll(body).forEach { m ->
                val before = body.substring(last, m.range.first)
                if (before.isNotBlank()) out += "text" to before
                srcAttr.find(m.value)?.groupValues?.get(1)?.let { src ->
                    val clean = runCatching {
                        java.net.URLDecoder.decode(src.substringBefore('#').replace("+", "%2B"), "UTF-8")
                    }.getOrDefault(src)
                    out += "image" to resolveZipPath(pageDir, clean)
                }
                last = m.range.last + 1
            }
            val rest = body.substring(last)
            if (rest.isNotBlank()) out += "text" to rest
            // Chapter break marker
            out += "break" to ""
        }
        return out
    }

    /**
     * Grovebook: the book's text as HTML, chapter by chapter in reading order.
     */
    fun getTextFromPages(): List<String> {
        val ref = getPackageHref()
        val doc = getPackageDocument(ref)
        val basePath = getParentDirectory(ref)
        return getPagesFromDocument(doc).mapNotNull { page ->
            val entryPath = resolveZipPath(basePath, page)
            getInputStream(entryPath)?.use { Jsoup.parse(it, null, "") }?.let { html ->
                html.select("img, image, svg, script, style").remove()
                html.body()?.html()
            }
        }
    }

    /**
     * Returns the path to the package document.
     */
    fun getPackageHref(): String {
        val meta = getInputStream(resolveZipPath("META-INF", "container.xml"))
        if (meta != null) {
            val metaDoc = meta.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
            val path = metaDoc.getElementsByTag("rootfile").first()?.attr("full-path")
            if (path != null) {
                return path
            }
        }
        return resolveZipPath("OEBPS", "content.opf")
    }

    /**
     * Returns the package document where all the files are listed.
     */
    fun getPackageDocument(ref: String): Document {
        return getInputStream(ref)!!.use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    /**
     * Returns all the pages from the epub.
     */
    private fun getPagesFromDocument(document: Document): List<String> {
        val pages = document.select("manifest > item")
            .filter { node -> "application/xhtml+xml" == node.attr("media-type") }
            .associateBy { it.attr("id") }

        val spine = document.select("spine > itemref").map { it.attr("idref") }
        return spine.mapNotNull { pages[it] }.map { it.attr("href") }
    }

    /**
     * Returns all the images contained in every page from the epub.
     */
    private fun getImagesFromPages(pages: List<String>, packageHref: String): List<String> {
        val result = mutableListOf<String>()
        val basePath = getParentDirectory(packageHref)
        pages.forEach { page ->
            val entryPath = resolveZipPath(basePath, page)
            val document = getInputStream(entryPath)!!.use { Jsoup.parse(it, null, "") }
            val imageBasePath = getParentDirectory(entryPath)

            document.allElements.forEach {
                when (it.tagName()) {
                    "img" -> result.add(resolveZipPath(imageBasePath, it.attr("src")))
                    "image" -> result.add(resolveZipPath(imageBasePath, it.attr("xlink:href")))
                }
            }
        }

        return result
    }

    /**
     * Returns the path separator used by the epub file.
     */
    private fun getPathSeparator(): String {
        val meta = getInputStream("META-INF\\container.xml")
        return if (meta != null) {
            meta.close()
            "\\"
        } else {
            "/"
        }
    }

    /**
     * Resolves a zip path from base and relative components and a path separator.
     */
    private fun resolveZipPath(basePath: String, relativePath: String): String {
        if (relativePath.startsWith(pathSeparator)) {
            // Path is absolute, so return as-is.
            return relativePath
        }

        var fixedBasePath = basePath.replace(pathSeparator, File.separator)
        if (!fixedBasePath.startsWith(File.separator)) {
            fixedBasePath = "${File.separator}$fixedBasePath"
        }

        val fixedRelativePath = relativePath.replace(pathSeparator, File.separator)
        val resolvedPath = File(fixedBasePath, fixedRelativePath).canonicalPath
        return resolvedPath.replace(File.separator, pathSeparator).substring(1)
    }

    /**
     * Gets the parent directory of a path.
     */
    private fun getParentDirectory(path: String): String {
        val separatorIndex = path.lastIndexOf(pathSeparator)
        return if (separatorIndex >= 0) {
            path.substring(0, separatorIndex)
        } else {
            ""
        }
    }
}
