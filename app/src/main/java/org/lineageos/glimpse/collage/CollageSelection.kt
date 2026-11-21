/* Validation for the album selection before launching the collage editor.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import org.lineageos.glimpse.models.MediaType

internal enum class CollageSelectionError { VIDEO, COUNT }
internal fun collageSelectionError(types: List<MediaType>): CollageSelectionError? = when {
    types.any { it != MediaType.IMAGE } -> CollageSelectionError.VIDEO
    types.size !in 2..6 -> CollageSelectionError.COUNT
    else -> null
}
