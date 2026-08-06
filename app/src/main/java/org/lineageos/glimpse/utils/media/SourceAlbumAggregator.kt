/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.lineageos.glimpse.models.Album
import org.lineageos.glimpse.models.Thumbnail

/**
 * A logical "smart album" produced by [SourceAlbumAggregator]. It groups media
 * captured by a known crowd-sourcing / gig-economy or social application
 * without ever moving the underlying files on disk.
 *
 * @param key Stable identifier used as the [Uri] last path segment.
 * @param displayName Human-readable album name.
 * @param coverUri Cover image for the album thumbnail.
 * @param mediaCount Number of matched items.
 * @param uris The matched media item uris (kept lightweight; optional).
 */
data class SourceAlbum(
    val key: String,
    val displayName: String,
    val coverUri: Uri?,
    val mediaCount: Int,
    val uris: List<Uri>,
) {
    fun toAlbum(): Album {
        val base = Uri.parse("content://org.lineageos.glimpse.source/$key")
        return Album(
            uri = base,
            name = displayName,
            thumbnail = coverUri?.let { Thumbnail(uri = it) },
            mediaCount = mediaCount,
        )
    }
}

/**
 * Known application sources to aggregate. Matching is performed through two
 * complementary, fully-offline strategies:
 *
 *  - **Strategy A (path matching):** the MediaStore `DATA` column is matched
 *    against well-known application storage directories.
 *  - **Strategy B (EXIF tag):** the `TAG_SOFTWARE` attribute is matched
 *    against application signature strings.
 *
 * Neither strategy copies or relocates any file; aggregation is purely a query
 * over the existing MediaStore, so it is fast and refreshes automatically.
 */
object SourceAlbumAggregator {

    data class Source(
        val key: String,
        val displayName: String,
        val pathKeywords: List<String>,
        val softwareKeywords: List<String>,
    )

    val KNOWN_SOURCES: List<Source> = listOf(
        Source(
            key = "meituan",
            displayName = "美团众包",
            pathKeywords = listOf("Meituan", "meituan", "美团", "CrowdsourcingTakeout"),
            softwareKeywords = listOf("Meituan", "美团"),
        ),
        Source(
            key = "eleme_fengniao",
            displayName = "蜂鸟即配",
            pathKeywords = listOf("Fengniao", "fengniao", "蜂鸟", "ElemeFengniao"),
            softwareKeywords = listOf("Fengniao", "蜂鸟", "Eleme"),
        ),
        Source(
            key = "sf_express",
            displayName = "顺丰同城",
            pathKeywords = listOf("SFExpress", "顺丰", "SF_SameCity", "SFExpressCourier"),
            softwareKeywords = listOf("SFExpress", "顺丰"),
        ),
        Source(
            key = "dada",
            displayName = "达达快送",
            pathKeywords = listOf("Dada", "达达", "jd_dada"),
            softwareKeywords = listOf("Dada", "达达"),
        ),
        Source(
            key = "taobao",
            displayName = "淘宝",
            pathKeywords = listOf("Taobao", "taobao", "淘宝", "com.taobao"),
            softwareKeywords = listOf("Taobao", "淘宝"),
        ),
        Source(
            key = "wechat",
            displayName = "微信",
            pathKeywords = listOf("com.tencent.mm", "Tencent/MicroMsg", "WeiXin"),
            softwareKeywords = listOf("WeChat", "微信"),
        ),
    )

    /**
     * Scan the MediaStore once and return every virtual [SourceAlbum] that has
     * at least one match. Image/video items are scanned. The scan is a single
     * MediaStore query plus a lazily-applied EXIF fallback.
     *
     * @param maxExifScans Limits how many images are read for EXIF tag matching,
     * to bound the cost of strategy B.
     */
    fun aggregate(
        context: Context,
        maxExifScans: Int = 60,
    ): List<SourceAlbum> {
        val resolver: ContentResolver = context.contentResolver
        val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        // On Android 10+ DATA is deprecated and often null due to scoped storage.
        // Query RELATIVE_PATH + DISPLAY_NAME as modern fallbacks, keep DATA for
        // legacy devices where it is still populated.
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
        )

        val selection = buildString {
            append("(")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            append(" OR ")
            append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
            append(")")
        }

        // key -> list of (uri, path)
        val byPath = HashMap<String, MutableList<Pair<Uri, String>>>()
        val unmatched = ArrayList<Pair<Uri, String>>()
        val imagesUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val videosUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        // Need column indices lazily — RELATIVE_PATH may be missing on very old API.
        resolver.query(filesUri, projection, selection, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val relPathIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val displayNameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val bucketNameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val data = if (dataIdx != -1) cursor.getString(dataIdx) else null
                val relPath = if (relPathIdx != -1) cursor.getString(relPathIdx) else null
                val displayName = if (displayNameIdx != -1) cursor.getString(displayNameIdx) else null
                val bucketName = if (bucketNameIdx != -1) cursor.getString(bucketNameIdx) else null
                val type = cursor.getInt(typeIdx)
                val contentUri = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    Uri.withAppendedPath(videosUri, id.toString())
                } else {
                    Uri.withAppendedPath(imagesUri, id.toString())
                }

                // Build a searchable haystack from all available path-like fields.
                val haystack = listOfNotNull(data, relPath, displayName, bucketName)
                    .joinToString(separator = "/")
                if (haystack.isEmpty()) continue

                val matched = KNOWN_SOURCES.firstOrNull { source ->
                    source.pathKeywords.any { kw -> haystack.contains(kw, ignoreCase = true) }
                }
                if (matched != null) {
                    byPath.getOrPut(matched.key) { mutableListOf() }.add(contentUri to haystack)
                } else if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                    unmatched.add(contentUri to haystack)
                }
            }
        }

        // Strategy B: EXIF SOFTWARE fallback on a bounded number of unmatched images.
        val matchedByExif = HashMap<String, MutableList<Pair<Uri, String>>>()
        var scans = 0
        for ((uri, _) in unmatched) {
            if (scans >= maxExifScans) break
            runCatching {
                resolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: return@use
                    val matched = KNOWN_SOURCES.firstOrNull { source ->
                        source.softwareKeywords.any { kw -> software.contains(kw, ignoreCase = true) }
                    }
                    if (matched != null) {
                        matchedByExif.getOrPut(matched.key) { mutableListOf() }.add(uri to "")
                    }
                }
            }
            scans++
        }

        val result = ArrayList<SourceAlbum>()
        for (source in KNOWN_SOURCES) {
            val pathHits = byPath[source.key] ?: emptyList()
            val exifHits = matchedByExif[source.key] ?: emptyList()
            val all = (pathHits + exifHits).distinctBy { it.first }
            if (all.isEmpty()) continue
            // Sort by recency of path file name is not reliable; keep as-is (MediaStore order is by id).
            result.add(
                SourceAlbum(
                    key = source.key,
                    displayName = source.displayName,
                    coverUri = all.first().first,
                    mediaCount = all.size,
                    uris = all.map { it.first },
                )
            )
        }

        // Highest count first so the busiest sources surface at the top of the section.
        return result.sortedByDescending { it.mediaCount }
    }
}
