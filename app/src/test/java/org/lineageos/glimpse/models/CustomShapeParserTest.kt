/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomShapeParserTest {

    @Test
    fun testParseSimpleSvgPath() {
        val svg = """
            <svg viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">
                <title>Test Triangle</title>
                <path d="M 10 10 L 90 10 L 50 90 Z" fill="red" />
            </svg>
        """.trimIndent()

        val result = CustomShapeParser.parse(svg, "Default")
        assertTrue("Expected Success but got $result", result is CustomShapeParseResult.Success)

        val success = result as CustomShapeParseResult.Success
        assertEquals("Test Triangle", success.name)
        assertTrue(success.commandCount >= 4)
        assertTrue(success.normalizedPathData.startsWith("M"))
        assertTrue(success.normalizedPathData.endsWith("Z"))

        // Original bounds: [10..90] x [10..90], square 80x80 -> normalized bounds should be [0..1] x [0..1]
        val (minX, minY, maxX, maxY) = computeNormalizedBounds(success.normalizedPathData)
        assertEquals(0f, minX, 0.01f)
        assertEquals(1f, maxX, 0.01f)
        assertEquals(0f, minY, 0.01f)
        assertEquals(1f, maxY, 0.01f)
    }

    @Test
    fun testParseRawPathDataString() {
        val pathData = "M 0 0 L 200 0 L 200 100 L 0 100 Z"
        val result = CustomShapeParser.parse(pathData, "Rectangle")

        assertTrue(result is CustomShapeParseResult.Success)
        val success = result as CustomShapeParseResult.Success
        assertEquals("Rectangle", success.name)

        // Width 200, height 100 -> aspect ratio 2:1
        // Width spans [0..1], height spans [0.25..0.75] centered
        val (minX, minY, maxX, maxY) = computeNormalizedBounds(success.normalizedPathData)
        assertEquals(0f, minX, 0.01f)
        assertEquals(1f, maxX, 0.01f)
        assertEquals(0.25f, minY, 0.01f)
        assertEquals(0.75f, maxY, 0.01f)
    }

    @Test
    fun testParseJsonPolygonFormat() {
        val json = """
            {
                "name": "Octagon",
                "path": "M 30 0 L 70 0 L 100 30 L 100 70 L 70 100 L 30 100 L 0 70 L 0 30 Z"
            }
        """.trimIndent()

        val result = CustomShapeParser.parse(json, "Fallback")
        assertTrue(result is CustomShapeParseResult.Success)
        val success = result as CustomShapeParseResult.Success
        assertEquals("Octagon", success.name)
        assertTrue(success.commandCount >= 9)
    }

    @Test
    fun testParseJsonCoordinatesArray() {
        val json = """
            {
                "name": "Diamond Array",
                "coordinates": [
                    [50, 0],
                    [100, 50],
                    [50, 100],
                    [0, 50]
                ]
            }
        """.trimIndent()

        val result = CustomShapeParser.parse(json, "Fallback")
        assertTrue(result is CustomShapeParseResult.Success)
        val success = result as CustomShapeParseResult.Success
        assertEquals("Diamond Array", success.name)
        assertTrue(success.normalizedPathData.startsWith("M"))
        assertTrue(success.normalizedPathData.endsWith("Z"))
    }

    @Test
    fun testUnsupportedCommandFailsGracefully() {
        // Arc command 'A' / 'a' is not supported
        val arcPath = "M 10 10 A 25 25 0 0 0 40 40 Z"
        val result = CustomShapeParser.parse(arcPath)

        assertTrue(result is CustomShapeParseResult.Error)
        val error = result as CustomShapeParseResult.Error
        assertTrue(error.message.contains("A") || error.message.contains("不支持"))
    }

    @Test
    fun testOversizedInputFails() {
        val hugePadding = " ".repeat(260 * 1024)
        val hugeInput = "<svg><path d=\"M 0 0 L 10 10 Z\"/></svg>$hugePadding"
        val result = CustomShapeParser.parse(hugeInput)

        assertTrue(result is CustomShapeParseResult.Error)
        val error = result as CustomShapeParseResult.Error
        assertTrue(error.message.contains("256"))
    }

    @Test
    fun testDegenerateZeroDimensionShapeFails() {
        val pointPath = "M 10 10 L 10 10 Z"
        val result = CustomShapeParser.parse(pointPath)

        assertTrue(result is CustomShapeParseResult.Error)
        val error = result as CustomShapeParseResult.Error
        assertTrue(error.message.contains("0") || error.message.contains("退化"))
    }

    @Test
    fun testCustomShapeSerializationRoundTrip() {
        val shapes = listOf(
            CustomShape("shape_1", "Arrow Up", "M 0.5 0 L 1 1 L 0 1 Z"),
            CustomShape("shape_2", "Banner", "M 0 0 L 1 0 L 0.8 0.5 L 1 1 L 0 1 Z"),
        )

        val serialized = CustomShape.serializeList(shapes)
        val deserialized = CustomShape.deserializeList(serialized)

        assertEquals(2, deserialized.size)
        assertEquals("shape_1", deserialized[0].id)
        assertEquals("Arrow Up", deserialized[0].name)
        assertEquals("M 0.5 0 L 1 1 L 0 1 Z", deserialized[0].pathData)

        assertEquals("shape_2", deserialized[1].id)
        assertEquals("Banner", deserialized[1].name)
        assertEquals("M 0 0 L 1 0 L 0.8 0.5 L 1 1 L 0 1 Z", deserialized[1].pathData)
    }

    private fun computeNormalizedBounds(pathData: String): FloatArray {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        val tokens = pathData.split(Regex("(?=[MmLlHhVvCcQqZz])|\\s+")).filter { it.isNotBlank() }
        var i = 0
        while (i < tokens.size) {
            val cmd = tokens[i]
            when (cmd) {
                "M", "L" -> {
                    val x = tokens[i + 1].toFloat()
                    val y = tokens[i + 2].toFloat()
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    minY = minOf(minY, y)
                    maxY = maxOf(maxY, y)
                    i += 3
                }
                "Z", "z" -> i += 1
                else -> i += 1
            }
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
