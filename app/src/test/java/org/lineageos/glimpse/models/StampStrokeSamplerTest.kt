/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StampStrokeSamplerTest {

    @Test
    fun testStartAddsInitialPoint() {
        val sampler = StampStrokeSampler(step = 20f)
        val initial = sampler.start(100f, 200f)

        assertEquals(1, initial.size)
        assertEquals(100f, initial[0].x, 0.001f)
        assertEquals(200f, initial[0].y, 0.001f)
        assertEquals(0f, initial[0].angle, 0.001f)
    }

    @Test
    fun testSubStepMovementAccumulatesWithoutEmitting() {
        val sampler = StampStrokeSampler(step = 50f)
        sampler.start(0f, 0f)

        val stamps1 = sampler.addPoint(20f, 0f)
        assertTrue(stamps1.isEmpty())

        val stamps2 = sampler.addPoint(40f, 0f)
        assertTrue(stamps2.isEmpty())

        // Total distance is now 60, should emit 1 stamp at x=50
        val stamps3 = sampler.addPoint(60f, 0f)
        assertEquals(1, stamps3.size)
        assertEquals(50f, stamps3[0].x, 0.001f)
        assertEquals(0f, stamps3[0].y, 0.001f)
        assertEquals(0f, stamps3[0].angle, 0.001f)
    }

    @Test
    fun testMultiStepMovementInSingleSegment() {
        val sampler = StampStrokeSampler(step = 25f)
        sampler.start(0f, 0f)

        // Move 100px to the right -> should emit at 25, 50, 75, 100
        val stamps = sampler.addPoint(100f, 0f)
        assertEquals(4, stamps.size)
        assertEquals(25f, stamps[0].x, 0.001f)
        assertEquals(50f, stamps[1].x, 0.001f)
        assertEquals(75f, stamps[2].x, 0.001f)
        assertEquals(100f, stamps[3].x, 0.001f)
        stamps.forEach { stamp ->
            assertEquals(0f, stamp.angle, 0.001f)
        }
    }

    @Test
    fun testTangentAnglesForCardinalDirections() {
        val sampler = StampStrokeSampler(step = 30f, followDirection = true)

        // Moving down (dy > 0) -> angle 90 degrees
        sampler.start(0f, 0f)
        val downStamps = sampler.addPoint(0f, 60f)
        assertEquals(2, downStamps.size)
        assertEquals(90f, downStamps[0].angle, 0.01f)

        // Moving left (dx < 0) -> angle 180 degrees
        sampler.start(100f, 0f)
        val leftStamps = sampler.addPoint(40f, 0f)
        assertEquals(2, leftStamps.size)
        assertEquals(180f, leftStamps[0].angle, 0.01f)

        // Moving up (dy < 0) -> angle -90 degrees
        sampler.start(0f, 100f)
        val upStamps = sampler.addPoint(0f, 40f)
        assertEquals(2, upStamps.size)
        assertEquals(-90f, upStamps[0].angle, 0.01f)
    }

    @Test
    fun testRemainderCarryOverAcrossSegments() {
        val sampler = StampStrokeSampler(step = 10f)
        sampler.start(0f, 0f)

        // Segment 1: move 15px right -> emits at 10px, remainder 5px
        val s1 = sampler.addPoint(15f, 0f)
        assertEquals(1, s1.size)
        assertEquals(10f, s1[0].x, 0.001f)

        // Segment 2: move 5px right -> total segment distance 5 + 5 remainder = 10px -> emits at 20px
        val s2 = sampler.addPoint(20f, 0f)
        assertEquals(1, s2.size)
        assertEquals(20f, s2[0].x, 0.001f)
    }

    @Test
    fun testSafetyCapAtMaxStamps() {
        val sampler = StampStrokeSampler(step = 1f)
        val initial = sampler.start(0f, 0f)
        assertEquals(1, initial.size)

        // Attempt to create 5000 stamps in one segment
        val stamps = sampler.addPoint(5000f, 0f)
        val totalStamps = initial.size + stamps.size
        assertEquals(StampStrokeSampler.MAX_STAMPS_PER_STROKE, totalStamps)
    }

    @Test
    fun testCustomBrushSerializationRoundTrip() {
        val brushes = listOf(
            CustomBrush(
                id = "brush_01",
                name = "Calligraphy",
                tipFile = "/path/tip1.png",
                colorMode = BrushColorMode.ORIGINAL,
                spacingPercent = 35,
                opacity = 90,
                followDirection = true,
                sizeDp = 28f,
            ),
            CustomBrush(
                id = "brush_02",
                name = "Pencil Tip",
                tipFile = "/path/tip2.png",
                colorMode = BrushColorMode.TINT,
                spacingPercent = 15,
                opacity = 100,
                followDirection = false,
                sizeDp = 12f,
            ),
        )

        val serialized = CustomBrush.serializeList(brushes)
        val deserialized = CustomBrush.deserializeList(serialized)

        assertEquals(2, deserialized.size)
        assertEquals(brushes[0].id, deserialized[0].id)
        assertEquals(brushes[0].name, deserialized[0].name)
        assertEquals(brushes[0].tipFile, deserialized[0].tipFile)
        assertEquals(brushes[0].colorMode, deserialized[0].colorMode)
        assertEquals(brushes[0].spacingPercent, deserialized[0].spacingPercent)
        assertEquals(brushes[0].opacity, deserialized[0].opacity)
        assertEquals(brushes[0].followDirection, deserialized[0].followDirection)
        assertEquals(brushes[0].sizeDp, deserialized[0].sizeDp, 0.001f)

        assertEquals(brushes[1].id, deserialized[1].id)
        assertEquals(brushes[1].name, deserialized[1].name)
        assertEquals(brushes[1].tipFile, deserialized[1].tipFile)
        assertEquals(brushes[1].colorMode, deserialized[1].colorMode)
        assertEquals(brushes[1].spacingPercent, deserialized[1].spacingPercent)
        assertEquals(brushes[1].opacity, deserialized[1].opacity)
        assertEquals(brushes[1].followDirection, deserialized[1].followDirection)
        assertEquals(brushes[1].sizeDp, deserialized[1].sizeDp, 0.001f)
    }

    @Test
    fun testCustomBrushLegacyDeserialization() {
        // Test parsing an old line with fewer fields (backwards compatibility)
        val legacyLine = "brush_legacy\tLegacy Name\t/legacy/path.png\t30\t18.0\t80\ttrue\tTINT"
        val parsed = CustomBrush.deserializeList(legacyLine)

        assertEquals(1, parsed.size)
        assertEquals("brush_legacy", parsed[0].id)
        assertEquals("Legacy Name", parsed[0].name)
        assertEquals("/legacy/path.png", parsed[0].tipFile)
        assertEquals(30, parsed[0].spacingPercent)
        assertEquals(18f, parsed[0].sizeDp, 0.001f)
        assertEquals(80, parsed[0].opacity)
        assertTrue(parsed[0].followDirection)
        assertEquals(BrushColorMode.TINT, parsed[0].colorMode)
    }
}
