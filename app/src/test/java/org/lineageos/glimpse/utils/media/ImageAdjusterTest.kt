/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAdjusterTest {

    @Test
    fun testDefaultParameters() {
        val params = AdjustParameters()
        assertTrue(params.isDefault)
        assertNull(ImageAdjuster.buildColorMatrix(params))
    }

    @Test
    fun testNonDefaultParameters() {
        val sat = AdjustParameters(saturation = 20)
        assertFalse(sat.isDefault)
        assertNotNull(ImageAdjuster.buildColorMatrix(sat))

        val con = AdjustParameters(contrast = -15)
        assertFalse(con.isDefault)
        assertNotNull(ImageAdjuster.buildColorMatrix(con))

        val temp = AdjustParameters(temperature = 30)
        assertFalse(temp.isDefault)
        assertNotNull(ImageAdjuster.buildColorMatrix(temp))

        val sharp = AdjustParameters(sharpen = 50)
        assertFalse(sharp.isDefault)
        // sharpen alone doesn't produce color matrix
        assertNull(ImageAdjuster.buildColorMatrix(sharp))
    }

    @Test
    fun testColorMatrixNotNull() {
        val combined = AdjustParameters(saturation = 10, contrast = 20, temperature = -10, sharpen = 30)
        val matrix = ImageAdjuster.buildColorMatrix(combined)
        assertNotNull(matrix)
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
