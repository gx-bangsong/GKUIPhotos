/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog

class IdPhotoPrintSheetComposerTest {

    @Test
    fun testOneInchSheetLayout() {
        val layout = IdPhotoPrintSheetComposer.computeLayout(295, 413)
        assertNotNull(layout)
        assertEquals(8, layout!!.count)
        assertEquals(4, layout.cols)
        assertEquals(2, layout.rows)
        assertEquals(1800, layout.sheetWidth)
        assertEquals(1200, layout.sheetHeight)

        val presetOneInch = IdPhotoCatalog.getById("standard_1_inch")!!
        assertEquals(8, IdPhotoPrintSheetComposer.calculatePrintCount(presetOneInch))
    }

    @Test
    fun testTwoInchSheetLayout() {
        val layout = IdPhotoPrintSheetComposer.computeLayout(413, 579)
        assertNotNull(layout)
        assertEquals(4, layout!!.count)
        assertEquals(2, layout.cols)
        assertEquals(2, layout.rows)
        assertEquals(1200, layout.sheetWidth)
        assertEquals(1800, layout.sheetHeight)

        val presetTwoInch = IdPhotoCatalog.getById("standard_2_inch")!!
        assertEquals(4, IdPhotoPrintSheetComposer.calculatePrintCount(presetTwoInch))
    }

    @Test
    fun testOversizedPresetsCannotPrintSheet() {
        // 5-inch (1051x1500) and 6-inch (1200x1800) exceed sheet or fit < 2
        assertNull(IdPhotoPrintSheetComposer.computeLayout(1051, 1500))
        assertNull(IdPhotoPrintSheetComposer.computeLayout(1200, 1800))

        val presetFiveInch = IdPhotoCatalog.getById("five_inch_3r")!!
        val presetSixInch = IdPhotoCatalog.getById("six_inch_4r")!!
        assertEquals(0, IdPhotoPrintSheetComposer.calculatePrintCount(presetFiveInch))
        assertEquals(0, IdPhotoPrintSheetComposer.calculatePrintCount(presetSixInch))
    }

    @Test
    fun testGenericNupComputation() {
        // Small 1-inch (260x378)
        val presetSmallOne = IdPhotoCatalog.getById("small_1_inch")!!
        val countSmallOne = IdPhotoPrintSheetComposer.calculatePrintCount(presetSmallOne)
        org.junit.Assert.assertTrue("Small 1-inch should fit at least 8 copies", countSmallOne >= 8)

        // US Visa (600x600)
        val presetVisa = IdPhotoCatalog.getById("visa_us")!!
        val visaCount = IdPhotoPrintSheetComposer.calculatePrintCount(presetVisa)
        // In 1800x1200: (1730/630) = 2 cols, (1130/630) = 1 row => 2 copies.
        assertEquals(2, visaCount)
    }
}
