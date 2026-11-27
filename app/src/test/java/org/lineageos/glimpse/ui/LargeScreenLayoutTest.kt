/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.lineageos.glimpse.ui.LargeScreenLayout.EditorLayoutMode

class LargeScreenLayoutTest {

    @Test
    fun editorModeSplitsOnlyAtTheWidthAndHeightBreakpoints() {
        // Width gate: 800dp, so natural landscape panes keep the split.
        assertEquals(EditorLayoutMode.SINGLE_COLUMN, LargeScreenLayout.editorLayoutMode(799, 600))
        assertEquals(EditorLayoutMode.TWO_COLUMN, LargeScreenLayout.editorLayoutMode(800, 360))
        // 7T Pro natural landscape pane (832x384dp).
        assertEquals(EditorLayoutMode.TWO_COLUMN, LargeScreenLayout.editorLayoutMode(832, 384))
        // Height gate: 230dp (unchanged). A wide but short window keeps the
        // single-column editor.
        assertEquals(EditorLayoutMode.SINGLE_COLUMN, LargeScreenLayout.editorLayoutMode(840, 229))
        assertEquals(EditorLayoutMode.TWO_COLUMN, LargeScreenLayout.editorLayoutMode(840, 230))
        // Not wide enough / portrait phone stay single-column.
        assertEquals(EditorLayoutMode.SINGLE_COLUMN, LargeScreenLayout.editorLayoutMode(599, 900))
        assertEquals(EditorLayoutMode.SINGLE_COLUMN, LargeScreenLayout.editorLayoutMode(384, 852))
        assertEquals(EditorLayoutMode.TWO_COLUMN, LargeScreenLayout.editorLayoutMode(1280, 900))
        // Landscape split-screen pane (true split 1067x230): two columns, so
        // the canvas keeps the full window height instead of ~90dp above the
        // tools deck.
        assertEquals(EditorLayoutMode.TWO_COLUMN, LargeScreenLayout.editorLayoutMode(1067, 230))
    }

    @Test
    fun wideWindowGateOpensAt600dp() {
        assertFalse(LargeScreenLayout.isWideWindow(599))
        assertTrue(LargeScreenLayout.isWideWindow(600))
    }

    @Test
    fun gridSpanIsTheFloorOfTheThumbnailPitchWithoutACap() {
        // pitch = 96dp minimum thumbnail + 8dp spacing = 104dp per column
        assertEquals(5, LargeScreenLayout.gridSpanCount(600, 96, 8))
        assertEquals(12, LargeScreenLayout.gridSpanCount(1280, 96, 8))
        // Not enough width for one column: clamp up to the minimum span.
        assertEquals(4, LargeScreenLayout.gridSpanCount(100, 96, 8))
        assertEquals(4, LargeScreenLayout.gridSpanCount(0, 96, 8))
        assertEquals(4, LargeScreenLayout.gridSpanCount(-5, 96, 8))
    }

    @Test
    fun gridSpanRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException::class.java) {
            LargeScreenLayout.gridSpanCount(600, 0, 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LargeScreenLayout.gridSpanCount(600, 96, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LargeScreenLayout.gridSpanCount(600, 96, 8, 0)
        }
    }

    @Test
    fun bottomSheetContentWidthCapsOnlyWhenTheWindowIsWiderThanTheCap() {
        assertEquals(640, LargeScreenLayout.bottomSheetContentWidthPx(1280, 640))
        assertEquals(500, LargeScreenLayout.bottomSheetContentWidthPx(500, 640))
        // Unmeasured window: fall back to the cap itself.
        assertEquals(640, LargeScreenLayout.bottomSheetContentWidthPx(0, 640))
        assertThrows(IllegalArgumentException::class.java) {
            LargeScreenLayout.bottomSheetContentWidthPx(500, 0)
        }
    }
}
