/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.loadThumbnail
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.Thumbnail
import org.lineageos.glimpse.utils.media.SourceAlbum

/**
 * Heterogeneous adapter for the Albums tab.
 *
 * - Row type [SOURCE_SECTION] (module 3): a full-width horizontal carousel of
 *   virtual aggregated "smart albums" produced by crowd-sourcing / social apps.
 * - Row type [ALBUM]: the regular album grid cell, identical to the legacy
 *   [SimpleListAdapter] cell.
 */
class AlbumsAdapter(
    private val onAlbumClick: (Album) -> Unit,
    private val onSourceAlbumClick: (SourceAlbum) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class SourceSection(val sourceAlbums: List<SourceAlbum>) : Row
        data class AlbumRow(val album: Album) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(albums: List<Album>, sourceAlbums: List<SourceAlbum>) {
        val newRows = buildList {
            // Always show the smart-album section so the feature is discoverable
            // even when no media matches yet; the ViewHolder shows a placeholder.
            add(Row.SourceSection(sourceAlbums))
            albums.forEach { add(Row.AlbumRow(it)) }
        }
        // Lightweight full refresh; albums lists are small.
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.SourceSection -> TYPE_SOURCE_SECTION
        is Row.AlbumRow -> TYPE_ALBUM
    }

    override fun getItemCount() = rows.size

    /**
     * Make the full-width source-carousel row span every grid column.
     */
    fun configureSpan(layoutManager: androidx.recyclerview.widget.GridLayoutManager) {
        layoutManager.spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val row = rows.getOrNull(position)
                return if (row is Row.SourceSection) layoutManager.spanCount else 1
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SOURCE_SECTION -> SourceSectionViewHolder(
                inflater.inflate(R.layout.source_albums_section, parent, false)
            )
            else -> AlbumViewHolder(
                inflater.inflate(R.layout.album_thumbnail_view, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.SourceSection -> (holder as SourceSectionViewHolder).bind(row.sourceAlbums)
            is Row.AlbumRow -> (holder as AlbumViewHolder).bind(row.album)
        }
    }

    inner class SourceSectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val recyclerView = view.findViewById<RecyclerView>(R.id.sourceAlbumsRecyclerView)
        private val emptyView = view.findViewById<View>(R.id.sourceEmptyView)
        private val adapter = SourceCarouselAdapter(onSourceAlbumClick)

        init {
            recyclerView.layoutManager =
                LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.adapter = this.adapter
            recyclerView.isNestedScrollingEnabled = false
        }

        fun bind(sourceAlbums: List<SourceAlbum>) {
            val isEmpty = sourceAlbums.isEmpty()
            recyclerView.isVisible = !isEmpty
            emptyView.isVisible = isEmpty
            if (!isEmpty) adapter.submitList(sourceAlbums)
        }
    }

    inner class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnailImageView = view.findViewById<ImageView>(R.id.thumbnailImageView)!!
        private val descriptionTextView = view.findViewById<TextView>(R.id.descriptionTextView)!!
        private val itemsCountTextView = view.findViewById<TextView>(R.id.itemsCountTextView)!!

        init {
            view.setOnClickListener {
                (bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION })?.let { pos ->
                    (rows[pos] as? Row.AlbumRow)?.album?.let(onAlbumClick)
                }
            }
        }

        fun bind(album: Album) {
            descriptionTextView.text = album.name
            album.mediaCount?.let { count ->
                itemsCountTextView.text = itemView.resources.getQuantityString(
                    R.plurals.album_thumbnail_items, count, count
                )
            }
            thumbnailImageView.loadThumbnail(
                album.thumbnail,
                options = RequestOptions()
                    .override(Thumbnail.MAX_THUMBNAIL_SIZE, Thumbnail.MAX_THUMBNAIL_SIZE)
                    .centerCrop()
            )
        }
    }

    companion object {
        private const val TYPE_SOURCE_SECTION = 0
        private const val TYPE_ALBUM = 1
    }
}

private class SourceCarouselAdapter(
    private val onClick: (SourceAlbum) -> Unit,
) : ListAdapter<SourceAlbum, SourceCarouselAdapter.CarouselViewHolder>(
    object : DiffUtil.ItemCallback<SourceAlbum>() {
        override fun areItemsTheSame(o: SourceAlbum, n: SourceAlbum) = o.key == n.key
        override fun areContentsTheSame(o: SourceAlbum, n: SourceAlbum) = o == n
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = CarouselViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.source_album_card, parent, false)
    )

    override fun onBindViewHolder(holder: CarouselViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class CarouselViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover = view.findViewById<ImageView>(R.id.sourceCoverImageView)!!
        private val name = view.findViewById<TextView>(R.id.sourceNameTextView)!!
        private val count = view.findViewById<TextView>(R.id.sourceCountTextView)!!

        init {
            view.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let {
                    onClick(getItem(it))
                }
            }
        }

        fun bind(source: SourceAlbum) {
            name.text = source.displayName
            count.text = itemView.resources.getQuantityString(
                R.plurals.album_thumbnail_items, source.mediaCount, source.mediaCount
            )
            cover.loadThumbnail(
                source.coverUri?.let { org.lineageos.glimpse.models.Thumbnail(uri = it) },
                options = RequestOptions()
                    .override(
                        org.lineageos.glimpse.models.Thumbnail.MAX_THUMBNAIL_SIZE,
                        org.lineageos.glimpse.models.Thumbnail.MAX_THUMBNAIL_SIZE
                    )
                    .centerCrop()
            )
        }
    }
}
