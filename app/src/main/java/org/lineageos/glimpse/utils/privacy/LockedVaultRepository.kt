/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.privacy

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class VaultItem(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateAdded: Long,
    val file: File,
)

/**
 * Isolated storage repository for the Oppo ColorOS-style Locked Album (Private Safe).
 * Media is stored within the app's internal private storage directory (`filesDir/locked_vault`),
 * completely hidden and isolated from Android's MediaStore, external apps, and public pickers.
 */
object LockedVaultRepository {
    private const val VAULT_DIR = "locked_vault"
    private const val INDEX_FILE = "vault_index.json"

    private fun getVaultDirectory(context: Context): File {
        val dir = File(context.filesDir, VAULT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    @Synchronized
    fun getVaultItems(context: Context): List<VaultItem> {
        val dir = getVaultDirectory(context)
        val indexFile = File(dir, INDEX_FILE)
        if (!indexFile.exists()) return emptyList()

        return runCatching {
            val jsonStr = indexFile.readText()
            val array = JSONArray(jsonStr)
            val list = mutableListOf<VaultItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val displayName = obj.getString("displayName")
                val mimeType = obj.getString("mimeType")
                val isVideo = obj.optBoolean("isVideo", false)
                val dateAdded = obj.optLong("dateAdded", System.currentTimeMillis())
                val mediaFile = File(dir, "$id.dat")
                if (mediaFile.exists()) {
                    list.add(VaultItem(id, displayName, mimeType, isVideo, dateAdded, mediaFile))
                }
            }
            list.sortedByDescending { it.dateAdded }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun importToVault(context: Context, sourceUri: Uri): VaultItem? {
        val dir = getVaultDirectory(context)
        val resolver = context.contentResolver
        val mimeType = resolver.getType(sourceUri) ?: "image/jpeg"
        val isVideo = mimeType.startsWith("video/")

        val id = UUID.randomUUID().toString()
        val displayName = sourceUri.lastPathSegment ?: "Private_$id"
        val targetFile = File(dir, "$id.dat")

        val copied = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrDefault(false)

        if (!copied || !targetFile.exists()) {
            targetFile.delete()
            return null
        }

        val item = VaultItem(
            id = id,
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            dateAdded = System.currentTimeMillis(),
            file = targetFile,
        )

        val current = getVaultItems(context).toMutableList()
        current.add(0, item)
        saveIndex(context, current)
        return item
    }

    @Synchronized
    fun restoreToPublic(context: Context, item: VaultItem): Uri? {
        if (!item.file.exists()) return null
        val resolver = context.contentResolver

        val collection = if (item.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val relativeDir = if (item.isVideo) {
            "${Environment.DIRECTORY_MOVIES}/Glimpse"
        } else {
            "${Environment.DIRECTORY_PICTURES}/Glimpse"
        }

        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType)
            ?: if (item.isVideo) "mp4" else "jpg"
        val cleanName = if (item.displayName.contains(".")) item.displayName else "${item.displayName}.$ext"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return null
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                item.file.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)

        if (written) {
            deleteItem(context, item)
            return uri
        } else {
            resolver.delete(uri, null, null)
            return null
        }
    }

    @Synchronized
    fun deleteItem(context: Context, item: VaultItem): Boolean {
        if (item.file.exists()) {
            item.file.delete()
        }
        val current = getVaultItems(context).filterNot { it.id == item.id }
        saveIndex(context, current)
        return true
    }

    private fun saveIndex(context: Context, items: List<VaultItem>) {
        val dir = getVaultDirectory(context)
        val indexFile = File(dir, INDEX_FILE)
        val array = JSONArray()
        items.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("displayName", item.displayName)
                put("mimeType", item.mimeType)
                put("isVideo", item.isVideo)
                put("dateAdded", item.dateAdded)
            }
            array.put(obj)
        }
        runCatching {
            indexFile.writeText(array.toString())
        }
    }
}
