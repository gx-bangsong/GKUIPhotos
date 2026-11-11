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
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * resources.displayMetrics.scaledDensity
        isFakeBoldText = true
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count !in 2..6) return
        val side = min(width, height).toFloat()
        val x = (width - side) / 2f
        val y = (height - side) / 2f
        val gap = resources.displayMetrics.density
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        number.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant)
        collageCells(count, layout).forEachIndexed { index, cell ->
            canvas.drawRect(x + cell.left * side + gap, y + cell.top * side + gap,
                x + cell.right * side - gap, y + cell.bottom * side - gap, paint)
            val cx = x + (cell.left + cell.right) * side / 2
            val cy = y + (cell.top + cell.bottom) * side / 2
            canvas.drawText((index + 1).toString(), cx, cy - (number.ascent() + number.descent()) / 2, number)
        }
    }
}
