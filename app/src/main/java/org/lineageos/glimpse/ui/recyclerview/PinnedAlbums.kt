/* Stable partitioning of ordinary albums into pinned and remaining sections.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.ui.recyclerview

internal fun <T> partitionPinned(items: List<T>, pinnedKeys: Set<String>, key: (T) -> String): Pair<List<T>, List<T>> =
    items.partition { key(it) in pinnedKeys }
