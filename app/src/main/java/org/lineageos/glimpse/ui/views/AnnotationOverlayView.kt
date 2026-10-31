/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class MarkupTool {
    NONE,
    DOODLE,
    SHAPES,
    TEXT,
    MOSAIC,
}

enum class ShapeType {
    ARROW,
    RECTANGLE,
    ELLIPSE,
    LINE,
}

sealed interface AnnotationItem {
    fun draw(canvas: Canvas)

    data class Doodle(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
    ) : AnnotationItem {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Doodle.color
            strokeWidth = this@Doodle.strokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
    }

    data class Shape(
        val type: ShapeType,
        var startX: Float,
        var startY: Float,
        var endX: Float,
        var endY: Float,
        val color: Int,
        val strokeWidth: Float,
    ) : AnnotationItem {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Shape.color
            strokeWidth = this@Shape.strokeWidth
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@Shape.color
            style = Paint.Style.FILL
        }

        override fun draw(canvas: Canvas) {
            when (type) {
                ShapeType.LINE -> {
                    canvas.drawLine(startX, startY, endX, endY, strokePaint)
                }
                ShapeType.RECTANGLE -> {
                    val l = min(startX, endX)
                    val t = min(startY, endY)
                    val r = max(startX, endX)
                    val b = max(startY, endY)
                    canvas.drawRect(l, t, r, b, strokePaint)
                }
                ShapeType.ELLIPSE -> {
                    val l = min(startX, endX)
                    val t = min(startY, endY)
                    val r = max(startX, endX)
                    val b = max(startY, endY)
                    canvas.drawOval(RectF(l, t, r, b), strokePaint)
                }
                ShapeType.ARROW -> {
                    canvas.drawLine(startX, startY, endX, endY, strokePaint)
                    val angle = atan2((endY - startY).toDouble(), (endX - startX).toDouble())
                    val arrowLen = max(24f, strokeWidth * 3.5f)
                    val arrowAngle = Math.PI / 6.0 // 30 degrees

                    val x1 = (endX - arrowLen * cos(angle - arrowAngle)).toFloat()
                    val y1 = (endY - arrowLen * sin(angle - arrowAngle)).toFloat()
                    val x2 = (endX - arrowLen * cos(angle + arrowAngle)).toFloat()
                    val y2 = (endY - arrowLen * sin(angle + arrowAngle)).toFloat()

                    val headPath = Path().apply {
                        moveTo(endX, endY)
                        lineTo(x1, y1)
                        lineTo(x2, y2)
                        close()
                    }
                    canvas.drawPath(headPath, fillPaint)
                }
            }
        }
    }

    data class Text(
        var text: String,
        var centerX: Float,
        var centerY: Float,
        var baseTextSize: Float = 32f,
        var scale: Float = 1.0f,
        var color: Int = Color.WHITE,
        var hasBackgroundPill: Boolean = true,
    ) : AnnotationItem {
        val effectiveTextSize: Float
            get() = (baseTextSize * scale).coerceIn(12f, 240f)

        fun computeBounds(outRect: RectF) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = effectiveTextSize
                isFakeBoldText = true
            }
            val textWidth = paint.measureText(text.ifEmpty { " " })
            val fm = paint.fontMetrics
            val textHeight = fm.bottom - fm.top
            val padX = effectiveTextSize * 0.45f
            val padY = effectiveTextSize * 0.3f

            val w = textWidth + padX * 2f
            val h = textHeight + padY * 2f
            outRect.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
        }

        override fun draw(canvas: Canvas) {
            if (text.isBlank()) return

            val bounds = RectF()
            computeBounds(bounds)

            if (hasBackgroundPill) {
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (this@Text.color == Color.WHITE) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)
                    style = Paint.Style.FILL
                }
                val pillRadius = bounds.height() * 0.25f
                canvas.drawRoundRect(bounds, pillRadius, pillRadius, pillPaint)
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = effectiveTextSize
                color = this@Text.color
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }

            val fm = paint.fontMetrics
            val baseline = bounds.centerY() - (fm.descent + fm.ascent) / 2f
            canvas.drawText(text, bounds.centerX(), baseline, paint)
        }
    }

    data class Mosaic(
        val path: Path,
        val brushSize: Float,
        val cellSize: Int,
    ) : AnnotationItem {
        override fun draw(canvas: Canvas) {
            // Drawn via mosaic shader in AnnotationOverlayView
        }
    }
}

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var activeTool: MarkupTool = MarkupTool.NONE
        set(value) {
            commitActiveShape()
            commitActiveText()
            field = value
            invalidate()
        }

    var selectedShapeType: ShapeType = ShapeType.ARROW
        set(value) {
            commitActiveShape()
            field = value
        }

    var activeColor: Int = Color.RED
    var activeStrokeWidth: Float = 8f
    var hasTextBackgroundPill: Boolean = true
    var activeMosaicCellSize: Int = 16
    var activeMosaicBrushSize: Float = 32f

    var onAnnotationChanged: (() -> Unit)? = null
    var onTextEditRequested: ((AnnotationItem.Text) -> Unit)? = null
    var cropRectProvider: (() -> RectF)? = null
    var displayBitmapProvider: (() -> Bitmap?)? = null

    val annotations = mutableListOf<AnnotationItem>()

    // Current doodle in progress
    private var currentDoodlePath: Path? = null
    private var lastDoodleX = 0f
    private var lastDoodleY = 0f

    // Current mosaic stroke in progress
    private var currentMosaicPath: Path? = null
    private var lastMosaicX = 0f
    private var lastMosaicY = 0f

    // Mosaic shader cache
    private val mosaicShaderCache = mutableMapOf<Int, BitmapShader>()

    // Active uncommitted shape with handles
    var activeShape: AnnotationItem.Shape? = null
        private set

    // Active text item
    var activeText: AnnotationItem.Text? = null
        private set

    // Handle dragging state
    private var draggedHandle: Int = -1 // 0: start, 1: end, -1: none
    private var isDraggingTextHandle: Boolean = false
    private var isMovingText: Boolean = false
    private var textDragStartX = 0f
    private var textDragStartY = 0f
    private var textInitialDist = 0f
    private var textInitialScale = 1f
    private var lastTapTime = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            activeText?.let { t ->
                t.scale = (t.scale * detector.scaleFactor).coerceIn(0.3f, 6.0f)
                onAnnotationChanged?.invoke()
                invalidate()
                return true
            }
            return false
        }
    })

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
    }
    private val boundsDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    fun invalidateMosaicCache() {
        mosaicShaderCache.clear()
        invalidate()
    }

    private fun getOrCreateMosaicShader(cellSize: Int): BitmapShader? {
        val cached = mosaicShaderCache[cellSize]
        if (cached != null) return cached

        val bmp = displayBitmapProvider?.invoke() ?: return null
        val cs = cellSize.coerceAtLeast(2)
        val sW = max(1, bmp.width / cs)
        val sH = max(1, bmp.height / cs)
        val sBmp = Bitmap.createScaledBitmap(bmp, sW, sH, false)
        val mBmp = Bitmap.createScaledBitmap(sBmp, bmp.width, bmp.height, false)
        if (sBmp !== mBmp && sBmp !== bmp) sBmp.recycle()

        val shader = BitmapShader(mBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        mosaicShaderCache[cellSize] = shader
        return shader
    }

    fun commitActiveShape() {
        val s = activeShape ?: return
        annotations.add(s)
        activeShape = null
        draggedHandle = -1
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun commitActiveText() {
        val t = activeText ?: return
        if (t.text.isNotBlank()) {
            annotations.add(t)
        }
        activeText = null
        isDraggingTextHandle = false
        isMovingText = false
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun addTextItem(text: String, cx: Float, cy: Float) {
        commitActiveShape()
        commitActiveText()
        val item = AnnotationItem.Text(
            text = text,
            centerX = cx,
            centerY = cy,
            color = activeColor,
            hasBackgroundPill = hasTextBackgroundPill,
        )
        activeText = item
        activeTool = MarkupTool.TEXT
        onAnnotationChanged?.invoke()
        invalidate()
    }

    fun undo(): Boolean {
        if (activeShape != null) {
            activeShape = null
            draggedHandle = -1
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        if (activeText != null) {
            activeText = null
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        if (annotations.isNotEmpty()) {
            annotations.removeAt(annotations.size - 1)
            invalidate()
            onAnnotationChanged?.invoke()
            return true
        }
        return false
    }

    fun clear() {
        activeShape = null
        activeText = null
        draggedHandle = -1
        annotations.clear()
        invalidate()
        onAnnotationChanged?.invoke()
    }

    fun hasAnnotations(): Boolean = annotations.isNotEmpty() || activeShape != null || activeText != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cropRect = cropRectProvider?.invoke()
        if (cropRect != null && !cropRect.isEmpty) {
            canvas.save()
            canvas.clipRect(cropRect)
        }

        // 1. Draw committed annotations
        for (item in annotations) {
            if (item is AnnotationItem.Mosaic) {
                val shader = getOrCreateMosaicShader(item.cellSize)
                if (shader != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = item.brushSize
                        isFilterBitmap = false
                    }
                    canvas.drawPath(item.path, paint)
                }
            } else {
                item.draw(canvas)
            }
        }

        // 2. Draw active doodle in progress
        currentDoodlePath?.let { path ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = activeColor
                strokeWidth = activeStrokeWidth
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, paint)
        }

        // 3. Draw active mosaic in progress
        currentMosaicPath?.let { path ->
            val shader = getOrCreateMosaicShader(activeMosaicCellSize)
            if (shader != null) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.shader = shader
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = activeMosaicBrushSize
                    isFilterBitmap = false
                }
                canvas.drawPath(path, paint)
            }
        }

        // 4. Draw active shape and handles
        activeShape?.let { shape ->
            shape.draw(canvas)
            drawHandle(canvas, shape.startX, shape.startY)
            drawHandle(canvas, shape.endX, shape.endY)
        }

        // 5. Draw active text with bounding outline and resize handle
        activeText?.let { t ->
            t.draw(canvas)
            val textBounds = RectF()
            t.computeBounds(textBounds)
            canvas.drawRoundRect(textBounds, 8f, 8f, boundsDashPaint)
            drawHandle(canvas, textBounds.right, textBounds.bottom)
        }

        if (cropRect != null && !cropRect.isEmpty) {
            canvas.restore()
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = 16f
        canvas.drawCircle(x, y, radius, handlePaint)
        canvas.drawCircle(x, y, radius, handleBorderPaint)
    }

    private fun findTouchedHandle(shape: AnnotationItem.Shape, x: Float, y: Float): Int {
        val hitRadius = 48f
        if (hypot((x - shape.startX).toDouble(), (y - shape.startY).toDouble()) <= hitRadius) return 0
        if (hypot((x - shape.endX).toDouble(), (y - shape.endY).toDouble()) <= hitRadius) return 1
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (activeTool == MarkupTool.NONE) return false

        if (activeTool == MarkupTool.TEXT) {
            scaleDetector.onTouchEvent(event)
        }

        val x = event.x
        val y = event.y

        when (activeTool) {
            MarkupTool.DOODLE -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val path = Path().apply { moveTo(x, y) }
                        currentDoodlePath = path
                        lastDoodleX = x
                        lastDoodleY = y
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentDoodlePath?.let { path ->
                            val dx = kotlin.math.abs(x - lastDoodleX)
                            val dy = kotlin.math.abs(y - lastDoodleY)
                            if (dx >= 2 || dy >= 2) {
                                path.quadTo(lastDoodleX, lastDoodleY, (x + lastDoodleX) / 2f, (y + lastDoodleY) / 2f)
                                lastDoodleX = x
                                lastDoodleY = y
                                invalidate()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentDoodlePath?.let { path ->
                            path.lineTo(x, y)
                            annotations.add(AnnotationItem.Doodle(path, activeColor, activeStrokeWidth))
                            currentDoodlePath = null
                            onAnnotationChanged?.invoke()
                            invalidate()
                        }
                        return true
                    }
                    else -> return false
                }
            }

            MarkupTool.SHAPES -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val existing = activeShape
                        if (existing != null) {
                            val handleIdx = findTouchedHandle(existing, x, y)
                            if (handleIdx != -1) {
                                draggedHandle = handleIdx
                                return true
                            } else {
                                commitActiveShape()
                            }
                        }

                        activeShape = AnnotationItem.Shape(
                            type = selectedShapeType,
                            startX = x,
                            startY = y,
                            endX = x,
                            endY = y,
                            color = activeColor,
                            strokeWidth = activeStrokeWidth,
                        )
                        draggedHandle = 1
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val shape = activeShape ?: return false
                        if (draggedHandle == 0) {
                            shape.startX = x
                            shape.startY = y
                        } else if (draggedHandle == 1) {
                            shape.endX = x
                            shape.endY = y
                        }
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        draggedHandle = -1
                        onAnnotationChanged?.invoke()
                        invalidate()
                        return true
                    }
                    else -> return false
                }
            }

            MarkupTool.TEXT -> {
                val t = activeText
                val hitRadius = 48f

                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (t != null) {
                            val bounds = RectF()
                            t.computeBounds(bounds)

                            // 1. Check resize handle at (bounds.right, bounds.bottom)
                            if (hypot((x - bounds.right).toDouble(), (y - bounds.bottom).toDouble()) <= hitRadius) {
                                isDraggingTextHandle = true
                                textInitialDist = hypot((x - t.centerX).toDouble(), (y - t.centerY).toDouble()).toFloat().coerceAtLeast(10f)
                                textInitialScale = t.scale
                                return true
                            }

                            // 2. Check tap/move inside bounds
                            if (bounds.contains(x, y)) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 300) {
                                    onTextEditRequested?.invoke(t)
                                    lastTapTime = 0
                                    return true
                                }
                                lastTapTime = now
                                isMovingText = true
                                textDragStartX = x
                                textDragStartY = y
                                return true
                            }

                            // Tap outside: commit current text
                            commitActiveText()
                        }

                        // Tap empty space: create new text item
                        val now = System.currentTimeMillis()
                        lastTapTime = now
                        val newItem = AnnotationItem.Text(
                            text = "",
                            centerX = x,
                            centerY = y,
                            color = activeColor,
                            hasBackgroundPill = hasTextBackgroundPill,
                        )
                        activeText = newItem
                        invalidate()
                        onTextEditRequested?.invoke(newItem)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (scaleDetector.isInProgress) return true

                        val currentText = activeText ?: return false
                        if (isDraggingTextHandle) {
                            val currentDist = hypot((x - currentText.centerX).toDouble(), (y - currentText.centerY).toDouble()).toFloat()
                            val newScale = (currentDist / textInitialDist * textInitialScale).coerceIn(0.3f, 6.0f)
                            currentText.scale = newScale
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        } else if (isMovingText) {
                            val dx = x - textDragStartX
                            val dy = y - textDragStartY
                            currentText.centerX += dx
                            currentText.centerY += dy
                            textDragStartX = x
                            textDragStartY = y
                            onAnnotationChanged?.invoke()
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isDraggingTextHandle = false
                        isMovingText = false
                    }
                }
                return true
            }

            MarkupTool.MOSAIC -> {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val path = Path().apply { moveTo(x, y) }
                        currentMosaicPath = path
                        lastMosaicX = x
                        lastMosaicY = y
                        invalidate()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        currentMosaicPath?.let { path ->
                            val dx = kotlin.math.abs(x - lastMosaicX)
                            val dy = kotlin.math.abs(y - lastMosaicY)
                            if (dx >= 2 || dy >= 2) {
                                path.quadTo(lastMosaicX, lastMosaicY, (x + lastMosaicX) / 2f, (y + lastMosaicY) / 2f)
                                lastMosaicX = x
                                lastMosaicY = y
                                invalidate()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        currentMosaicPath?.let { path ->
                            path.lineTo(x, y)
                            annotations.add(AnnotationItem.Mosaic(path, activeMosaicBrushSize, activeMosaicCellSize))
                            currentMosaicPath = null
                            onAnnotationChanged?.invoke()
                            invalidate()
                        }
                        return true
                    }
                    else -> return false
                }
            }

            else -> return false
        }
    }

    /**
     * Rasterize all annotations onto a target canvas mapped relative to the visible crop rectangle.
     */
    fun rasterize(canvas: Canvas, cropRect: RectF, exportBitmap: Bitmap? = null) {
        commitActiveShape()
        commitActiveText()
        if (annotations.isEmpty()) return
        if (cropRect.isEmpty) return

        canvas.save()
        val scaleX = canvas.width.toFloat() / cropRect.width()
        val scaleY = canvas.height.toFloat() / cropRect.height()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-cropRect.left, -cropRect.top)

        // Precompute export mosaic shaders if exportBitmap is provided
        val exportShaders = mutableMapOf<Int, BitmapShader>()
        val mosaicItems = annotations.filterIsInstance<AnnotationItem.Mosaic>()
        if (exportBitmap != null && mosaicItems.isNotEmpty()) {
            val cellSizes = mosaicItems.map { it.cellSize }.distinct()
            for (cs in cellSizes) {
                val clampedCs = cs.coerceAtLeast(2)
                val sW = max(1, exportBitmap.width / clampedCs)
                val sH = max(1, exportBitmap.height / clampedCs)
                val sBmp = Bitmap.createScaledBitmap(exportBitmap, sW, sH, false)
                val mBmp = Bitmap.createScaledBitmap(sBmp, exportBitmap.width, exportBitmap.height, false)
                if (sBmp !== mBmp && sBmp !== exportBitmap) sBmp.recycle()
                exportShaders[cs] = BitmapShader(mBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
        }

        for (item in annotations) {
            if (item is AnnotationItem.Mosaic) {
                val shader = exportShaders[item.cellSize] ?: getOrCreateMosaicShader(item.cellSize)
                if (shader != null) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.shader = shader
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        strokeWidth = item.brushSize
                        isFilterBitmap = false
                    }
                    canvas.drawPath(item.path, paint)
                }
            } else {
                item.draw(canvas)
            }
        }

        canvas.restore()
    }
}
