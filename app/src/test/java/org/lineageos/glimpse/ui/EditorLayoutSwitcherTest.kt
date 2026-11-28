/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.Guideline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.lineageos.glimpse.R
import org.lineageos.glimpse.ui.LargeScreenLayout.EditorLayoutMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorLayoutSwitcherTest {

    private val width = 1600
    private val height = 900
    private val deckChildHeight = 200

    private lateinit var root: ConstraintLayout
    private lateinit var crop: View
    private lateinit var overlay: View
    private lateinit var deck: LinearLayout
    private lateinit var guide: Guideline

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        root = ConstraintLayout(context)

        crop = View(context).apply { id = R.id.cropImageView }
        overlay = View(context).apply { id = R.id.annotationOverlayView }
        deck = LinearLayout(context).apply {
            id = R.id.editorBottomDeck
            orientation = LinearLayout.VERTICAL
            addView(
                View(context),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    deckChildHeight,
                ),
            )
        }
        guide = Guideline(context).apply {
            id = R.id.editorSplitGuide
            layoutParams = ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ).apply { orientation = ConstraintLayout.LayoutParams.VERTICAL }
        }

        // Initial single-column constraints, as in activity_image_editor.xml.
        root.addView(
            crop,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintSet.PARENT_ID
                endToEnd = ConstraintSet.PARENT_ID
                topToTop = ConstraintSet.PARENT_ID
                bottomToTop = R.id.editorBottomDeck
            },
        )
        root.addView(
            overlay,
            ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = ConstraintSet.PARENT_ID
                endToEnd = ConstraintSet.PARENT_ID
                topToTop = ConstraintSet.PARENT_ID
                bottomToTop = R.id.editorBottomDeck
            },
        )
        root.addView(
            deck,
            ConstraintLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomToBottom = ConstraintSet.PARENT_ID
            },
        )
        root.addView(guide, guide.layoutParams)
    }

    private fun layout(w: Int = width, h: Int = height) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
    }

    @Test
    fun shortLandscapePanesKeepFullHeightCanvas() {
        // Ruling ⑥ round 2: at the 800dp gate (800x360) and at the 7T Pro
        // natural pane (832x384) the split keeps the canvas at the full
        // window height — the visible-height floor the real-device check
        // asserts (image fully visible, not ~90dp above the tools deck).
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)

        layout(800, 360)
        assertEquals(0, crop.top)
        assertEquals(360, crop.height)
        assertEquals(360, crop.bottom)
        assertEquals(600, crop.width)
        assertEquals(crop.right, deck.left)
        assertEquals(200, deck.width)
        assertEquals(360, deck.height)
        assertEquals(360 - deckChildHeight, deck.getChildAt(0).top)

        layout(832, 384)
        assertEquals(0, crop.top)
        assertEquals(384, crop.height)
        assertEquals(384, crop.bottom)
        assertEquals(624, crop.width)
        assertEquals(crop.right, deck.left)
        assertEquals(208, deck.width)
        assertEquals(384, deck.height)
        assertEquals(384 - deckChildHeight, deck.getChildAt(0).top)
    }

    @Test
    fun twoColumnModeSplitsCanvasAndToolsAtTheGuide() {
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)
        layout()

        // Canvas occupies the left 75%, tools the right 25%, both full height.
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(height, crop.bottom)
        assertEquals(width, deck.right)
        assertEquals(0, deck.top)
        assertEquals(height, deck.bottom)
        assertEquals(crop.right, deck.left)
        assertEquals(1200, crop.width)
        assertEquals(400, deck.width)
        assertEquals(1200, overlay.right)
        assertEquals(height, overlay.height)

        // Tools column stacks its children at the bottom.
        assertEquals(
            Gravity.BOTTOM,
            deck.gravity and Gravity.VERTICAL_GRAVITY_MASK,
        )
        assertEquals(height - deckChildHeight, deck.getChildAt(0).top)
    }

    @Test
    fun singleColumnModeRestoresFullWidthCanvasOverWrapContentDeck() {
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)
        layout()
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.SINGLE_COLUMN)
        layout()

        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(width, crop.width)
        assertEquals(deck.top, crop.bottom)
        assertEquals(height - deckChildHeight, crop.height)
        assertEquals(width, deck.width)
        assertEquals(deckChildHeight, deck.height)
        assertEquals(height, deck.bottom)
        assertEquals(0, deck.getChildAt(0).top)
        assertEquals(
            Gravity.TOP,
            deck.gravity and Gravity.VERTICAL_GRAVITY_MASK,
        )
    }

    @Test
    fun switchingModesKeepsViewInstancesAndMarkupState() {
        crop.tag = "crop-state"
        val cropInstance = crop
        val overlayInstance = overlay
        val deckInstance = deck

        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)
        layout()
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.SINGLE_COLUMN)
        layout()
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)
        layout()
        // Re-applying the current mode must not disturb anything either.
        EditorLayoutSwitcher.apply(root, EditorLayoutMode.TWO_COLUMN)
        layout()

        assertSame(cropInstance, root.findViewById(R.id.cropImageView))
        assertSame(overlayInstance, root.findViewById(R.id.annotationOverlayView))
        assertSame(deckInstance, root.findViewById(R.id.editorBottomDeck))
        assertEquals("crop-state", crop.tag)
        assertEquals(4, root.childCount)
        assertSame(root, deck.parent)
        // Still split correctly after the round trip.
        assertEquals(1200, crop.width)
        assertEquals(400, deck.width)
        assertEquals(height - deckChildHeight, deck.getChildAt(0).top)
    }
}
