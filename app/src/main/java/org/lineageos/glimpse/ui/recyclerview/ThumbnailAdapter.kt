/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.request.RequestOptions
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.load
import org.lineageos.glimpse.ext.quickSelectDateEnabled
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.Thumbnail
import org.lineageos.glimpse.viewmodels.AlbumViewModel
import java.util.Date
import kotlin.reflect.safeCast

class ThumbnailAdapter : ListAdapter<AlbumViewModel.AlbumContent, RecyclerView.ViewHolder>(
    UniqueItemDiffCallback()
) {
    private val inSelectionMode = MutableLiveData(false)

    // Reverse lookup list for media items
    private var mediaToIndex: Map<Media, Int>? = null

    // Precomputed map from Date to the list of Media belonging to that date header
    private var dateToMedias: Map<Date, List<Media>> = emptyMap()

    private var recyclerView: RecyclerView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDateHeaderUpdate = false

    var isQuickSelectEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyDateHeadersChanged()
            }
        }

    private val dateHeaderSelectionObserver =
        object : SelectionTracker.SelectionObserver<Media>() {
            override fun onSelectionChanged() {
                notifyDateHeadersChanged()
            }

            override fun onSelectionRefresh() {
                notifyDateHeadersChanged()
            }

            override fun onSelectionRestored() {
                notifyDateHeadersChanged()
            }
        }

    var selectionTracker: SelectionTracker<Media>? = null
        set(value) {
            field = value
            value?.addObserver(dateHeaderSelectionObserver)
        }

    val itemKeyProvider = object : ItemKeyProvider<Media>(SCOPE_CACHED) {
        override fun getKey(position: Int) = getItem(position).let {
            AlbumViewModel.AlbumContent.MediaItem::class.safeCast(it)?.media
        }

        override fun getPosition(key: Media): Int {
            if (key == DATE_HEADER_KEY) return -1
            return mediaToIndex?.get(key) ?: -1
        }
    }

    private var onItemSelected: (media: Media) -> Unit = {}

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        isQuickSelectEnabled = PreferenceManager.getDefaultSharedPreferences(
            recyclerView.context
        ).quickSelectDateEnabled
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        LayoutInflater.from(parent.context).let { layoutInflater ->
            when (viewType) {
                AlbumViewModel.AlbumContent.ViewType.THUMBNAIL.ordinal -> ThumbnailViewHolder(
                    layoutInflater.inflate(R.layout.thumbnail_view, parent, false)
                )

                AlbumViewModel.AlbumContent.ViewType.DATE_HEADER.ordinal -> DateHeaderViewHolder(
                    layoutInflater.inflate(R.layout.date_header_view, parent, false)
                )

                else -> throw Exception("Unknown view type $viewType")
            }
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder.itemViewType) {
            AlbumViewModel.AlbumContent.ViewType.THUMBNAIL.ordinal -> {
                val thumbnailViewHolder = holder as ThumbnailViewHolder
                val media = (getItem(position) as AlbumViewModel.AlbumContent.MediaItem).media
                thumbnailViewHolder.bind(
                    media, selectionTracker?.isSelected(media) == true,
                )
            }

            AlbumViewModel.AlbumContent.ViewType.DATE_HEADER.ordinal -> {
                val dateHeaderViewHolder = holder as DateHeaderViewHolder
                dateHeaderViewHolder.bind((getItem(position) as AlbumViewModel.AlbumContent.DateHeader).date)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_DATE_CHECK) && holder is DateHeaderViewHolder) {
            holder.updateCheckState()
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onCurrentListChanged(
        previousList: MutableList<AlbumViewModel.AlbumContent>,
        currentList: MutableList<AlbumViewModel.AlbumContent>
    ) {
        super.onCurrentListChanged(previousList, currentList)

        val dataTypeToIndex = mutableMapOf<Media, Int>()
        val dateMap = mutableMapOf<Date, MutableList<Media>>()
        var currentDate: Date? = null

        for (i in currentList.indices) {
            when (val item = currentList[i]) {
                is AlbumViewModel.AlbumContent.DateHeader -> {
                    currentDate = item.date
                    dateMap[currentDate] = mutableListOf()
                }

                is AlbumViewModel.AlbumContent.MediaItem -> {
                    dataTypeToIndex[item.media] = i
                    currentDate?.let { dateMap[it]?.add(item.media) }
                }
            }
        }

        this.mediaToIndex = dataTypeToIndex.toMap()
        this.dateToMedias = dateMap
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)

        ThumbnailViewHolder::class.safeCast(holder)?.onViewAttachedToWindow()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        ThumbnailViewHolder::class.safeCast(holder)?.onViewDetachedFromWindow()

        super.onViewDetachedFromWindow(holder)
    }

    override fun getItemViewType(position: Int) = getItem(position).viewType

    fun setInSelectionMode(inSelectionMode: Boolean) {
        this.inSelectionMode.value = inSelectionMode
    }

    fun setOnItemSelected(onItemSelected: ((media: Media) -> Unit)?) {
        this.onItemSelected = onItemSelected ?: {}
    }

    fun getMediaForDate(date: Date): List<Media> = dateToMedias[date] ?: emptyList()

    fun notifyDateHeadersChanged() {
        if (pendingDateHeaderUpdate) return
        pendingDateHeaderUpdate = true
        mainHandler.post {
            pendingDateHeaderUpdate = false
            updateVisibleDateHeaders()
        }
    }

    private fun updateVisibleDateHeaders() {
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val vh = rv.getChildViewHolder(child)
            if (vh is DateHeaderViewHolder) {
                vh.updateCheckState()
            }
        }
    }

    inner class ThumbnailViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Views
        private val selectionCheckedImageView =
            itemView.findViewById<ImageView>(R.id.selectionCheckedImageView)
        private val selectionScrimView = itemView.findViewById<View>(R.id.selectionScrimView)
        private val videoOverlayImageView =
            itemView.findViewById<ImageView>(R.id.videoOverlayImageView)!!
        private val thumbnailImageView = itemView.findViewById<ImageView>(R.id.thumbnailImageView)!!

        private lateinit var media: Media
        private var isSelected = false

        private val inSelectionModeObserver = Observer { inSelectionMode: Boolean ->
            selectionCheckedImageView.isVisible = inSelectionMode
        }

        val itemDetails = object : ItemDetailsLookup.ItemDetails<Media>() {
            override fun getPosition() = bindingAdapterPosition
            override fun getSelectionKey() = media
        }

        fun onViewAttachedToWindow() {
            inSelectionMode.observeForever(inSelectionModeObserver)
        }

        fun onViewDetachedFromWindow() {
            inSelectionMode.removeObserver(inSelectionModeObserver)
        }

        fun bind(media: Media, isSelected: Boolean = false) {
            this.media = media
            this.isSelected = isSelected

            itemView.setOnClickListener {
                onItemSelected(media)
            }

            thumbnailImageView.load(
                media.uri,
                options = RequestOptions()
                    .override(
                        Thumbnail.MAX_THUMBNAIL_SIZE,
                        Thumbnail.MAX_THUMBNAIL_SIZE
                    )
                    .centerCrop()
            )

            videoOverlayImageView.isVisible = media.mediaType == MediaType.VIDEO

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                thumbnailImageView.setRenderEffect(
                    when (isSelected) {
                        true -> createBlurRenderEffect()
                        false -> null
                    }
                )
            } else {
                selectionScrimView.isVisible = isSelected
            }

            selectionCheckedImageView.setImageResource(
                when (isSelected) {
                    true -> R.drawable.ic_check_circle
                    false -> R.drawable.ic_check_circle_outline
                }
            )
        }
    }

    inner class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Views
        private val textView = view.findViewById<TextView>(R.id.dateTextView)
        private val checkImageView = view.findViewById<ImageView>(R.id.dateCheckImageView)

        private var currentDate: Date? = null

        val itemDetails = object : ItemDetailsLookup.ItemDetails<Media>() {
            override fun getPosition() = bindingAdapterPosition
            override fun getSelectionKey(): Media = DATE_HEADER_KEY
        }

        init {
            checkImageView.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            itemView.setOnClickListener {
                if (isQuickSelectEnabled) {
                    checkImageView.performClick()
                }
            }
        }

        fun bind(date: Date) {
            this.currentDate = date
            textView.text = DateUtils.getRelativeTimeSpanString(
                date.time,
                System.currentTimeMillis(),
                DateUtils.DAY_IN_MILLIS
            )
            updateCheckState()

            checkImageView.setOnClickListener {
                val date = currentDate ?: return@setOnClickListener
                val dayMedias = dateToMedias[date] ?: return@setOnClickListener
                if (dayMedias.isEmpty()) return@setOnClickListener

                val allSelected = dayMedias.all { selectionTracker?.isSelected(it) == true }
                val newSelectedState = !allSelected

                // Instant optimistic UI feedback
                checkImageView.setImageResource(
                    if (newSelectedState) R.drawable.ic_check_circle
                    else R.drawable.ic_check_circle_outline
                )

                // Single atomic batch selection update
                selectionTracker?.setItemsSelected(dayMedias, newSelectedState)
            }
        }

        fun updateCheckState() {
            checkImageView.isVisible = isQuickSelectEnabled
            if (isQuickSelectEnabled) {
                val date = currentDate
                val dayMedias = if (date != null) dateToMedias[date] ?: emptyList() else emptyList()
                val allSelected = dayMedias.isNotEmpty() && dayMedias.all {
                    selectionTracker?.isSelected(it) == true
                }
                checkImageView.setImageResource(
                    if (allSelected) R.drawable.ic_check_circle
                    else R.drawable.ic_check_circle_outline
                )
            }
        }
    }

    companion object {
        private const val BLUR_RADIUS = 15f
        const val PAYLOAD_DATE_CHECK = "payload_date_check"

        val DATE_HEADER_KEY = Media(
            uri = Uri.parse("glimpse://date_header"),
            mediaType = MediaType.IMAGE,
            mimeType = "",
            albumUri = Uri.EMPTY,
            albumName = null,
            displayName = null,
            isFavorite = false,
            isTrashed = false,
            dateAdded = Date(0),
            dateModified = Date(0),
            width = 0,
            height = 0,
            orientation = 0,
            sizeBytes = 0,
        )

        // Exposed as a function (not an eager companion val) so the RenderEffect
        // class is only referenced when this is actually called, which happens
        // solely on API 31+ behind the SDK_INT >= S guard in bind(). An eager
        // initializer would run at <clinit> time and throw NoClassDefFoundError
        // on the API 30 minSdk. Method bodies are lazily verified by ART, so
        // referencing RenderEffect here is safe on API 30 as long as this is
        // never invoked there.
        @RequiresApi(Build.VERSION_CODES.S)
        private fun createBlurRenderEffect() = RenderEffect.createBlurEffect(
            BLUR_RADIUS, BLUR_RADIUS,
            Shader.TileMode.MIRROR
        )
    }
}
