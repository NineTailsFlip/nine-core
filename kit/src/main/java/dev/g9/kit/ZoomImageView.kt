package dev.g9.kit

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import life.furrer.nine.core.ui.Palette

/** A picture fitted to the screen on black, with the keypad's zoom: [toggleZoom] goes to [zoom]x
 *  on the centre and back, [pan] moves a zoomed picture, clamped so it never leaves the screen.
 *  An animated drawable (GIF, animated WebP) starts when set and stops when replaced. */
class ZoomImageView(context: Context) : ImageView(context) {

    /** How far OK zooms in on the centre. */
    var zoom: Float = 2.5f

    var isZoomed = false
        private set

    private var panX = 0f
    private var panY = 0f

    init {
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Palette.BLACK)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMatrix() }
    }

    val hasPicture: Boolean get() = drawable != null

    /** Replaces the picture and goes back to the whole of it. */
    fun show(picture: Drawable?) {
        (drawable as? AnimatedImageDrawable)?.stop()
        isZoomed = false
        panX = 0f
        panY = 0f
        setImageDrawable(picture)
        applyMatrix()
        (picture as? AnimatedImageDrawable)?.start()
    }

    fun toggleZoom() {
        if (!hasPicture) return
        isZoomed = !isZoomed
        panX = 0f
        panY = 0f
        applyMatrix()
    }

    /** A quarter of the screen per key press reads as one deliberate step. */
    fun panStep(): Float = width / 4f

    fun pan(dx: Float, dy: Float) {
        if (!isZoomed) return
        panX += dx
        panY += dy
        applyMatrix()
    }

    private fun applyMatrix() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f || dw <= 0f || dh <= 0f) return
        val fit = minOf(vw / dw, vh / dh)
        val scale = if (isZoomed) fit * zoom else fit
        val maxPanX = ((dw * scale - vw) / 2).coerceAtLeast(0f)
        val maxPanY = ((dh * scale - vh) / 2).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        imageMatrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((vw - dw * scale) / 2 + panX, (vh - dh * scale) / 2 + panY)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        (drawable as? AnimatedImageDrawable)?.stop()
    }
}
