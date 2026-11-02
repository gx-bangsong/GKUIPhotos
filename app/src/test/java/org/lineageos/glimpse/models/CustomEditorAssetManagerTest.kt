/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEditorAssetManagerTest {

    @Test
    fun testEditorAssetProperties() {
        val asset = CustomEditorAssetManager.CustomAsset(
            id = "test_001",
            name = "My Texture",
            filePath = "/tmp/test.png",
        )
        assertEquals("test_001", asset.id)
        assertEquals("My Texture", asset.name)
        assertEquals("/tmp/test.png", asset.filePath)
        assertNotNull(asset)
    }

    @Test
    fun testCustomBrushModelDefaults() {
        val brush = CustomBrush(
            id = "b_default",
            name = "Default Stamp",
            tipFile = "/tips/stamp.png",
            colorMode = BrushColorMode.ORIGINAL,
        )
        assertEquals("b_default", brush.id)
        assertEquals("Default Stamp", brush.name)
        assertEquals("/tips/stamp.png", brush.tipFile)
        assertEquals(BrushColorMode.ORIGINAL, brush.colorMode)
        assertEquals(25, brush.spacingPercent)
        assertEquals(100, brush.opacity)
        assertEquals(false, brush.followDirection)
        assertEquals(24f, brush.sizeDp, 0.001f)
    }

    @Test
    fun testCustomShapeModelProperties() {
        val shape = CustomShape(
            id = "s_01",
            name = "Star",
            pathData = "M 0.5 0 L 0.6 0.4 L 1 0.4 L 0.7 0.6 L 0.8 1 L 0.5 0.7 L 0.2 1 L 0.3 0.6 L 0 0.4 L 0.4 0.4 Z",
        )
        assertEquals("s_01", shape.id)
        assertEquals("Star", shape.name)
        assertTrue(shape.pathData.startsWith("M"))
        assertTrue(shape.pathData.endsWith("Z"))
    }
}
