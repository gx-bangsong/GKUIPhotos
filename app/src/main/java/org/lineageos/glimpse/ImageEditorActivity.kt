/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.ext.editSaveBehavior
import org.lineageos.glimpse.ext.createWriteRequest
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.utils.EditSaveBehavior
import org.lineageos.glimpse.utils.media.ImageFilter
import java.io.ByteArrayOutputStream

/**
 * Modules 4 &amp; 5: an offline, fully on-device image editor.
 *
 * Features:
 *  - Rotate (90° steps)
 *  - Pure-offline filters ([ImageFilter], built from [android.graphics.ColorMatrix])
 *  - Interactive crop with a rule-of-thirds + dashed face-alignment overlay, and
 *    preset ID-photo aspect ratios (module 5).
 *  - Flexible save: "ask every time" / "always overwrite" / "always save as new",
 *    driven by the global preference (module 4). Overwriting uses the
 *    [MediaStore.createWriteRequest] consent flow; saving-as-new inserts a fresh
 *    entry under Pictures/Glimpse.
 */
class ImageEditorActivity : AppCompatActivity(R.layout.activity_image_editor) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val cropImageView by lazy { findViewById<CropImageView>(R.id.cropImageView) }
    private val filterChipGroup by lazy { findViewById<ChipGroup>(R.id.filterChipGroup) }
    private val ratioChipGroup by lazy { findViewById<ChipGroup>(R.id.ratioChipGroup) }
    private val rotateButton by lazy { findViewById<android.widget.Button>(R.id.rotateButton) }
    private val faceGuideButton by lazy { findViewById<android.widget.Button>(R.id.faceGuideButton) }
    private val savingOverlay by lazy { findViewById<android.view.View>(R.id.savingOverlay) }

    private var sourceUri: Uri? = null
    private var sourceMimeType: String? = null

    /** Rotated (but unfiltered) source. */
    private var baseBitmap: Bitmap? = null
    private var rotation = 0
    private var currentFilter: ImageFilter = ImageFilter.NONE

    /** Pending bytes to write once a write request is granted (overwrite path). */
    private var pendingOverwrite: ByteArray? = null

    private val writeRequestContract =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val data = pendingOverwrite ?: return@registerForActivityResult
            pendingOverwrite = null
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = sourceUri ?: return@registerForActivityResult
                lifecycleScope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri, "wt")?.use { it.write(data) }
                        }
                    }.onSuccess { finishWithSaved() }
                        .onFailure { showFailed() }
                }
            } else {
                hideSaving()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceUri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_MEDIA_URI)
        sourceMimeType = intent.type ?: intent.getStringExtra(EXTRA_MIME_TYPE) ?: "image/jpeg"
        val uri = sourceUri
        if (uri == null) {
            finish()
            return
        }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onMenuItemSelected)

        rotateButton.setOnClickListener {
            rotation = (rotation + 90) % 360
            refreshBaseBitmap()
        }
        faceGuideButton.setOnClickListener {
            cropImageView.showFaceGuide = !cropImageView.showFaceGuide
            cropImageView.invalidate()
        }

        setupFilterChips()
        setupRatioChips()

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadBitmap(uri) }
            if (bmp == null) {
                showFailed()
                finish()
                return@launch
            }
            baseBitmap = bmp
            cropImageView.aspectRatio = bmp.width.toFloat() / bmp.height.toFloat()
            refreshBaseBitmap()
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            onSave()
            return true
        }
        return false
    }

    private fun setupFilterChips() {
        val filters = listOf(
            ImageFilter.NONE to R.string.editor_filter_none,
            ImageFilter.GRAYSCALE to R.string.editor_filter_grayscale,
            ImageFilter.SEPIA to R.string.editor_filter_sepia,
            ImageFilter.INVERT to R.string.editor_filter_invert,
            ImageFilter.WARM to R.string.editor_filter_warm,
            ImageFilter.COOL to R.string.editor_filter_cool,
        )
        filters.forEachIndexed { index, (filter, label) ->
            val chip = Chip(this).apply {
                text = getString(label)
                isCheckable = true
                id = index
                isChecked = filter == ImageFilter.NONE
            }
            chip.setOnClickListener {
                currentFilter = filter
                refreshBaseBitmap()
            }
            filterChipGroup.addView(chip)
        }
    }

    private fun setupRatioChips() {
        data class Ratio(val labelRes: Int, val ratio: Float, val faceGuide: Boolean)
        val ratios = listOf(
            Ratio(R.string.editor_crop, 0f, false), // 0 = match image (free)
            Ratio(R.string.id_photo_preset_one_inch, 25f / 35f, true),
            Ratio(R.string.id_photo_preset_two_inch, 35f / 49f, true),
            Ratio(R.string.id_photo_preset_passport, 35f / 45f, true),
            Ratio(R.string.id_photo_preset_square, 1f, false),
        )
        ratios.forEachIndexed { index, r ->
            val chip = Chip(this).apply {
                text = getString(r.labelRes)
                isCheckable = true
                id = index
                isChecked = index == 0
            }
            chip.setOnClickListener {
                val bmp = baseBitmap ?: return@setOnClickListener
                cropImageView.aspectRatio = if (r.ratio == 0f) {
                    bmp.width.toFloat() / bmp.height.toFloat()
                } else r.ratio
                cropImageView.showFaceGuide = r.faceGuide
                cropImageView.reset()
            }
            ratioChipGroup.addView(chip)
        }
    }

    /** Rebuild the displayed (rotated + filtered) bitmap on the crop view. */
    private fun refreshBaseBitmap() {
        val bmp = baseBitmap ?: return
        lifecycleScope.launch {
            val displayed = withContext(Dispatchers.Default) {
                val rotated = rotate(bmp, rotation)
                applyFilter(rotated, currentFilter)
            }
            cropImageView.setBitmap(displayed)
        }
    }

    private fun onSave() {
        val result = cropImageView.crop() ?: baseBitmap ?: return
        showSaving()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                encode(result, sourceMimeType ?: "image/jpeg")
            }
            val behavior = PreferenceManager.getDefaultSharedPreferences(this@ImageEditorActivity)
                .editSaveBehavior
            when (behavior) {
                EditSaveBehavior.ALWAYS_SAVE_AS_NEW -> saveAsNew(bytes)
                EditSaveBehavior.ALWAYS_OVERWRITE -> overwrite(bytes)
                EditSaveBehavior.ASK -> askAndSave(bytes)
            }
        }
    }

    private fun askAndSave(bytes: ByteArray) {
        hideSaving()
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_save_dialog_title)
            .setMessage(null)
            .setPositiveButton(R.string.editor_save_overwrite) { _, _ ->
                showSaving(); overwrite(bytes)
            }
            .setNegativeButton(R.string.editor_save_new) { _, _ ->
                showSaving(); saveAsNew(bytes)
            }
            .show()
    }

    private fun overwrite(bytes: ByteArray) {
        val uri = sourceUri ?: run { hideSaving(); return }
        // Fast path: try to write directly (works for app-owned media).
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    true
                }.getOrDefault(false)
            }
            if (written) {
                finishWithSaved()
            } else {
                // Fallback: request user consent via the system write request.
                pendingOverwrite = bytes
                try {
                    writeRequestContract.launch(contentResolver.createWriteRequest(uri))
                } catch (e: Exception) {
                    pendingOverwrite = null
                    hideSaving()
                    showFailed()
                }
            }
        }
    }

    private fun saveAsNew(bytes: ByteArray) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeToMediaStore(bytes) }.isSuccess
            }
            if (ok) finishWithSaved() else showFailed()
        }
    }

    private fun writeToMediaStore(bytes: ByteArray): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "Glimpse_Edit_${System.currentTimeMillis()}"
        val (mime, ext) = if (sourceMimeType?.contains("png") == true) {
            "image/png" to "png"
        } else {
            "image/jpeg" to "jpg"
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Glimpse")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun finishWithSaved() {
        hideSaving()
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun showFailed() {
        hideSaving()
        Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
    }

    private fun showSaving() {
        savingOverlay.isVisible = true
    }

    private fun hideSaving() {
        savingOverlay.isVisible = false
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        // Decode bounds first to downsample very large images and avoid OOM.
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxEdge = 2048
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        opts.inSampleSize = sample
        return contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyFilter(source: Bitmap, filter: ImageFilter): Bitmap {
        if (filter == ImageFilter.NONE) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return out
    }

    private fun encode(bitmap: Bitmap, mimeType: String): ByteArray {
        val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
        else if (mimeType.contains("webp")) Bitmap.CompressFormat.WEBP
        else Bitmap.CompressFormat.JPEG
        val out = ByteArrayOutputStream()
        bitmap.compress(format, 95, out)
        return out.toByteArray()
    }

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MIME_TYPE = "mime_type"

        fun createIntent(context: android.content.Context, uri: Uri, mimeType: String?) =
            Intent(context, ImageEditorActivity::class.java).apply {
                // IMPORTANT: setData() and setType() each clear the other, so both
                // the URI and the MIME type must be set together via setDataAndType.
                // (Setting them separately left intent.data == null, which made the
                // editor finish() immediately in onCreate.)
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                // Belt-and-suspenders: also carry the URI as an extra.
                putExtra(EXTRA_MEDIA_URI, uri)
            }
    }
}
