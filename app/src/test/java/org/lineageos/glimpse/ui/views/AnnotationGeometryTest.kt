/* Tests annotation fit and movement bounds without Android framework mocks.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.views

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationGeometryTest {
    @Test fun clampsMovementWithoutChangingSize() {
        val fit = fitAnnotation(-100f, 500f, 40f, 20f, 0f, 0f, 100f, 100f)
        assertEquals(1f, fit.scaleFactor, 0.0001f)
        assertEquals(20f, fit.centerX, 0.0001f)
        assertEquals(90f, fit.centerY, 0.0001f)
    }
    @Test fun shrinksOversizedObjectBeforeClamping() {
        val fit = fitAnnotation(0f, 0f, 200f, 50f, 10f, 20f, 110f, 120f)
        assertEquals(0.5f, fit.scaleFactor, 0.0001f)
        assertEquals(60f, fit.centerX, 0.0001f)
        assertEquals(32.5f, fit.centerY, 0.0001f)
    }
}
