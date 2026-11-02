/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationItemTest {

    @Test
    fun testShapeTypeEnum() {
        assertEquals(10, ShapeType.values().size)
        assertTrue(ShapeType.values().contains(ShapeType.ARROW))
        assertTrue(ShapeType.values().contains(ShapeType.DOUBLE_ARROW))
        assertTrue(ShapeType.values().contains(ShapeType.RECTANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.ROUNDED_RECTANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.ELLIPSE))
        assertTrue(ShapeType.values().contains(ShapeType.LINE))
        assertTrue(ShapeType.values().contains(ShapeType.TRIANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.STAR))
        assertTrue(ShapeType.values().contains(ShapeType.DIAMOND))
        assertTrue(ShapeType.values().contains(ShapeType.CUSTOM))
    }

    @Test
    fun testMarkupToolEnum() {
        assertEquals(6, MarkupTool.values().size)
        assertTrue(MarkupTool.values().contains(MarkupTool.DOODLE))
        assertTrue(MarkupTool.values().contains(MarkupTool.SHAPES))
        assertTrue(MarkupTool.values().contains(MarkupTool.TEXT))
        assertTrue(MarkupTool.values().contains(MarkupTool.MOSAIC))
        assertTrue(MarkupTool.values().contains(MarkupTool.STICKER))
    }

    @Test
    fun testShapeStyleEnum() {
        assertEquals(3, ShapeStyle.values().size)
        assertTrue(ShapeStyle.values().contains(ShapeStyle.OUTLINE))
        assertTrue(ShapeStyle.values().contains(ShapeStyle.SOLID))
        assertTrue(ShapeStyle.values().contains(ShapeStyle.TRANSLUCENT))
    }

    @Test
    fun testBrushTypeEnum() {
        assertEquals(5, BrushType.values().size)
        assertTrue(BrushType.values().contains(BrushType.NORMAL))
        assertTrue(BrushType.values().contains(BrushType.HIGHLIGHTER))
        assertTrue(BrushType.values().contains(BrushType.NEON))
        assertTrue(BrushType.values().contains(BrushType.DASHED))
        assertTrue(BrushType.values().contains(BrushType.CUSTOM_TEXTURE))
    }

    @Test
    fun testMosaicEffectEnum() {
        assertEquals(6, MosaicEffect.values().size)
        assertTrue(MosaicEffect.values().contains(MosaicEffect.PIXELATE))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.BLUR))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.BLACKOUT))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.STRIPES))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.NOISE))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.CUSTOM_PATTERN))
    }

    @Test
    fun testShapeMutationAndStyle() {
        val shape = AnnotationItem.Shape(
            type = ShapeType.ARROW,
            startX = 10f,
            startY = 20f,
            endX = 100f,
            endY = 200f,
            color = 0xFFFF0000.toInt(),
            strokeWidth = 8f,
            style = ShapeStyle.OUTLINE,
        )

        assertEquals(10f, shape.startX)
        assertEquals(20f, shape.startY)
        assertEquals(100f, shape.endX)
        assertEquals(200f, shape.endY)
        assertEquals(ShapeStyle.OUTLINE, shape.style)

        shape.endX = 150f
        shape.endY = 250f
        shape.style = ShapeStyle.SOLID
        assertEquals(150f, shape.endX)
        assertEquals(250f, shape.endY)
        assertEquals(ShapeStyle.SOLID, shape.style)
    }

    @Test
    fun testUndoStackSemantics() {
        val stack = mutableListOf<AnnotationItem>()
        assertTrue(stack.isEmpty())

        val shape1 = AnnotationItem.Shape(ShapeType.RECTANGLE, 0f, 0f, 10f, 10f, 0xFFFFFFFF.toInt(), 4f)
        val shape2 = AnnotationItem.Shape(ShapeType.LINE, 5f, 5f, 20f, 20f, 0xFF0000FF.toInt(), 8f)

        stack.add(shape1)
        stack.add(shape2)
        assertEquals(2, stack.size)

        val popped = stack.removeAt(stack.size - 1)
        assertEquals(shape2, popped)
        assertEquals(1, stack.size)

        stack.clear()
        assertTrue(stack.isEmpty())
    }

    @Test
    fun testTextAnnotation() {
        val textItem = AnnotationItem.Text(
            text = "Hello World",
            centerX = 100f,
            centerY = 200f,
            baseTextSize = 32f,
            scale = 1.5f,
            color = 0xFFFFFF00.toInt(),
            hasBackgroundPill = true,
        )

        assertEquals("Hello World", textItem.text)
        assertEquals(48f, textItem.effectiveTextSize, 0.01f)
        assertTrue(textItem.hasBackgroundPill)

        textItem.scale = 2.0f
        assertEquals(64f, textItem.effectiveTextSize, 0.01f)
    }

    @Test
    fun testMosaicAnnotation() {
        val mosaic = AnnotationItem.Mosaic(
            path = null,
            brushSize = 32f,
            effect = MosaicEffect.BLUR,
            cellSize = 16,
        )

        assertEquals(32f, mosaic.brushSize, 0.01f)
        assertEquals(MosaicEffect.BLUR, mosaic.effect)
        assertEquals(16, mosaic.cellSize)
        assertNull(mosaic.path)
    }

    @Test
    fun testCustomShapeAnnotation() {
        val customShape = AnnotationItem.Shape(
            type = ShapeType.CUSTOM,
            startX = 10f,
            startY = 20f,
            endX = 110f,
            endY = 120f,
            color = 0xFF00FF00.toInt(),
            strokeWidth = 6f,
            style = ShapeStyle.TRANSLUCENT,
            customShapeId = "shape_123",
            customPathData = "M 0 0 L 1 0 L 0.5 1 Z",
        )

        assertEquals(ShapeType.CUSTOM, customShape.type)
        assertEquals("shape_123", customShape.customShapeId)
        assertEquals("M 0 0 L 1 0 L 0.5 1 Z", customShape.customPathData)
        assertEquals(ShapeStyle.TRANSLUCENT, customShape.style)
    }

    @Test
    fun testStampStrokeAnnotation() {
        val pt = org.lineageos.glimpse.models.StampPoint(10f, 20f, 45f)
        val stroke = AnnotationItem.StampStroke(
            brushId = "brush_abc",
            points = listOf(pt),
            sizeInImage = 32f,
            sizeDp = 24f,
            color = 0xFFFF0000.toInt(),
            opacity = 80,
            colorMode = org.lineageos.glimpse.models.BrushColorMode.TINT,
            tipBitmap = null,
        )

        assertEquals("brush_abc", stroke.brushId)
        assertEquals(1, stroke.points.size)
        assertEquals(10f, stroke.points[0].x, 0.01f)
        assertEquals(20f, stroke.points[0].y, 0.01f)
        assertEquals(45f, stroke.points[0].angle, 0.01f)
        assertEquals(32f, stroke.sizeInImage, 0.01f)
        assertEquals(24f, stroke.sizeDp, 0.01f)
        assertEquals(0xFFFF0000.toInt(), stroke.color)
        assertEquals(80, stroke.opacity)
        assertEquals(org.lineageos.glimpse.models.BrushColorMode.TINT, stroke.colorMode)
        assertNull(stroke.tipBitmap)
    }
}
