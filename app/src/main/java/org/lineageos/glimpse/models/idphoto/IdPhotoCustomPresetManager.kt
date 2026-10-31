/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models.idphoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.lineageos.glimpse.R

object IdPhotoCustomPresetManager {

    private const val PREFS_NAME = "id_photo_custom_presets"
    private const val KEY_CUSTOM_LIST = "custom_presets_json"
    private const val MAX_SAVED_PRESETS = 3

    /**
     * Validate input parameters.
     * Returns null if valid, or an error string resource id if invalid.
     */
    fun validate(isPxMode: Boolean, w: Float, h: Float, dpi: Int = 300): Int? {
        if (w <= 0f || h <= 0f) return R.string.id_photo_custom_error_invalid
        val ratio = w / h
        if (ratio < 0.3f || ratio > 3.0f) return R.string.id_photo_custom_error_invalid

        if (isPxMode) {
            if (w > 4000f || h > 4000f) return R.string.id_photo_custom_error_invalid
        } else {
            if (w > 200f || h > 200f) return R.string.id_photo_custom_error_invalid
            if (dpi <= 0 || dpi > 1200) return R.string.id_photo_custom_error_invalid
        }
        return null
    }

    fun createPreset(isPxMode: Boolean, w: Float, h: Float, dpi: Int = 300): IdPhotoPreset {
        return if (isPxMode) {
            val wPx = w.toInt()
            val hPx = h.toInt()
            IdPhotoPreset(
                id = "custom_px_${wPx}_${hPx}",
                nameRes = R.string.id_photo_custom_title,
                widthMm = wPx,
                heightMm = hPx,
                dpi = 300,
                group = PresetGroup.COMMON,
                specSource = "自定义",
                description = "${wPx}×${hPx} px",
                customTargetWidthPx = wPx,
                customTargetHeightPx = hPx,
            )
        } else {
            val wMm = w.toInt()
            val hMm = h.toInt()
            IdPhotoPreset(
                id = "custom_mm_${wMm}_${hMm}_$dpi",
                nameRes = R.string.id_photo_custom_title,
                widthMm = wMm,
                heightMm = hMm,
                dpi = dpi,
                group = PresetGroup.COMMON,
                specSource = "自定义",
                description = "${wMm}×${hMm} mm",
            )
        }
    }

    fun getSavedCustomPresets(context: Context): List<IdPhotoPreset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CUSTOM_LIST, null) ?: return emptyList()
        val list = mutableListOf<IdPhotoPreset>()
        return try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val isPx = obj.optBoolean("isPx", false)
                val w = obj.optDouble("w", 0.0).toFloat()
                val h = obj.optDouble("h", 0.0).toFloat()
                val dpi = obj.optInt("dpi", 300)
                if (validate(isPx, w, h, dpi) == null) {
                    list.add(createPreset(isPx, w, h, dpi))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomPreset(context: Context, preset: IdPhotoPreset) {
        val current = getSavedCustomPresets(context).toMutableList()
        // Deduplicate by dimensions
        current.removeAll {
            it.targetWidthPx == preset.targetWidthPx && it.targetHeightPx == preset.targetHeightPx
        }
        current.add(0, preset)
        val trimmed = current.take(MAX_SAVED_PRESETS)

        val arr = JSONArray()
        for (item in trimmed) {
            val obj = JSONObject().apply {
                val isPx = item.customTargetWidthPx != null
                put("isPx", isPx)
                if (isPx) {
                    put("w", item.targetWidthPx)
                    put("h", item.targetHeightPx)
                    put("dpi", 300)
                } else {
                    put("w", item.widthMm)
                    put("h", item.heightMm)
                    put("dpi", item.dpi)
                }
            }
            arr.put(obj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LIST, arr.toString())
            .apply()
    }

    fun deleteCustomPreset(context: Context, presetId: String) {
        val current = getSavedCustomPresets(context).toMutableList()
        current.removeAll { it.id == presetId }

        val arr = JSONArray()
        for (item in current) {
            val obj = JSONObject().apply {
                val isPx = item.customTargetWidthPx != null
                put("isPx", isPx)
                if (isPx) {
                    put("w", item.targetWidthPx)
                    put("h", item.targetHeightPx)
                    put("dpi", 300)
                } else {
                    put("w", item.widthMm)
                    put("h", item.heightMm)
                    put("dpi", item.dpi)
                }
            }
            arr.put(obj)
        }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_LIST, arr.toString())
            .apply()
    }
}
