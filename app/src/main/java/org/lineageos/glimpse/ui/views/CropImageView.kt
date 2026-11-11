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
import kotlin.math.abs
import kotlin.math.hypot
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

    /** Aspect ratio of the locked crop rectangle (w:h). 0f = freeform crop (match image). */
    var aspectRatio: Float = 0f
        set(value) {
            field = value.coerceAtLeast(0f)
            computeCropRect()
            requestLayout()
            invalidate()
        }

    /** When true the face-alignment oval is drawn inside the crop rectangle. Default is false. */
    var showFaceGuide: Boolean = false

    /** When true (default), the dimming scrim, rule-of-thirds grid, and crop borders are drawn. */
    var showCropGuides: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    /** Callback invoked whenever the user modifies framing via pan, zoom, or handle drag gestures. */
    var onFramingModified: (() -> Unit)? = null

    /** Tracks whether the user has actively manipulated the crop rectangle, ratio, or pan/zoom. */
    var hasUserModifiedCrop: Boolean = false
        private set

    /** Tiled repeating privacy watermark text drawn live inside the crop area. */
    var watermarkText: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private enum class DragHandle {
        NONE,
        CORNER_TL,
        CORNER_TR,
        CORNER_BL,
        CORNER_BR,
        EDGE_TOP,
        EDGE_BOTTOM,
        EDGE_LEFT,
        EDGE_RIGHT,
        PAN,
    }

    private var activeDragHandle = DragHandle.NONE

    // Gesture state
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.3f, 8f)
            hasUserModifiedCrop = true
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
    private val cornerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, Color.argb(160, 0, 0, 0))
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
        cropRect.setEmpty()
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
        hasUserModifiedCrop = false
        computeCropRect()
        invalidate()
    }

    fun getCropRect(): RectF {
        if (cropRect.isEmpty) computeCropRect()
        return RectF(cropRect)
    }

    /**
     * Returns the visible bounds of the image in view coordinates.
     */
    fun getImageBounds(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        getImageMatrix().mapRect(rect)
        return rect
    }

    fun getDisplayBitmap(): Bitmap? = bitmap

    /**
     * Returns the current live transformation matrix mapping original bitmap coordinates to view space.
     */
    fun getImageMatrix(): Matrix {
        val bmp = bitmap ?: return Matrix()
        val matrix = Matrix()
        val cx = width / 2f
        val cy = height / 2f
        matrix.postScale(scaleFactor, scaleFactor)
        matrix.postTranslate(
            cx - bmp.width / 2f * scaleFactor + translateX,
            cy - bmp.height / 2f * scaleFactor + translateY,
        )
        return matrix
    }

    /**
     * Applies a new preset aspect ratio live.
     * If [oldRatio] shares the same aspect ratio (< 0.005 difference), current pan and zoom
     * coordinates are preserved pixel-identically.
     * If cross-ratio, re-fits the viewport centered while preserving zoom intent.
     */
    fun applyPresetRatio(newRatio: Float, oldRatio: Float? = null) {
        hasUserModifiedCrop = true
        val isSameRatio = oldRatio != null && kotlin.math.abs(newRatio - oldRatio) < 0.005f
        aspectRatio = newRatio
        if (!isSameRatio && oldRatio != null) {
            translateX = 0f
            translateY = 0f
            scaleFactor = max(scaleFactor, fitScale())
            computeCropRect()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!initialized && bitmap != null && width > 0 && height > 0) {
            scaleFactor = fitScale()
            computeCropRect()
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

        // Compute the centered crop rectangle if not yet initialized.
        if (cropRect.isEmpty) {
            computeCropRect()
        }

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

    fun computeCropRect() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val padding = 24f * density
        val maxW = width - padding * 2
        val maxH = height - padding * 2
        val bmp = bitmap
        val ratio = if (aspectRatio > 0.01f) {
            aspectRatio
        } else if (bmp != null && bmp.height > 0) {
            bmp.width.toFloat() / bmp.height.toFloat()
        } else {
            1f
        }
        var w = maxW
        var h = w / ratio
        if (h > maxH) {
            h = maxH
            w = h * ratio
        }
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        cropRect.set(left, top, left + w, top + h)
    }

    private fun drawGuides(canvas: Canvas) {
        if (!showCropGuides) return

        // Dim everything except the crop rectangle.
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

        // Google Photos corner handles: 4 white circles with shadow
        val handleR = 6f * resources.displayMetrics.density
        canvas.drawCircle(cropRect.left, cropRect.top, handleR, cornerHandlePaint)
        canvas.drawCircle(cropRect.right, cropRect.top, handleR, cornerHandlePaint)
        canvas.drawCircle(cropRect.left, cropRect.bottom, handleR, cornerHandlePaint)
        canvas.drawCircle(cropRect.right, cropRect.bottom, handleR, cornerHandlePaint)

        // Dashed face-alignment oval (only when showFaceGuide is explicitly enabled by ID photo).
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
        val density = resources.displayMetrics.density
        val handleRadius = 36f * density
        val minCropSize = 48f * density
        val padding = 16f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y

                activeDragHandle = when {
                    hypot(event.x - cropRect.left, event.y - cropRect.top) <= handleRadius -> DragHandle.CORNER_TL
                    hypot(event.x - cropRect.right, event.y - cropRect.top) <= handleRadius -> DragHandle.CORNER_TR
                    hypot(event.x - cropRect.left, event.y - cropRect.bottom) <= handleRadius -> DragHandle.CORNER_BL
                    hypot(event.x - cropRect.right, event.y - cropRect.bottom) <= handleRadius -> DragHandle.CORNER_BR
                    aspectRatio <= 0.01f && abs(event.y - cropRect.top) <= handleRadius && event.x in cropRect.left..cropRect.right -> DragHandle.EDGE_TOP
                    aspectRatio <= 0.01f && abs(event.y - cropRect.bottom) <= handleRadius && event.x in cropRect.left..cropRect.right -> DragHandle.EDGE_BOTTOM
                    aspectRatio <= 0.01f && abs(event.x - cropRect.left) <= handleRadius && event.y in cropRect.top..cropRect.bottom -> DragHandle.EDGE_LEFT
                    aspectRatio <= 0.01f && abs(event.x - cropRect.right) <= handleRadius && event.y in cropRect.top..cropRect.bottom -> DragHandle.EDGE_RIGHT
                    else -> DragHandle.PAN
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                if (activeDragHandle != DragHandle.PAN && activeDragHandle != DragHandle.NONE) {
                    val maxR = width - padding
                    val maxB = height - padding

                    when (activeDragHandle) {
                        DragHandle.CORNER_TL -> {
                            var newLeft = (cropRect.left + dx).coerceIn(padding, cropRect.right - minCropSize)
                            var newTop = (cropRect.top + dy).coerceIn(padding, cropRect.bottom - minCropSize)
                            if (aspectRatio > 0.01f) {
                                val w = cropRect.right - newLeft
                                val h = w / aspectRatio
                                newTop = (cropRect.bottom - h).coerceIn(padding, cropRect.bottom - minCropSize)
                            }
                            cropRect.left = newLeft
                            cropRect.top = newTop
                        }
                        DragHandle.CORNER_TR -> {
                            var newRight = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, maxR)
                            var newTop = (cropRect.top + dy).coerceIn(padding, cropRect.bottom - minCropSize)
                            if (aspectRatio > 0.01f) {
                                val w = newRight - cropRect.left
                                val h = w / aspectRatio
                                newTop = (cropRect.bottom - h).coerceIn(padding, cropRect.bottom - minCropSize)
                            }
                            cropRect.right = newRight
                            cropRect.top = newTop
                        }
                        DragHandle.CORNER_BL -> {
                            var newLeft = (cropRect.left + dx).coerceIn(padding, cropRect.right - minCropSize)
                            var newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, maxB)
                            if (aspectRatio > 0.01f) {
                                val w = cropRect.right - newLeft
                                val h = w / aspectRatio
                                newBottom = (cropRect.top + h).coerceIn(cropRect.top + minCropSize, maxB)
                            }
                            cropRect.left = newLeft
                            cropRect.bottom = newBottom
                        }
                        DragHandle.CORNER_BR -> {
                            var newRight = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, maxR)
                            var newBottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, maxB)
                            if (aspectRatio > 0.01f) {
                                val w = newRight - cropRect.left
                                val h = w / aspectRatio
                                newBottom = (cropRect.top + h).coerceIn(cropRect.top + minCropSize, maxB)
                            }
                            cropRect.right = newRight
                            cropRect.bottom = newBottom
                        }
                        DragHandle.EDGE_TOP -> {
                            cropRect.top = (cropRect.top + dy).coerceIn(padding, cropRect.bottom - minCropSize)
                        }
                        DragHandle.EDGE_BOTTOM -> {
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, maxB)
                        }
                        DragHandle.EDGE_LEFT -> {
                            cropRect.left = (cropRect.left + dx).coerceIn(padding, cropRect.right - minCropSize)
                        }
                        DragHandle.EDGE_RIGHT -> {
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, maxR)
                        }
                        else -> {}
                    }
                    hasUserModifiedCrop = true
                    onFramingModified?.invoke()
                    invalidate()
                } else if (activeDragHandle == DragHandle.PAN) {
                    if (!scaleDetector.isInProgress && (dx != 0f || dy != 0f)) {
                        translateX += dx
                        translateY += dy
                        hasUserModifiedCrop = true
                        onFramingModified?.invoke()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragHandle = DragHandle.NONE
            }
        }
        return true
    }

    /**
     * Extract the portion of the source bitmap currently shown inside the crop
     * rectangle, returning a new (possibly filtered-elsewhere) [Bitmap].
     * Returns null if [hasUserModifiedCrop] is false and [force] is false.
     */
    private fun cropSourceRect(): Rect? {
        val bmp = bitmap ?: return null
        if (cropRect.isEmpty) computeCropRect()
        if (!getImageMatrix().invert(inverseMatrix)) return null
        val corners = floatArrayOf(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
        inverseMatrix.mapPoints(corners)
        val left = min(corners[0], corners[2]).toInt().coerceIn(0, bmp.width - 1)
        val top = min(corners[1], corners[3]).toInt().coerceIn(0, bmp.height - 1)
        val right = max(corners[0], corners[2]).toInt().coerceIn(left + 1, bmp.width)
        val bottom = max(corners[1], corners[3]).toInt().coerceIn(top + 1, bmp.height)
        return Rect(left, top, right, bottom)
    }

    /** The exact view-space bounds of the pixels returned by [crop]. */
    fun getCropOutputBounds(): RectF {
        val source = cropSourceRect() ?: return RectF()
        return RectF(source).also { getImageMatrix().mapRect(it) }
    }

    fun crop(force: Boolean = false): Bitmap? {
        val bmp = bitmap ?: return null
        if (!hasUserModifiedCrop && !force) return null
        val source = cropSourceRect() ?: return null
        return Bitmap.createBitmap(bmp, source.left, source.top, source.width(), source.height())
    }

    /**
     * Computes the normalized crop rectangle [0f..1f] relative to the source bitmap.
     * Used by full-resolution pipelines such as [IdPhotoGenerator].
     */
    fun getNormalizedCropRect(): RectF? {
        val bmp = bitmap ?: return null
        if (cropRect.isEmpty) computeCropRect()
        getImageMatrix().invert(inverseMatrix)
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
