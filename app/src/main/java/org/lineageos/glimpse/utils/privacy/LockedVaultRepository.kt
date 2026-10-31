/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.privacy

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
        val displayName = runCatching {
            resolver.query(
                sourceUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: (sourceUri.lastPathSegment ?: "Private_$id")

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
        val baseName = File(item.displayName).name
        val cleanName = if (baseName.contains(".")) baseName else "$baseName.$ext"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, cleanName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.DATE_ADDED, item.dateAdded / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, item.dateAdded / 1000)
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

    @Synchronized
    fun deleteItems(context: Context, items: Collection<VaultItem>): Boolean {
        if (items.isEmpty()) return true
        val idsToDelete = items.map { it.id }.toSet()
        items.forEach { item ->
            if (item.file.exists()) {
                item.file.delete()
            }
        }
        val current = getVaultItems(context).filterNot { it.id in idsToDelete }
        saveIndex(context, current)
        return true
    }

    @Synchronized
    fun deleteItemsByIds(context: Context, ids: Collection<String>): Boolean {
        if (ids.isEmpty()) return true
        val dir = getVaultDirectory(context)
        val idSet = ids.toSet()
        ids.forEach { id ->
            val file = File(dir, "$id.dat")
            if (file.exists()) {
                file.delete()
            }
        }
        val current = getVaultItems(context).filterNot { it.id in idSet }
        saveIndex(context, current)
        return true
    }

    /**
     * Resolves an arbitrary media URI (e.g. from PhotoPicker, DocumentsProvider, or MediaStore)
     * to a canonical MediaStore external content URI.
     */
    fun resolveToMediaStoreUri(context: Context, uri: Uri): Uri? {
        val scheme = uri.scheme ?: return null
        if (scheme != "content") return null

        val authority = uri.authority ?: return null

        // 1. Direct MediaStore URI (e.g. content://media/external/images/media/1234)
        if (authority == MediaStore.AUTHORITY || authority.startsWith("media")) {
            val path = uri.path.orEmpty()
            if (path.contains("images/media") || path.contains("video/media")) {
                return uri
            }
        }

        // 2. Storage Access Framework MediaDocumentsProvider URI
        // e.g. content://com.android.providers.media.documents/document/image%3A12345
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (authority == "com.android.providers.media.documents") {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val id = split[1].toLongOrNull()
                    if (id != null) {
                        return when (type) {
                            "image" -> ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                            "video" -> ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                            else -> null
                        }
                    }
                }
            }
        }

        // 3. Android PhotoPicker URI
        // e.g. content://media/picker/0/12345 or content://com.android.providers.media.photopicker/media/12345
        if (authority == "com.android.providers.media.photopicker" || uri.path?.contains("/picker/") == true) {
            val id = uri.lastPathSegment?.toLongOrNull()
            if (id != null) {
                val mimeType = context.contentResolver.getType(uri) ?: ""
                return if (mimeType.startsWith("video/")) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                }
            }
        }

        // 4. Fallback: query MediaStore by DISPLAY_NAME and SIZE
        return runCatching {
            var displayName: String? = null
            var size: Long? = null
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx != -1) displayName = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
            if (displayName != null && size != null && size!! > 0) {
                val mimeType = context.contentResolver.getType(uri) ?: ""
                val collection = if (mimeType.startsWith("video/")) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                context.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?",
                    arrayOf(displayName, size.toString()),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        ContentUris.withAppendedId(collection, id)
                    } else null
                }
            } else null
        }.getOrNull()
    }

    /**
     * Checks if the app owns the specified media item.
     */
    fun isOwner(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                    if (idx != -1) {
                        cursor.getString(idx) == context.packageName
                    } else false
                } else false
            } ?: false
        }.getOrDefault(false)
    }

    /**
     * Determines whether direct deletion (ContentResolver.delete()) is legally privileged
     * for all requested URIs without triggering a Scoped Storage SecurityException.
     */
    fun canDirectDelete(context: Context, uris: Collection<Uri>): Boolean {
        if (uris.isEmpty()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)) {
            return true
        }
        return uris.all { isOwner(context, it) }
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
