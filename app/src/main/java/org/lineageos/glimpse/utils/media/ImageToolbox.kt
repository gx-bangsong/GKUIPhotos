/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Configuration for the image compression tool.
 */
data class CompressOptions(
    /** Quality hint in 0..100, ignored when [maxBytes] is set. */
    val quality: Int = 70,
    /** If set, the encoder keeps lowering the quality until output <= this many bytes. */
    val maxBytes: Long? = null,
    /** Output format for the compressed bitmap. */
    val format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
)

/**
 * Target format for the format-conversion tool, along with the MIME type that
 * will be used when inserting the result into the MediaStore.
 */
enum class ImageTargetFormat(val mime: String, val ext: String, val compressFormat: Bitmap.CompressFormat) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
    PNG("image/png", "png", Bitmap.CompressFormat.PNG),
    WEBP("image/webp", "webp", Bitmap.CompressFormat.WEBP);
}

/**
 * Result of an offline media-processing operation.
 */
data class ToolboxResult(
    /** Uri of the freshly written file inside the MediaStore, when applicable. */
    val uri: Uri?,
    /** Display name of the produced file. */
    val displayName: String,
    /** Original size in bytes, if known. */
    val originalBytes: Long?,
    /** Resulting size in bytes. */
    val resultBytes: Long,
)

/**
 * Fully-offline image/video processing toolkit. Every routine runs heavy work
 * synchronously and is meant to be invoked from a `Dispatchers.Default` /
 * `Dispatchers.IO` coroutine by the caller. No network access is performed at
 * any point.
 */
object ImageToolbox {

    // ----------------------------------------------------------------------
    // 1. Compress image size
    // ----------------------------------------------------------------------

