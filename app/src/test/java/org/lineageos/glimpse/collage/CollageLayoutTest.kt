/* Tests collage layout coverage and bounds.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageLayoutTest {
    @Test fun layoutsCoverTheCanvasWithoutOverlap() {
        for (count in 2..6) for (layout in 0..1) for (split in listOf(0.2f, 0.5f, 0.8f)) {
            val cells = collageCells(count, layout, split)
            assertEquals(count, cells.size)
            assertEquals(1f, cells.sumOf { ((it.right - it.left) * (it.bottom - it.top)).toDouble() }.toFloat(), 0.00001f)
            cells.forEachIndexed { i, a ->
                assertTrue(a.left >= 0 && a.top >= 0 && a.right <= 1 && a.bottom <= 1)
                assertTrue(a.left < a.right && a.top < a.bottom)
                cells.drop(i + 1).forEach { b ->
                    assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
                }
            }
        }
    }
    @Test fun fourPhotoTemplatesAreDifferentGeometriesAndSplitMovesDivider() {
        val grid = collageCells(4, 0)
        val featured = collageCells(4, 1)
        assertTrue(grid != featured)
        assertEquals(1f, featured.first().bottom, 0f)
        assertEquals(0.3f, collageCells(4, 1, 0.3f).first().right, 0.0001f)
        val photos = mutableListOf("a", "b", "c", "d")
        moveCollageItem(photos, 0, 0, 3)
        assertEquals(featured, collageCells(4, 1))
    }
    @Test fun panAndZoomAlwaysCoverTheCell() {
        val cell = Cell(0f, 0f, 100f, 100f)
        for (zoom in listOf(1f, 2f, 4f)) for (offset in listOf(-1f, 0f, 1f)) {
            val dest = collageDestination(cell, 400, 200, zoom, offset, offset)
            assertTrue(dest.left <= cell.left && dest.right >= cell.right)
            assertTrue(dest.top <= cell.top && dest.bottom >= cell.bottom)
        }
        val left = collageDestination(cell, 400, 200, 1f, 1f, 0f)
        assertEquals(cell.left, left.left, 0.0001f)
    }
    @Test fun movingPhotosPreservesSelectedPhoto() {
        val items = mutableListOf("a", "b", "c", "d")
        val selected = moveCollageItem(items, 2, 0, 3)
        assertEquals(listOf("b", "c", "d", "a"), items)
        assertEquals(1, selected)
        assertEquals(0, moveCollageItem(items, selected, 1, 0))
        assertEquals(listOf("c", "b", "d", "a"), items)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOnePhoto() { collageCells(1, 0) }
    @Test(expected = IllegalArgumentException::class) fun rejectsSevenPhotos() { collageCells(7, 0) }
}
