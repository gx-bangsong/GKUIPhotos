/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.OptIn
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.zoomimage.GlideZoomImageView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ext.fade
import org.lineageos.glimpse.ext.load
import org.lineageos.glimpse.models.Media
import org.lineageos.glimpse.models.MediaType
import org.lineageos.glimpse.models.MotionPhoto
import org.lineageos.glimpse.ui.MediaGestureListener
import org.lineageos.glimpse.viewmodels.LocalPlayerViewModel

class MediaViewerAdapter(
    private val localPlayerViewModel: LocalPlayerViewModel,
    private val onNavigate: (forward: Boolean) -> Unit,
) : ListAdapter<Media, MediaViewerAdapter.MediaViewHolder>(UniqueItemDiffCallback()) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = MediaViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.media_view, parent, false),
    )

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: MediaViewHolder) {
        super.onViewAttachedToWindow(holder)

        holder.onViewAttachedToWindow()
    }

    override fun onViewDetachedFromWindow(holder: MediaViewHolder) {
        holder.onViewDetachedFromWindow()

        super.onViewDetachedFromWindow(holder)
    }

    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Views
        private val imageView = view.findViewById<GlideZoomImageView>(R.id.imageView)

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val playerControlView =
            view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)
        private val speedButton = view.findViewById<MaterialButton>(R.id.speedButton)

        private var media: Media? = null
        private var motionPhoto: MotionPhoto? = null
        private var isCurrentlyDisplayedView = false
        private val mediaGestureListener = MediaGestureListener(
            context = itemView.context,
            onNavigate = onNavigate,
        )

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val mediaPositionObserver: (Int?) -> Unit = { currentPosition: Int? ->
            isCurrentlyDisplayedView = currentPosition == bindingAdapterPosition

            val isVideo = media?.mediaType == MediaType.VIDEO || motionPhoto != null
            val isNowVideoPlayer = isCurrentlyDisplayedView && isVideo

            imageView.isVisible = !isNowVideoPlayer
            playerView.isVisible = isNowVideoPlayer

            // Module 1: speed button is only relevant for the video player and is
            // hidden while in fullscreen (the controls themselves are hidden there).
            speedButton.isVisible = isNowVideoPlayer && !localPlayerViewModel.fullscreenMode.value
            updateSpeedButtonLabel()

            if (!isNowVideoPlayer || localPlayerViewModel.fullscreenMode.value) {
                playerControlView.hideImmediately()
            } else {
                playerControlView.show()
            }

            val player = when (isNowVideoPlayer) {
                true -> localPlayerViewModel.exoPlayer
                false -> null
            }

            playerView.player = player
            playerControlView.player = player

            // Update media gesture listener
            updateMediaGestureListener(isNowVideoPlayer)

            // Update native seek buttons visibility
            if (isNowVideoPlayer) {
                updateNativeSeekButtons()
            }
        }

        private val sheetsHeightObserver = { sheetsHeight: Pair<Int, Int> ->
            if (!localPlayerViewModel.fullscreenMode.value) {
                val (topHeight, bottomHeight) = sheetsHeight

                // Place the player controls between the two sheets
                playerControlView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = topHeight
                    bottomMargin = bottomHeight
                }
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val fullscreenModeObserver = { fullscreenMode: Boolean ->
            if (media?.mediaType == MediaType.VIDEO) {
                playerControlView.fade(!fullscreenMode)
                // Module 1: hide the speed chip while the controls are hidden.
                speedButton.fade(!fullscreenMode)
            }
        }

        private val displayedMediaToMotionPhotoObserver = { it: Pair<Media?, MotionPhoto?> ->
            val (displayedMedia, motionPhoto) = it
            this.motionPhoto = motionPhoto
            // Trigger a refresh of the UI
            mediaPositionObserver(localPlayerViewModel.mediaPosition.value)
        }

        private var observersJob: Job? = null

        init {
            imageView.setOnClickListener {
                localPlayerViewModel.toggleFullscreenMode()
            }
            playerView.setOnClickListener {
                localPlayerViewModel.toggleFullscreenMode()
            }

            // Module 1: playback-speed selector popup.
            speedButton.setOnClickListener {
                showSpeedMenu()
            }

            // A single touch listener handles both edge taps and double taps.
            imageView.setOnTouchListener(mediaGestureListener)
            playerView.setOnTouchListener(mediaGestureListener)
        }

        /**
         * Module 1: show the multi-speed popup (0.5x / 1.0x / 1.5x / 2.0x). The
         * chosen speed is applied with pitch preserved (see
         * [org.lineageos.glimpse.viewmodels.LocalPlayerViewModel.setPlaybackSpeed]).
         */
        private fun showSpeedMenu() {
            val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
            val popup = PopupMenu(itemView.context, speedButton)
            speeds.forEachIndexed { index, speed ->
                popup.menu.add(0, index, index, speedLabel(speed))
            }
            popup.setOnMenuItemClickListener { item ->
                speeds.getOrNull(item.itemId)?.let { speed ->
                    localPlayerViewModel.setPlaybackSpeed(speed)
                    updateSpeedButtonLabel()
                    true
                } ?: false
            }
            popup.show()
        }

        private fun updateSpeedButtonLabel() {
            speedButton.text = speedLabel(localPlayerViewModel.playbackSpeed)
        }

        private fun speedLabel(speed: Float): String {
            val formatted = if (speed == speed.toInt().toFloat()) {
                speed.toInt().toString()
            } else {
                speed.toString()
            }
            return "${formatted}x"
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun updateMediaGestureListener(isVideoPlayer: Boolean) {
            mediaGestureListener.edgeTapNavigationEnabled =
                localPlayerViewModel.edgeTapNavigationEnabled

            mediaGestureListener.doubleTapSeekEnabled =
                isVideoPlayer && localPlayerViewModel.doubleTapToSeekEnabled

            mediaGestureListener.seekTimeSeconds = localPlayerViewModel.doubleTapToSeekSeconds
            mediaGestureListener.player = when (
                isVideoPlayer && localPlayerViewModel.doubleTapToSeekEnabled
            ) {
                true -> localPlayerViewModel.exoPlayer
                false -> null
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun updateNativeSeekButtons() {
            val hideButtons = localPlayerViewModel.hideNativeSeekButtons

            // Update PlayerView to show/hide rewind and fast-forward buttons
            playerView.setShowRewindButton(!hideButtons)
            playerView.setShowFastForwardButton(!hideButtons)
        }

        fun bind(media: Media) {
            this.media = media

            imageView.load(media.uri)
        }

        fun onViewAttachedToWindow() {
            observersJob = itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                launch {
                    localPlayerViewModel.mediaPosition.collectLatest(mediaPositionObserver)
                }
                launch {
                    localPlayerViewModel.sheetsHeight.collectLatest(sheetsHeightObserver)
                }
                launch {
                    localPlayerViewModel.fullscreenMode.collectLatest(fullscreenModeObserver)
                }
                launch {
                    localPlayerViewModel.displayedMediaToMotionPhoto.collectLatest(
                        displayedMediaToMotionPhotoObserver
                    )
                }
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        fun onViewDetachedFromWindow() {
            observersJob?.cancel()
            observersJob = null

            mediaGestureListener.player = null
            playerView.player = null
            playerControlView.player = null
        }
    }
}
