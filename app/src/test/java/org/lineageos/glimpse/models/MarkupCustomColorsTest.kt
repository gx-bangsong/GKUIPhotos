/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkupCustomColorsTest {
    private class Backing {
        var encoded: String? = null
        fun open() = MarkupColorStore({ encoded }, { encoded = it })
    }

    @Test fun mostRecentFirstAndEvictsOldest() {
        val store = Backing().open()
        (1..9).forEach(store::add)
        assertEquals(listOf(9, 8, 7, 6, 5, 4, 3, 2), store.get())
    }

    @Test fun exactArgbDuplicateMovesWithoutGrowing() {
        val opaque = 0xff123456.toInt()
        val translucent = 0x0d123456
        val store = Backing().open()
        store.add(0xffabcdef.toInt())
        store.add(translucent)
        store.add(opaque)
        store.add(translucent)
        assertEquals(listOf(translucent, opaque, 0xffabcdef.toInt()), store.get())
    }

    @Test fun listAndArgbSurviveStoreRecreationAndRemoval() {
        val backing = Backing()
        backing.open().apply { add(0xff123456.toInt()); add(0x0d123456) }
        val reopened = backing.open()
        assertEquals(listOf(0x0d123456, 0xff123456.toInt()), reopened.get())
        reopened.remove(0x0d123456)
        assertEquals(listOf(0xff123456.toInt()), backing.open().get())
    }

    @Test fun applyingToTextClampsAlphaWithoutChangingRgb() {
        val original = 0x0d123456
        assertEquals(0x1a123456, MarkupCustomColors.forText(original))
        assertEquals(0x0d123456, original)
        assertEquals(0x1a123456, MarkupCustomColors.forText(0x1a123456))
        assertEquals(0xff123456.toInt(), MarkupCustomColors.forText(0xff123456.toInt()))
    }
}
