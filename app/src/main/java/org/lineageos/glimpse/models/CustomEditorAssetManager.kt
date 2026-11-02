/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

object CustomEditorAssetManager {

    private const val PREFS_NAME = "custom_editor_assets"
    private const val KEY_BRUSHES = "custom_brushes_json"
    private const val KEY_BRUSHES_DATA = "custom_brushes_tsv"
    private const val KEY_SHAPES_DATA = "custom_shapes_tsv"
    private const val KEY_MOSAICS = "custom_mosaics_json"
    private const val KEY_STICKERS = "custom_stickers_json"

    data class CustomAsset(
        val id: String,
        val name: String,
        val filePath: String,
    ) {
        fun loadBitmap(): Bitmap? = runCatching {
            BitmapFactory.decodeFile(filePath)
        }.getOrNull()
    }

    private fun getAssetDir(context: Context, subDir: String): File {
        val dir = File(context.filesDir, subDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadAssetList(context: Context, key: String): List<CustomAsset> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<CustomAsset>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name")
                val path = obj.optString("path")
                if (id.isNotBlank() && File(path).exists()) {
                    list.add(CustomAsset(id, name, path))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAssetList(context: Context, key: String, list: List<CustomAsset>) {
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("path", item.filePath)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, arr.toString())
            .apply()
    }

    private fun importBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val origMax = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (origMax / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    // --- Custom Stamp Brushes ---
    fun getCustomBrushesList(context: Context): List<CustomBrush> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val content = prefs.getString(KEY_BRUSHES_DATA, null)
        val list = CustomBrush.deserializeList(content)
        return list.filter { File(it.tipFile).exists() }
    }

    fun saveCustomBrushesList(context: Context, list: List<CustomBrush>) {
        val serialized = CustomBrush.serializeList(list)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRUSHES_DATA, serialized)
            .apply()
    }

    fun importCustomBrush(context: Context, uri: Uri, name: String = "自定义画笔"): CustomBrush? {
        val bmp = importBitmap(context, uri, 256) ?: return null
        val id = "brush_" + UUID.randomUUID().toString().take(8)
        val file = File(getAssetDir(context, "custom_brushes"), "$id.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val colorMode = CustomBrush.detectColorMode(bmp)
        bmp.recycle()

        val brush = CustomBrush(
            id = id,
            name = name,
            tipFile = file.absolutePath,
            spacingPercent = 25,
            sizeDp = 24f,
            opacity = 100,
            followDirection = false,
            colorMode = colorMode,
        )
        val current = getCustomBrushesList(context).toMutableList()
        current.add(0, brush)
        saveCustomBrushesList(context, current)
        return brush
    }

    fun deleteCustomBrush(context: Context, id: String) {
        val current = getCustomBrushesList(context).toMutableList()
        val item = current.find { it.id == id }
        if (item != null) {
            File(item.tipFile).delete()
            current.remove(item)
            saveCustomBrushesList(context, current)
        }
    }

    // --- Custom Shapes ---
    fun getCustomShapes(context: Context): List<CustomShape> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val content = prefs.getString(KEY_SHAPES_DATA, null)
        return CustomShape.deserializeList(content)
    }

    fun saveCustomShapes(context: Context, list: List<CustomShape>) {
        val serialized = CustomShape.serializeList(list)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SHAPES_DATA, serialized)
            .apply()
    }

    fun importCustomShape(context: Context, content: String, defaultName: String = "自定义形状"): CustomShapeParseResult {
        val parseResult = CustomShapeParser.parse(content, defaultName)
        if (parseResult is CustomShapeParseResult.Success) {
            val id = "shape_" + UUID.randomUUID().toString().take(8)
            val shape = CustomShape(
                id = id,
                name = parseResult.name,
                pathData = parseResult.normalizedPathData,
            )
            val current = getCustomShapes(context).toMutableList()
            current.add(0, shape)
            saveCustomShapes(context, current)
        }
        return parseResult
    }

    fun deleteCustomShape(context: Context, id: String) {
        val current = getCustomShapes(context).toMutableList()
        val item = current.find { it.id == id }
        if (item != null) {
            current.remove(item)
            saveCustomShapes(context, current)
        }
    }

    // --- Custom Mosaics ---
    fun getCustomMosaics(context: Context): List<CustomAsset> = loadAssetList(context, KEY_MOSAICS)

    fun importCustomMosaic(context: Context, uri: Uri, name: String = "自定义遮挡"): CustomAsset? {
        val bmp = importBitmap(context, uri, 128) ?: return null
        val id = "mosaic_" + UUID.randomUUID().toString().take(8)
        val file = File(getAssetDir(context, "custom_mosaics"), "$id.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()

        val asset = CustomAsset(id, name, file.absolutePath)
        val current = getCustomMosaics(context).toMutableList()
        current.add(0, asset)
        saveAssetList(context, KEY_MOSAICS, current)
        return asset
    }

    fun deleteCustomMosaic(context: Context, id: String) {
        val current = getCustomMosaics(context).toMutableList()
        val item = current.find { it.id == id }
        if (item != null) {
            File(item.filePath).delete()
            current.remove(item)
            saveAssetList(context, KEY_MOSAICS, current)
        }
    }

    // --- Custom Stickers ---
    fun getCustomStickers(context: Context): List<CustomAsset> = loadAssetList(context, KEY_STICKERS)

    fun importCustomSticker(context: Context, uri: Uri, name: String = "贴纸"): CustomAsset? {
        val bmp = importBitmap(context, uri, 512) ?: return null
        val id = "sticker_" + UUID.randomUUID().toString().take(8)
        val file = File(getAssetDir(context, "custom_stickers"), "$id.png")
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val asset = CustomAsset(id, name, file.absolutePath)
        val current = getCustomStickers(context).toMutableList()
        current.add(0, asset)
        saveAssetList(context, KEY_STICKERS, current)
        return asset
    }

    fun deleteCustomSticker(context: Context, id: String) {
        val current = getCustomStickers(context).toMutableList()
        val item = current.find { it.id == id }
        if (item != null) {
            File(item.filePath).delete()
            current.remove(item)
            saveAssetList(context, KEY_STICKERS, current)
        }
    }
}
