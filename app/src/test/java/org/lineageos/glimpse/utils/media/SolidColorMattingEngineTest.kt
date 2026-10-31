/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class SolidColorMattingEngineTest {

    private fun packRgb(r: Int, g: Int, b: Int): Int {
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Test 1: Alpha monotonicity across the transition band [T_inner, T_outer].
     * Asserts that alpha is strictly non-decreasing from 0.0 to 1.0.
     */
    @Test
    fun testAlphaMonotonicityAcrossTransitionBand() {
        val tInner = SolidColorMattingEngine.DEFAULT_T_INNER
        val tOuter = SolidColorMattingEngine.DEFAULT_T_OUTER

        assertEquals(0f, SolidColorMattingEngine.computeMatteAlpha(tInner), 0.0001f)
        assertEquals(0.5f, SolidColorMattingEngine.computeMatteAlpha((tInner + tOuter) / 2f), 0.0001f)
        assertEquals(1f, SolidColorMattingEngine.computeMatteAlpha(tOuter), 0.0001f)

        var prevAlpha = 0f
        var d = tInner
        while (d <= tOuter) {
            val alpha = SolidColorMattingEngine.computeMatteAlpha(d)
            assertTrue("Alpha must be non-decreasing at d=$d, was $alpha < $prevAlpha", alpha >= prevAlpha)
            assertTrue("Alpha must be in [0, 1] at d=$d, was $alpha", alpha in 0f..1f)
            prevAlpha = alpha
            d += 0.2f
        }
    }

    /**
     * Test 2: Pure-color background keys correctly.
     * Pure blue background with a skin-tone center subject:
     * - Background purity check passes.
     * - Background pixels are keyed out (alpha = 0, replaced with target white color).
     * - Foreground center subject is preserved (alpha = 1, skin color preserved).
     */
    @Test
    fun testPureColorBackgroundKeyedCorrectly() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h)

        // Industry standard blue: (67, 142, 219)
        val blueBg = packRgb(67, 142, 219)
        // Center skin subject: (230, 180, 150)
        val skinSubject = packRgb(230, 180, 150)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val isSubject = x in 30..70 && y in 30..70
                pixels[y * w + x] = if (isSubject) skinSubject else blueBg
            }
        }

        val targetWhite = packRgb(255, 255, 255)
        val result = SolidColorMattingEngine.processPixels(pixels, w, h, targetWhite)

        assertTrue("Expected pure color background to succeed matting", result is MattingPixelsResult.Success)
        val success = result as MattingPixelsResult.Success

        // Top-left background pixel must be replaced by target white
        val bgOut = success.pixels[0]
        val bgR = (bgOut shr 16) and 0xFF
        val bgG = (bgOut shr 8) and 0xFF
        val bgB = bgOut and 0xFF
        assertEquals(255, bgR)
        assertEquals(255, bgG)
        assertEquals(255, bgB)

        // Center subject pixel must remain skin-colored
        val fgOut = success.pixels[50 * w + 50]
        val fgR = (fgOut shr 16) and 0xFF
        val fgG = (fgOut shr 8) and 0xFF
        val fgB = fgOut and 0xFF
        assertEquals(230, fgR)
        assertEquals(180, fgG)
        assertEquals(150, fgB)
    }

    /**
     * Test 3: Gradient-lit solid background (±12% luminance ramp) is still accepted.
     * Verifies that the λ=0.15 YCbCr ellipsoid distance design handles non-uniform studio lighting.
     */
    @Test
    fun testGradientLitBackgroundAccepted() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h)

        val baseR = 67
        val baseG = 142
        val baseB = 219

        // Linear vertical gradient of ±12% luminance ramp
        for (y in 0 until h) {
            val factor = 1.0f - 0.12f + (y.toFloat() / h.toFloat()) * 0.24f
            val r = (baseR * factor).roundToInt().coerceIn(0, 255)
            val g = (baseG * factor).roundToInt().coerceIn(0, 255)
            val b = (baseB * factor).roundToInt().coerceIn(0, 255)
            val color = packRgb(r, g, b)
            for (x in 0 until w) {
                pixels[y * w + x] = color
            }
        }

        val stats = SolidColorMattingEngine.sampleBorderStatsFromPixels(pixels, w, h)
        assertTrue("Gradient-lit background must be accepted as pure by border stats", stats.isPurityAcceptable)
        assertTrue("Chroma std dev must be low for smooth gradient", stats.sigmaChroma <= SolidColorMattingEngine.DEFAULT_CHROMA_STD_DEV_THRESHOLD)
        assertTrue("Gradient mean must be low for smooth ramp", stats.gradientMean <= SolidColorMattingEngine.DEFAULT_SOBEL_GRADIENT_MEAN_THRESHOLD)

        val targetWhite = packRgb(255, 255, 255)
        val result = SolidColorMattingEngine.processPixels(pixels, w, h, targetWhite)
        assertTrue("Expected gradient-lit solid background to succeed matting", result is MattingPixelsResult.Success)
    }

    /**
     * Test 4: Checkerboard/noisy cluttered background is refused.
     * Verifies graceful refusal when background contains high chroma variance and strong texture.
     */
    @Test
    fun testCheckerboardNoisyBackgroundRefused() {
        val w = 100
        val h = 100
        val pixels = IntArray(w * h)

        // High-frequency alternating high-contrast pattern (cluttered background)
        val col1 = packRgb(255, 0, 0)
        val col2 = packRgb(0, 255, 0)

        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if ((x / 4 + y / 4) % 2 == 0) col1 else col2
            }
        }

        val stats = SolidColorMattingEngine.sampleBorderStatsFromPixels(pixels, w, h)
        assertFalse("Checkerboard background must be rejected by purity check", stats.isPurityAcceptable)

        val targetWhite = packRgb(255, 255, 255)
        val result = SolidColorMattingEngine.processPixels(pixels, w, h, targetWhite)
        assertTrue("Checkerboard background must return MattingPixelsResult.Refused", result is MattingPixelsResult.Refused)
        assertEquals(SolidColorMattingEngine.REFUSAL_MESSAGE, (result as MattingPixelsResult.Refused).reason)
    }
}
