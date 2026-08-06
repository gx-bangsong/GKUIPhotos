/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Module 5: an interactive crop surface used by the ID-photo tool (and the
 * generic editor crop). It renders the source [Bitmap] with pan/zoom gestures,
 * draws a fixed-aspect crop rectangle, and overlays rule-of-thirds lines plus a
 * dashed face-alignment oval so the user can frame a standard head-and-shoulders
 * photo purely by hand — no cloud, no face detection.
 *
 * Call [crop] to extract the visible crop region as a new [Bitmap].
 */
class CropImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private val inverseMatrix = Matrix()

    /** Aspect ratio of the locked crop rectangle (w:h). */
    var aspectRatio: Float = 1f
        set(value) {
            field = value.coerceAtLeast(0.1f)
            requestLayout()
            invalidate()
        }

    /** When true the face-alignment oval is drawn inside the crop rectangle. */
    var showFaceGuide: Boolean = true

    // Gesture state
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.3f, 8f)
            invalidate()
            return true
        }
    })

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }
    private val ovalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val scrimPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val cropRect = RectF()
    private var initialized = false

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        initialized = false
        requestLayout()
        invalidate()
    }

    fun reset() {
        scaleFactor = fitScale()
        translateX = 0f
        translateY = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!initialized && bitmap != null && width > 0 && height > 0) {
            scaleFactor = fitScale()
            initialized = true
        }
    }

    private fun fitScale(): Float {
        val bmp = bitmap ?: return 1f
        // Fit the bitmap to cover the whole view (center-crop) as the starting point.
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        return max(sx, sy).coerceAtLeast(0.01f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        // Center the bitmap; apply current scale/translate.
        val cx = width / 2f
        val cy = height / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scaleFactor, scaleFactor)
        drawMatrix.postTranslate(
            cx - bmp.width / 2f * scaleFactor + translateX,
            cy - bmp.height / 2f * scaleFactor + translateY,
        )
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

        // Compute the centered crop rectangle respecting aspect ratio.
        computeCropRect()
        drawGuides(canvas)
    }

    private fun computeCropRect() {
        val padding = 24f
        val maxW = width - padding * 2
        val maxH = height - padding * 2
        var w = maxW
        var h = w / aspectRatio
        if (h > maxH) {
            h = maxH
            w = h * aspectRatio
        }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        cropRect.set(left, top, left + w, top + h)
    }

    private fun drawGuides(canvas: Canvas) {
        // Dim everything except the crop rectangle. Scrim + erase must share a
        // layer so the CLEAR xfermode actually punches a hole through the scrim.
        val full = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val saveCount = canvas.saveLayer(full, null)
        canvas.drawRect(full, scrimPaint)
        canvas.drawRect(cropRect, clearPaint)
        canvas.restoreToCount(saveCount)

        // Rule of thirds.
        val thirdW = cropRect.width() / 3f
        val thirdH = cropRect.height() / 3f
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left + 2 * thirdW, cropRect.top, cropRect.left + 2 * thirdW, cropRect.bottom, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, gridPaint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * thirdH, cropRect.right, cropRect.top + 2 * thirdH, gridPaint)
        canvas.drawRect(cropRect, gridPaint)

        // Dashed face-alignment oval (about 60% of the crop height, near the top).
        if (showFaceGuide) {
            val ovalH = cropRect.height() * 0.6f
            val ovalW = ovalH * 0.75f
            val oval = RectF(
                cropRect.centerX() - ovalW / 2f,
                cropRect.top + cropRect.height() * 0.12f,
                cropRect.centerX() + ovalW / 2f,
                cropRect.top + cropRect.height() * 0.12f + ovalH,
            )
            canvas.drawOval(oval, ovalPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    /**
     * Extract the portion of the source bitmap currently shown inside the crop
     * rectangle, returning a new (possibly filtered-elsewhere) [Bitmap].
     */
    fun crop(): Bitmap? {
        val bmp = bitmap ?: return null
        computeCropRect()
        drawMatrix.invert(inverseMatrix)
        val srcCorners = floatArrayOf(
            cropRect.left, cropRect.top,
            cropRect.right, cropRect.bottom,
        )
        inverseMatrix.mapPoints(srcCorners)
        var left = min(srcCorners[0], srcCorners[2]).toInt().coerceIn(0, bmp.width - 1)
        var top = min(srcCorners[1], srcCorners[3]).toInt().coerceIn(0, bmp.height - 1)
        var right = max(srcCorners[0], srcCorners[2]).toInt().coerceIn(left + 1, bmp.width)
        var bottom = max(srcCorners[1], srcCorners[3]).toInt().coerceIn(top + 1, bmp.height)
        return Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
    }
}
