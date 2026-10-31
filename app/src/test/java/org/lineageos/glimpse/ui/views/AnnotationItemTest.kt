/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.ui.views

import android.graphics.Color
import android.graphics.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationItemTest {

    @Test
    fun testShapeTypeEnum() {
        assertEquals(4, ShapeType.values().size)
        assertTrue(ShapeType.values().contains(ShapeType.ARROW))
        assertTrue(ShapeType.values().contains(ShapeType.RECTANGLE))
        assertTrue(ShapeType.values().contains(ShapeType.ELLIPSE))
        assertTrue(ShapeType.values().contains(ShapeType.LINE))
    }

    @Test
    fun testMarkupToolEnum() {
        assertEquals(5, MarkupTool.values().size)
        assertTrue(MarkupTool.values().contains(MarkupTool.DOODLE))
        assertTrue(MarkupTool.values().contains(MarkupTool.SHAPES))
        assertTrue(MarkupTool.values().contains(MarkupTool.TEXT))
        assertTrue(MarkupTool.values().contains(MarkupTool.MOSAIC))
    }

    @Test
    fun testShapeMutation() {
        val shape = AnnotationItem.Shape(
            type = ShapeType.ARROW,
            startX = 10f,
            startY = 20f,
            endX = 100f,
            endY = 200f,
            color = Color.RED,
            strokeWidth = 8f,
        )

        assertEquals(10f, shape.startX)
        assertEquals(20f, shape.startY)
        assertEquals(100f, shape.endX)
        assertEquals(200f, shape.endY)

        shape.endX = 150f
        shape.endY = 250f
        assertEquals(150f, shape.endX)
        assertEquals(250f, shape.endY)
    }

    @Test
    fun testUndoStackSemantics() {
        val stack = mutableListOf<AnnotationItem>()
        assertTrue(stack.isEmpty())

        val shape1 = AnnotationItem.Shape(ShapeType.RECTANGLE, 0f, 0f, 10f, 10f, Color.WHITE, 4f)
        val shape2 = AnnotationItem.Shape(ShapeType.LINE, 5f, 5f, 20f, 20f, Color.BLUE, 8f)

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
            color = Color.YELLOW,
            hasBackgroundPill = true,
        )

        assertEquals("Hello World", textItem.text)
        assertEquals(48f, textItem.effectiveTextSize, 0.01f)
        assertTrue(textItem.hasBackgroundPill)

        textItem.scale = 2.0f
        assertEquals(64f, textItem.effectiveTextSize, 0.01f)
    }
}
