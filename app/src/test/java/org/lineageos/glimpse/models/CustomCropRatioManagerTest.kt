/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCropRatioManagerTest {

    @Test
    fun testValidate() {
        assertTrue(CustomCropRatioManager.validate(1f, 1f))
        assertTrue(CustomCropRatioManager.validate(16f, 9f))
        assertTrue(CustomCropRatioManager.validate(21f, 9f))
        assertTrue(CustomCropRatioManager.validate(4f, 3f))

        assertFalse(CustomCropRatioManager.validate(0f, 10f))
        assertFalse(CustomCropRatioManager.validate(10f, 0f))
        assertFalse(CustomCropRatioManager.validate(-5f, 5f))
        assertFalse(CustomCropRatioManager.validate(1f, 100f)) // ratio 0.01 < 0.1
        assertFalse(CustomCropRatioManager.validate(100f, 1f)) // ratio 100 > 10.0
    }

    @Test
    fun testFormatLabel() {
        assertEquals("21:9", CustomCropRatioManager.formatLabel(21f, 9f))
        assertEquals("16:9", CustomCropRatioManager.formatLabel(16f, 9f))
        assertEquals("A4", CustomCropRatioManager.formatLabel(210f, 297f, "A4"))
    }

    @Test
    fun testSerializationAndParsing() {
        val list = listOf(
            CustomCropRatioManager.CustomRatio("21:9", 21f, 9f),
            CustomCropRatioManager.CustomRatio("电影超宽", 2.35f, 1f),
        )

        val serialized = CustomCropRatioManager.serializeRatios(list)
        val parsed = CustomCropRatioManager.parseRatios(serialized)

        assertEquals(2, parsed.size)
        assertEquals("21:9", parsed[0].label)
        assertEquals(21f / 9f, parsed[0].ratio, 0.001f)
        assertEquals("电影超宽", parsed[1].label)
        assertEquals(2.35f, parsed[1].ratio, 0.001f)
    }

    @Test
    fun testEmptyAndMalformedStrings() {
        assertTrue(CustomCropRatioManager.parseRatios(null).isEmpty())
        assertTrue(CustomCropRatioManager.parseRatios("").isEmpty())
        assertTrue(CustomCropRatioManager.parseRatios("invalid;malformed|data").isEmpty())
    }
}
