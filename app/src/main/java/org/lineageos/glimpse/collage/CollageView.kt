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
import android.view.ScaleGestureDetector
import android.view.View
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
    private var gestureIndex = -1
    private var hadMultiplePointers = false
    private var rejectScale = false
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
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val photo = photos.getOrNull(gestureIndex) ?: return false
            photo.zoom = (photo.zoom * detector.scaleFactor).coerceIn(1f, 4f)
            onSelectionChanged?.invoke()
            invalidate()
            return true
        }
    })

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
            if (i == selected) {
                val inset = border.strokeWidth / 2f
                dest.inset(inset, inset)
                canvas.drawRect(dest, border)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || photos.size !in 2..6) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = square()
            gestureIndex = collageCells(photos.size, layout, split).indexOfFirst {
                rect(it, bounds).contains(event.x, event.y)
            }
            if (gestureIndex < 0) return false
            selected = gestureIndex
            hadMultiplePointers = false
            rejectScale = false
            lastX = event.x
            lastY = event.y
            onSelectionChanged?.invoke()
            invalidate()
        }
        if (gestureIndex !in photos.indices) return false
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            hadMultiplePointers = true
            val cell = rect(collageCells(photos.size, layout, split)[gestureIndex], square())
            // Never turn a two-cell touch into a pinch on the wrong photo.
            if ((0 until event.pointerCount).any { !cell.contains(event.getX(it), event.getY(it)) }) {
                rejectScale = true
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                detector.onTouchEvent(cancel)
                cancel.recycle()
            }
        }
        if (!rejectScale) detector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!hadMultiplePointers && event.pointerCount == 1) {
                    val photo = photos[gestureIndex]
                    val cell = rect(collageCells(photos.size, layout, split)[gestureIndex], square())
                    val scale = max(cell.width() / photo.preview.width, cell.height() / photo.preview.height) * photo.zoom
                    val dx = (photo.preview.width * scale - cell.width()) / 2f
                    val dy = (photo.preview.height * scale - cell.height()) / 2f
                    if (dx > 0f) photo.offsetX = (photo.offsetX + (event.x - lastX) / dx).coerceIn(-1f, 1f)
                    if (dy > 0f) photo.offsetY = (photo.offsetY + (event.y - lastY) / dy).coerceIn(-1f, 1f)
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> {
                gestureIndex = -1
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> gestureIndex = -1
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
