/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.utils.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import java.io.ByteArrayOutputStream
import java.io.File

object IdPhotoGenerator {

    /**
     * Resample a cropped bitmap to target dimensions using bilinear/bicubic filtering.
     */
    fun resample(sourceCropped: Bitmap, targetW: Int, targetH: Int): Bitmap {
        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            isDither = true
        }
        val matrix = Matrix()
        matrix.setScale(targetW.toFloat() / sourceCropped.width, targetH.toFloat() / sourceCropped.height)
        canvas.drawBitmap(sourceCropped, matrix, paint)
        return result
    }

    /**
     * Zero-dependency binary patch on JPEG byte stream to inject JFIF APP0 density header.
     * Sets units = 1 (DPI), Xdensity = dpi, Ydensity = dpi.
     * If Skia emitted no APP0 marker, returns bytes unmodified (EXIF-only fallback).
     */
    fun patchJfifApp0Density(jpegBytes: ByteArray, dpi: Int): ByteArray {
        if (jpegBytes.size < 18 || jpegBytes[0] != 0xFF.toByte() || jpegBytes[1] != 0xD8.toByte()) {
            return jpegBytes
        }
        if (jpegBytes[2] == 0xFF.toByte() && jpegBytes[3] == 0xE0.toByte()) {
            if (jpegBytes[6] == 'J'.code.toByte() &&
                jpegBytes[7] == 'F'.code.toByte() &&
                jpegBytes[8] == 'I'.code.toByte() &&
                jpegBytes[9] == 'F'.code.toByte() &&
                jpegBytes[10] == 0.toByte()
            ) {
                jpegBytes[13] = 1.toByte() // 1 = Dots Per Inch
                jpegBytes[14] = ((dpi shr 8) and 0xFF).toByte()
                jpegBytes[15] = (dpi and 0xFF).toByte()
                jpegBytes[16] = ((dpi shr 8) and 0xFF).toByte()
                jpegBytes[17] = (dpi and 0xFF).toByte()
            }
        }
        return jpegBytes
    }

    /**
     * Executes the end-to-end ID photo generation pipeline:
     * 1. Decodes full-resolution source bitmap via ImageToolbox.decode;
     * 2. Crops according to normalized crop coordinates (from CropImageView viewport);
     * 3. Resamples cropped region to preset.targetWidthPx x preset.targetHeightPx;
     * 4. Encodes to JPEG at high quality (95);
     * 5. Injects JFIF APP0 density header and EXIF resolution tags for preset.dpi;
     * 6. Writes result to Pictures/Glimpse via ImageToolbox.writeBitmapToMediaStore.
     */
    fun generate(
        context: Context,
        sourceUri: Uri,
        cropRectNormalized: RectF,
        preset: IdPhotoPreset,
    ): Uri {
        val (sourceBitmap, _, sourceName) = ImageToolbox.decode(sourceUri, context.contentResolver)

        val srcW = sourceBitmap.width
        val srcH = sourceBitmap.height

        val left = (cropRectNormalized.left * srcW).toInt().coerceIn(0, srcW - 1)
        val top = (cropRectNormalized.top * srcH).toInt().coerceIn(0, srcH - 1)
        val right = (cropRectNormalized.right * srcW).toInt().coerceIn(left + 1, srcW)
        val bottom = (cropRectNormalized.bottom * srcH).toInt().coerceIn(top + 1, srcH)
        val cropW = right - left
        val cropH = bottom - top

        val cropped = Bitmap.createBitmap(sourceBitmap, left, top, cropW, cropH)
        val resampled = if (cropped.width == preset.targetWidthPx && cropped.height == preset.targetHeightPx) {
            cropped
        } else {
            resample(cropped, preset.targetWidthPx, preset.targetHeightPx).also {
                if (it !== cropped) cropped.recycle()
            }
        }

        val rawJpegStream = ByteArrayOutputStream()
        resampled.compress(Bitmap.CompressFormat.JPEG, 95, rawJpegStream)
        val rawBytes = rawJpegStream.toByteArray()
        val jfifBytes = patchJfifApp0Density(rawBytes, preset.dpi)

        val tempFile = File.createTempFile("id_photo_exif", ".jpg", context.cacheDir)
        val finalBytes: ByteArray
        try {
            tempFile.writeBytes(jfifBytes)
            val exif = ExifInterface(tempFile)
            exif.setAttribute(ExifInterface.TAG_X_RESOLUTION, "${preset.dpi}/1")
            exif.setAttribute(ExifInterface.TAG_Y_RESOLUTION, "${preset.dpi}/1")
            exif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT, "2") // 2 = Inches
            exif.saveAttributes()
            finalBytes = patchJfifApp0Density(tempFile.readBytes(), preset.dpi)
        } finally {
            tempFile.delete()
            resampled.recycle()
            sourceBitmap.recycle()
        }

        val presetName = context.getString(preset.nameRes)
        val displayName = ImageToolbox.buildName(sourceName, presetName, "jpg")
        return ImageToolbox.writeBitmapToMediaStore(
            context = context,
            data = finalBytes,
            displayName = displayName,
            mimeType = "image/jpeg",
            relativePath = "Pictures/Glimpse",
        )
    }
}
