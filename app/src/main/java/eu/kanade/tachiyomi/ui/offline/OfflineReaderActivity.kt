package eu.kanade.tachiyomi.ui.offline

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.ui.reader.loader.ArchivePageLoader
import eu.kanade.tachiyomi.ui.reader.loader.EpubPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.GroveReadingTheme
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PdfPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.TextPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PageAnimation
import eu.kanade.tachiyomi.ui.reader.viewer.pager.CurlablePage
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PageCurl
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader

/**
 * Grovebook: reads a book file from the offline library with the paper page-curl.
 */
class OfflineReaderActivity : ComponentActivity() {

    private var loader: PageLoader? = null
    private lateinit var pager: Pager
    private lateinit var indicator: TextView
    private lateinit var uri: Uri
    private lateinit var readingTheme: GroveReadingTheme
    private lateinit var rootView: FrameLayout
    private lateinit var optionsBar: LinearLayout
    private lateinit var spinner: ProgressBar
    private var isPdf = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = intent.data ?: return finish()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        readingTheme = GroveReadingTheme.get(this)
        val root = FrameLayout(this).apply { setBackgroundColor(readingTheme.background) }
        rootView = root
        pager = Pager(this)
        pager.id = View.generateViewId()
        pager.offscreenPageLimit = 1
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        spinner = ProgressBar(this)
        root.addView(spinner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        indicator = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 20, 40, 30))
            setPadding(28, 10, 28, 10)
            textSize = 13f
            alpha = 0f
        }
        root.addView(
            indicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = 48 },
        )
        optionsBar = buildOptionsBar()
        root.addView(
            optionsBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        setContentView(root)
        load(OfflineLibrary.lastPage(this, uri))
    }

    /** Background choices shown when the middle of the page is tapped. */
    /** Background and page-animation choices shown when the middle of the page is tapped. */
    private fun buildOptionsBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 14, 12, 14)
        setBackgroundColor(Color.argb(240, 0x12, 0x2E, 0x22))
        visibility = View.GONE
        alpha = 0f

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(0x7F, 0xD3, 0xA4))
            setPadding(16, 4, 16, 6)
        }

        fun chipRow(build: LinearLayout.() -> Unit) = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; build() })
        }

        fun LinearLayout.chip(text: String, bg: Int, fg: Int, selected: Boolean, onClick: () -> Unit) {
            addView(
                TextView(context).apply {
                    this.text = text.lowercase()
                    textSize = 15f
                    setTextColor(fg)
                    setPadding(28, 16, 28, 16)
                    background = GradientDrawable().apply {
                        setColor(bg)
                        setStroke(4, if (selected) Color.rgb(0x7F, 0xD3, 0xA4) else Color.TRANSPARENT)
                    }
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = 6; marginEnd = 6 },
            )
        }

        addView(label("background"))
        addView(
            chipRow {
                GroveReadingTheme.entries.forEach { option ->
                    chip(option.label, option.background, option.text, option == readingTheme) { applyTheme(option) }
                }
            },
        )
        addView(label("page turn"))
        addView(
            chipRow {
                val current = PageAnimation.fromKey(appGraph.readerPreferences.pageAnimation.get())
                PageAnimation.entries.forEach { anim ->
                    chip(anim.label, Color.rgb(0x1F, 0x1F, 0x1F), Color.WHITE, anim == current) { applyAnimation(anim) }
                }
            },
        )
    }

    private fun applyAnimation(anim: PageAnimation) {
        appGraph.readerPreferences.pageAnimation.set(anim.name)
        val (transformer, reverse) = anim.transformer(false)
        // Reset any leftover transforms from the previous animation
        for (i in 0 until pager.childCount) {
            pager.getChildAt(i).apply {
                rotationY = 0f; scaleX = 1f; scaleY = 1f; alpha = 1f; translationX = 0f; translationZ = 0f
                (this as? CurlablePage)?.curlProgress = 0f
            }
        }
        pager.setPageTransformer(reverse, transformer)
        // Rebuild the options so the new choice is highlighted
        replaceOptionsBar()
    }

    private fun replaceOptionsBar() {
        val index = rootView.indexOfChild(optionsBar)
        rootView.removeView(optionsBar)
        optionsBar = buildOptionsBar().also { it.visibility = View.VISIBLE; it.alpha = 1f }
        rootView.addView(
            optionsBar,
            index,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
    }

    private fun toggleOptions() {
        if (optionsBar.visibility == View.VISIBLE) {
            optionsBar.animate().alpha(0f).translationY(40f).setDuration(200)
                .withEndAction { optionsBar.visibility = View.GONE }.start()
        } else {
            optionsBar.visibility = View.VISIBLE
            optionsBar.translationY = 40f
            optionsBar.animate().alpha(1f).translationY(0f).setDuration(260).start()
        }
    }

    private fun applyTheme(option: GroveReadingTheme) {
        if (option == readingTheme) return
        GroveReadingTheme.set(this, option)
        readingTheme = option
        rootView.setBackgroundColor(option.background)
        replaceOptionsBar()
        // Text books are re-laid out in the new colours
        val page = pager.currentItem
        loader?.recycle()
        loader = null
        pager.adapter = null
        load(page)
    }

    private fun load(startPage: Int) {
        spinner.visibility = View.VISIBLE
        lifecycleScope.launch {
            val pages = try {
                withContext(Dispatchers.IO) {
                    val l = createLoader(uri)
                    loader = l
                    l.getPages()
                }
            } catch (e: Throwable) {
                spinner.visibility = View.GONE
                indicator.text = "Can't open this book: ${e.message ?: e.javaClass.simpleName}"
                indicator.alpha = 1f
                return@launch
            }
            spinner.visibility = View.GONE
            OfflineLibrary.savePageCount(this@OfflineReaderActivity, uri, pages.size)
            pager.adapter = BookAdapter(pages)
            PageAnimation.fromKey(appGraph.readerPreferences.pageAnimation.get()).transformer(false)
                .let { (transformer, reverse) -> pager.setPageTransformer(reverse, transformer) }
            pager.setCurrentItem(startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)), false)
            showIndicator(pager.currentItem, pages.size)
            pager.clearOnPageChangeListeners()
            pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    OfflineLibrary.saveLastPage(this@OfflineReaderActivity, uri, position)
                    showIndicator(position, pages.size)
                }
            })
            pager.tapListener = { ev ->
                val x = ev.x / pager.width.coerceAtLeast(1)
                when {
                    x < 0.3f -> if (pager.currentItem > 0) pager.setCurrentItem(pager.currentItem - 1, true)
                    x > 0.7f -> if (pager.currentItem < pages.size - 1) pager.setCurrentItem(pager.currentItem + 1, true)
                    else -> {
                        showIndicator(pager.currentItem, pages.size)
                        toggleOptions()
                    }
                }
            }
        }
    }

    private fun showIndicator(page: Int, total: Int) {
        indicator.text = "${intent.getStringExtra(EXTRA_TITLE).orEmpty()}  ·  ${page + 1} / $total"
        indicator.animate().cancel()
        indicator.alpha = 1f
        indicator.animate().alpha(0f).setStartDelay(1600).setDuration(500).start()
    }

    private fun createLoader(uri: Uri): PageLoader {
        val file = UniFile.fromUri(this, uri) ?: error("File not found")
        val ext = file.name.orEmpty().substringAfterLast('.', "").lowercase()
        isPdf = ext == "pdf"
        return when (ext) {
            "pdf" -> PdfPageLoader(this, file)
            "txt", "md", "text", "html", "htm", "xhtml", "fb2" -> TextPageLoader(this, file)
            "epub" -> EpubPageLoader(this, file.epubReader(this, null))
            else -> ArchivePageLoader(file.archiveReader(this, null), this, appGraph.readerPreferences)
        }
    }

    // Grovebook: count reading time for stats
    private var sessionStart = 0L

    override fun onResume() {
        super.onResume()
        sessionStart = System.currentTimeMillis()
    }

    override fun onPause() {
        if (sessionStart > 0 && ::uri.isInitialized) {
            // Cap one session at 2 hours in case the screen was left on
            OfflineLibrary.addReadTime(this, uri, (System.currentTimeMillis() - sessionStart).coerceAtMost(7_200_000L))
        }
        sessionStart = 0
        super.onPause()
    }

    override fun onDestroy() {
        loader?.recycle()
        super.onDestroy()
    }

    private inner class BookAdapter(private val pages: List<ReaderPage>) : PagerAdapter() {
        override fun getCount() = pages.size
        override fun isViewFromObject(view: View, obj: Any) = view === obj

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val view = CurlPageView(container.context, readingTheme.background, isPdf && readingTheme.isDark)
            container.addView(view)
            val page = pages[position]
            val targetWidth = resources.displayMetrics.widthPixels
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { decode(page, targetWidth) }.getOrNull()
                }
                if (bitmap != null) view.setBitmap(bitmap)
            }
            return view
        }

        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }
    }

    private fun decode(page: ReaderPage, targetWidth: Int): Bitmap? {
        val open = page.stream ?: return null
        val bytes = open().use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** A page that can curl like paper. */
    private class CurlPageView(context: Context, paper: Int, invert: Boolean) : FrameLayout(context), CurlablePage {
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(paper)
            if (invert) {
                // Night mode for PDFs: white pages become black, text becomes light
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            -0.85f, 0f, 0f, 0f, 230f,
                            0f, -0.85f, 0f, 0f, 230f,
                            0f, 0f, -0.85f, 0f, 225f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    ),
                )
            }
        }
        private val pageCurl = PageCurl(this).also { it.paperColor = paper }

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun setBitmap(bitmap: Bitmap) {
            pageCurl.reset()
            image.setImageBitmap(bitmap)
        }

        override var curlFromLeft: Boolean = false
        override var curlProgress: Float = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun dispatchDraw(canvas: Canvas) {
            pageCurl.draw(canvas, curlProgress, curlFromLeft) { super.dispatchDraw(it) }
        }

        override fun onDetachedFromWindow() {
            pageCurl.reset()
            super.onDetachedFromWindow()
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"

        fun newIntent(context: Context, uri: Uri, title: String): Intent =
            Intent(context, OfflineReaderActivity::class.java).apply {
                data = uri
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
