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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
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
    /** Resolution scale ratio in 0.1..1.0 (e.g. 0.75 for 75%, 0.5 for 50%). */
    val scaleRatio: Float = 1.0f,
    /** Optional maximum edge (width or height) in pixels (e.g. 3840 for 4K, 1920 for 1080p). */
    val maxEdge: Int? = null,
)

/**
 * Target format for the format-conversion tool, along with the MIME type that
 * will be used when inserting the result into the MediaStore.
 */
enum class ImageTargetFormat(val mime: String, val ext: String, val compressFormat: Bitmap.CompressFormat?) {
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),
    PNG("image/png", "png", Bitmap.CompressFormat.PNG),
    WEBP("image/webp", "webp", Bitmap.CompressFormat.WEBP),
    PDF("application/pdf", "pdf", null);
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
        val (decodedBitmap, originalBytes, sourceName) = decode(sourceUri, resolver)

        // 1. Resolution limit downscaling if maxEdge specified
        val currentBitmap = if (options.maxEdge != null && options.maxEdge > 0) {
            val longest = maxOf(decodedBitmap.width, decodedBitmap.height)
            if (longest > options.maxEdge) {
                val scale = options.maxEdge.toFloat() / longest
                val targetW = (decodedBitmap.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decodedBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
                if (scaled !== decodedBitmap) decodedBitmap.recycle()
                scaled
            } else {
                decodedBitmap
            }
        } else {
            decodedBitmap
        }

        // 2. Dimension scale ratio downscaling if scaleRatio < 1.0f
        val bitmap = if (options.scaleRatio in 0.05f..0.999f) {
            val targetW = (currentBitmap.width * options.scaleRatio).toInt().coerceAtLeast(1)
            val targetH = (currentBitmap.height * options.scaleRatio).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(currentBitmap, targetW, targetH, true)
            if (scaled !== currentBitmap) currentBitmap.recycle()
            scaled
        } else {
            currentBitmap
        }

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
        if (target == ImageTargetFormat.PDF) {
            return imagesToPdf(context, listOf(sourceUri))
        }

        val resolver: ContentResolver = context.contentResolver
        val (bitmap, originalBytes, sourceName) = decode(sourceUri, resolver)
        val format = target.compressFormat ?: Bitmap.CompressFormat.JPEG

        try {
            val (data, _) = encodeOnce(bitmap, format, 90)
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
    // 3. Edit image (rotate & filter)
    // ----------------------------------------------------------------------

    fun edit(
        context: Context,
        sourceUri: Uri,
        rotation: Int = 0,
        filter: ImageFilter = ImageFilter.NONE,
    ): ToolboxResult {
        val resolver: ContentResolver = context.contentResolver
        val (bitmap, originalBytes, sourceName) = decode(sourceUri, resolver)

        try {
            val rotated = if (rotation % 360 != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rb = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rb !== bitmap) bitmap.recycle()
                rb
            } else {
                bitmap
            }

            val filtered = if (filter != ImageFilter.NONE) {
                val fb = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(fb)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
                }
                canvas.drawBitmap(rotated, 0f, 0f, paint)
                if (fb !== rotated) rotated.recycle()
                fb
            } else {
                rotated
            }

            try {
                val (data, _) = encodeOnce(filtered, Bitmap.CompressFormat.JPEG, 90)
                val outName = buildName(sourceName, "edited", "jpg")
                val uri = writeBitmapToMediaStore(
                    context, data, outName, "image/jpeg",
                    relativePath = "${Environment.DIRECTORY_PICTURES}/Glimpse",
                )
                return ToolboxResult(
                    uri = uri,
                    displayName = outName,
                    originalBytes = originalBytes,
                    resultBytes = data.size.toLong(),
                )
            } finally {
                filtered.recycle()
            }
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw e
        }
    }

    // ----------------------------------------------------------------------
    // 4. Batch operations
    // ----------------------------------------------------------------------

    fun batchCompress(
        context: Context,
        uris: List<Uri>,
        options: CompressOptions,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<ToolboxResult> {
        val results = mutableListOf<ToolboxResult>()
        uris.forEachIndexed { index, uri ->
            runCatching {
                compress(context, uri, options)
            }.onSuccess { results.add(it) }
                .onFailure {
                    Log.e("ImageToolbox", "Failed to compress $uri", it)
                }
            onProgress?.invoke(index + 1, uris.size)
        }
        return results
    }

    fun batchConvert(
        context: Context,
        uris: List<Uri>,
        target: ImageTargetFormat,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<ToolboxResult> {
        if (target == ImageTargetFormat.PDF) {
            val result = imagesToPdf(context, uris, onProgress)
            return listOf(result)
        }

        val results = mutableListOf<ToolboxResult>()
        uris.forEachIndexed { index, uri ->
            runCatching {
                convert(context, uri, target)
            }.onSuccess { results.add(it) }
                .onFailure {
                    Log.e("ImageToolbox", "Failed to convert $uri", it)
                }
            onProgress?.invoke(index + 1, uris.size)
        }
        return results
    }

    fun imagesToPdf(
        context: Context,
        uris: List<Uri>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): ToolboxResult {
        require(uris.isNotEmpty()) { "No images to convert to PDF" }
        val resolver = context.contentResolver
        val document = android.graphics.pdf.PdfDocument()
        var totalOriginalBytes = 0L

        try {
            uris.forEachIndexed { index, uri ->
                val (bitmap, originalBytes, _) = decode(uri, resolver)
                if (originalBytes != null) totalOriginalBytes += originalBytes

                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    bitmap.width, bitmap.height, index + 1
                ).create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                document.finishPage(page)
                bitmap.recycle()

                onProgress?.invoke(index + 1, uris.size)
            }

            val out = ByteArrayOutputStream()
            document.writeTo(out)
            val data = out.toByteArray()

            val baseName = if (uris.size == 1) {
                uris.first().lastPathSegment ?: "document"
            } else {
                "glimpse_collection"
            }
            val outName = buildName(baseName, "converted", "pdf")
            val uri = writePdfToMediaStore(context, data, outName)

            return ToolboxResult(
                uri = uri,
                displayName = outName,
                originalBytes = if (totalOriginalBytes > 0) totalOriginalBytes else null,
                resultBytes = data.size.toLong(),
            )
        } finally {
            document.close()
        }
    }

    fun batchEdit(
        context: Context,
        uris: List<Uri>,
        rotation: Int,
        filter: ImageFilter,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
    ): List<ToolboxResult> {
        val results = mutableListOf<ToolboxResult>()
        uris.forEachIndexed { index, uri ->
            runCatching {
                edit(context, uri, rotation, filter)
            }.onSuccess { results.add(it) }
                .onFailure {
                    Log.e("ImageToolbox", "Failed to edit $uri", it)
                }
            onProgress?.invoke(index + 1, uris.size)
        }
        return results
    }

    // ----------------------------------------------------------------------
    // 5. Video -> GIF
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
        maxFrames: Int = 36,
        onProgress: ((Float) -> Unit)? = null,
    ): ToolboxResult {
        val resolver = context.contentResolver
        val retriever = MediaMetadataRetriever()
        var fd: android.content.res.AssetFileDescriptor? = null
        try {
            try {
                fd = resolver.openAssetFileDescriptor(sourceUri, "r")
                if (fd != null) {
                    retriever.setDataSource(fd.fileDescriptor)
                } else {
                    retriever.setDataSource(context, sourceUri)
                }
            } catch (e: Exception) {
                try { retriever.setDataSource(context, sourceUri) } catch (_: Exception) { throw e }
            }

            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val total = durationMs.coerceAtLeast(1L)
            val windowMs = (durationSeconds * 1000L).coerceAtMost(total)

            val intervalUs = (1_000_000L / fps.coerceIn(1, 15)).coerceAtLeast(1L)
            val frames = ArrayList<GifCodec.GifFrame>()

            val frameDelayMs = (1000 / fps.coerceIn(1, 15)).toInt().coerceAtLeast(33)
            var tUs = 0L
            var consecutiveNulls = 0
            while (tUs < windowMs * 1000L && frames.size < maxFrames) {
                var bmp: Bitmap? = null
                try {
                    bmp = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    Log.w("ImageToolbox", "getFrameAtTime failed at $tUs us", e)
                }
                if (bmp != null) {
                    val scaled = scaleDown(bmp, maxEdge)
                    if (scaled !== bmp) bmp.recycle()
                    frames.add(GifCodec.GifFrame(scaled, frameDelayMs))
                    consecutiveNulls = 0
                } else {
                    consecutiveNulls++
                    if (consecutiveNulls > 6) {
                        tUs += intervalUs * 2
                        consecutiveNulls = 0
                        onProgress?.invoke((tUs.toFloat() / (windowMs * 1000f)).coerceIn(0f, 1f))
                        continue
                    }
                }
                onProgress?.invoke((tUs.toFloat() / (windowMs * 1000f)).coerceIn(0f, 1f))
                tUs += intervalUs
            }
            if (frames.isEmpty()) {
                try {
                    val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        val scaled = scaleDown(bmp, maxEdge)
                        if (scaled !== bmp) bmp.recycle()
                        frames.add(GifCodec.GifFrame(scaled, frameDelayMs))
                    }
                } catch (e: Exception) {
                    Log.w("ImageToolbox", "fallback frame at 0 failed", e)
                }
            }
            if (frames.isEmpty()) throw IllegalStateException("No frames extracted — video may be DRM or unsupported: $sourceUri")

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
            try { fd?.close() } catch (_: Exception) {}
            retriever.release()
        }
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    /**
     * Applies a repeating diagonal semi-transparent privacy watermark across the bitmap,
     * styled like Xiaomi's privacy watermark to protect sensitive IDs and documents.
     */
    fun applyWatermark(source: Bitmap, text: String): Bitmap {
        if (text.isBlank()) return source
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        val diagonal = kotlin.math.hypot(w, h)
        // Scaled proportionally to bitmap width
        val baseSize = (w / 24f).coerceIn(24f, 96f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = baseSize
            color = Color.argb(90, 255, 255, 255)
            style = Paint.Style.FILL
            isFakeBoldText = true
            setShadowLayer(3f, 1.5f, 1.5f, Color.argb(140, 0, 0, 0))
        }

        val textWidth = paint.measureText(text)
        val textHeight = paint.fontMetrics.let { it.bottom - it.top }
        val stepX = textWidth + baseSize * 3.2f
        val stepY = textHeight + baseSize * 3.8f

        val cx = w / 2f
        val cy = h / 2f
        canvas.save()
        canvas.rotate(-30f, cx, cy)

        val startX = cx - diagonal
        val endX = cx + diagonal
        val startY = cy - diagonal
        val endY = cy + diagonal

        var y = startY
        var row = 0
        while (y <= endY) {
            val rowOffset = if (row % 2 == 0) 0f else stepX / 2f
            var x = startX + rowOffset
            while (x <= endX) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restore()
        return out
    }

    internal fun decode(sourceUri: Uri, resolver: ContentResolver): Triple<Bitmap, Long?, String> {
        val originalBytes = resolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(sourceUri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxEdge = 4096
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
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

    internal fun writeBitmapToMediaStore(
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

    private fun writePdfToMediaStore(
        context: Context,
        data: ByteArray,
        displayName: String,
    ): Uri {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/Glimpse")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed for PDF")
        resolver.openOutputStream(uri).use { stream: OutputStream? ->
            requireNotNull(stream) { "Cannot open output stream for $uri" }
            stream.write(data)
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
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

    internal fun buildName(source: String, suffix: String, ext: String): String {
        val base = source.substringBeforeLast('.').takeIf { it.isNotBlank() } ?: "image"
        val unique = "_${System.currentTimeMillis() % 100000}"
        return "${base}_${suffix}$unique.$ext"
    }
}
