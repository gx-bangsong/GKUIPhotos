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

    // 保留旧常量供迁移和兼容
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
     * OPPO 式智能相册扩展：支持自定义 AppRule
     */
    fun aggregateWithRules(
        context: Context,
        rules: List<org.lineageos.glimpse.models.AppRule>,
        maxExifScans: Int = 60,
    ): List<SourceAlbum> {
        // 转换为内部 Source 模型
        val sources = rules.filter { it.enabled }.map {
            Source(
                key = it.key,
                displayName = it.displayName,
                pathKeywords = it.pathKeywords,
                softwareKeywords = it.softwareKeywords
            )
        }
        return aggregateInternal(context, sources, maxExifScans)
    }

    /**
     * 旧入口，兼容调用，使用内置规则
     */
    fun aggregate(
        context: Context,
        maxExifScans: Int = 60,
    ): List<SourceAlbum> {
        return aggregateInternal(context, KNOWN_SOURCES, maxExifScans)
    }

    private fun aggregateInternal(
        context: Context,
        sources: List<Source>,
        maxExifScans: Int,
    ): List<SourceAlbum> {
        val resolver: ContentResolver = context.contentResolver
        val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
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

        resolver.query(filesUri, projection, selection, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val data = cursor.getString(dataIdx) ?: continue
                val type = cursor.getInt(typeIdx)
                val contentUri = if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    Uri.withAppendedPath(videosUri, id.toString())
                } else {
                    Uri.withAppendedPath(imagesUri, id.toString())
                }

                val matched = sources.firstOrNull { source ->
                    source.pathKeywords.any { kw -> data.contains(kw, ignoreCase = true) }
                }
                if (matched != null) {
                    byPath.getOrPut(matched.key) { mutableListOf() }.add(contentUri to data)
                } else if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                    unmatched.add(contentUri to data)
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
                    val matched = sources.firstOrNull { source ->
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
        for (source in sources) {
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
