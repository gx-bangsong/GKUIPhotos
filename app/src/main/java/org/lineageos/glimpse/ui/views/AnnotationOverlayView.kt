/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
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
}

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var activeTool: MarkupTool = MarkupTool.NONE
        set(value) {
            commitActiveShape()
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

    var onAnnotationChanged: (() -> Unit)? = null

    val annotations = mutableListOf<AnnotationItem>()

    // Current doodle in progress
    private var currentDoodlePath: Path? = null
    private var lastDoodleX = 0f
    private var lastDoodleY = 0f

    // Active uncommitted shape with handles
    var activeShape: AnnotationItem.Shape? = null
        private set

    // Handle dragging state
    private var draggedHandle: Int = -1 // 0: start, 1: end, -1: none

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2196F3")
        strokeWidth = 3f
    }

    private val clipBoundsRect = RectF()
    var cropRectProvider: (() -> RectF)? = null

    fun commitActiveShape() {
        val s = activeShape ?: return
        annotations.add(s)
        activeShape = null
        draggedHandle = -1
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
        draggedHandle = -1
        annotations.clear()
        invalidate()
        onAnnotationChanged?.invoke()
    }

    fun hasAnnotations(): Boolean = annotations.isNotEmpty() || activeShape != null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cropRect = cropRectProvider?.invoke()
        if (cropRect != null && !cropRect.isEmpty) {
            canvas.save()
            canvas.clipRect(cropRect)
        }

        // 1. Draw committed annotations
        for (item in annotations) {
            item.draw(canvas)
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

        // 3. Draw active shape and handles
        activeShape?.let { shape ->
            shape.draw(canvas)

            // Draw handles at start and end points
            drawHandle(canvas, shape.startX, shape.startY)
            drawHandle(canvas, shape.endX, shape.endY)
        }

        if (cropRect != null && !cropRect.isEmpty) {
            canvas.restore()
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = 18f
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
                        // Check if tapping existing handle
                        val existing = activeShape
                        if (existing != null) {
                            val handleIdx = findTouchedHandle(existing, x, y)
                            if (handleIdx != -1) {
                                draggedHandle = handleIdx
                                return true
                            } else {
                                // Tap away commits the existing shape
                                commitActiveShape()
                            }
                        }

                        // Start new shape
                        activeShape = AnnotationItem.Shape(
                            type = selectedShapeType,
                            startX = x,
                            startY = y,
                            endX = x,
                            endY = y,
                            color = activeColor,
                            strokeWidth = activeStrokeWidth,
                        )
                        draggedHandle = 1 // dragging the end handle
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

            else -> return false
        }
    }

    /**
     * Rasterize all annotations onto a target canvas mapped relative to the visible crop rectangle.
     */
    fun rasterize(canvas: Canvas, cropRect: RectF) {
        commitActiveShape()
        if (annotations.isEmpty()) return
        if (cropRect.isEmpty) return

        canvas.save()
        val scaleX = canvas.width.toFloat() / cropRect.width()
        val scaleY = canvas.height.toFloat() / cropRect.height()
        canvas.scale(scaleX, scaleY)
        canvas.translate(-cropRect.left, -cropRect.top)

        for (item in annotations) {
            item.draw(canvas)
        }

        canvas.restore()
    }
}
