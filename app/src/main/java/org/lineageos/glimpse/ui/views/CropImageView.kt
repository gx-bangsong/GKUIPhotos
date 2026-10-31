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

    /** When true (default), the dimming scrim, rule-of-thirds grid, and crop borders are drawn. */
    var showCropGuides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Callback invoked whenever the user modifies framing via pan or zoom gestures. */
    var onFramingModified: (() -> Unit)? = null

    /** Tiled repeating privacy watermark text drawn live inside the crop area. */
    var watermarkText: String? = null
        set(value) {
            field = value
            invalidate()
        }

    // Gesture state
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.3f, 8f)
            onFramingModified?.invoke()
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

    @JvmOverloads
    fun setBitmap(bmp: Bitmap, restoreTransform: Triple<Float, Float, Float>? = null) {
        bitmap = bmp
        if (restoreTransform != null) {
            scaleFactor = restoreTransform.first
            translateX = restoreTransform.second
            translateY = restoreTransform.third
            initialized = true
        } else {
            initialized = false
        }
        requestLayout()
        invalidate()
    }

    fun getTransformState(): Triple<Float, Float, Float> = Triple(scaleFactor, translateX, translateY)

    fun setTransformState(scale: Float, tx: Float, ty: Float) {
        scaleFactor = scale
        translateX = tx
        translateY = ty
        initialized = true
        invalidate()
    }

    fun reset() {
        scaleFactor = fitScale()
        translateX = 0f
        translateY = 0f
        invalidate()
    }

    fun getCropRect(): RectF {
        computeCropRect()
        return RectF(cropRect)
    }

    fun getDisplayBitmap(): Bitmap? = bitmap

    /**
     * Applies a new preset aspect ratio live.
     * If [oldRatio] shares the same aspect ratio (< 0.005 difference), current pan and zoom
     * coordinates are preserved pixel-identically.
     * If cross-ratio, re-fits the viewport centered while preserving zoom intent.
     */
    fun applyPresetRatio(newRatio: Float, oldRatio: Float? = null) {
        val isSameRatio = oldRatio != null && kotlin.math.abs(newRatio - oldRatio) < 0.005f
        aspectRatio = newRatio
        if (!isSameRatio && oldRatio != null) {
            translateX = 0f
            translateY = 0f
            scaleFactor = max(scaleFactor, fitScale())
            invalidate()
        }
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

        // Draw live tiled privacy watermark inside cropRect if configured
        val watermark = watermarkText
        if (!watermark.isNullOrBlank()) {
            drawWatermark(canvas, cropRect, watermark)
        }

        drawGuides(canvas)
    }

    private fun drawWatermark(canvas: Canvas, rect: RectF, text: String) {
        val w = rect.width()
        val h = rect.height()
        if (w <= 0 || h <= 0) return

        canvas.save()
        canvas.clipRect(rect)

        val diagonal = kotlin.math.hypot(w, h)
        val baseSize = (w / 24f).coerceIn(24f, 48f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = baseSize
            color = Color.argb(90, 255, 255, 255)
            style = Paint.Style.FILL
            isFakeBoldText = true
            setShadowLayer(2.5f, 1.2f, 1.2f, Color.argb(140, 0, 0, 0))
        }

        val textWidth = paint.measureText(text)
        val textHeight = paint.fontMetrics.let { it.bottom - it.top }
        val stepX = textWidth + baseSize * 3.2f
        val stepY = textHeight + baseSize * 3.8f

        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.rotate(-30f, cx, cy)

        val startX = cx - diagonal
        val endX = cx + diagonal
        val startY = cy - diagonal
        val endY = cy + diagonal

        var y = startY
        var row = 0
        while (y <= endY) {
            val rowOffset = if (row % 2 == 0) 0f else stepX / 2f
            var x = startX + rowOffset
            while (x <= endX) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restore()
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
        if (!showCropGuides) return

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
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (dx != 0f || dy != 0f) {
                        translateX += dx
                        translateY += dy
                        onFramingModified?.invoke()
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                    }
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

    /**
     * Computes the normalized crop rectangle [0f..1f] relative to the source bitmap.
     * Used by full-resolution pipelines such as [IdPhotoGenerator].
     */
    fun getNormalizedCropRect(): RectF? {
        val bmp = bitmap ?: return null
        computeCropRect()
        drawMatrix.invert(inverseMatrix)
        val srcCorners = floatArrayOf(
            cropRect.left, cropRect.top,
            cropRect.right, cropRect.bottom,
        )
        inverseMatrix.mapPoints(srcCorners)
        val minX = min(srcCorners[0], srcCorners[2]).coerceIn(0f, bmp.width.toFloat())
        val minY = min(srcCorners[1], srcCorners[3]).coerceIn(0f, bmp.height.toFloat())
        val maxX = max(srcCorners[0], srcCorners[2]).coerceIn(0f, bmp.width.toFloat())
        val maxY = max(srcCorners[1], srcCorners[3]).coerceIn(0f, bmp.height.toFloat())
        if (maxX <= minX || maxY <= minY) return null
        return RectF(minX / bmp.width, minY / bmp.height, maxX / bmp.width, maxY / bmp.height)
    }
}
