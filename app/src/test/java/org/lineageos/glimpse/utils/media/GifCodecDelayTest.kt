/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Purpose: pins how GifCodec turns frame delays in milliseconds into GIF delays in 1/100 s
 * (cumulative rounding with a 2 cs floor), without encoding any bitmap.
 */
class GifCodecDelayTest {

    private fun cs(vararg delaysMs: Int) = GifCodec.centisecondDelays(delaysMs.toList()).toList()

    @Test
    fun knownSequencesMatchTheEncoderTest() {
        assertEquals(listOf(10, 7, 2, 13), cs(100, 70, 20, 125))
        assertEquals(listOf(2, 2, 2), cs(15, 15, 15))
    }

    @Test
    fun fifteenFramesAt15FpsKeepTheTotalWithinOneCentisecond() {
        // Frame starts of a 15 fps clip, rounded to whole ms: 0, 67, 133, ..., 1000.
        val startsMs = (0..15).map { (it * 1000.0 / 15).roundToInt() }
        val delaysMs = (0 until 15).map { startsMs[it + 1] - startsMs[it] }
        val out = GifCodec.centisecondDelays(delaysMs).toList()
        assertEquals(15, out.size)
        assertTrue("each frame 6..7 cs: $out", out.all { it in 6..7 })
        assertTrue("total ${out.sum()} cs vs 100 cs", abs(out.sum() - 100) <= 1)
        var start = 0
        out.forEachIndexed { i, d ->
            start += d
            assertTrue("start of frame ${i + 1} at $start cs", abs(start - (i + 1) * 100.0 / 15) <= 1.0)
        }
        // The same clip with every delay truncated or rounded to whole ms stays within 1 cs of its input.
        listOf(66, 67).forEach { ms ->
            val total = GifCodec.centisecondDelays(List(15) { ms }).sum()
            assertTrue("$ms ms x 15 -> $total cs", abs(total - 15 * ms / 10.0) <= 1.0)
        }
    }

    @Test
    fun runsOfTenMillisecondFramesUseTheTwoCentisecondFloorAndThenCatchUp() {
        assertEquals(List(8) { 2 }, cs(*IntArray(8) { 10 }))
        // 30 ms are written as 60 ms; the next frame gives the difference back, so the total is exact.
        assertEquals(listOf(2, 2, 2, 7), cs(10, 10, 10, 100))
        assertEquals(13, cs(10, 10, 10, 100).sum())
    }

    @Test
    fun editorFloorOf20msBecomesExactlyTwoCentiseconds() {
        // GifEditorActivity never passes less than 20 ms; that floor must map to 2 cs, not be raised.
        assertEquals(listOf(2, 2, 2), cs(20, 20, 20))
        assertEquals(listOf(10, 2, 10), cs(100, 20, 100))
    }

    @Test
    fun editorRoundTripKeepsRawDelays() {
        // GifCodec.decode reports raw 10/7/2 cs as 100/70/20 ms; encoding them must give 10/7/2 back.
        assertArrayEquals(intArrayOf(10, 7, 2), GifCodec.centisecondDelays(listOf(100, 70, 20)))
    }

    @Test
    fun negativeAndOversizedDelaysStayInTheFieldRange() {
        assertEquals(listOf(2, 2), cs(-50, 0))
        assertEquals(listOf(0xFFFF), cs(1_000_000))
    }
}
