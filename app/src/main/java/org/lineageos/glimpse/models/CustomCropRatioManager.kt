/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import android.content.Context
import kotlin.math.abs

object CustomCropRatioManager {

    private const val PREFS_NAME = "custom_crop_ratios"
    private const val KEY_RATIOS = "saved_ratios_string"
    private const val MAX_SAVED_RATIOS = 8

    data class CustomRatio(val label: String, val width: Float, val height: Float) {
        val ratio: Float get() = if (height > 0f) width / height else 1f
    }

    fun validate(w: Float, h: Float): Boolean {
        if (w <= 0f || h <= 0f || w.isNaN() || h.isNaN()) return false
        val r = w / h
        return r in 0.1f..10.0f
    }

    fun formatLabel(w: Float, h: Float, customLabel: String? = null): String {
        if (!customLabel.isNullOrBlank()) return customLabel.trim()
        return if (w % 1f == 0f && h % 1f == 0f) {
            "${w.toInt()}:${h.toInt()}"
        } else {
            "$w:$h"
        }
    }

    fun parseRatios(serialized: String?): List<CustomRatio> {
        if (serialized.isNullOrBlank()) return emptyList()
        val list = mutableListOf<CustomRatio>()
        val entries = serialized.split(";")
        for (entry in entries) {
            val parts = entry.split("|")
            if (parts.size >= 3) {
                val label = parts[0].trim()
                val w = parts[1].toFloatOrNull() ?: continue
                val h = parts[2].toFloatOrNull() ?: continue
                if (validate(w, h)) {
                    list.add(CustomRatio(formatLabel(w, h, label), w, h))
                }
            }
        }
        return list
    }

    fun serializeRatios(ratios: List<CustomRatio>): String {
        return ratios.joinToString(";") { "${it.label}|${it.width}|${it.height}" }
    }

    fun getSavedRatios(context: Context): List<CustomRatio> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_RATIOS, null)
        return parseRatios(str)
    }

    fun saveRatio(context: Context, ratio: CustomRatio) {
        val current = getSavedRatios(context).toMutableList()
        current.removeAll { abs(it.ratio - ratio.ratio) < 0.005f }
        current.add(0, ratio)
        val trimmed = current.take(MAX_SAVED_RATIOS)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RATIOS, serializeRatios(trimmed))
            .apply()
    }

    fun deleteRatio(context: Context, targetRatio: Float) {
        val current = getSavedRatios(context).toMutableList()
        current.removeAll { abs(it.ratio - targetRatio) < 0.005f }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RATIOS, serializeRatios(current))
            .apply()
    }
}
