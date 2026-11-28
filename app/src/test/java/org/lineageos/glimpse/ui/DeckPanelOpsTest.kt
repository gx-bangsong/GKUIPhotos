/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])

/**
 * Regression tests for the rapid-rotation crash (ruling ⑥ round 3):
 * `applyDeckPresentation` re-entering after an uneven number of flips used a
 * stale insertion index (`IndexOutOfBoundsException: index=3 count=2`).
 */
class DeckPanelOpsTest {

    private fun buildStrip(): Triple<LinearLayout, Triple<View, View, View>, LinearLayout> {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val strip = LinearLayout(activity)
        val anchor = LinearLayout(activity)
        val angleText = View(activity)
        val ruler = View(activity)
        val tools = View(activity)
        strip.addView(angleText)
        strip.addView(ruler)
        strip.addView(tools)
        return Triple(strip, Triple(angleText, ruler, tools), anchor)
    }

    @Test
    fun rapidRotationFlipsTimesFourNeverThrowsAndKeepsOrder() {
        val (strip, views, anchor) = buildStrip()
        val (angleText, ruler, tools) = views

        repeat(4) {
            DeckPanelOps.applyCropOrder(
                strip, tools, angleText, ruler,
                true, anchor,
            )
            assertEquals(1, strip.childCount)
            assertSame(tools, strip.getChildAt(0))
            assertEquals(2, anchor.childCount)
            assertSame(angleText, anchor.getChildAt(0))
            assertSame(ruler, anchor.getChildAt(1))

            DeckPanelOps.applyCropOrder(
                strip, tools, angleText, ruler,
                false, anchor,
            )
            assertEquals(3, strip.childCount)
            assertSame(angleText, strip.getChildAt(0))
            assertSame(ruler, strip.getChildAt(1))
            assertSame(tools, strip.getChildAt(2))
            assertEquals(0, anchor.childCount)
        }
    }

    @Test
    fun repeatedSameModeIsANoOp() {
        val (strip, views, anchor) = buildStrip()
        val (angleText, ruler, tools) = views

        repeat(3) {
            DeckPanelOps.applyCropOrder(
                strip, tools, angleText, ruler,
                true, anchor,
            )
        }
        assertEquals(1, strip.childCount)
        assertEquals(2, anchor.childCount)

        repeat(3) {
            DeckPanelOps.applyCropOrder(
                strip, tools, angleText, ruler,
                false, anchor,
            )
        }
        assertEquals(3, strip.childCount)
        assertEquals(0, anchor.childCount)
    }

    @Test
    fun placeChildClampsOutOfRangeIndexesInsteadOfThrowing() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val strip = LinearLayout(activity)
        val child = View(activity)

        DeckPanelOps.placeChild(strip, child, 99)
        assertEquals(1, strip.childCount)
        assertSame(child, strip.getChildAt(0))

        // Even a stale index computed by a caller lands at the end, safely.
        DeckPanelOps.placeChild(strip, child, 99)
        assertEquals(1, strip.childCount)
    }
}
