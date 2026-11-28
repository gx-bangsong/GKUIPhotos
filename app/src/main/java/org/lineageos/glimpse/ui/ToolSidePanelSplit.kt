/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import kotlin.math.roundToInt

/**
 * Runtime single/two-column split for the CoordinatorLayout-based tool
 * screens — GIF editor and ID photo (ruling ⑥ round 3).
 *
 * Those activities handle `configChanges` themselves, so orientation changes
 * never re-inflate: the preview and its control panel are re-arranged on the
 * inflated tree, mirroring [EditorLayoutSwitcher] for the image editor.
 *
 * Two-column pins the preview to the left ~75% and turns the control panel
 * into a full-height column on the right; single-column restores the
 * original bottom-panel geometry (full-width preview above a fixed-height or
 * wrap-content panel). Callers pass the original single-column dimensions
 * captured from the inflated layout parameters.
 *
 * No-op while the root has not been measured yet — callers should only mark
 * the mode as applied when this function actually ran.
 */
object ToolSidePanelSplit {
    /** Mirrors [EditorLayoutSwitcher]'s canvas/tools split fraction. */
    private const val GUIDE_PERCENT = 0.75f

    fun apply(
        root: ViewGroup,
        preview: View,
        panel: View,
        mode: LargeScreenLayout.EditorLayoutMode,
        singlePreviewBottomMarginPx: Int,
        singlePanelHeightPx: Int,
    ): Boolean {
        val width = root.width
        if (width <= 0) return false

        val previewLp = preview.layoutParams
        val panelLp = panel.layoutParams

        when (mode) {
            LargeScreenLayout.EditorLayoutMode.TWO_COLUMN -> {
                val split = (width * GUIDE_PERCENT).roundToInt()
                previewLp.width = split
                (previewLp as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
                panelLp.width = width - split
                panelLp.height = ViewGroup.LayoutParams.MATCH_PARENT
                (panelLp as? CoordinatorLayout.LayoutParams)?.gravity =
                    Gravity.END
            }
            LargeScreenLayout.EditorLayoutMode.SINGLE_COLUMN -> {
                previewLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                (previewLp as? ViewGroup.MarginLayoutParams)?.bottomMargin =
                    singlePreviewBottomMarginPx
                panelLp.width = ViewGroup.LayoutParams.MATCH_PARENT
                panelLp.height = singlePanelHeightPx
                (panelLp as? CoordinatorLayout.LayoutParams)?.gravity =
                    Gravity.BOTTOM
            }
        }
        preview.layoutParams = previewLp
        panel.layoutParams = panelLp
        return true
    }
}
