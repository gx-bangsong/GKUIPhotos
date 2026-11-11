/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.color.MaterialColors
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
    private val onPinToggle: (Album) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class PinnedSection(val albums: List<Album>) : Row
        data class SourceSection(val sourceAlbums: List<SourceAlbum>) : Row
        data class AlbumRow(val album: Album) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submit(albums: List<Album>, sourceAlbums: List<SourceAlbum>, pinnedKeys: Set<String>) {
        val (pinned, remaining) = partitionPinned(albums, pinnedKeys) { it.uri.toString() }
        val newRows = buildList {
            if (pinned.isNotEmpty()) add(Row.PinnedSection(pinned))
            if (sourceAlbums.isNotEmpty()) {
                add(Row.SourceSection(sourceAlbums))
            }
            remaining.forEach { add(Row.AlbumRow(it)) }
        }
        // Lightweight full refresh; albums lists are small.
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.PinnedSection -> TYPE_PINNED_SECTION
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
                return if (row is Row.SourceSection || row is Row.PinnedSection) layoutManager.spanCount else 1
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PINNED_SECTION -> PinnedSectionViewHolder(
                inflater.inflate(R.layout.source_albums_section, parent, false)
            )
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
            is Row.PinnedSection -> (holder as PinnedSectionViewHolder).bind(row.albums)
            is Row.SourceSection -> (holder as SourceSectionViewHolder).bind(row.sourceAlbums)
            is Row.AlbumRow -> (holder as AlbumViewHolder).bind(row.album, false)
        }
    }

    inner class SourceSectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val recyclerView = view.findViewById<RecyclerView>(R.id.sourceAlbumsRecyclerView)
        private val adapter = SourceCarouselAdapter(onSourceAlbumClick)

        init {
            recyclerView.layoutManager =
                LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            recyclerView.adapter = this.adapter
            recyclerView.isNestedScrollingEnabled = false
        }

        fun bind(sourceAlbums: List<SourceAlbum>) {
            adapter.submitList(sourceAlbums)
        }
    }

    inner class PinnedSectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val carousel = view.findViewById<RecyclerView>(R.id.sourceAlbumsRecyclerView)
        private val pinnedAdapter = PinnedCarouselAdapter()

        init {
            view.findViewById<TextView>(R.id.sourceAlbumsHeader).setText(R.string.pinned_albums_header)
            carousel.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
            carousel.adapter = pinnedAdapter
            carousel.isNestedScrollingEnabled = false
        }

        fun bind(albums: List<Album>) = pinnedAdapter.submitList(albums)
    }

    inner class PinnedCarouselAdapter : ListAdapter<Album, AlbumViewHolder>(
        object : DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(old: Album, new: Album) = old.uri == new.uri
            override fun areContentsTheSame(old: Album, new: Album) = old == new
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.album_thumbnail_view, parent, false)
            view.layoutParams = RecyclerView.LayoutParams(
                (160 * parent.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
            )
            return AlbumViewHolder(view)
        }
        override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) = holder.bind(getItem(position), true)
    }

    inner class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnailImageView = view.findViewById<ImageView>(R.id.thumbnailImageView)!!
        private val descriptionTextView = view.findViewById<TextView>(R.id.descriptionTextView)!!
        private val itemsCountTextView = view.findViewById<TextView>(R.id.itemsCountTextView)!!
        private val pinButton = view.findViewById<ImageButton>(R.id.pinAlbumButton)!!

        fun bind(album: Album, pinned: Boolean) {
            itemView.setOnClickListener { onAlbumClick(album) }
            pinButton.setOnClickListener { onPinToggle(album) }
            pinButton.contentDescription = itemView.context.getString(if (pinned) R.string.album_unpin else R.string.album_pin)
            pinButton.imageTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(pinButton, if (pinned) com.google.android.material.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            descriptionTextView.text = album.name
            itemsCountTextView.text = album.mediaCount?.let { count ->
                itemView.resources.getQuantityString(R.plurals.album_thumbnail_items, count, count)
            } ?: ""
            thumbnailImageView.loadThumbnail(
                album.thumbnail,
                options = RequestOptions()
                    .override(Thumbnail.MAX_THUMBNAIL_SIZE, Thumbnail.MAX_THUMBNAIL_SIZE)
                    .centerCrop()
            )
        }
    }

    companion object {
        private const val TYPE_PINNED_SECTION = 2
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
