/* Selectable, accessible collage filmstrip with drag-to-reorder support.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.lineageos.glimpse.R

internal class CollageFilmstripAdapter(
    private val photos: () -> List<CollagePhoto>,
    private val selected: () -> Int,
    private val select: (Int) -> Unit,
    private val move: (Int, Int) -> Unit,
) : RecyclerView.Adapter<CollageFilmstripAdapter.Holder>() {
    var dragging = false

    override fun getItemCount() = photos().size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val density = parent.resources.displayMetrics.density
        val card = MaterialCardView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams((64 * density).toInt(), (64 * density).toInt()).apply {
                marginEnd = (8 * density).toInt()
            }
            radius = 10 * density
            cardElevation = 0f
            isClickable = true
            isFocusable = true
        }
        val image = ImageView(parent.context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(image)
        return Holder(card, image).also { holder ->
            card.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) select(pos)
            }
            ViewCompat.addAccessibilityAction(card, parent.context.getString(R.string.collage_move_before)) { _, _ ->
                val pos = holder.bindingAdapterPosition
                if (pos > 0) { move(pos, pos - 1); true } else false
            }
            ViewCompat.addAccessibilityAction(card, parent.context.getString(R.string.collage_move_after)) { _, _ ->
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < itemCount - 1) { move(pos, pos + 1); true } else false
            }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = holder.itemView as MaterialCardView
        val isSelected = position == selected()
        holder.image.setImageBitmap(photos()[position].preview)
        card.isSelected = isSelected
        card.strokeWidth = ((if (isSelected) 3 else if (dragging) 1 else 0) * card.resources.displayMetrics.density).toInt()
        card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
        card.contentDescription = card.context.getString(
            if (isSelected) R.string.collage_selected_photo else R.string.collage_photo_description,
            position + 1, itemCount
        )
    }

    class Holder(card: MaterialCardView, val image: ImageView) : RecyclerView.ViewHolder(card)
}
