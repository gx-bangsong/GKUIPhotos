/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class AngleRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var currentAngle: Float = 0f
        set(value) {
            field = value.coerceIn(-45f, 45f)
            invalidate()
        }

    var onAngleChanged: ((Float) -> Unit)? = null

    private var lastTouchX = 0f

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = 1.2f * resources.displayMetrics.density
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#999999")
        strokeWidth = 1.6f * resources.displayMetrics.density
    }

    private val centerIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8C7FA")
        strokeWidth = 2.4f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density
        // Every degree occupies 8dp
        val pxPerDegree = 8f * density

        val minAngle = ((currentAngle - (cx / pxPerDegree)).toInt() - 2).coerceAtLeast(-45)
        val maxAngle = ((currentAngle + (cx / pxPerDegree)).toInt() + 2).coerceAtMost(45)

        for (deg in minAngle..maxAngle) {
            val x = cx + (deg - currentAngle) * pxPerDegree
            if (x < 0f || x > w) continue

            val isZero = deg == 0
            val isFive = deg % 5 == 0

            val tickHalfH = when {
                isZero -> h * 0.45f
                isFive -> h * 0.32f
                else -> h * 0.18f
            }

            val paint = if (isFive || isZero) majorTickPaint else minorTickPaint
            canvas.drawLine(x, cy - tickHalfH, x, cy + tickHalfH, paint)
        }

        // Fixed center indicator mark at cx
        val indH = h * 0.48f
        canvas.drawLine(cx, cy - indH, cx, cy + indH, centerIndicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val pxPerDegree = 8f * density

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                val dAngle = -dx / pxPerDegree
                val newAngle = (currentAngle + dAngle).coerceIn(-45f, 45f)
                if (newAngle != currentAngle) {
                    currentAngle = newAngle
                    onAngleChanged?.invoke(currentAngle)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
