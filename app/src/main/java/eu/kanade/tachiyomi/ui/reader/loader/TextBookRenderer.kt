package eu.kanade.tachiyomi.ui.reader.loader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Grovebook: lays out plain or styled text as real book pages
 * (paper colour, serif type, page numbers) and renders each page to an image.
 */
internal class TextBookRenderer(
    text: CharSequence,
    private val theme: GroveReadingTheme = GroveReadingTheme.PAPER,
) {

    private val width = 1080
    private val height = 1620
    private val margin = 88
    private val footer = 70

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text
        textSize = 42f
        typeface = Typeface.SERIF
    }

    private val body: CharSequence = stripColours(text)

    private val layout: StaticLayout = StaticLayout.Builder
        .obtain(body, 0, body.length, paint, width - margin * 2)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(10f, 1.12f)
        .setIncludePad(false)
        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        .build()

    /** First and last line (inclusive) of each page. */
    private val pages: List<IntRange> = buildList {
        val contentHeight = height - margin - footer - margin / 2
        var start = 0
        while (start < layout.lineCount) {
            val top = layout.getLineTop(start)
            var end = start
            while (end + 1 < layout.lineCount && layout.getLineBottom(end + 1) - top <= contentHeight) {
                end++
            }
            add(start..end)
            start = end + 1
        }
        if (isEmpty()) add(0..0)
    }

    val pageCount: Int get() = pages.size

    @Synchronized
    fun renderPage(index: Int): InputStream {
        val range = pages[index]
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme.background)

        if (layout.lineCount > 0) {
            val top = layout.getLineTop(range.first)
            val bottom = layout.getLineBottom(range.last)
            canvas.save()
            canvas.translate(margin.toFloat(), (margin - top).toFloat())
            canvas.clipRect(0, top, width - margin * 2, bottom)
            layout.draw(canvas)
            canvas.restore()
        }

        val numberPaint = TextPaint(paint).apply {
            textSize = 28f
            color = theme.muted
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("${index + 1} / $pageCount", width / 2f, (height - footer / 2).toFloat(), numberPaint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        bitmap.recycle()
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun stripColours(text: CharSequence): CharSequence {
        if (text !is android.text.Spanned) return text
        val copy = android.text.SpannableStringBuilder(text)
        copy.getSpans(0, copy.length, android.text.style.ForegroundColorSpan::class.java).forEach { copy.removeSpan(it) }
        copy.getSpans(0, copy.length, android.text.style.BackgroundColorSpan::class.java).forEach { copy.removeSpan(it) }
        copy.getSpans(0, copy.length, android.text.style.ImageSpan::class.java).forEach { copy.removeSpan(it) }
        // Trim the big runs of blank lines Html.fromHtml leaves behind
        var i = 0
        while (i < copy.length - 2) {
            if (copy[i] == '\n' && copy[i + 1] == '\n' && copy[i + 2] == '\n') copy.delete(i, i + 1) else i++
        }
        return copy
    }
}
