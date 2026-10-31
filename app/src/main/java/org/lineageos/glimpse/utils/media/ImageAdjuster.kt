/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class AdjustParameters(
    val saturation: Int = 0,    // -100..100 (0 = neutral 1.0)
    val contrast: Int = 0,      // -100..100 (0 = neutral 1.0)
    val temperature: Int = 0,   // -100..100 (0 = neutral)
    val sharpen: Int = 0,       // 0..100 (0 = off)
) {
    val isDefault: Boolean
        get() = saturation == 0 && contrast == 0 && temperature == 0 && sharpen == 0
}

object ImageAdjuster {

    /**
     * Compute combined ColorMatrix for Saturation, Contrast, and Temperature.
     */
    fun buildColorMatrix(params: AdjustParameters): ColorMatrix? {
        if (params.saturation == 0 && params.contrast == 0 && params.temperature == 0) {
            return null
        }

        val matrix = ColorMatrix()

        // 1. Saturation: -100..100 -> 0.0f..2.0f
        if (params.saturation != 0) {
            val sat = 1f + (params.saturation / 100f)
            val satMat = ColorMatrix().apply { setSaturation(sat.coerceAtLeast(0f)) }
            matrix.postConcat(satMat)
        }

        // 2. Contrast: -100..100 -> 0.5f..1.5f
        if (params.contrast != 0) {
            val c = 1f + (params.contrast / 100f) * 0.5f
            val t = (-0.5f * c + 0.5f) * 255f
            val contrastMat = ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            matrix.postConcat(contrastMat)
        }

        // 3. Color Temperature (Warm / Cool shift)
        if (params.temperature != 0) {
            val factor = params.temperature / 100f
            val rScale: Float
            val gScale: Float
            val bScale: Float

            if (factor > 0) {
                // Warm: boost Red and slight Green, reduce Blue
                rScale = 1f + factor * 0.25f
                gScale = 1f + factor * 0.05f
                bScale = 1f - factor * 0.25f
            } else {
                // Cool: boost Blue, reduce Red
                val absF = abs(factor)
                rScale = 1f - absF * 0.25f
                gScale = 1f
                bScale = 1f + absF * 0.25f
            }

            val tempMat = ColorMatrix(
                floatArrayOf(
                    rScale, 0f, 0f, 0f, 0f,
                    0f, gScale, 0f, 0f, 0f,
                    0f, 0f, bScale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
            matrix.postConcat(tempMat)
        }

        return matrix
    }

    /**
     * Apply 3x3 unsharp convolution kernel on bitmap in pure Kotlin.
     * Strength is blended in 0..100% to ensure clean edges without halos.
     */
    fun applySharpen(source: Bitmap, strengthPercent: Int): Bitmap {
        val pct = strengthPercent.coerceIn(0, 100)
        if (pct == 0) return source

        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        val dstPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        // Blend weight factor: 0.0 to 0.6 at 100% (calibrated to prevent halos)
        val blend = (pct / 100f) * 0.6f

        for (y in 0 until h) {
            val ym1 = if (y > 0) y - 1 else y
            val yp1 = if (y < h - 1) y + 1 else y
            val rowOffset = y * w
            val rowM1Offset = ym1 * w
            val rowP1Offset = yp1 * w

            for (x in 0 until w) {
                val xm1 = if (x > 0) x - 1 else x
                val xp1 = if (x < w - 1) x + 1 else x

                val centerPix = srcPixels[rowOffset + x]
                val a = (centerPix ushr 24) and 0xFF
                val rC = (centerPix ushr 16) and 0xFF
                val gC = (centerPix ushr 8) and 0xFF
                val bC = centerPix and 0xFF

                val topPix = srcPixels[rowM1Offset + x]
                val botPix = srcPixels[rowP1Offset + x]
                val leftPix = srcPixels[rowOffset + xm1]
                val rightPix = srcPixels[rowOffset + xp1]

                // Standard unsharp mask: 5 * center - (top + bottom + left + right)
                val rSharp = 5 * rC - (((topPix ushr 16) and 0xFF) + ((botPix ushr 16) and 0xFF) +
                        ((leftPix ushr 16) and 0xFF) + ((rightPix ushr 16) and 0xFF))
                val gSharp = 5 * gC - (((topPix ushr 8) and 0xFF) + ((botPix ushr 8) and 0xFF) +
                        ((leftPix ushr 8) and 0xFF) + ((rightPix ushr 8) and 0xFF))
                val bSharp = 5 * bC - ((topPix and 0xFF) + (botPix and 0xFF) +
                        (leftPix and 0xFF) + (rightPix and 0xFF))

                // Blend with original pixel
                val rOut = (rC + (rSharp - rC) * blend).toInt().coerceIn(0, 255)
                val gOut = (gC + (gSharp - gC) * blend).toInt().coerceIn(0, 255)
                val bOut = (bC + (bSharp - bC) * blend).toInt().coerceIn(0, 255)

                dstPixels[rowOffset + x] = (a shl 24) or (rOut shl 16) or (gOut shl 8) or bOut
            }
        }

        return Bitmap.createBitmap(dstPixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Apply all adjustments to source bitmap.
     */
    fun applyAdjustments(source: Bitmap, params: AdjustParameters): Bitmap {
        if (params.isDefault) return source

        var current = source

        // Step 1: ColorMatrix (Saturation + Contrast + Temperature)
        val colorMatrix = buildColorMatrix(params)
        if (colorMatrix != null) {
            val colorAdjusted = Bitmap.createBitmap(current.width, current.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(colorAdjusted)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix)
            }
            canvas.drawBitmap(current, 0f, 0f, paint)
            if (current !== source) current.recycle()
            current = colorAdjusted
        }

        // Step 2: Sharpen
        if (params.sharpen > 0) {
            val sharpened = applySharpen(current, params.sharpen)
            if (current !== source) current.recycle()
            current = sharpened
        }

        return current
    }
}
