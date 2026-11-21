package tachiyomi.source.local.io

import com.hippo.unifile.UniFile
import tachiyomi.core.common.storage.extension
import tachiyomi.source.local.io.Archive.isSupported as isArchiveSupported

sealed interface Format {
    data class Directory(val file: UniFile) : Format
    data class Archive(val file: UniFile) : Format
    data class Epub(val file: UniFile) : Format

    // Grovebook: extra book formats
    data class Pdf(val file: UniFile) : Format
    data class Text(val file: UniFile) : Format

    class UnknownFormatException : Exception()

    companion object {
        val TEXT_TYPES = listOf("txt", "md", "text", "html", "htm", "xhtml", "fb2")

        fun valueOf(file: UniFile) = when {
            file.isDirectory -> Directory(file)
            file.extension.equals("epub", true) -> Epub(file)
            file.extension.equals("pdf", true) -> Pdf(file)
            file.extension?.lowercase() in TEXT_TYPES -> Text(file)
            isArchiveSupported(file) -> Archive(file)
            else -> throw UnknownFormatException()
        }
    }
}
