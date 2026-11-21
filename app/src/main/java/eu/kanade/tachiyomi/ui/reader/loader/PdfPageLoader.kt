package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Grovebook: reads PDF books, rendering each page on demand.
 */
internal class PdfPageLoader(
    private val context: Context,
    private val file: UniFile,
) : PageLoader() {

    override var isLocal: Boolean = true

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val lock = Any()

    override suspend fun getPages(): List<ReaderPage> {
        val fd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: error("Can't open ${file.name}")
        descriptor = fd
        val pdf = PdfRenderer(fd)
        renderer = pdf
        return List(pdf.pageCount) { i ->
            ReaderPage(i).apply {
                stream = { render(i) }
                status = Page.State.Ready
            }
        }
    }

    private fun render(index: Int): InputStream = synchronized(lock) {
        val pdf = renderer ?: error("PDF closed")
        pdf.openPage(index).use { page ->
            val targetWidth = 1440
            val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
            val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            ByteArrayInputStream(out.toByteArray())
        }
    }

    override fun recycle() {
        super.recycle()
        synchronized(lock) {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }
}
