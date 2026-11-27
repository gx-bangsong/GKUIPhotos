/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Wide-window bottom-sheet treatment (ruling ⑥).
 *
 * Sheets keep their bottom-anchored semantics on every window size, but on
 * wide windows (>= [LargeScreenLayout.COMPACT_MAX_WIDTH_DP]) their content
 * width is capped at [LargeScreenLayout.BOTTOM_SHEET_MAX_WIDTH_DP] and the
 * sheet is centered horizontally instead of stretching edge to edge.
 *
 * [BottomSheetDialog] windows already center their sheet container
 * (`design_bottom_sheet_dialog.xml` uses `center_horizontal`), so only the
 * behavior's max width needs to be set there. The inline sheet inside
 * `fragment_album.xml` has no layout gravity, so it gets
 * [Gravity.CENTER_HORIZONTAL] as well.
 */

/** BottomSheetBehavior.NO_MAX_SIZE; the constant is private in material. */
private const val NO_MAX_SIZE = -1

private fun wideScreenSheetMaxWidthPx(context: Context): Int {
    val configuration = context.resources.configuration
    if (!LargeScreenLayout.isWideWindow(configuration.screenWidthDp)) return NO_MAX_SIZE
    val density = context.resources.displayMetrics.density
    val windowWidthPx = (configuration.screenWidthDp * density).toInt()
    val maxWidthPx = (LargeScreenLayout.BOTTOM_SHEET_MAX_WIDTH_DP * density).toInt()
    return LargeScreenLayout.bottomSheetContentWidthPx(windowWidthPx, maxWidthPx)
}

/**
 * Cap the width of a dialog-hosted bottom sheet on wide windows.
 * Call after `setContentView` and before `show()`.
 */
fun BottomSheetDialog.applyWideScreenCap() {
    behavior.maxWidth = wideScreenSheetMaxWidthPx(context)
}

/**
 * Cap the width of an inline bottom sheet on wide windows and center it
 * horizontally within its coordinator parent.
 */
fun View.applyWideScreenCap(behavior: BottomSheetBehavior<*>) {
    behavior.maxWidth = wideScreenSheetMaxWidthPx(context)
    val params = layoutParams as? CoordinatorLayout.LayoutParams ?: return
    if (params.gravity != Gravity.CENTER_HORIZONTAL) {
        params.gravity = Gravity.CENTER_HORIZONTAL
        // Reassign so the changed gravity triggers a layout pass.
        layoutParams = params
    }
}
