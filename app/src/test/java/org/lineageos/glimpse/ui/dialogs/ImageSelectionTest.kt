/* Tests image-only toolbox selection and batch eligibility.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.dialogs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.glimpse.models.MediaType

class ImageSelectionTest {
    @Test
    fun batchEditRequiresTwoImagesAndExcludesVideos() {
        val image = MediaType.IMAGE
        val video = MediaType.VIDEO
        val cases = listOf(
            emptyList<MediaType>() to false,
            listOf(image) to false,
            listOf(image, image) to true,
            listOf(image, video) to false,
            listOf(image, image, video) to true,
            listOf(video) to false,
        )
        cases.forEach { (selection, expected) ->
            val images = selectedImages(selection) { it }
            assertEquals(selection.count { it == image }, images.size)
            assertTrue(images.all { it == image })
            assertEquals(expected, canBatchEdit(images.size))
        }
        assertFalse(canBatchEdit(1))
    }
}