    /**
     * Compress the image referenced by [sourceUri] and write the result into the
     * `Pictures/Glimpse` collection of the MediaStore. When [options.maxBytes] is
     * set, the encoder iteratively lowers the quality until the budget is met.
     */
    fun compress(context: Context, sourceUri: Uri, options: CompressOptions): ToolboxResult {
        val resolver: ContentResolver = context.contentResolver
        val (bitmap, originalBytes, sourceName) = decode(sourceUri, resolver)

        try {
            val (data, usedQuality) = when (options.maxBytes) {
                null -> encodeOnce(bitmap, options.format, options.quality)
                else -> encodeToTargetSize(bitmap, options.format, options.maxBytes)
            }
            val outName = buildName(sourceName, "compressed", extensionFor(options.format))
            val uri = writeBitmapToMediaStore(
                context, data, outName,
                mimeForFormat(options.format),
                relativePath = "${Environment.DIRECTORY_PICTURES}/Glimpse",
            )
            return ToolboxResult(
                uri = uri,
                displayName = outName,
                originalBytes = originalBytes,
                resultBytes = data.size.toLong(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ----------------------------------------------------------------------
    // 2. Convert image format
    // ----------------------------------------------------------------------

    fun convert(context: Context, sourceUri: Uri, target: ImageTargetFormat): ToolboxResult {
        val resolver: ContentResolver = context.contentResolver
        val (bitmap, originalBytes, sourceName) = decode(sourceUri, resolver)

        try {
            val (data, _) = encodeOnce(bitmap, target.compressFormat, 90)
            val outName = buildName(sourceName, "converted", target.ext)
            val uri = writeBitmapToMediaStore(
                context, data, outName, target.mime,
                relativePath = "${Environment.DIRECTORY_PICTURES}/Glimpse",
            )
            return ToolboxResult(
                uri = uri,
                displayName = outName,
                originalBytes = originalBytes,
                resultBytes = data.size.toLong(),
            )
        } finally {
            bitmap.recycle()
        }
    }

    // ----------------------------------------------------------------------
    // 3. Video -> GIF
    // ----------------------------------------------------------------------

    /**
     * Convert the first [durationSeconds] of the video at [sourceUri] into an
     * animated GIF, sampling at roughly [fps] frames per second. Frames are
     * extracted with [MediaMetadataRetriever] (framework-only) and encoded with
     * the bundled [GifCodec].
     *
     * @param onProgress Optional callback reporting progress in 0..1.
     */
    fun videoToGif(
        context: Context,
        sourceUri: Uri,
        durationSeconds: Int = 5,
        fps: Int = 8,
        maxEdge: Int = 480,
        onProgress: ((Float) -> Unit)? = null,
    ): ToolboxResult {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, sourceUri)
        try {
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val total = durationMs.coerceAtLeast(1L)
            val windowMs = (durationSeconds * 1000L).coerceAtMost(total)

            val intervalUs = (1_000_000L / fps.coerceIn(1, 15)).coerceAtLeast(1L)
            val frames = ArrayList<GifCodec.GifFrame>()

            val frameDelayMs = (1000 / fps.coerceIn(1, 15)).toInt().coerceAtLeast(33)
            var tUs = 0L
            while (tUs < windowMs * 1000L) {
                val bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    val scaled = scaleDown(bmp, maxEdge)
                    if (scaled !== bmp) bmp.recycle()
                    frames.add(GifCodec.GifFrame(scaled, frameDelayMs))
                }
                onProgress?.invoke((tUs.toFloat() / (windowMs * 1000f)).coerceIn(0f, 1f))
                tUs += intervalUs
            }
            if (frames.isEmpty()) {
                retriever.getFrameAtTime(0)?.let { bmp ->
                    val scaled = scaleDown(bmp, maxEdge)
                    if (scaled !== bmp) bmp.recycle()
                    frames.add(GifCodec.GifFrame(scaled, frameDelayMs))
                }
            }

            val out = ByteArrayOutputStream()
            GifCodec.encode(frames, out, loopCount = 0)
            frames.forEach { it.bitmap.recycle() }
            val data = out.toByteArray()

            val sourceName = sourceUri.lastPathSegment ?: "video"
            val outName = buildName(sourceName, "gif", "gif")
            val uri = writeBitmapToMediaStore(
                context, data, outName, "image/gif",
                relativePath = "${Environment.DIRECTORY_PICTURES}/Glimpse",
            )
            onProgress?.invoke(1f)
            return ToolboxResult(
                uri = uri,
                displayName = outName,
                originalBytes = null,
                resultBytes = data.size.toLong(),
            )
        } finally {
            retriever.release()
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private fun decode(sourceUri: Uri, resolver: ContentResolver): Triple<Bitmap, Long?, String> {
        val originalBytes = resolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = resolver.openInputStream(sourceUri).use { input ->
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IllegalStateException("Failed to decode $sourceUri")
        val name = sourceUri.lastPathSegment ?: "image"
        return Triple(bitmap, originalBytes, name)
    }

    private fun encodeOnce(
        bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
    ): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality.coerceIn(1, 100), out)
        return out.toByteArray() to quality
    }

    /**
     * Iteratively lower the JPEG quality (and optionally downscale once) until
     * the encoded size is within [targetBytes]. Pure local work.
     */
    private fun encodeToTargetSize(
        bitmap: Bitmap, format: Bitmap.CompressFormat, targetBytes: Long,
    ): Pair<ByteArray, Int> {
        var current = bitmap
        var quality = 85
        var data = encodeOnce(current, format, quality).first
        var madeRecycledCopy = false
        while (data.size > targetBytes) {
            if (quality > 30) {
                quality -= 10
            } else if (!madeRecycledCopy) {
                // Halve the resolution once before dropping quality further.
                val scaled = Bitmap.createScaledBitmap(
                    current, (current.width * 0.75f).toInt().coerceAtLeast(1),
                    (current.height * 0.75f).toInt().coerceAtLeast(1), true
                )
                if (madeRecycledCopy) current.recycle()
                current = scaled
                madeRecycledCopy = true
                quality = 80
            } else {
                break
            }
            data = encodeOnce(current, format, quality).first
        }
        if (madeRecycledCopy) current.recycle()
        return data to quality
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun writeBitmapToMediaStore(
        context: Context,
        data: ByteArray,
        displayName: String,
        mimeType: String,
        relativePath: String,
    ): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri).use { stream: OutputStream? ->
            requireNotNull(stream) { "Cannot open output stream for $uri" }
            stream.write(data)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun extensionFor(format: Bitmap.CompressFormat) = when (format) {
        Bitmap.CompressFormat.JPEG -> "jpg"
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP -> "webp"
        Bitmap.CompressFormat.WEBP_LOSSY -> "webp"
        Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
    }

    private fun mimeForFormat(format: Bitmap.CompressFormat) = when (format) {
        Bitmap.CompressFormat.JPEG -> "image/jpeg"
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.WEBP,
        Bitmap.CompressFormat.WEBP_LOSSY,
        Bitmap.CompressFormat.WEBP_LOSSLESS -> "image/webp"
    }

    private fun buildName(source: String, suffix: String, ext: String): String {
        val base = source.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "image"
        val unique = "_${System.currentTimeMillis() % 100000}"
        return "${base}_${suffix}$unique.$ext"
    }
}
