package com.textreader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** ImageView con zoom por pellizco, arrastre y doble toque para acercar/alejar. */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val drawMatrix = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private val maxZoomFactor = 8f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!scaleDetector.isInProgress) {
                    drawMatrix.postTranslate(-distanceX, -distanceY)
                    constrain()
                    imageMatrix = drawMatrix
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current > fitScale * 1.05f) {
                    fit()
                } else {
                    zoomBy((fitScale * 3f) / current, e.x, e.y)
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    private fun fit() {
        val d = drawable ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (vw <= 0f || vh <= 0f || dw <= 0f || dh <= 0f) return
        fitScale = min(vw / dw, vh / dh)
        drawMatrix.reset()
        drawMatrix.postScale(fitScale, fitScale)
        drawMatrix.postTranslate((vw - dw * fitScale) / 2f, (vh - dh * fitScale) / 2f)
        imageMatrix = drawMatrix
    }

    private fun currentScale(): Float {
        drawMatrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val current = currentScale()
        if (current <= 0f) return
        val target = (current * factor).coerceIn(fitScale, fitScale * maxZoomFactor)
        val applied = target / current
        drawMatrix.postScale(applied, applied, focusX, focusY)
        constrain()
        imageMatrix = drawMatrix
    }

    /** Evita que la imagen se salga de la pantalla o quede descentrada cuando es más chica que la vista. */
    private fun constrain() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        val vw = width.toFloat()
        val vh = height.toFloat()
        var dx = 0f
        var dy = 0f
        if (rect.width() <= vw) {
            dx = (vw - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < vw) {
            dx = vw - rect.right
        }
        if (rect.height() <= vh) {
            dy = (vh - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < vh) {
            dy = vh - rect.bottom
        }
        drawMatrix.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }
}
