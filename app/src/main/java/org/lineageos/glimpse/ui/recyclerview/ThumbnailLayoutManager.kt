/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.glimpse.ext.px
import org.lineageos.glimpse.ui.LargeScreenLayout
import org.lineageos.glimpse.viewmodels.AlbumViewModel

private const val TARGET_SPAN_COUNT = 4
private val THUMBNAIL_PADDING_PX = 4.px

class ThumbnailLayoutManager(
    context: Context,
    adapter: RecyclerView.Adapter<*>,
) : DisplayAwareGridLayoutManager(context, TARGET_SPAN_COUNT, THUMBNAIL_PADDING_PX) {
    // Constructor parameters are out of scope in member functions; keep a reference.
    private val gridContext: Context = context

    init {
        spanSizeLookup = ThumbnailSpanSizeLookup(adapter, this)
    }

    /**
     * Recompute the column count from the available content width
     * (ruling ⑥, album grid tier).
     *
     * Below [LargeScreenLayout.COMPACT_MAX_WIDTH_DP] the original
     * display-metrics algorithm of [DisplayAwareGridLayoutManager] is kept,
     * so the phone layout stays exactly as before. At or above it the count
     * grows with the width: `width / (96dp + 8dp)` (floor), keeping the
     * original minimum of four columns.
     *
     * @return true when the span count changed.
     */
    fun updateSpanForAvailableWidth(widthPx: Int): Boolean {
        if (widthPx <= 0) return false
        val density = gridContext.resources.displayMetrics.density
        val widthDp = widthPx / density
        val newSpan = if (widthDp < LargeScreenLayout.COMPACT_MAX_WIDTH_DP) {
            DisplayAwareGridLayoutManager.getSpanCount(
                gridContext,
                TARGET_SPAN_COUNT,
                THUMBNAIL_PADDING_PX,
            )
        } else {
            LargeScreenLayout.gridSpanCount(
                availableWidthPx = widthPx,
                minThumbnailWidthPx = LargeScreenLayout.GRID_MIN_THUMBNAIL_DP.px,
                spacingPx = 2 * THUMBNAIL_PADDING_PX,
            )
        }
        if (newSpan == spanCount) return false
        spanCount = newSpan
        return true
    }

    /**
     * Date headers always span the full row. The span is read from the
     * layout manager at call time so it stays correct after
     * [updateSpanForAvailableWidth] changes the column count on
     * resize/rotation.
     */
    private class ThumbnailSpanSizeLookup(
        private val adapter: RecyclerView.Adapter<*>,
        private val layoutManager: ThumbnailLayoutManager,
    ) : SpanSizeLookup() {
        override fun getSpanSize(position: Int) = when (adapter.getItemViewType(position)) {
            AlbumViewModel.AlbumContent.ViewType.THUMBNAIL.ordinal -> 1
            AlbumViewModel.AlbumContent.ViewType.DATE_HEADER.ordinal -> layoutManager.spanCount
            else -> throw Exception("Unknown view type")
        }
    }
}
