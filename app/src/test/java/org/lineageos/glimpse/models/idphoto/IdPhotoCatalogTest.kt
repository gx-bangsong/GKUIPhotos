/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models.idphoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quality-gate unit test for the 32-preset ID photo catalog.
 * Validates:
 * 1. Exactly 32 presets are registered;
 * 2. Hardcoded expected targetWidthPx & targetHeightPx match reviewed specification table;
 * 3. Exact matching for the 6 same-ratio groups (5:7, 11:16, 7:9, 1:1, 13:16, 3:4).
 */
class IdPhotoCatalogTest {

    @Test
    fun catalog_containsExactly32Presets() {
        assertEquals(32, IdPhotoCatalog.presets.size)
    }

    @Test
    fun catalog_effectivePixelsMatchReviewedTableValues() {
        // Hardcoded expected values for all 32 presets (never re-derived)
        val expectedPixels = mapOf(
            // 一、常用尺寸
            "standard_1_inch" to (295 to 413),
            "standard_2_inch" to (413 to 579),
            "small_1_inch" to (260 to 378),
            "large_1_inch" to (390 to 567),
            "small_2_inch" to (413 to 531),
            "large_2_inch" to (413 to 626),
            "three_inch" to (650 to 992),
            "five_inch_3r" to (1051 to 1500),
            "six_inch_4r" to (1200 to 1800),
            // 二、官方证件
            "id_card_cn" to (358 to 441),
            "driver_license_paper" to (260 to 378),
            "driver_license_electronic" to (520 to 756),
            "passport_cn" to (390 to 567),
            "social_security_card" to (358 to 441),
            "marriage_cert" to (626 to 413),
            // 三、国际签证
            "visa_schengen" to (413 to 531),
            "visa_us" to (600 to 600),
            "visa_japan_standard" to (413 to 531),
            "visa_japan_square" to (531 to 531),
            "visa_uk" to (413 to 531),
            "visa_canada_online" to (413 to 531),
            "passport_canada_paper" to (591 to 827),
            "visa_australia" to (413 to 531),
            "visa_singapore" to (413 to 531),
            "visa_korea" to (413 to 531),
            "visa_thailand" to (472 to 709),
            // 四、各类考试
            "exam_cet" to (390 to 567),
            "exam_ntce" to (480 to 640),
            "exam_ncre" to (390 to 567),
            "exam_civil_service" to (413 to 531),
            "exam_cpa" to (178 to 220),
            "exam_postgraduate" to (480 to 640),
        )

        assertEquals(32, expectedPixels.size)

        for ((id, expected) in expectedPixels) {
            val preset = IdPhotoCatalog.getById(id)
            assertNotNull("Preset not found for id: $id", preset)
            assertEquals("Width mismatch for $id", expected.first, preset!!.targetWidthPx)
            assertEquals("Height mismatch for $id", expected.second, preset.targetHeightPx)
        }
    }

    @Test
    fun catalog_dpiValuesMatchSpecification() {
        val presetIdCard = IdPhotoCatalog.getById("id_card_cn")
        assertNotNull(presetIdCard)
        assertEquals(350, presetIdCard!!.dpi)

        val presetElecDriver = IdPhotoCatalog.getById("driver_license_electronic")
        assertNotNull(presetElecDriver)
        assertEquals(600, presetElecDriver!!.dpi)

        val presetSocial = IdPhotoCatalog.getById("social_security_card")
        assertNotNull(presetSocial)
        assertEquals(350, presetSocial!!.dpi)

        val presetUsVisa = IdPhotoCatalog.getById("visa_us")
        assertNotNull(presetUsVisa)
        assertEquals(300, presetUsVisa!!.dpi)
    }

    @Test
    fun catalog_sameRatioGroupsMatchPlan() {
        // Group 1: 5:7 比例组
        val group5_7 = setOf("standard_1_inch", "standard_2_inch", "passport_canada_paper")
        val preset1Inch = IdPhotoCatalog.getById("standard_1_inch")!!
        val resolved5_7 = IdPhotoCatalog.getSameRatioPresets(preset1Inch).map { it.id }.toSet()
        assertEquals(group5_7, resolved5_7)

        // Group 2: 11:16 比例组
        val group11_16 = setOf(
            "small_1_inch", "large_1_inch", "driver_license_paper",
            "driver_license_electronic", "passport_cn", "exam_cet", "exam_ncre"
        )
        val presetLarge1Inch = IdPhotoCatalog.getById("large_1_inch")!!
        val resolved11_16 = IdPhotoCatalog.getSameRatioPresets(presetLarge1Inch).map { it.id }.toSet()
        assertEquals(group11_16, resolved11_16)

        // Group 3: 7:9 比例组
        val group7_9 = setOf(
            "small_2_inch", "visa_schengen", "visa_japan_standard",
            "visa_uk", "visa_canada_online", "visa_australia",
            "visa_singapore", "visa_korea", "exam_civil_service"
        )
        val presetSmall2Inch = IdPhotoCatalog.getById("small_2_inch")!!
        val resolved7_9 = IdPhotoCatalog.getSameRatioPresets(presetSmall2Inch).map { it.id }.toSet()
        assertEquals(group7_9, resolved7_9)

        // Group 4: 1:1 比例组
        val group1_1 = setOf("visa_us", "visa_japan_square")
        val presetUsVisa = IdPhotoCatalog.getById("visa_us")!!
        val resolved1_1 = IdPhotoCatalog.getSameRatioPresets(presetUsVisa).map { it.id }.toSet()
        assertEquals(group1_1, resolved1_1)

        // Group 5: 13:16 比例组
        val group13_16 = setOf("id_card_cn", "social_security_card")
        val presetIdCard = IdPhotoCatalog.getById("id_card_cn")!!
        val resolved13_16 = IdPhotoCatalog.getSameRatioPresets(presetIdCard).map { it.id }.toSet()
        assertEquals(group13_16, resolved13_16)

        // Group 6: 3:4 比例组
        val group3_4 = setOf("exam_ntce", "exam_postgraduate")
        val presetNtce = IdPhotoCatalog.getById("exam_ntce")!!
        val resolved3_4 = IdPhotoCatalog.getSameRatioPresets(presetNtce).map { it.id }.toSet()
        assertEquals(group3_4, resolved3_4)
    }
}
