package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs
import kotlin.math.max

/**
 * Grovebook: page-turn animations for left/right reading.
 */
enum class PageAnimation(val label: String) {
    CURL("Paper curl"),
    SLIDE("Slide"),
    FADE("Fade"),
    STACK("Stack of pages"),
    ZOOM("Zoom out"),
    CUBE("Cube"),
    FLIP("Card flip"),
    TURNSTILE("Turnstile"),
    ;

    /** The transformer to install, and whether pages further left draw on top. */
    fun transformer(rightToLeft: Boolean): Pair<ViewPager.PageTransformer?, Boolean> = when (this) {
        CURL -> BookFlipTransformer(rightToLeft) to !rightToLeft
        SLIDE -> null to false
        else -> SimpleTransformer(this) to true
    }

    companion object {
        fun fromKey(key: String?): PageAnimation = entries.firstOrNull { it.name == key } ?: CURL
    }
}

private class SimpleTransformer(private val kind: PageAnimation) : ViewPager.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val w = page.width.toFloat()
        // Start from a clean page every time
        page.alpha = 1f
        page.translationX = 0f
        page.translationZ = 0f
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.pivotX = w / 2f
        page.pivotY = page.height / 2f
        page.cameraDistance = page.resources.displayMetrics.density * 9000f
        (page as? CurlablePage)?.curlProgress = 0f

        if (position <= -1f || position >= 1f) {
            page.alpha = 0f
            return
        }
        when (kind) {
            PageAnimation.FADE -> {
                page.translationX = -position * w
                page.alpha = 1f - abs(position)
            }
            PageAnimation.STACK -> {
                if (position > 0f) {
                    // Next page waits underneath and rises up
                    page.translationX = -position * w
                    val s = 0.85f + 0.15f * (1f - position)
                    page.scaleX = s
                    page.scaleY = s
                    page.alpha = 1f - position * 0.6f
                } else {
                    page.translationZ = 4f
                }
            }
            PageAnimation.ZOOM -> {
                val s = max(0.82f, 1f - abs(position) * 0.18f)
                page.scaleX = s
                page.scaleY = s
                page.alpha = 0.5f + 0.5f * (1f - abs(position))
            }
            PageAnimation.CUBE -> {
                page.pivotX = if (position < 0f) w else 0f
                page.rotationY = 90f * position
            }
            PageAnimation.FLIP -> {
                page.translationX = -position * w
                page.rotationY = 180f * position
                page.alpha = if (abs(position) < 0.5f) 1f else 0f
            }
            PageAnimation.TURNSTILE -> {
                // Windows Phone turnstile: pages swing on their left edge
                page.translationX = -position * w
                page.pivotX = 0f
                page.rotationY = -80f * position
                page.alpha = 1f - abs(position) * 0.7f
                if (position < 0f) page.translationZ = 2f
            }
            else -> {}
        }
    }
}
