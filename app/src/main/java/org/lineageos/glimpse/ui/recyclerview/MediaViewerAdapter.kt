/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.recyclerview

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        // Views
        private val imageView = view.findViewById<GlideZoomImageView>(R.id.imageView)

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val playerControlView =
            view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)

        // Content frame is the actual video surface; clicking it should toggle
        // our own chrome-fullscreen. Clicking the controller (which hosts the
        // play/pause button) must NOT toggle fullscreen, otherwise the play
        // button and fullscreen toggle overlap in gesture handling, especially
        // in PiP where the window is tiny.
        private val contentFrame: View? =
            playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)

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

            val inPip = localPlayerViewModel.isInPictureInPictureMode.value

            // PiP 模式下完全禁用自定义控制器，遵循 Android 原生 PiP 规范：
            // 系统自带关闭/还原按钮，播放控制交由系统或保持静默播放，不要把
            // 完整播放器 UI 照搬到悬浮小窗上。
            playerView.useController = !inPip

            if (!isNowVideoPlayer || localPlayerViewModel.fullscreenMode.value || inPip) {
                playerControlView.hideImmediately()
                if (inPip) playerControlView.isVisible = false
            } else {
                playerControlView.isVisible = true
                playerControlView.show()
            }

            val player = when (isNowVideoPlayer) {
                true -> localPlayerViewModel.exoPlayer
                false -> null
            }

            playerView.player = player
            playerControlView.player = if (inPip) null else player

            // Update media gesture listener
            updateMediaGestureListener(isNowVideoPlayer)

            // Update native seek buttons visibility
            if (isNowVideoPlayer) {
                updateNativeSeekButtons()
                hideFullscreenButton()
            }
        }

        private val sheetsHeightObserver = { sheetsHeight: Pair<Int, Int> ->
            if (!localPlayerViewModel.fullscreenMode.value &&
                !localPlayerViewModel.isInPictureInPictureMode.value
            ) {
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
                val inPip = localPlayerViewModel.isInPictureInPictureMode.value
                if (inPip) {
                    playerView.useController = false
                    playerControlView.hideImmediately()
                    playerControlView.isVisible = false
                } else {
                    playerView.useController = true
                    playerControlView.isVisible = true
                    if (fullscreenMode) {
                        playerControlView.fade(false)
                    } else {
                        playerControlView.fade(true)
                    }
                }
            }
        }

        private val pipModeObserver = { inPip: Boolean ->
            if (media?.mediaType == MediaType.VIDEO) {
                if (inPip) {
                    // 进入 PiP：彻底禁用 App 自己的播放器 UI，遵循系统规范
                    // 不要把完整的进度条/播放/全屏按钮塞进悬浮窗，
                    // 系统自带的关闭与全屏还原已足够。
                    playerView.useController = false
                    playerControlView.player = null
                    playerControlView.hideImmediately()
                    playerControlView.isVisible = false
                    playerControlView.fade(false)
                } else {
                    // 退出 PiP：恢复控制器
                    playerView.useController = true
                    if (isCurrentlyDisplayedView) {
                        playerControlView.player = localPlayerViewModel.exoPlayer
                        if (!localPlayerViewModel.fullscreenMode.value) {
                            playerControlView.isVisible = true
                            playerControlView.show()
                            playerControlView.fade(true)
                        }
                    }
                    hideFullscreenButton()
                }
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
            // Hide ExoPlayer's own fullscreen button everywhere. Glimpse
            // implements its own chrome-fullscreen by tapping the content,
            // so the Exo button is redundant and in a tiny PiP window it
            // visually overlaps / intercepts the play/pause button.
            hideFullscreenButton()

            imageView.setOnClickListener {
                if (!localPlayerViewModel.isInPictureInPictureMode.value) {
                    localPlayerViewModel.toggleFullscreenMode()
                }
            }

            // Prefer clicking the content frame rather than the whole PlayerView.
            // The content frame sits behind the controller, so clicks on
            // controller buttons (play/pause) will not bubble up to toggle
            // fullscreen. This eliminates the play vs fullscreen conflict.
            val toggleFullscreenIfAllowed = View.OnClickListener {
                if (!localPlayerViewModel.isInPictureInPictureMode.value) {
                    localPlayerViewModel.toggleFullscreenMode()
                }
            }
            contentFrame?.setOnClickListener(toggleFullscreenIfAllowed)
            // Fallback for devices where exo_content_frame id is absent: keep a
            // listener on PlayerView but the controller is made clickable to
            // intercept (see below), so its buttons still won't trigger it.
            playerView.setOnClickListener(toggleFullscreenIfAllowed)

            // Make the controller clickable so it consumes taps. Without this,
            // a tap on the play button could still propagate to the parent
            // PlayerView/contentFrame and toggle fullscreen at the same time.
            playerControlView.isClickable = true
            playerControlView.isFocusable = true
            // No-op click listener ensures controller consumes the click.
            playerControlView.setOnClickListener { /* consume */ }

            // A single touch listener handles both edge taps and double taps.
            imageView.setOnTouchListener(mediaGestureListener)
            // Attach gesture listener to contentFrame if present, otherwise to
            // PlayerView. This avoids the gesture layer sitting on top of the
            // controller and stealing play-button touches.
            (contentFrame ?: playerView).setOnTouchListener(mediaGestureListener)
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun hideFullscreenButton() {
            // Media3 API: if no listener is set, the fullscreen button should
            // be hidden. We also explicitly hide the view for safety across
            // library versions where the id may be exo_fullscreen_button or
            // exo_fullscreen.
            try {
                playerView.setFullscreenButtonClickListener(null)
            } catch (_: Exception) {
                // Older/newer APIs may not have this method; ignore.
            }
            // Best-effort explicit hiding for different IDs used in various
            // media3 / exoplayer versions.
            val fullscreenButtonIds = listOf(
                androidx.media3.ui.R.id.exo_fullscreen_button,
                androidx.media3.ui.R.id.exo_fullscreen,
                // Legacy exo id, may not exist in media3 but safe to try via resource lookup.
            )
            for (id in fullscreenButtonIds) {
                try {
                    playerView.findViewById<View>(id)?.apply {
                        isVisible = false
                        // Also disable to prevent touch.
                        isEnabled = false
                    }
                } catch (_: Exception) {
                }
            }
            // Also hide through the control view directly.
            try {
                playerControlView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen_button)?.let {
                    it.isVisible = false
                    it.isEnabled = false
                }
                playerControlView.findViewById<View>(androidx.media3.ui.R.id.exo_fullscreen)?.let {
                    it.isVisible = false
                    it.isEnabled = false
                }
            } catch (_: Exception) {
            }
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun updateMediaGestureListener(isVideoPlayer: Boolean) {
            val inPip = localPlayerViewModel.isInPictureInPictureMode.value
            mediaGestureListener.edgeTapNavigationEnabled =
                localPlayerViewModel.edgeTapNavigationEnabled && !inPip

            mediaGestureListener.doubleTapSeekEnabled =
                isVideoPlayer && localPlayerViewModel.doubleTapToSeekEnabled && !inPip

            mediaGestureListener.seekTimeSeconds = localPlayerViewModel.doubleTapToSeekSeconds

            // The player is needed for both double-tap seek and the press-and-hold
            // fast-forward, so attach it for any video player regardless of whether
            // double-tap seek itself is enabled.
            mediaGestureListener.player = when {
                !isVideoPlayer -> null
                inPip -> null
                else -> localPlayerViewModel.exoPlayer
            }

            // Press-and-hold fast-forward (customizable in Settings).
            mediaGestureListener.longPressSpeedEnabled =
                isVideoPlayer && localPlayerViewModel.longPressSpeedEnabled && !inPip
            mediaGestureListener.longPressSpeed = localPlayerViewModel.longPressSpeed
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
            hideFullscreenButton()
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
                    localPlayerViewModel.isInPictureInPictureMode.collectLatest(pipModeObserver)
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
            // 保证 ViewHolder 复用时控制器状态重置，不把 PiP 的无控制器状态带到正常页面
            playerView.useController = true
            playerControlView.isVisible = true
        }
    }
}
