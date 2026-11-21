package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Grovebook: a soft paper page curl. The page bends around a rolling cylinder,
 * so it lifts and curves like real paper instead of turning as a stiff board.
 */
interface CurlablePage {
    /** 0 = flat, 1 = fully turned away. */
    var curlProgress: Float

    /** Curl from the left edge (right-to-left books) instead of the right edge. */
    var curlFromLeft: Boolean
}

class PageCurl(private val view: ViewGroup) {

    private var snapshot: Bitmap? = null

    /** Colour behind the page content in the snapshot. */
    var paperColor: Int = Color.TRANSPARENT
    private val cols = 28
    private val rows = 16
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val colors = IntArray((cols + 1) * (rows + 1))
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun reset() {
        snapshot?.recycle()
        snapshot = null
    }

    /**
     * Draws the page. When flat it just calls [drawChildren]; while turning it snapshots
     * the page once and draws it warped around the cylinder.
     */
    fun draw(canvas: Canvas, progress: Float, fromLeft: Boolean, drawChildren: (Canvas) -> Unit) {
        val w = view.width
        val h = view.height
        if (progress <= 0.001f || w == 0 || h == 0) {
            if (snapshot != null) reset()
            drawChildren(canvas)
            return
        }
        val bmp = snapshot ?: try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                c.drawColor(paperColor)
                drawChildren(c)
                snapshot = b
            }
        } catch (e: Throwable) {
            // e.g. hardware bitmaps can't be snapshotted; fall back to a plain page
            drawChildren(canvas)
            return
        }

        val p = min(1f, progress)
        val radius = w * 0.14f
        // Fold line moves across the page; at p = 1 everything has rolled away
        val fold = w - p * (w + PI.toFloat() * radius)
        val halfH = h / 2f

        var i = 0
        var ci = 0
        var curlEdge = fold
        for (r in 0..rows) {
            val y = h * r / rows.toFloat()
            for (c in 0..cols) {
                val x0 = w * c / cols.toFloat()
                // Work in "from right" space; mirror for left-edge curls
                val x = if (fromLeft) w - x0 else x0
                var nx: Float
                var z: Float
                var shade: Float
                val d = x - fold
                if (d <= 0f) {
                    nx = x
                    z = 0f
                    shade = 1f
                } else if (d < PI * radius) {
                    val a = d / radius
                    nx = fold + radius * sin(a)
                    z = radius * (1f - cos(a))
                    // Light falls off as the paper turns away, back side is paler
                    shade = if (a < PI / 2) 1f - 0.35f * sin(a) else 0.72f + 0.18f * -cos(a)
                } else {
                    nx = fold - (d - PI.toFloat() * radius)
                    z = 2f * radius
                    shade = 0.86f
                }
                if (nx > curlEdge) curlEdge = nx
                // Perspective: lifted paper grows a little
                val lift = 1f + z / (w * 2.2f)
                val ny = halfH + (y - halfH) * lift
                verts[i++] = if (fromLeft) w - nx else nx
                verts[i++] = ny
                val v = (shade.coerceIn(0f, 1f) * 255).toInt()
                colors[ci++] = Color.argb(255, v, v, v)
            }
        }

        // Soft shadow the curl casts onto the page underneath
        val shadowWidth = w * 0.12f * (1f - p * 0.5f)
        val alpha = (110 * (1f - p)).toInt().coerceIn(0, 110)
        if (alpha > 0) {
            val sx = if (fromLeft) w - curlEdge else curlEdge
            val ex = if (fromLeft) sx - shadowWidth else sx + shadowWidth
            shadowPaint.shader = LinearGradient(
                sx, 0f, ex, 0f,
                Color.argb(alpha, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(min(sx, ex), 0f, maxOf(sx, ex), h.toFloat(), shadowPaint)
        }

        canvas.drawBitmapMesh(bmp, cols, rows, verts, 0, colors, 0, bitmapPaint)
    }
}
