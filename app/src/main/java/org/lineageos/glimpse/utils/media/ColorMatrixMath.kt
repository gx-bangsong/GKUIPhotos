/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import kotlin.math.abs

/**
 * Pure-Kotlin 4x5 ColorMatrix mathematics.
 * Zero android.graphics dependencies, guaranteeing full unit testability on standard JVM.
 */
object ColorMatrixMath {

    const val LUMA_R = 0.213f
    const val LUMA_G = 0.715f
    const val LUMA_B = 0.072f

    fun createIdentityMatrix(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )

    /**
     * 4x5 affine matrix multiplication: computes result = a * b.
     * Treats the implicit 5th row as [0, 0, 0, 0, 1].
     */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(20)
        for (r in 0 until 4) {
            val rOffset = r * 5
            for (c in 0 until 4) {
                result[rOffset + c] = (0 until 4).fold(0f) { acc, k ->
                    acc + a[rOffset + k] * b[k * 5 + c]
                }
            }
            // 5th column: translation component
            result[rOffset + 4] = (0 until 4).fold(0f) { acc, k ->
                acc + a[rOffset + k] * b[k * 5 + 4]
            } + a[rOffset + 4]
        }
        return result
    }

    /**
     * Saturation matrix matching standard luminance weights (0.213, 0.715, 0.072).
     * @param saturationPercent -100..100 (0 = neutral identity, -100 = full desaturation, 100 = 2x saturation).
     */
    fun createSaturationMatrix(saturationPercent: Int): FloatArray {
        val clamped = saturationPercent.coerceIn(-100, 100)
        val sat = 1f + (clamped / 100f) // 0.0f..2.0f
        val invSat = 1f - sat
        val r = LUMA_R * invSat
        val g = LUMA_G * invSat
        val b = LUMA_B * invSat

        return floatArrayOf(
            r + sat, g, b, 0f, 0f,
            r, g + sat, b, 0f, 0f,
            r, g, b + sat, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Contrast matrix.
     * @param contrastPercent -100..100 (0 = neutral identity, -100 = low contrast, 100 = high contrast).
     */
    fun createContrastMatrix(contrastPercent: Int): FloatArray {
        val clamped = contrastPercent.coerceIn(-100, 100)
        val c = 1f + (clamped / 100f) * 0.5f // 0.5f..1.5f
        val t = (-0.5f * c + 0.5f) * 255f

        return floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Temperature (Warm/Cool) matrix.
     * Linear temperature response:
     * - factor > 0 (Warm): Red scaled by (1 + factor*0.25), Green by (1 + factor*0.05), Blue by (1 - factor*0.25)
     * - factor < 0 (Cool): Blue scaled by (1 + |factor|*0.25), Red by (1 - |factor|*0.25), Green unchanged
     * @param temperaturePercent -100..100 (0 = neutral identity).
     */
    fun createTemperatureMatrix(temperaturePercent: Int): FloatArray {
        val clamped = temperaturePercent.coerceIn(-100, 100)
        val factor = clamped / 100f

        val rScale: Float
        val gScale: Float
        val bScale: Float

        if (factor > 0) {
            rScale = 1f + factor * 0.25f
            gScale = 1f + factor * 0.05f
            bScale = 1f - factor * 0.25f
        } else {
            val absF = abs(factor)
            rScale = 1f - absF * 0.25f
            gScale = 1f
            bScale = 1f + absF * 0.25f
        }

        return floatArrayOf(
            rScale, 0f, 0f, 0f, 0f,
            0f, gScale, 0f, 0f, 0f,
            0f, 0f, bScale, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /**
     * Compose combined color matrix for Saturation, Contrast, and Temperature in pure Kotlin.
     * Composition order: Saturation -> Contrast -> Temperature.
     */
    fun buildColorMatrixArray(params: AdjustParameters): FloatArray? {
        if (params.saturation == 0 && params.contrast == 0 && params.temperature == 0) {
            return null
        }

        var current = createIdentityMatrix()

        if (params.saturation != 0) {
            val satMat = createSaturationMatrix(params.saturation)
            current = multiply(satMat, current)
        }

        if (params.contrast != 0) {
            val conMat = createContrastMatrix(params.contrast)
            current = multiply(conMat, current)
        }

        if (params.temperature != 0) {
            val tempMat = createTemperatureMatrix(params.temperature)
            current = multiply(tempMat, current)
        }

        return current
    }
}
