/* Image-only selection for the image toolbox.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.dialogs

import org.lineageos.glimpse.models.MediaType

internal fun <T> selectedImages(items: List<T>, typeOf: (T) -> MediaType): List<T> =
    items.filter { typeOf(it) == MediaType.IMAGE }

internal fun canBatchEdit(imageCount: Int): Boolean = imageCount >= 2
