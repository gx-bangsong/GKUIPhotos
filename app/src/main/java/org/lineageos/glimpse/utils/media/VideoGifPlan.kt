/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import kotlin.math.ceil

/**
 * Purpose: the video-to-GIF sampling plan, kept free of Android types so it can be unit-tested.
 *
 * A clip of duration D at the requested fps becomes N = min(maxFrames, ceil(D * fps)) frames
 * sampled at t_i = i * D / N. Each kept frame lasts until the next kept frame starts and the
 * last one until D, so the GIF always plays for the whole clip. When N is capped the effective
 * frame rate is N / D, never below [MIN_FPS] because the duration is limited to
 * maxFrames / MIN_FPS seconds.
 */
object VideoGifPlan {
    const val MAX_FRAMES = 36
    const val MIN_FPS = 2

    /** Longest clip, in seconds, that still gets [MIN_FPS] with [maxFrames] frames. */
    fun maxSeconds(maxFrames: Int = MAX_FRAMES) = maxFrames / MIN_FPS

    /** Frames for a clip of [durationUs] at [fps]: min(maxFrames, ceil(duration * fps)), at least 1. */
    fun frameCount(durationUs: Long, fps: Int, maxFrames: Int = MAX_FRAMES): Int {
        val wanted = ceil(durationUs * fps / 1_000_000.0).toLong()
        return wanted.coerceIn(1L, maxFrames.toLong()).toInt()
    }

    /** Frames for the estimate shown before converting [seconds] at [fps]. */
    fun frameCount(seconds: Int, fps: Int, maxFrames: Int = MAX_FRAMES): Int =
        frameCount(seconds * 1_000_000L, fps, maxFrames)

    /** Sample times t_i = i * durationUs / N, relative to the clip start. */
    fun sampleTimesUs(durationUs: Long, fps: Int, maxFrames: Int = MAX_FRAMES): List<Long> {
        val n = frameCount(durationUs, fps, maxFrames)
        return (0 until n).map { it * durationUs / n }
    }

    /**
     * Delays in ms for the frames that were actually extracted at [keptTimesUs] (ascending).
     * A frame lasts until the next kept frame, so a failed frame's time goes to the frame
     * before it; the first kept frame starts at 0 and the last lasts until [durationUs].
     * Times are rounded to whole ms cumulatively, so the delays add up to round(duration).
     */
    fun delaysMs(keptTimesUs: List<Long>, durationUs: Long): List<Int> {
        require(keptTimesUs.isNotEmpty()) { "No frames kept" }
        val startsMs = keptTimesUs.mapIndexed { i, t -> if (i == 0) 0L else (t + 500) / 1000 }
        val endMs = (durationUs + 500) / 1000
        return startsMs.indices.map { i ->
            val next = if (i + 1 < startsMs.size) startsMs[i + 1] else endMs
            (next - startsMs[i]).toInt()
        }
    }

    /** More than half of the planned frames failed: the result would not represent the clip. */
    fun tooManyFailures(failed: Int, planned: Int) = failed * 2 > planned
}
