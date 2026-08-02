/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.graphics.ColorMatrix

/**
 * Pure-offline image filters built from [ColorMatrix] (no GPU/network/ML). Each
 * filter produces a matrix suitable for both live preview (`setColorFilter`)
 * and baked-in export.
 */
enum class ImageFilter(val key: String) {
    NONE("none"),
    GRAYSCALE("grayscale"),
    SEPIA("sepia"),
    INVERT("invert"),
    WARM("warm"),
    COOL("cool");

    fun colorMatrix(): ColorMatrix = when (this) {
        NONE -> ColorMatrix()
        GRAYSCALE -> ColorMatrix().apply { setSaturation(0f) }
        SEPIA -> ColorMatrix(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        INVERT -> ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        WARM -> ColorMatrix(
            floatArrayOf(
                1.2f, 0f, 0f, 0f, 0f,
                0f, 1.05f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        COOL -> ColorMatrix(
            floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
    }
}

/**
 * Preset ID-photo / avatar crop aspect ratios (module 5). Width is always 1
 * unless square; the [w]/[h] pair defines the lock ratio for the crop overlay.
 */
data class IdPhotoPreset(
    val key: String,
    /** Display label resource id (resolved by caller). */
    val ratioW: Int,
    val ratioH: Int,
)
