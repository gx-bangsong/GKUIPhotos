/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui

import android.view.View
import android.view.ViewGroup

/**
 * Idempotent child placement for the editor deck (ruling ⑥ round 3).
 *
 * The activity handles configChanges, so rapid rotation back and forth can
 * re-enter [org.lineageos.glimpse.ImageEditorActivity.applyDeckPresentation]
 * with the previous arrangement still (or already) applied. Computing an
 * insertion index from a child that is about to be removed produced stale
 * indexes (`IndexOutOfBoundsException: index=3 count=2` when restoring the
 * crop tools row after a two-column arrangement).
 *
 * [placeChild] therefore always detaches the child from its previous parent
 * first and only then computes the insertion point, clamped to the current
 * `childCount` so every call is safe to repeat in any order.
 */
object DeckPanelOps {

    /** Move [child] to [index] inside [parent]; safe and idempotent. */
    fun placeChild(parent: ViewGroup, child: View, index: Int) {
        if (child.parent === parent && parent.indexOfChild(child) == index) return
        (child.parent as? ViewGroup)?.removeView(child)
        parent.addView(child, index.coerceIn(0, parent.childCount))
    }

    /**
     * Apply the crop strip arrangement for [twoColumn]:
     * - two-column: the free/rotate/flip tool row sits first in the strip
     *   while the 0° angle text + ruler move into [rulerAnchor] pinned under
     *   the photo display area (ruling ⑥ round 3);
     * - single-column: original order restored inside the strip
     *   (angle text, ruler, tool row) and the anchor is left empty.
     *
     * With a null [rulerAnchor] only the strip order is managed. Every path
     * goes through [placeChild], so flipping modes repeatedly (rapid
     * rotation ×4) can never throw or strand a child.
     */
    fun applyCropOrder(
        strip: ViewGroup,
        tools: View,
        angleText: View,
        ruler: View,
        twoColumn: Boolean,
        rulerAnchor: ViewGroup?,
    ) {
        if (twoColumn) {
            placeChild(strip, tools, 0)
            if (rulerAnchor != null) {
                placeChild(rulerAnchor, angleText, 0)
                placeChild(rulerAnchor, ruler, 1)
            }
        } else {
            placeChild(strip, angleText, 0)
            placeChild(strip, ruler, 1)
            placeChild(strip, tools, 2)
        }
    }
}
