/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Options describing which EXIF metadata categories should be stripped from an
 * image prior to sharing it.
 */
data class ExifStripOptions(
    /** Strip all GPS / location related tags. */
    val stripLocation: Boolean = true,
    /** Strip device model/make, software, timestamps and other identifying tags. */
    val stripDeviceAndTime: Boolean = true,
)

/**
 * Purely-offline EXIF privacy helper. Clones the original image into the
 * application cache and strips the requested EXIF tags using
 * [androidx.exifinterface.media.ExifInterface]. The original file is never
 * touched; the returned [Uri] points to a private temporary file that is safe
 * to hand to a share [android.content.Intent].
 *
 * If the source format cannot store EXIF attributes the file is copied as-is
 * (so non-image EXIF-bearing formats still share), and the call is a no-op for
 * the metadata itself.
 */
object ExifStripper {

    /** Tags related to capture location. */
    private val LOCATION_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    )

    /** Tags related to device identity and capture time. */
    private val DEVICE_AND_TIME_TAGS = listOf(
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_LENS_MAKE,
        ExifInterface.TAG_LENS_MODEL,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_HOST_COMPUTER,
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_USER_COMMENT,
    )

    /**
     * Create a privacy-cleaned copy of [sourceUri] in the app cache.
     *
     * @return A content/file [Uri] for the temporary cleaned image.
     */
    fun strip(
        context: Context,
        sourceUri: Uri,
        options: ExifStripOptions,
    ): Uri {
        val resolver: ContentResolver = context.contentResolver
        val ext = guessExtension(resolver, sourceUri)
        val tempDir = File(context.cacheDir, "shared_clean").apply { mkdirs() }
        val outFile = File.createTempFile("glimpse_share_", ext, tempDir)

        // 1. Byte-copy the original to the cache file.
        resolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Cannot open input stream for $sourceUri" }
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }

        // 2. Strip EXIF tags in place when the format supports it.
        val canStrip = ext.lowercase() in setOf(".jpg", ".jpeg", ".tif", ".tiff", ".heic", ".heif", ".webp")
        if (canStrip) {
            runCatching {
                val exif = ExifInterface(outFile)
                val tagsToStrip = buildList {
                    if (options.stripLocation) addAll(LOCATION_TAGS)
                    if (options.stripDeviceAndTime) addAll(DEVICE_AND_TIME_TAGS)
                }
                tagsToStrip.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
        }

        return Uri.fromFile(outFile)
    }

    /**
     * Delete temporary cleaned files older than the given age, to keep cache usage bounded.
     */
    fun cleanupOldTempFiles(context: Context, maxAgeMillis: Long = 24 * 60 * 60 * 1000L) {
        val dir = File(context.cacheDir, "shared_clean")
        if (!dir.exists()) return
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) f.delete()
        }
    }

    private fun guessExtension(resolver: ContentResolver, uri: Uri): String {
        val mime = resolver.getType(uri)
            ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString())
            ?: "jpg"
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
        return ".$ext"
    }
}
