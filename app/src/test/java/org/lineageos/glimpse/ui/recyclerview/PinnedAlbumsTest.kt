/* Tests stable partitioning and stale-key handling for album pins.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.recyclerview

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedAlbumsTest {
    @Test fun preservesOrderAndDoesNotDuplicate() {
        val (pinned, remaining) = partitionPinned(listOf("a", "b", "c", "d"), setOf("d", "b", "gone")) { it }
        assertEquals(listOf("b", "d"), pinned)
        assertEquals(listOf("a", "c"), remaining)
    }

    @Test fun filteredAndEmptyInputsDoNotInventCards() {
        assertEquals(listOf("b") to emptyList<String>(), partitionPinned(listOf("b"), setOf("a", "b")) { it })
        assertEquals(emptyList<String>() to emptyList<String>(), partitionPinned(emptyList<String>(), setOf("a")) { it })
    }
}
