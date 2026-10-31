/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset

/**
 * 6-inch photo paper (4R, 102x152mm, 1200x1800 px @ 300 DPI) print sheet layout composer.
 * Spec: docs/ID_PHOTO_PLAN.md §4.6.
 */
object IdPhotoPrintSheetComposer {

    const val SHEET_SHORT_PX = 1200
    const val SHEET_LONG_PX = 1800
    const val SHEET_DPI = 300
    const val MARGIN_PX = 50
    const val GAP_PX = 30

    data class SheetLayout(
        val sheetWidth: Int,
        val sheetHeight: Int,
        val cols: Int,
        val rows: Int,
        val count: Int,
    )

    /**
     * Compute optimal sheet layout for a given preset.
     * Follows §4.6 exact rules:
     * - 一寸: 2 rows x 4 cols = 8-up on 1800x1200 landscape sheet
     * - 二寸: 2 rows x 2 cols = 4-up on 1200x1800 portrait sheet
     * - Generic N-up: fit maximum count with 50px margins, 30px gaps
     */
    fun computeLayout(cellW: Int, cellH: Int): SheetLayout? {
        if (cellW <= 0 || cellH <= 0) return null

        // §4.6 Special cases
        // Standard 一寸 (295x413 px) -> 8-up (2 rows x 4 cols on landscape)
        if (cellW == 295 && cellH == 413) {
            return SheetLayout(
                sheetWidth = SHEET_LONG_PX,
                sheetHeight = SHEET_SHORT_PX,
                cols = 4,
                rows = 2,
                count = 8,
            )
        }

        // Standard 二寸 (413x579 px) -> 4-up (2 rows x 2 cols on portrait)
        if (cellW == 413 && cellH == 579) {
            return SheetLayout(
                sheetWidth = SHEET_SHORT_PX,
                sheetHeight = SHEET_LONG_PX,
                cols = 2,
                rows = 2,
                count = 4,
            )
        }

        // Generic N-up computation: test both landscape (1800x1200) and portrait (1200x1800)
        fun fit(sheetW: Int, sheetH: Int): SheetLayout {
            val availW = sheetW - 2 * MARGIN_PX
            val availH = sheetH - 2 * MARGIN_PX
            val cols = if (availW >= cellW) (availW + GAP_PX) / (cellW + GAP_PX) else 0
            val rows = if (availH >= cellH) (availH + GAP_PX) / (cellH + GAP_PX) else 0
            return SheetLayout(sheetW, sheetH, cols, rows, cols * rows)
        }

        val landscape = fit(SHEET_LONG_PX, SHEET_SHORT_PX)
        val portrait = fit(SHEET_SHORT_PX, SHEET_LONG_PX)

        val best = if (landscape.count >= portrait.count) landscape else portrait
        return if (best.count >= 2) best else null
    }

    /**
     * Calculate print count for preview badge ("可排 N 张").
     * Returns 0 if cell exceeds sheet or cannot fit at least 2 copies.
     */
    fun calculatePrintCount(preset: IdPhotoPreset): Int {
        val layout = computeLayout(preset.targetWidthPx, preset.targetHeightPx)
        return layout?.count ?: 0
    }

    /**
     * Compose print sheet bitmap with dashed light-gray cut lines.
     */
    fun composeSheet(singlePhoto: Bitmap, preset: IdPhotoPreset): Bitmap? {
        val cellW = preset.targetWidthPx
        val cellH = preset.targetHeightPx
        val layout = computeLayout(cellW, cellH) ?: return null

        val sheetBitmap = Bitmap.createBitmap(
            layout.sheetWidth,
            layout.sheetHeight,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(sheetBitmap)
        canvas.drawColor(Color.WHITE)

        val cutLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#D3D3D3")
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }

        val gridW = layout.cols * cellW + (layout.cols - 1) * GAP_PX
        val gridH = layout.rows * cellH + (layout.rows - 1) * GAP_PX
        val startX = (layout.sheetWidth - gridW) / 2
        val startY = (layout.sheetHeight - gridH) / 2

        for (r in 0 until layout.rows) {
            val y = startY + r * (cellH + GAP_PX)
            for (c in 0 until layout.cols) {
                val x = startX + c * (cellW + GAP_PX)

                canvas.drawBitmap(singlePhoto, x.toFloat(), y.toFloat(), null)
                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + cellW).toFloat(),
                    (y + cellH).toFloat(),
                    cutLinePaint,
                )
            }
        }

        return sheetBitmap
    }
}
