/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.Gravity
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import org.lineageos.glimpse.R

/**
 * Applies the single- or two-column editor split to an inflated
 * `activity_image_editor.xml` (ruling ⑥).
 *
 * Both layout variants are view-tree identical; only the constraints of
 * `cropImageView`, `annotationOverlayView` and `editorBottomDeck` differ.
 * The delta is expressed as a [ConstraintSet] so the mode can be switched
 * at runtime (resize, rotation, fold) without re-inflating, which keeps
 * markup content, undo history, selection and edit parameters intact.
 *
 * Two-column mode pins the canvas and overlay to the left ~75% of the
 * window (right edge at the 0.75 `editorSplitGuide`) and the bottom deck to
 * the right ~30%, full height. Single-column mode restores the original
 * full-width canvas sitting on top of a wrap-content bottom deck.
 *
 * Pure view-tree manipulation: call with a root measured against the
 * current window size; the caller is expected to guard against redundant
 * applications of the same mode.
 */
object EditorLayoutSwitcher {
    /** Canvas/tools split point as a fraction of the window width. */
    private const val GUIDE_PERCENT = 0.75f

    fun apply(root: ConstraintLayout, mode: LargeScreenLayout.EditorLayoutMode) {
        val crop = R.id.cropImageView
        val overlay = R.id.annotationOverlayView
        val deck = R.id.editorBottomDeck
        val guide = R.id.editorSplitGuide

        val set = ConstraintSet()
        set.clone(root)

        when (mode) {
            LargeScreenLayout.EditorLayoutMode.TWO_COLUMN -> {
                set.setGuidelinePercent(guide, GUIDE_PERCENT)
                for (id in intArrayOf(crop, overlay)) {
                    set.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    set.connect(id, ConstraintSet.END, guide, ConstraintSet.END)
                    set.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    set.connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                    set.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
                    set.constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
                }
                set.connect(deck, ConstraintSet.START, guide, ConstraintSet.START)
                set.connect(deck, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(deck, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                set.connect(deck, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(deck, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(deck, ConstraintSet.MATCH_CONSTRAINT)
            }
            LargeScreenLayout.EditorLayoutMode.SINGLE_COLUMN -> {
                for (id in intArrayOf(crop, overlay)) {
                    set.connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                    set.connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                    set.connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                    set.connect(id, ConstraintSet.BOTTOM, deck, ConstraintSet.TOP)
                    set.constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
                    set.constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
                }
                // The deck drops its top edge and goes back to wrap content;
                // clear() zeroes the top margin left over from full height.
                set.clear(deck, ConstraintSet.TOP)
                set.connect(deck, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                set.connect(deck, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                set.connect(deck, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                set.constrainWidth(deck, ConstraintSet.MATCH_CONSTRAINT)
                set.constrainHeight(deck, ConstraintSet.WRAP_CONTENT)
            }
        }
        set.applyTo(root)

        // Wrap content makes TOP a no-op in single-column mode; full height
        // stacks the deck's children at the bottom of the tools column.
        root.findViewById<LinearLayout>(deck).gravity = when (mode) {
            LargeScreenLayout.EditorLayoutMode.TWO_COLUMN -> Gravity.BOTTOM
            LargeScreenLayout.EditorLayoutMode.SINGLE_COLUMN -> Gravity.TOP
        }
    }
}
