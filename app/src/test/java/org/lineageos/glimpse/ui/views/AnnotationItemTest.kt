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
        assertEquals(9, ShapeType.values().size)
        assertTrue(ShapeType.values().contains(ShapeType.ARROW))
        assertTrue(ShapeType.values().contains(ShapeType.DOUBLE_ARROW))
        assertTrue(ShapeType.values().contains(ShapeType.RECTANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.ROUNDED_RECTANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.ELLIPSE))
        assertTrue(ShapeType.values().contains(ShapeType.LINE))
        assertTrue(ShapeType.values().contains(ShapeType.TRIANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.STAR))
        assertTrue(ShapeType.values().contains(ShapeType.DIAMOND))
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
        assertEquals(4, BrushType.values().size)
        assertTrue(BrushType.values().contains(BrushType.NORMAL))
        assertTrue(BrushType.values().contains(BrushType.HIGHLIGHTER))
        assertTrue(BrushType.values().contains(BrushType.NEON))
        assertTrue(BrushType.values().contains(BrushType.DASHED))
    }

    @Test
    fun testMosaicEffectEnum() {
        assertEquals(5, MosaicEffect.values().size)
        assertTrue(MosaicEffect.values().contains(MosaicEffect.PIXELATE))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.BLUR))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.BLACKOUT))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.STRIPES))
        assertTrue(MosaicEffect.values().contains(MosaicEffect.NOISE))
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
}
