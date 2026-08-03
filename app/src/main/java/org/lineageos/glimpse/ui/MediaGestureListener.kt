/*
 * SPDX-FileCopyrightText: 2025 Guidix
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/**
 * Unified media gesture listener.
 * - Single tap on screen edges navigates between media.
 * - Double tap seeks backward or forward for video playback.
 * - Press-and-hold (long press) temporarily fast-forwards the video at
 *   [longPressSpeed]; releasing restores the previous speed.
 */
class MediaGestureListener(
    context: Context,
    private val onNavigate: (forward: Boolean) -> Unit,
) : View.OnTouchListener {
    private val edgePercent = 0.2f
    private var currentView: View? = null

    var edgeTapNavigationEnabled: Boolean = false
    var doubleTapSeekEnabled: Boolean = false
    var seekTimeSeconds: Int = 10
    var player: Player? = null

    /** Whether press-and-hold temporarily fast-forwards the video. */
    var longPressSpeedEnabled: Boolean = false
    /** Speed applied while the video is long-pressed (e.g. 2.0). */
    var longPressSpeed: Float = 2.0f

    private val handler = Handler(Looper.getMainLooper())
    private var isFastForwarding = false
    private var speedBeforeFast = 1f

    private val engageFastSpeed = Runnable {
        val p = player ?: return@Runnable
        if (!longPressSpeedEnabled) return@Runnable
        speedBeforeFast = p.playbackParameters.speed
        p.playbackParameters = PlaybackParameters(longPressSpeed, 1.0f)
        isFastForwarding = true
        Toast.makeText(context, "${formatSpeed(longPressSpeed)}x", Toast.LENGTH_SHORT).show()
    }

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!edgeTapNavigationEnabled) {
                    return false
                }

                val view = currentView ?: return false
                val viewWidth = view.width
                if (viewWidth <= 0) {
                    return false
                }

                val leftEdgeThreshold = viewWidth * edgePercent
                val rightEdgeThreshold = viewWidth * (1 - edgePercent)

                return when {
                    e.x < leftEdgeThreshold -> {
                        onNavigate(false)
                        true
                    }

                    e.x > rightEdgeThreshold -> {
                        onNavigate(true)
                        true
                    }

                    else -> false
                }
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!doubleTapSeekEnabled) {
                    return false
                }

                val player = player ?: return false
                val view = currentView ?: return false

                if (view.width <= 0 || player.duration <= 0) {
                    return false
                }

                val seekTimeMs = seekTimeSeconds * 1000L
                val isLeftSide = e.x < view.width / 2f
                val currentPosition = player.currentPosition
                val duration = player.duration

                val newPosition = if (isLeftSide) {
                    (currentPosition - seekTimeMs).coerceAtLeast(0)
                } else {
                    (currentPosition + seekTimeMs).coerceAtMost(duration)
                }

                player.seekTo(newPosition)
                return true
            }
        }
    )

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        currentView = v
        if (v.width <= 0 || v.height <= 0) {
            return false
        }

        val handled = gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Schedule fast-forward if the press is held past the long-press
                // timeout. A quick tap/double-tap will fire ACTION_UP first and
                // cancel this before it runs.
                if (longPressSpeedEnabled && player != null) {
                    handler.postDelayed(engageFastSpeed, LONG_PRESS_SPEED_DELAY_MS)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(engageFastSpeed)
                if (isFastForwarding) {
                    player?.playbackParameters = PlaybackParameters(speedBeforeFast, 1.0f)
                    isFastForwarding = false
                    // Consume the up so a long-press doesn't also toggle fullscreen.
                    return true
                }
            }
        }

        // Do not consume ACTION_DOWN so regular pressed/click behavior still works.
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> false
            else -> handled
        }
    }

    companion object {
        private const val LONG_PRESS_SPEED_DELAY_MS = 400L
    }
}
