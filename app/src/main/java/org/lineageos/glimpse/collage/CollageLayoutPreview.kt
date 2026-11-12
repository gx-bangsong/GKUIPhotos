/* Draws the same normalized layout geometry used by collage export.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.min

internal class CollageLayoutPreview(context: Context, private val count: Int, private val layout: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count !in 2..6) return
        val side = min(width, height).toFloat()
        val x = (width - side) / 2f
        val y = (height - side) / 2f
        val gap = resources.displayMetrics.density
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        collageCells(count, layout).forEach { cell ->
            canvas.drawRect(x + cell.left * side + gap, y + cell.top * side + gap,
                x + cell.right * side - gap, y + cell.bottom * side - gap, paint)
        }
    }
}
