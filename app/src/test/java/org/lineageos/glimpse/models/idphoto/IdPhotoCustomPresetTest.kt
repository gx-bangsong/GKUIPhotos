/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models.idphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IdPhotoCustomPresetTest {

    @Test
    fun testValidationBounds() {
        // Valid mm inputs
        assertNull(IdPhotoCustomPresetManager.validate(false, 25f, 35f, 300))
        assertNull(IdPhotoCustomPresetManager.validate(false, 33f, 48f, 300))
        assertNull(IdPhotoCustomPresetManager.validate(false, 200f, 200f, 300))

        // Valid px inputs
        assertNull(IdPhotoCustomPresetManager.validate(true, 295f, 413f))
        assertNull(IdPhotoCustomPresetManager.validate(true, 4000f, 4000f))

        // Invalid: non-positive
        assertNotNull(IdPhotoCustomPresetManager.validate(false, 0f, 35f))
        assertNotNull(IdPhotoCustomPresetManager.validate(false, -10f, 35f))

        // Invalid: mm > 200
        assertNotNull(IdPhotoCustomPresetManager.validate(false, 201f, 100f))

        // Invalid: px > 4000
        assertNotNull(IdPhotoCustomPresetManager.validate(true, 4001f, 2000f))

        // Invalid: ratio outside 0.3..3.0
        assertNotNull(IdPhotoCustomPresetManager.validate(false, 10f, 100f)) // ratio 0.1 < 0.3
        assertNotNull(IdPhotoCustomPresetManager.validate(false, 100f, 20f)) // ratio 5.0 > 3.0
    }

    @Test
    fun testMmModePresetDerivation() {
        val preset = IdPhotoCustomPresetManager.createPreset(isPxMode = false, w = 25f, h = 35f, dpi = 300)
        assertEquals(25, preset.widthMm)
        assertEquals(35, preset.heightMm)
        assertEquals(300, preset.dpi)
        assertEquals(295, preset.targetWidthPx)
        assertEquals(413, preset.targetHeightPx)
        assertEquals(25f / 35f, preset.aspectRatio, 0.001f)
    }

    @Test
    fun testPxModePresetDirectTarget() {
        val preset = IdPhotoCustomPresetManager.createPreset(isPxMode = true, w = 480f, h = 640f)
        assertEquals(480, preset.targetWidthPx)
        assertEquals(640, preset.targetHeightPx)
        assertEquals(480f / 640f, preset.aspectRatio, 0.001f)
    }
}
