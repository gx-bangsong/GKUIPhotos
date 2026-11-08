/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.MotionEvent
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.widget.RecyclerView
import org.lineageos.glimpse.models.Media
import kotlin.reflect.safeCast

class ThumbnailItemDetailsLookup(
    private val recyclerView: RecyclerView,
) : ItemDetailsLookup<Media>() {
    override fun getItemDetails(e: MotionEvent) =
        recyclerView.findChildViewUnder(e.x, e.y)?.let { childView ->
            when (val viewHolder = recyclerView.getChildViewHolder(childView)) {
                is ThumbnailAdapter.ThumbnailViewHolder -> viewHolder.itemDetails
                is ThumbnailAdapter.DateHeaderViewHolder -> viewHolder.itemDetails
                else -> null
            }
        }
}
