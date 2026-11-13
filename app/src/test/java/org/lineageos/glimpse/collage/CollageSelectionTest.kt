/* Tests collage selection rules including mixed media.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.lineageos.glimpse.models.MediaType

class CollageSelectionTest {
    @Test fun validatesCountAndRejectsVideos() {
        val photo = MediaType.IMAGE
        assertEquals(CollageSelectionError.COUNT, collageSelectionError(listOf(photo)))
        assertNull(collageSelectionError(List(2) { photo }))
        assertNull(collageSelectionError(List(6) { photo }))
        assertEquals(CollageSelectionError.COUNT, collageSelectionError(List(7) { photo }))
        assertEquals(CollageSelectionError.VIDEO, collageSelectionError(listOf(photo, photo, MediaType.VIDEO)))
    }
}
