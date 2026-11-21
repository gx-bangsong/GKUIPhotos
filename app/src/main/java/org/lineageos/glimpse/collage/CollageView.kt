/* Interactive collage preview and shared cell renderer.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

internal data class CollagePhoto(
    val uri: Uri, val preview: Bitmap,
    var zoom: Float = 1f, var offsetX: Float = 0f, var offsetY: Float = 0f,
)

/** Draws a center-cropped photo using offsets relative to the available overflow. */
internal fun drawCollagePhoto(canvas: Canvas, bitmap: Bitmap, cell: RectF, zoom: Float, x: Float, y: Float) {
    val rect = collageDestination(Cell(cell.left, cell.top, cell.right, cell.bottom),
        bitmap.width, bitmap.height, zoom, x, y)
    val dest = RectF(rect.left, rect.top, rect.right, rect.bottom)
    canvas.save()
    canvas.clipRect(cell)
    canvas.drawBitmap(bitmap, null, dest, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    canvas.restore()
}

class CollageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    internal var photos: MutableList<CollagePhoto> = mutableListOf()
    internal var layout = 0
    internal var split = 0.5f
    internal var selected = 0
    internal var onSelectionChanged: (() -> Unit)? = null
    internal var onPhotoTap: (() -> Unit)? = null
    internal var showSelection = false
    internal var onReorder: ((Int, Int) -> Unit)? = null
    private var previousSelection = 0
    private var dividerGesture = false
    private var originalSplit = .5f
    private var originalZoom = 1f
    private var originalX = 0f
    private var originalY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var reordering = false
    private var targetIndex = -1
    private var dragX = 0f
    private var dragY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPress = Runnable {
        if (gestureIndex in photos.indices && !moved && !hadMultiplePointers) {
            reordering = true
            targetIndex = gestureIndex
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }
    private val targetBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@CollageView, androidx.appcompat.R.attr.colorPrimary)
        style = Paint.Style.STROKE; strokeWidth = 5 * resources.displayMetrics.density
    }
    private var gestureIndex = -1
    private var hadMultiplePointers = false
    private var rejectScale = false
    private var lastSpan = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@CollageView, androidx.appcompat.R.attr.colorPrimary)
        style = Paint.Style.STROKE; strokeWidth = 3 * resources.displayMetrics.density
    }
    private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@CollageView, com.google.android.material.R.attr.colorSurface)
        style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density
    }

    private fun square(): RectF {
        val edge = min(width, height).toFloat().coerceAtLeast(0f)
        return RectF((width - edge) / 2f, (height - edge) / 2f, (width + edge) / 2f, (height + edge) / 2f)
    }

    private fun rect(cell: Cell, square: RectF) = RectF(
        square.left + cell.left * square.width(), square.top + cell.top * square.height(),
        square.left + cell.right * square.width(), square.top + cell.bottom * square.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (photos.size !in 2..6) return
        val bounds = square()
        collageCells(photos.size, layout, split).forEachIndexed { i, cell ->
            val photo = photos[i]
            val dest = rect(cell, bounds)
            drawCollagePhoto(canvas, photo.preview, dest, photo.zoom, photo.offsetX, photo.offsetY)
            canvas.drawRect(dest, divider)
            if (showSelection && i == selected) {
                val inset = border.strokeWidth / 2f
                dest.inset(inset, inset)
                canvas.drawRect(dest, border)
            }
        }
        if (reordering && gestureIndex in photos.indices) {
            val cells = collageCells(photos.size, layout, split)
            if (targetIndex in cells.indices) {
                val target = rect(cells[targetIndex], bounds)
                target.inset(targetBorder.strokeWidth / 2f, targetBorder.strokeWidth / 2f)
                canvas.drawRect(target, targetBorder)
            }
            val size = min(bounds.width(), bounds.height()) * .20f
            val floating = RectF(dragX - size / 2, dragY - size / 2, dragX + size / 2, dragY + size / 2)
            val photo = photos[gestureIndex]
            drawCollagePhoto(canvas, photo.preview, floating, photo.zoom, photo.offsetX, photo.offsetY)
            canvas.drawRect(floating, border)
        }
    }

    private fun cellAt(x: Float, y: Float): Int = collageCells(photos.size, layout, split).indexOfFirst {
        rect(it, square()).contains(x, y)
    }

    private fun horizontalDivider() = layout == 1 && photos.size != 4

    private fun touchesDivider(x: Float, y: Float): Boolean {
        val bounds = square()
        val vertical = !horizontalDivider()
        val line = if (vertical) bounds.left + bounds.width() * split else bounds.top + bounds.height() * split
        if (kotlin.math.abs((if (vertical) x else y) - line) > 10 * resources.displayMetrics.density) return false
        val cells = collageCells(photos.size, layout, split)
        val across = if (vertical) (y - bounds.top) / bounds.height() else (x - bounds.left) / bounds.width()
        if (across !in 0f..1f) return false
        val before = cells.any { c ->
            if (vertical) kotlin.math.abs(c.right - split) < .001f && across >= c.top && across < c.bottom
            else kotlin.math.abs(c.bottom - split) < .001f && across >= c.left && across < c.right
        }
        val after = cells.any { c ->
            if (vertical) kotlin.math.abs(c.left - split) < .001f && across >= c.top && across < c.bottom
            else kotlin.math.abs(c.top - split) < .001f && across >= c.left && across < c.right
        }
        return before && after
    }

    private fun restoreGesture() {
        photos.getOrNull(gestureIndex)?.let {
            it.zoom = originalZoom; it.offsetX = originalX; it.offsetY = originalY
        }
        selected = previousSelection
        onSelectionChanged?.invoke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || photos.size !in 2..6) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureIndex = cellAt(event.x, event.y)
            if (gestureIndex < 0) return false
            parent?.requestDisallowInterceptTouchEvent(true)
            previousSelection = selected
            originalSplit = split
            dividerGesture = touchesDivider(event.x, event.y)
            val photo = photos[gestureIndex]
            originalZoom = photo.zoom; originalX = photo.offsetX; originalY = photo.offsetY
            if (!dividerGesture) selected = gestureIndex
            hadMultiplePointers = false; rejectScale = false; moved = false; reordering = false
            targetIndex = -1
            downX = event.x; downY = event.y; lastX = event.x; lastY = event.y
            dragX = event.x; dragY = event.y
            if (!dividerGesture) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            onSelectionChanged?.invoke()
            invalidate()
        }
        if (gestureIndex !in photos.indices) return false
        if (dividerGesture) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - downX, event.y - downY) > slop) moved = true
                    if (moved) {
                        val bounds = square()
                        split = (if (horizontalDivider()) (event.y - bounds.top) / bounds.height()
                            else (event.x - bounds.left) / bounds.width()).coerceIn(.2f, .8f)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_POINTER_DOWN -> { split = originalSplit; dividerGesture = false; invalidate() }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) { split = originalSplit; restoreGesture() }
                    else if (!moved) { selected = gestureIndex; onSelectionChanged?.invoke(); onPhotoTap?.invoke(); performClick() }
                    gestureIndex = -1; dividerGesture = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            removeCallbacks(longPress)
            // A gesture that becomes a pinch must not also commit a pan.
            if (moved) {
                photos[gestureIndex].offsetX = originalX
                photos[gestureIndex].offsetY = originalY
                invalidate()
            }
            hadMultiplePointers = true
            val cell = rect(collageCells(photos.size, layout, split)[gestureIndex], square())
            if (reordering || (0 until event.pointerCount).any { !cell.contains(event.getX(it), event.getY(it)) }) {
                rejectScale = true
                if (reordering) { restoreGesture(); reordering = false }
            } else if (event.pointerCount == 2) {
                lastSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
            } else {
                rejectScale = true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (reordering) {
                    dragX = event.x; dragY = event.y
                    targetIndex = cellAt(event.x, event.y)
                    invalidate()
                } else if (hadMultiplePointers && !rejectScale && event.pointerCount == 2) {
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    if (lastSpan > 1f && span > 1f) {
                        val photo = photos[gestureIndex]
                        photo.zoom = (photo.zoom * span / lastSpan).coerceIn(1f, 4f)
                        onSelectionChanged?.invoke()
                        invalidate()
                    }
                    lastSpan = span
                } else if (!hadMultiplePointers && event.pointerCount == 1) {
                    if (!moved && hypot(event.x - downX, event.y - downY) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                    }
                    if (moved) {
                        val photo = photos[gestureIndex]
                        val cell = rect(collageCells(photos.size, layout, split)[gestureIndex], square())
                        val scale = max(cell.width() / photo.preview.width, cell.height() / photo.preview.height) * photo.zoom
                        val dx = (photo.preview.width * scale - cell.width()) / 2f
                        val dy = (photo.preview.height * scale - cell.height()) / 2f
                        if (dx > 0f) photo.offsetX = (photo.offsetX + (event.x - lastX) / dx).coerceIn(-1f, 1f)
                        if (dy > 0f) photo.offsetY = (photo.offsetY + (event.y - lastY) / dy).coerceIn(-1f, 1f)
                        invalidate()
                    }
                }
                lastX = event.x; lastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (reordering && event.actionMasked == MotionEvent.ACTION_UP) targetIndex = cellAt(event.x, event.y)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
                    (reordering && (targetIndex !in photos.indices || targetIndex == gestureIndex))) {
                    restoreGesture()
                } else if (reordering) {
                    onReorder?.invoke(gestureIndex, targetIndex)
                } else if (!moved && !hadMultiplePointers) {
                    onPhotoTap?.invoke()
                    performClick()
                }
                gestureIndex = -1; reordering = false; targetIndex = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
