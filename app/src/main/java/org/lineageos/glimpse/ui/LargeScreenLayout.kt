/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

/**
 * Large-screen breakpoints and layout math (ruling ⑥).
 *
 * Tiers are defined by the app's available window width in dp:
 * - < 600dp: keep the existing single-column behavior.
 * - 600–799dp: the editor stays single-column; only the album grid gains
 *   columns from the available content width.
 * - >= 800dp: the editor splits canvas (~70%) and tools (~30%).
 *
 * Height matters for the split: a short window falls back to a single
 * column instead of forcing a cramped two-column layout.
 *
 * This object is pure Kotlin (no Android view dependencies) so the
 * breakpoint and span math can be unit-tested on the JVM.
 */
object LargeScreenLayout {
    /** Window width below which the existing single-column layout is kept. */
    const val COMPACT_MAX_WIDTH_DP = 600

    /**
     * Window width at or above which the editor may use two columns.
     * 800dp so natural landscape panes like 832x384dp keep the split.
     */
    const val TWO_COLUMN_MIN_WIDTH_DP = 800

    /** Minimum window height for the two-column editor; below this, fall back. */
    const val TWO_COLUMN_MIN_HEIGHT_DP = 230

    /** Maximum width for bottom sheets on wide windows (kept bottom-anchored). */
    const val BOTTOM_SHEET_MAX_WIDTH_DP = 640

    /** Minimum visible thumbnail width for the album grid, in dp. */
    const val GRID_MIN_THUMBNAIL_DP = 96

    /** Minimum album-grid columns (preserves the phone layout). */
    const val GRID_MIN_SPAN = 4

    enum class EditorLayoutMode {
        SINGLE_COLUMN,
        TWO_COLUMN,
    }

    /**
     * Editor mode for the given available window size in dp.
     * Two columns require both a wide window and enough height.
     */
    fun editorLayoutMode(availableWidthDp: Int, availableHeightDp: Int): EditorLayoutMode =
        if (availableWidthDp >= TWO_COLUMN_MIN_WIDTH_DP &&
            availableHeightDp >= TWO_COLUMN_MIN_HEIGHT_DP
        ) {
            EditorLayoutMode.TWO_COLUMN
        } else {
            EditorLayoutMode.SINGLE_COLUMN
        }

    /** True when the window is wide enough for the capped bottom-sheet treatment. */
    fun isWideWindow(availableWidthDp: Int): Boolean = availableWidthDp >= COMPACT_MAX_WIDTH_DP

    /**
     * Album-grid column count from the available content width.
     *
     * Each column needs at least [minThumbnailWidthPx] of visible thumbnail
     * plus [spacingPx] of inter-item spacing, so the count is
     * `availableWidthPx / (minThumbnailWidthPx + spacingPx)` (floor),
     * clamped up to [minSpan]. No device size is hardcoded.
     */
    fun gridSpanCount(
        availableWidthPx: Int,
        minThumbnailWidthPx: Int,
        spacingPx: Int,
        minSpan: Int = GRID_MIN_SPAN,
    ): Int {
        require(minThumbnailWidthPx > 0) { "minThumbnailWidthPx must be positive" }
        require(spacingPx >= 0) { "spacingPx must not be negative" }
        require(minSpan >= 1) { "minSpan must be at least 1" }
        if (availableWidthPx <= 0) return minSpan
        val cell = minThumbnailWidthPx + spacingPx
        val columns = availableWidthPx / cell
        return columns.coerceAtLeast(minSpan)
    }

    /**
     * Bottom-sheet content width in px: the full window width on narrow
     * windows, capped at [maxWidthPx] on wide ones.
     */
    fun bottomSheetContentWidthPx(windowWidthPx: Int, maxWidthPx: Int): Int {
        require(maxWidthPx > 0) { "maxWidthPx must be positive" }
        if (windowWidthPx <= 0) return maxWidthPx
        return minOf(windowWidthPx, maxWidthPx)
    }
}
