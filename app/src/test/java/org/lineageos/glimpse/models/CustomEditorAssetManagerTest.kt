/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
