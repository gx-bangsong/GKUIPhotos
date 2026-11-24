/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Purpose: pins the video-to-GIF sampling plan (frame count, sample times, delays, failure
 * handling) and that its delays survive GifCodec's centisecond rounding with the clip's length.
 */
class VideoGifPlanTest {

    private fun seconds(s: Int) = s * 1_000_000L

    @Test
    fun framesAreCappedAtThirtySixAndNeverBelowTwoFps() {
        assertEquals(30, VideoGifPlan.frameCount(3, 10))
        assertEquals(36, VideoGifPlan.frameCount(10, 15)) // 3.6 fps
        assertEquals(36, VideoGifPlan.frameCount(5, 8))
        assertEquals(36, VideoGifPlan.frameCount(18, 5)) // 2 fps
        assertEquals(18, VideoGifPlan.maxSeconds())
        assertEquals(2.0, VideoGifPlan.frameCount(VideoGifPlan.maxSeconds(), 15) / VideoGifPlan.maxSeconds().toDouble(), 0.0)
        assertEquals(1, VideoGifPlan.frameCount(1_000L, 5))
    }

    @Test
    fun samplesAreUniformAndStartAtZero() {
        val times = VideoGifPlan.sampleTimesUs(seconds(10), 15)
        assertEquals(36, times.size)
        assertEquals(0L, times.first())
        times.forEachIndexed { i, t -> assertEquals(i * seconds(10) / 36, t) }
        assertEquals(9_722_222L, times.last())
    }

    @Test
    fun delaysCoverTheWholeClipAndSurviveCentisecondRounding() {
        // seconds, fps, expected frames
        listOf(Triple(3, 10, 30), Triple(4, 8, 32), Triple(10, 15, 36), Triple(5, 8, 36),
            Triple(18, 5, 36), Triple(2, 10, 20), Triple(1, 5, 5)).forEach { (s, fps, n) ->
            val times = VideoGifPlan.sampleTimesUs(seconds(s), fps)
            val ms = VideoGifPlan.delaysMs(times, seconds(s))
            assertEquals("$s s @ $fps", n, ms.size)
            assertEquals("$s s @ $fps total ms", s * 1000, ms.sum())
            val cs = GifCodec.centisecondDelays(ms).toList()
            val intervalCs = s * 100.0 / n
            assertEquals("$s s @ $fps total cs", s * 100, cs.sum())
            assertTrue("$s s @ $fps raw $cs vs $intervalCs", cs.all { abs(it - intervalCs) <= 1.0 })
        }
    }

    @Test
    fun aFailedFrameMergesIntoThePreviousOne() {
        // Planned 0/100/200/300 ms in a 400 ms clip; the 200 ms frame failed.
        assertEquals(listOf(100, 200, 100), VideoGifPlan.delaysMs(listOf(0L, 100_000L, 300_000L), 400_000L))
        // The first frame failed: the first extracted frame starts the clip.
        assertEquals(listOf(200, 100), VideoGifPlan.delaysMs(listOf(100_000L, 200_000L), 300_000L))
        // The last frame failed: the frame before lasts until the end.
        assertEquals(listOf(100, 300), VideoGifPlan.delaysMs(listOf(0L, 100_000L), 400_000L))
    }

    @Test
    fun exactlyHalfFailedPassesAndOneMoreIsAnError() {
        // Threshold: an error only when more than half of the planned frames failed.
        assertFalse(VideoGifPlan.tooManyFailures(18, 36)) // exactly half
        assertTrue(VideoGifPlan.tooManyFailures(19, 36)) // one more
        assertFalse(VideoGifPlan.tooManyFailures(15, 30))
        assertTrue(VideoGifPlan.tooManyFailures(16, 30))
        // Odd plans have no exact half: floor(N / 2) passes, one more fails.
        assertFalse(VideoGifPlan.tooManyFailures(2, 5))
        assertTrue(VideoGifPlan.tooManyFailures(3, 5))
        assertFalse(VideoGifPlan.tooManyFailures(0, 1))
        assertTrue(VideoGifPlan.tooManyFailures(1, 1))
        assertFalse(VideoGifPlan.tooManyFailures(0, 36))
    }

    @Test
    fun exactlyHalfFailedStillCoversTheWholeClip() {
        // Every second frame of 36 over 10 s failed: 18 frames remain, each twice as long.
        val planned = VideoGifPlan.sampleTimesUs(seconds(10), 15)
        val kept = planned.filterIndexed { i, _ -> i % 2 == 0 }
        assertFalse(VideoGifPlan.tooManyFailures(planned.size - kept.size, planned.size))
        val ms = VideoGifPlan.delaysMs(kept, seconds(10))
        assertEquals(18, ms.size)
        assertEquals(10_000, ms.sum())
        assertEquals(10_000, GifCodec.centisecondDelays(ms).sum() * 10)
    }
}
