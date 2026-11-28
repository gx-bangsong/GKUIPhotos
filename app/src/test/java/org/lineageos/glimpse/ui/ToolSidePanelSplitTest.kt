/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.coordinatorlayout.widget.CoordinatorLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])

/**
 * Geometry for the GIF editor / ID photo two-column split
 * (ruling ⑥ round 3): preview left 70%, full-height panel on the right,
 * original bottom-panel geometry restored in single-column.
 */
class ToolSidePanelSplitTest {

    private fun buildTree(): Triple<CoordinatorLayout, View, View> {
        val activity = Robolectric.buildActivity(
            androidx.activity.ComponentActivity::class.java
        ).setup().get()
        val root = CoordinatorLayout(activity)
        val preview = View(activity)
        val panel = View(activity)

        val previewLp = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 300 }
        val panelLp = CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 300,
        ).apply { gravity = Gravity.BOTTOM }
        root.addView(preview, previewLp)
        root.addView(panel, panelLp)

        root.measure(
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1600, 900)
        return Triple(root, preview, panel)
    }

    @Test
    fun twoColumnSplitsCanvasAt70PercentWithFullHeightPanel() {
        val (root, preview, panel) = buildTree()

        assertTrue(
            ToolSidePanelSplit.apply(
                root, preview, panel,
                LargeScreenLayout.EditorLayoutMode.TWO_COLUMN,
                singlePreviewBottomMarginPx = 300,
                singlePanelHeightPx = 300,
            )
        )

        assertEquals(1200, preview.layoutParams.width)
        assertEquals(0, (preview.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
        assertEquals(400, panel.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, panel.layoutParams.height)
        assertEquals(
            Gravity.END,
            (panel.layoutParams as CoordinatorLayout.LayoutParams).gravity,
        )
    }

    @Test
    fun singleColumnRestoresOriginalBottomPanelGeometry() {
        val (root, preview, panel) = buildTree()

        assertTrue(
            ToolSidePanelSplit.apply(
                root, preview, panel,
                LargeScreenLayout.EditorLayoutMode.SINGLE_COLUMN,
                singlePreviewBottomMarginPx = 300,
                singlePanelHeightPx = 300,
            )
        )

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, preview.layoutParams.width)
        assertEquals(300, (preview.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, panel.layoutParams.width)
        assertEquals(300, panel.layoutParams.height)
        assertEquals(
            Gravity.BOTTOM,
            (panel.layoutParams as CoordinatorLayout.LayoutParams).gravity,
        )
    }

    @Test
    fun applyBeforeFirstLayoutIsRefused() {
        val (root, preview, panel) = buildTree()
        root.layout(0, 0, 0, 0)

        assertFalse(
            ToolSidePanelSplit.apply(
                root, preview, panel,
                LargeScreenLayout.EditorLayoutMode.TWO_COLUMN,
                singlePreviewBottomMarginPx = 300,
                singlePanelHeightPx = 300,
            )
        )
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, preview.layoutParams.width)
        assertEquals(300, panel.layoutParams.height)
    }
}
