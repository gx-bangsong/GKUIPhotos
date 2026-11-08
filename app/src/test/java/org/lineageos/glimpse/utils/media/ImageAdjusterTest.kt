/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAdjusterTest {

    private val delta = 0.0001f

    @Test
    fun testIdentityAtNeutral() {
        val identity = ColorMatrixMath.createIdentityMatrix()
        assertEquals(20, identity.size)

        // Diagonal elements must be 1.0f
        assertEquals(1f, identity[0], delta)
        assertEquals(1f, identity[6], delta)
        assertEquals(1f, identity[12], delta)
        assertEquals(1f, identity[18], delta)

        // All other elements must be 0.0f
        for (i in 0 until 20) {
            if (i !in listOf(0, 6, 12, 18)) {
                assertEquals("Element at $i must be 0.0f", 0f, identity[i], delta)
            }
        }

        // Each individual factory at 0 must yield identity
        assertArrayEquals(identity, ColorMatrixMath.createSaturationMatrix(0), delta)
        assertArrayEquals(identity, ColorMatrixMath.createContrastMatrix(0), delta)
        assertArrayEquals(identity, ColorMatrixMath.createTemperatureMatrix(0), delta)

        // Default parameters yield null composite array
        assertNull(ColorMatrixMath.buildColorMatrixArray(AdjustParameters()))
    }

    @Test
    fun testKnownDesaturationAtSaturationZero() {
        // Saturation percent -100 corresponds to sat = 0.0 (full desaturation)
        val mat = ColorMatrixMath.createSaturationMatrix(-100)

        // Check AOSP standard luminance coefficients
        val expectedR = ColorMatrixMath.LUMA_R // 0.213f
        val expectedG = ColorMatrixMath.LUMA_G // 0.715f
        val expectedB = ColorMatrixMath.LUMA_B // 0.072f

        // Row 0 (Red output)
        assertEquals(expectedR, mat[0], delta)
        assertEquals(expectedG, mat[1], delta)
        assertEquals(expectedB, mat[2], delta)
        assertEquals(0f, mat[3], delta)
        assertEquals(0f, mat[4], delta)

        // Row 1 (Green output)
        assertEquals(expectedR, mat[5], delta)
        assertEquals(expectedG, mat[6], delta)
        assertEquals(expectedB, mat[7], delta)
        assertEquals(0f, mat[8], delta)
        assertEquals(0f, mat[9], delta)

        // Row 2 (Blue output)
        assertEquals(expectedR, mat[10], delta)
        assertEquals(expectedG, mat[11], delta)
        assertEquals(expectedB, mat[12], delta)
        assertEquals(0f, mat[13], delta)
        assertEquals(0f, mat[14], delta)

        // Row 3 (Alpha output)
        assertEquals(0f, mat[15], delta)
        assertEquals(0f, mat[16], delta)
        assertEquals(0f, mat[17], delta)
        assertEquals(1f, mat[18], delta)
        assertEquals(0f, mat[19], delta)

        // Luminance weights must sum to 1.0f (energy-preserving grayscale)
        assertEquals(1f, expectedR + expectedG + expectedB, delta)
    }

    @Test
    fun testLinearTemperatureResponse() {
        // 1. Warm response at +40 and +80
        val warm40 = ColorMatrixMath.createTemperatureMatrix(40)
        assertEquals(1f + 0.4f * 0.25f, warm40[0], delta)  // Red scale: 1.10f
        assertEquals(1f + 0.4f * 0.05f, warm40[6], delta)  // Green scale: 1.02f
        assertEquals(1f - 0.4f * 0.25f, warm40[12], delta) // Blue scale: 0.90f
        assertEquals(1f, warm40[18], delta)

        val warm80 = ColorMatrixMath.createTemperatureMatrix(80)
        assertEquals(1f + 0.8f * 0.25f, warm80[0], delta)  // Red scale: 1.20f
        assertEquals(1f + 0.8f * 0.05f, warm80[6], delta)  // Green scale: 1.04f
        assertEquals(1f - 0.8f * 0.25f, warm80[12], delta) // Blue scale: 0.80f

        // Verify linearity: step at 80 is exactly 2x step at 40
        val redDelta40 = warm40[0] - 1f
        val redDelta80 = warm80[0] - 1f
        assertEquals(redDelta80, redDelta40 * 2f, delta)

        // 2. Cool response at -60
        val cool60 = ColorMatrixMath.createTemperatureMatrix(-60)
        assertEquals(1f - 0.6f * 0.25f, cool60[0], delta)  // Red scale: 0.85f
        assertEquals(1f, cool60[6], delta)                 // Green scale: 1.00f
        assertEquals(1f + 0.6f * 0.25f, cool60[12], delta) // Blue scale: 1.15f
    }

    @Test
    fun testClampingBounds() {
        // Out-of-bounds saturation clamped to [-100, 100]
        assertArrayEquals(
            ColorMatrixMath.createSaturationMatrix(-100),
            ColorMatrixMath.createSaturationMatrix(-250),
            delta
        )
        assertArrayEquals(
            ColorMatrixMath.createSaturationMatrix(100),
            ColorMatrixMath.createSaturationMatrix(300),
            delta
        )

        // Out-of-bounds contrast clamped to [-100, 100]
        assertArrayEquals(
            ColorMatrixMath.createContrastMatrix(-100),
            ColorMatrixMath.createContrastMatrix(-150),
            delta
        )
        assertArrayEquals(
            ColorMatrixMath.createContrastMatrix(100),
            ColorMatrixMath.createContrastMatrix(200),
            delta
        )

        // Out-of-bounds temperature clamped to [-100, 100]
        assertArrayEquals(
            ColorMatrixMath.createTemperatureMatrix(-100),
            ColorMatrixMath.createTemperatureMatrix(-180),
            delta
        )
        assertArrayEquals(
            ColorMatrixMath.createTemperatureMatrix(100),
            ColorMatrixMath.createTemperatureMatrix(500),
            delta
        )
    }

    @Test
    fun testMatrixMultiplicationAndComposition() {
        val identity = ColorMatrixMath.createIdentityMatrix()

        // Multiplying identity by identity yields identity
        assertArrayEquals(identity, ColorMatrixMath.multiply(identity, identity), delta)

        // Multiplying identity by any matrix M yields M
        val sat = ColorMatrixMath.createSaturationMatrix(50)
        assertArrayEquals(sat, ColorMatrixMath.multiply(identity, sat), delta)
        assertArrayEquals(sat, ColorMatrixMath.multiply(sat, identity), delta)

        // Composition of non-default parameters produces a valid 20-element FloatArray
        val combined = AdjustParameters(saturation = 10, contrast = 20, temperature = -10, sharpen = 30)
        val composed = ColorMatrixMath.buildColorMatrixArray(combined)
        assertNotNull(composed)
        assertEquals(20, composed!!.size)

        // Sharpen alone does not produce a color matrix
        val sharpenOnly = AdjustParameters(sharpen = 40)
        assertNull(ColorMatrixMath.buildColorMatrixArray(sharpenOnly))
    }

    @Test
    fun testParameterClampingAndEquality() {
        val params = AdjustParameters(
            saturation = 50,
            contrast = -30,
            temperature = 25,
            sharpen = 80
        )
        assertEquals(50, params.saturation)
        assertEquals(-30, params.contrast)
        assertEquals(25, params.temperature)
        assertEquals(80, params.sharpen)
        assertFalse(params.isDefault)

        val reset = params.copy(saturation = 0, contrast = 0, temperature = 0, sharpen = 0)
        assertTrue(reset.isDefault)
    }
}
