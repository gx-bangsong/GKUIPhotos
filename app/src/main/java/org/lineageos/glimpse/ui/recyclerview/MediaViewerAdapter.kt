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
        private val imageView = view.findViewById<GlideZoomImageView>(R.id.imageView)

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private val playerControlView =
            view.findViewById<PlayerControlView>(androidx.media3.ui.R.id.exo_controller)
        private val playerView = view.findViewById<PlayerView>(R.id.playerView)

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

            // PiP 模式下完全禁用自定义控制器，遵循 Android 原生 PiP 规范
            playerView.setUseController(!inPip)

            if (!isNowVideoPlayer || localPlayerViewModel.fullscreenMode.value || inPip) {
                playerControlView.hideImmediately()
                if (inPip) playerControlView.visibility = View.GONE
            } else {
                playerControlView.visibility = View.VISIBLE
                playerControlView.show()
            }

            val player = when (isNowVideoPlayer) {
                true -> localPlayerViewModel.exoPlayer
                false -> null
            }

            playerView.player = player
            if (inPip) {
                playerControlView.setPlayer(null)
            } else {
                playerControlView.setPlayer(player)
            }

            updateMediaGestureListener(isNowVideoPlayer)

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
                    playerView.setUseController(false)
                    playerControlView.hideImmediately()
                    playerControlView.visibility = View.GONE
                } else {
                    playerView.setUseController(true)
                    playerControlView.visibility = View.VISIBLE
                    if (fullscreenMode) {
                        playerControlView.fade(false)
                    } else {
                        playerControlView.fade(true)
                    }
                }
            }
        }

        private val pipModeObserver = { pipActive: Boolean ->
            // 修复：非 PiP 下长按倍速被破坏 — 退出 PiP 后必须恢复手势
            val isVideo = media?.mediaType == MediaType.VIDEO || motionPhoto != null
            if (pipActive) {
                playerView.setUseController(false)
                playerControlView.setPlayer(null)
                playerControlView.hideImmediately()
                playerControlView.visibility = View.GONE
                playerControlView.fade(false)
                // PiP 下禁用所有手势，避免与系统 PiP 冲突
                updateMediaGestureListener(false)
            } else {
                playerView.setUseController(true)
                if (isCurrentlyDisplayedView) {
                    playerControlView.setPlayer(localPlayerViewModel.exoPlayer)
                    if (!localPlayerViewModel.fullscreenMode.value) {
                        playerControlView.visibility = View.VISIBLE
                        playerControlView.show()
                        playerControlView.fade(true)
                    }
                }
                hideFullscreenButton()
                // 恢复：长按倍速必须在非 PiP 视频页可用
                if (isVideo) {
                    updateMediaGestureListener(true)
                }
            }
        }

        private val displayedMediaToMotionPhotoObserver = { it: Pair<Media?, MotionPhoto?> ->
            val (displayedMedia, motionPhoto) = it
            this.motionPhoto = motionPhoto
            mediaPositionObserver(localPlayerViewModel.mediaPosition.value)
        }

        private var observersJob: Job? = null

        init {
            hideFullscreenButton()

            imageView.setOnClickListener {
                if (!localPlayerViewModel.isInPictureInPictureMode.value) {
                    localPlayerViewModel.toggleFullscreenMode()
                }
            }

            val toggleFullscreenIfAllowed = View.OnClickListener {
                if (!localPlayerViewModel.isInPictureInPictureMode.value) {
                    localPlayerViewModel.toggleFullscreenMode()
                }
            }
            contentFrame?.setOnClickListener(toggleFullscreenIfAllowed)
            playerView.setOnClickListener(toggleFullscreenIfAllowed)

            playerControlView.isClickable = true
            playerControlView.isFocusable = true
            playerControlView.setOnClickListener { /* consume */ }

            imageView.setOnTouchListener(mediaGestureListener)
            // 非 PiP 下长按倍速：手势监听要同时挂在 contentFrame 和 playerView
            // 避免因为 contentFrame 存在而导致 playerView 没有手势
            contentFrame?.setOnTouchListener(mediaGestureListener)
            playerView.setOnTouchListener(mediaGestureListener)
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun hideFullscreenButton() {
            try {
                playerView.setFullscreenButtonClickListener(null)
            } catch (_: Exception) {
            }
            // 使用 getIdentifier 避免直接引用不存在的 R.id 导致编译失败
            // 兼容不同 media3 版本：exo_fullscreen / exo_fullscreen_button
            val ctx = playerView.context
            val possibleIds = listOf(
                ctx.resources.getIdentifier(
                    "exo_fullscreen_button",
                    "id",
                    ctx.packageName
                ),
                ctx.resources.getIdentifier(
                    "exo_fullscreen",
                    "id",
                    ctx.packageName
                ),
                ctx.resources.getIdentifier(
                    "exo_fullscreen_button",
                    "id",
                    "androidx.media3.ui"
                ),
                ctx.resources.getIdentifier(
                    "exo_fullscreen",
                    "id",
                    "androidx.media3.ui"
                ),
                androidx.media3.ui.R.id.exo_fullscreen
            )
            for (id in possibleIds) {
                if (id == 0) continue
                try {
                    playerView.findViewById<View>(id)?.let { v ->
                        v.visibility = View.GONE
                        v.isEnabled = false
                    }
                } catch (_: Exception) {
                }
                try {
                    playerControlView.findViewById<View>(id)?.let { v ->
                        v.visibility = View.GONE
                        v.isEnabled = false
                    }
                } catch (_: Exception) {
                }
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
            mediaGestureListener.player = when {
                !isVideoPlayer -> null
                inPip -> null
                else -> localPlayerViewModel.exoPlayer
            }
            mediaGestureListener.longPressSpeedEnabled =
                isVideoPlayer && localPlayerViewModel.longPressSpeedEnabled && !inPip
            mediaGestureListener.longPressSpeed = localPlayerViewModel.longPressSpeed
        }

        @OptIn(androidx.media3.common.util.UnstableApi::class)
        private fun updateNativeSeekButtons() {
            val hideButtons = localPlayerViewModel.hideNativeSeekButtons
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
            playerControlView.setPlayer(null)
            playerView.setUseController(true)
            playerControlView.visibility = View.VISIBLE
        }
    }
}
