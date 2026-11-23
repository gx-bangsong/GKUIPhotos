/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.models

import android.content.Context

/** Persisted, most-recent-first ARGB palette. The backing store outlives this instance. */
internal class MarkupColorStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    fun get(): List<Int> = read().orEmpty().split(',')
        .mapNotNull { it.toLongOrNull(16)?.takeIf { value -> value in 0..0xffffffffL }?.toInt() }
        .distinct()
        .take(MarkupCustomColors.MAX_COLORS)

    fun add(color: Int) {
        val colors = (listOf(color) + get().filterNot { it == color }).take(MarkupCustomColors.MAX_COLORS)
        save(colors)
    }

    fun remove(color: Int) = save(get().filterNot { it == color })

    private fun save(colors: List<Int>) {
        write(colors.joinToString(",") { (it.toLong() and 0xffffffffL).toString(16).padStart(8, '0') })
    }
}

/** Shared palette for markup text and text backgrounds. */
object MarkupCustomColors {
    const val MAX_COLORS = 8
    const val MIN_TEXT_ALPHA = 26
    private const val PREFERENCES = "markup_custom_colors"
    private const val KEY_COLORS = "argb"

    /** Clamp only the applied text color, never the palette entry. */
    fun forText(color: Int): Int {
        val alpha = (color ushr 24) and 0xff
        return (color and 0x00ffffff) or (alpha.coerceAtLeast(MIN_TEXT_ALPHA) shl 24)
    }

    private fun store(context: Context): MarkupColorStore {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return MarkupColorStore(
            read = { preferences.getString(KEY_COLORS, null) },
            write = { preferences.edit().putString(KEY_COLORS, it).apply() },
        )
    }

    @Synchronized fun get(context: Context): List<Int> = store(context).get()
    @Synchronized fun add(context: Context, color: Int) = store(context).add(color)
    @Synchronized fun remove(context: Context, color: Int) = store(context).remove(color)
}
