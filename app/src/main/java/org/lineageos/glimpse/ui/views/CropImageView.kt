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
            onPreviewGeometryChanged?.invoke()
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

    /** Redraw the sibling overlay when preview geometry changes. */
    var onPreviewGeometryChanged: (() -> Unit)? = null

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
    private var handleStartBounds: RectF? = null

    // Gesture state
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    // Once a second finger participates, do not resume a crop-handle drag or pan
    // until a fresh DOWN. The scale detector still receives the entire stream.
    private var hadMultiplePointers = false

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
    // This dashed, two-tone target is informational: it has no resize handles.
    private val recoveryTargetPaint = Paint().apply { color = Color.argb(60, 255, 255, 255) }
    private val recoveryTargetOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val recoveryTargetHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Editor preview clips to the same integer source pixels used by its export. */
    var clipToOutputBounds: Boolean = false
        set(value) { field = value; invalidate() }

    /** The editor paints crop guides above its sibling annotation layer. */
    var renderEditorGuidesOnOverlay: Boolean = false
        set(value) { field = value; invalidate() }

    fun drawEditorGuides(canvas: Canvas) {
        if (renderEditorGuidesOnOverlay && showCropGuides) {
            cropSnapshot()?.let { drawGuides(canvas, it.viewBounds) }
        }
    }

    /** One coherent geometry/pixel snapshot. Rects and matrices are private copies. */
    class CropSnapshot internal constructor(
        val bitmap: Bitmap,
        source: Rect,
        displayToView: Matrix,
        val isCropped: Boolean,
    ) {
        private val sourceRect = Rect(source)
        private val viewMatrix = Matrix(displayToView)
        val source: Rect get() = Rect(sourceRect)
        val outputWidth: Int get() = sourceRect.width()
        val outputHeight: Int get() = sourceRect.height()
        val displayToView: Matrix get() = Matrix(viewMatrix)
        val viewBounds: RectF get() = RectF(sourceRect).also { viewMatrix.mapRect(it) }
        val displayToOutput: Matrix get() = Matrix().apply {
            setTranslate(-sourceRect.left.toFloat(), -sourceRect.top.toFloat())
        }
        fun createOutputBitmap(): Bitmap = Bitmap.createBitmap(bitmap, sourceRect.left, sourceRect.top,
            outputWidth, outputHeight)
    }

    private data class CropKey(val bitmap: Bitmap, val matrix: Matrix, val requested: RectF,
        val cropped: Boolean)
    private var cachedCropKey: CropKey? = null
    private var cachedCropSnapshot: CropSnapshot? = null
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
        onPreviewGeometryChanged?.invoke()
    }

    /** Replace only pixel colors, preserving crop and viewport for adjustments/filters. */
    fun replaceBitmapPreservingFraming(bmp: Bitmap): Boolean {
        val old = bitmap ?: return false
        if (old.width != bmp.width || old.height != bmp.height) return false
        bitmap = bmp
        invalidate()
        onPreviewGeometryChanged?.invoke()
        return true
    }

    /** A complete externally coordinated two-pointer gesture; coordinates are in this view. */
    fun updatePreviewGesture(previousX: Float, previousY: Float, x: Float, y: Float, factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val old = scaleFactor
        val next = (old * factor).coerceIn(0.3f, 8f)
        val ratio = next / old
        val cx = width / 2f
        val cy = height / 2f
        translateX = x - cx - (previousX - cx - translateX) * ratio
        translateY = y - cy - (previousY - cy - translateY) * ratio
        scaleFactor = next
        hasUserModifiedCrop = true
        onFramingModified?.invoke()
        invalidate()
    }

    fun getTransformState(): Triple<Float, Float, Float> = Triple(scaleFactor, translateX, translateY)

    fun setTransformState(scale: Float, tx: Float, ty: Float) {
        scaleFactor = scale
        translateX = tx
        translateY = ty
        initialized = true
        invalidate()
        onPreviewGeometryChanged?.invoke()
    }

    fun reset() {
        scaleFactor = fitScale()
        translateX = 0f
        translateY = 0f
        hasUserModifiedCrop = false
        computeCropRect()
        invalidate()
        onPreviewGeometryChanged?.invoke()
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
            onPreviewGeometryChanged?.invoke()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onPreviewGeometryChanged?.invoke()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!initialized && bitmap != null && width > 0 && height > 0) {
            scaleFactor = fitScale()
            computeCropRect()
            initialized = true
            onPreviewGeometryChanged?.invoke()
        }
    }

    private fun fitScale(): Float {
        val bmp = bitmap ?: return 1f
        if (clipToOutputBounds) {
            // The unmodified output is the full image: keep its entire boundary
            // visible inside the editor so the initial handles are reachable.
            val padding = 24f * resources.displayMetrics.density
            val availableWidth = (width - 2f * padding).coerceAtLeast(1f)
            val availableHeight = (height - 2f * padding).coerceAtLeast(1f)
            return min(availableWidth / bmp.width, availableHeight / bmp.height).coerceAtLeast(0.01f)
        }
        // ID-photo retains its existing center-crop starting point.
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        return max(sx, sy).coerceAtLeast(0.01f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        val snapshot = cropSnapshot()
        if (snapshot == null) {
            // With no export boundary, show the un-clipped photo (if it is still
            // on screen) and a filled target, but no frame or draggable handles.
            // Even an entirely off-screen image can be panned from anywhere.
            if (clipToOutputBounds) {
                canvas.drawBitmap(bmp, getImageMatrix(), bitmapPaint)
                drawRecoveryTarget(canvas, getCropRect())
            } else {
                drawGuides(canvas, getCropRect())
            }
            return
        }
        drawMatrix.set(snapshot.displayToView)
        canvas.save()
        // Crop mode shows the whole photo; the overlay paints the guides/scrim
        // after annotations, so excluded photo and ink are dimmed together.
        if (clipToOutputBounds && !(renderEditorGuidesOnOverlay && showCropGuides)) {
            canvas.clipRect(snapshot.viewBounds)
        }
        canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
        canvas.restore()

        // Draw live tiled privacy watermark inside cropRect if configured
        val watermark = watermarkText
        if (!watermark.isNullOrBlank()) {
            drawWatermark(canvas, if (clipToOutputBounds) snapshot.viewBounds else cropRect, watermark)
        }

        if (!renderEditorGuidesOnOverlay) {
            drawGuides(canvas, if (clipToOutputBounds) snapshot.viewBounds else getCropRect())
        }
    }

    private fun drawRecoveryTarget(canvas: Canvas, rect: RectF) {
        val density = resources.displayMetrics.density
        val dash = android.graphics.DashPathEffect(floatArrayOf(8f * density, 6f * density), 0f)
        recoveryTargetOutline.apply {
            color = Color.BLACK
            strokeWidth = 5f * density
            pathEffect = dash
        }
        recoveryTargetHighlight.apply {
            color = Color.WHITE
            strokeWidth = 2f * density
            pathEffect = dash
        }
        canvas.drawRect(rect, recoveryTargetPaint)
        canvas.drawRect(rect, recoveryTargetOutline)
        canvas.drawRect(rect, recoveryTargetHighlight)
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

    private fun drawGuides(canvas: Canvas, cropRect: RectF) {
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
                hadMultiplePointers = false
                lastTouchX = event.x
                lastTouchY = event.y

                val snapshot = if (clipToOutputBounds) cropSnapshot() else null
                val touchRect = snapshot?.viewBounds ?: getCropRect()
                activeDragHandle = when {
                    // Invalid editor crops draw no handles: every drag pans until the
                    // image and requested crop overlap again. Re-hit-test on next DOWN.
                    !showCropGuides || (clipToOutputBounds && snapshot == null) -> DragHandle.PAN
                    hypot(event.x - touchRect.left, event.y - touchRect.top) <= handleRadius -> DragHandle.CORNER_TL
                    hypot(event.x - touchRect.right, event.y - touchRect.top) <= handleRadius -> DragHandle.CORNER_TR
                    hypot(event.x - touchRect.left, event.y - touchRect.bottom) <= handleRadius -> DragHandle.CORNER_BL
                    hypot(event.x - touchRect.right, event.y - touchRect.bottom) <= handleRadius -> DragHandle.CORNER_BR
                    aspectRatio <= 0.01f && abs(event.y - touchRect.top) <= handleRadius && event.x in touchRect.left..touchRect.right -> DragHandle.EDGE_TOP
                    aspectRatio <= 0.01f && abs(event.y - touchRect.bottom) <= handleRadius && event.x in touchRect.left..touchRect.right -> DragHandle.EDGE_BOTTOM
                    aspectRatio <= 0.01f && abs(event.x - touchRect.left) <= handleRadius && event.y in touchRect.top..touchRect.bottom -> DragHandle.EDGE_LEFT
                    aspectRatio <= 0.01f && abs(event.x - touchRect.right) <= handleRadius && event.y in touchRect.top..touchRect.bottom -> DragHandle.EDGE_RIGHT
                    else -> DragHandle.PAN
                }
                // Do not mutate the requested crop merely on a tap. If a handle
                // moves, begin from the boundary the user actually saw.
                handleStartBounds = if (clipToOutputBounds && activeDragHandle != DragHandle.PAN &&
                    activeDragHandle != DragHandle.NONE) RectF(touchRect) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiplePointers = true
                activeDragHandle = DragHandle.NONE
                handleStartBounds = null
            }
            MotionEvent.ACTION_MOVE -> {
                if (hadMultiplePointers) return true
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                lastTouchX = event.x
                lastTouchY = event.y

                if (activeDragHandle != DragHandle.PAN && activeDragHandle != DragHandle.NONE) {
                    val maxR = width - padding
                    val maxB = height - padding
                    handleStartBounds?.let { bounds ->
                        if (maxR - padding >= minCropSize && maxB - padding >= minCropSize) {
                            val left = bounds.left.coerceIn(padding, maxR - minCropSize)
                            val top = bounds.top.coerceIn(padding, maxB - minCropSize)
                            cropRect.set(left, top, bounds.right.coerceIn(left + minCropSize, maxR),
                                bounds.bottom.coerceIn(top + minCropSize, maxB))
                        }
                        handleStartBounds = null
                    }

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
                handleStartBounds = null
                hadMultiplePointers = false
            }
        }
        return true
    }

    /**
     * Extract the portion of the source bitmap currently shown inside the crop
     * rectangle, returning a new (possibly filtered-elsewhere) [Bitmap].
     * Returns null if [hasUserModifiedCrop] is false and [force] is false.
     */
    fun cropSnapshot(force: Boolean = false): CropSnapshot? {
        val bmp = bitmap ?: return null
        if (cropRect.isEmpty) computeCropRect()
        val matrix = getImageMatrix()
        val cropped = hasUserModifiedCrop || force
        val requested = if (cropped) RectF(cropRect) else RectF()
        val key = CropKey(bmp, Matrix(matrix), requested, cropped)
        if (key == cachedCropKey) return cachedCropSnapshot
        val source = if (!cropped) Rect(0, 0, bmp.width, bmp.height) else {
            val inverse = Matrix()
            if (!matrix.invert(inverse)) return null
            val corners = floatArrayOf(requested.left, requested.top, requested.right, requested.bottom)
            inverse.mapPoints(corners)
            // No intersecting pixels means no output; never manufacture an edge pixel.
            if (max(corners[0], corners[2]) <= 0f || min(corners[0], corners[2]) >= bmp.width ||
                max(corners[1], corners[3]) <= 0f || min(corners[1], corners[3]) >= bmp.height) {
                cachedCropKey = key
                cachedCropSnapshot = null
                return null
            }
            val left = min(corners[0], corners[2]).toInt().coerceIn(0, bmp.width - 1)
            val top = min(corners[1], corners[3]).toInt().coerceIn(0, bmp.height - 1)
            val right = max(corners[0], corners[2]).toInt().coerceIn(left + 1, bmp.width)
            val bottom = max(corners[1], corners[3]).toInt().coerceIn(top + 1, bmp.height)
            Rect(left, top, right, bottom)
        }
        return CropSnapshot(bmp, source, matrix, cropped).also {
            cachedCropKey = key
            cachedCropSnapshot = it
        }
    }

    /** Requested crop, rounded and clamped in display-bitmap coordinates. */
    fun cropSourceRect(): Rect? = cropSnapshot(force = true)?.source

    /** View-space bounds of the actual output, including the uncropped case. */
    fun getCropOutputBounds(): RectF = cropSnapshot()?.viewBounds ?: RectF()

    fun crop(force: Boolean = false): Bitmap? {
        if (!hasUserModifiedCrop && !force) return null
        return cropSnapshot(force)?.createOutputBitmap()
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
