package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.view.View
import androidx.viewpager.widget.ViewPager

/**
 * Grovebook: horizontal paging as a real book. The page being turned stays in place
 * and curls away like paper ([PageCurl]); the next page waits flat underneath.
 * Left-to-right books curl from the right edge, right-to-left books from the left.
 */
class BookFlipTransformer(private val rightToLeft: Boolean) : ViewPager.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val width = page.width.toFloat()
        // Reset anything the old rigid flip may have set
        page.rotationY = 0f
        page.scaleX = 1f
        page.scaleY = 1f
        page.pivotX = width / 2f

        if (position <= -1f || position >= 1f) {
            page.alpha = 0f
            (page as? CurlablePage)?.curlProgress = 0f
            return
        }
        page.alpha = 1f
        // Keep every page still; only the curl moves
        page.translationX = -position * width

        val turning = if (rightToLeft) position > 0f else position < 0f
        val curl = page as? CurlablePage
        if (turning) {
            page.translationZ = 4f
            if (curl != null) {
                curl.curlFromLeft = rightToLeft
                curl.curlProgress = kotlin.math.abs(position)
            } else {
                // Unknown page type: gentle slide instead
                page.translationX = 0f
            }
        } else {
            page.translationZ = 0f
            curl?.curlProgress = 0f
        }
    }
}
