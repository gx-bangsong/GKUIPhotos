/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.viewmodels.AlbumViewModel
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThumbnailLayoutManagerTest {

    private class FakeAdapter(
        private val viewTypes: List<Int>,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = viewTypes.size
        override fun getItemViewType(position: Int) = viewTypes[position]
        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder = object : RecyclerView.ViewHolder(View(parent.context)) {}
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    }

    @Test
    fun spanFollowsAvailableWidthAndDateHeadersSpanAllColumns() {
        val context = RuntimeEnvironment.getApplication()
        val density = context.resources.displayMetrics.density
        val adapter = FakeAdapter(
            listOf(
                AlbumViewModel.AlbumContent.ViewType.DATE_HEADER.ordinal,
                AlbumViewModel.AlbumContent.ViewType.THUMBNAIL.ordinal,
                AlbumViewModel.AlbumContent.ViewType.THUMBNAIL.ordinal,
            ),
        )
        val layoutManager = ThumbnailLayoutManager(context, adapter)
        val initialSpan = layoutManager.spanCount

        // Narrow windows keep the original display-metrics algorithm: the
        // constructor computed the very same value, so nothing changes.
        assertFalse(layoutManager.updateSpanForAvailableWidth((599 * density).toInt()))
        assertEquals(initialSpan, layoutManager.spanCount)

        // 600dp of content width is the first wide size: floor(600dp / 104dp)
        // = 5 columns, from the width rather than any device constant.
        layoutManager.updateSpanForAvailableWidth((600 * density).toInt())
        assertEquals(5, layoutManager.spanCount)

        // The span-size lookup reads the live span count; no re-set needed.
        assertEquals(5, layoutManager.spanSizeLookup.getSpanSize(0))
        assertEquals(1, layoutManager.spanSizeLookup.getSpanSize(1))
        assertEquals(1, layoutManager.spanSizeLookup.getSpanSize(2))

        // Back below the gate: original algorithm output again.
        layoutManager.updateSpanForAvailableWidth((599 * density).toInt())
        assertEquals(initialSpan, layoutManager.spanCount)
        assertEquals(initialSpan, layoutManager.spanSizeLookup.getSpanSize(0))

        // Non-positive widths are ignored instead of forcing a bogus span.
        assertFalse(layoutManager.updateSpanForAvailableWidth(0))
        assertEquals(initialSpan, layoutManager.spanCount)
    }
}
