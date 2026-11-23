/* Pure geometry for keeping an annotation and its handles in a visible area.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.views

internal data class AnnotationFit(val scaleFactor: Float, val centerX: Float, val centerY: Float)

internal fun fitAnnotation(
    centerX: Float, centerY: Float, width: Float, height: Float,
    left: Float, top: Float, right: Float, bottom: Float,
): AnnotationFit {
    val availableWidth = (right - left).coerceAtLeast(0f)
    val availableHeight = (bottom - top).coerceAtLeast(0f)
    val factor = minOf(1f, availableWidth / width.coerceAtLeast(0.001f), availableHeight / height.coerceAtLeast(0.001f))
    fun clamp(center: Float, half: Float, low: Float, high: Float): Float =
        if (half * 2f >= high - low) (low + high) / 2f else center.coerceIn(low + half, high - half)
    return AnnotationFit(factor, clamp(centerX, width * factor / 2f, left, right),
        clamp(centerY, height * factor / 2f, top, bottom))
}
