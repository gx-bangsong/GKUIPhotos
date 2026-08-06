/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.utils.media.GifCodec
import org.lineageos.glimpse.utils.media.ImageFilter
import java.io.ByteArrayOutputStream

/**
 * Module 7: a full-featured, fully-offline GIF editor.
 *
 * Animated GIFs are decoded into individual frames (via the bundled [GifCodec]),
 * surfaced through a frame scrubber for per-frame preview, and can be:
 *  - re-timed (faster / slower),
 *  - trimmed in time (start / end frame),
 *  - rotated / filtered (applied to every frame, like a still image),
 *  before being re-encoded locally into a new animated GIF.
 *
 * Because it only relies on the framework + the self-contained [GifCodec], the
 * whole pipeline runs with zero network access.
 */
class GifEditorActivity : AppCompatActivity(R.layout.activity_gif_editor) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val preview by lazy { findViewById<ImageView>(R.id.gifPreview) }
    private val loadingIndicator by lazy { findViewById<CircularProgressIndicator>(R.id.loadingIndicator) }
    private val frameSeekBar by lazy { findViewById<SeekBar>(R.id.frameSeekBar) }
    private val trimStartSeekBar by lazy { findViewById<SeekBar>(R.id.trimStartSeekBar) }
    private val trimEndSeekBar by lazy { findViewById<SeekBar>(R.id.trimEndSeekBar) }
    private val speedLabel by lazy { findViewById<TextView>(R.id.speedLabel) }
    private val speedSlowerButton by lazy { findViewById<android.widget.Button>(R.id.speedSlowerButton) }
    private val speedFasterButton by lazy { findViewById<android.widget.Button>(R.id.speedFasterButton) }
    private val rotateButton by lazy { findViewById<android.widget.Button>(R.id.rotateButton) }
    private val filterButton by lazy { findViewById<android.widget.Button>(R.id.filterButton) }

    private var sourceUri: Uri? = null

    private var frames: List<GifCodec.GifFrame> = emptyList()
    private var speedMultiplier = 1.0f
    private var rotation = 0
    private var filter: ImageFilter = ImageFilter.NONE

    private val handler = Handler(Looper.getMainLooper())
    private var previewIndex = 0
    private var isPlaying = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (frames.isEmpty()) return
            preview.setImageBitmap(frames[previewIndex.coerceIn(0, frames.lastIndex)].bitmap)
            frameSeekBar.progress = previewIndex
            previewIndex = (previewIndex + 1).coerceAtMost(frames.lastIndex)
            if (previewIndex >= frames.lastIndex) previewIndex = 0
            val delay = (frames.getOrNull(previewIndex)?.delayMs ?: 100)
            val adjusted = (delay / speedMultiplier).toLong().coerceIn(16L, 1000L)
            handler.postDelayed(this, adjusted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourceUri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_MEDIA_URI)
        val uri = sourceUri
        if (uri == null) { finish(); return }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setOnMenuItemClickListener(::onMenuItemSelected)

        speedSlowerButton.setOnClickListener { changeSpeed(0.5f) }
        speedFasterButton.setOnClickListener { changeSpeed(2.0f) }
        rotateButton.setOnClickListener { rotation = (rotation + 90) % 360; applyStaticTransform() }
        filterButton.setOnClickListener { pickFilter() }

        frameSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    pausePreview()
                    frames.getOrNull(progress)?.let { preview.setImageBitmap(it.bitmap) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { pausePreview() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        trimStartSeekBar.max = 100
        trimEndSeekBar.max = 100
        trimEndSeekBar.progress = 100

        loadingIndicator.show()
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.Default) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { GifCodec.decode(it) }
                }.getOrNull()
            }
            loadingIndicator.hide()
            if (decoded.isNullOrEmpty()) {
                Toast.makeText(this@GifEditorActivity, R.string.gif_editor_not_a_gif, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            frames = decoded
            frameSeekBar.max = frames.lastIndex
            startPreview()
        }
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_export) {
            onExport()
            return true
        }
        return false
    }

    private fun changeSpeed(factor: Float) {
        speedMultiplier = (speedMultiplier * factor).coerceIn(0.25f, 8f)
        speedLabel.text = speedLabel(speedMultiplier)
    }

    private fun speedLabel(s: Float): String {
        val v = if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()
        return "${v}x"
    }

    private fun pickFilter() {
        val labels = arrayOf(
            getString(R.string.editor_filter_none),
            getString(R.string.editor_filter_grayscale),
            getString(R.string.editor_filter_sepia),
            getString(R.string.editor_filter_invert),
        )
        val filters = arrayOf(ImageFilter.NONE, ImageFilter.GRAYSCALE, ImageFilter.SEPIA, ImageFilter.INVERT)
        AlertDialog.Builder(this)
            .setTitle(R.string.editor_filter)
            .setItems(labels) { _, which ->
                filter = filters[which]
                applyStaticTransform()
            }
            .show()
    }

    private fun applyStaticTransform() {
        if (frames.isEmpty()) return
        val first = frames[0].bitmap
        preview.setImageBitmap(transform(first))
    }

    private fun transform(src: Bitmap): Bitmap {
        var out = src
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            out = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
        }
        if (filter != ImageFilter.NONE) {
            val filtered = Bitmap.createBitmap(out.width, out.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(filtered)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
            }
            canvas.drawBitmap(out, 0f, 0f, paint)
            out = filtered
        }
        return out
    }

    private fun startPreview() {
        if (isPlaying || frames.isEmpty()) return
        isPlaying = true
        previewIndex = 0
        handler.post(frameRunnable)
    }

    private fun pausePreview() {
        isPlaying = false
        handler.removeCallbacks(frameRunnable)
    }

    override fun onPause() {
        pausePreview()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        startPreview()
    }

    private fun onExport() {
        pausePreview()
        if (frames.isEmpty()) return

        // Compute the trimmed, re-timed, transformed frame set on a background
        // thread, then encode and write to the MediaStore.
        loadingIndicator.show()
        lifecycleScope.launch {
            val data = withContext(Dispatchers.Default) {
                val startRatio = trimStartSeekBar.progress / 100f
                val endRatio = trimEndSeekBar.progress / 100f
                val total = frames.size
                val start = (startRatio * total).toInt().coerceIn(0, total - 1)
                val end = (endRatio * total).toInt().coerceIn(start + 1, total)
                val sub = frames.subList(start, end)
                val processed = sub.map { f ->
                    GifCodec.GifFrame(transform(f.bitmap), (f.delayMs / speedMultiplier).toInt().coerceAtLeast(20))
                }
                val out = ByteArrayOutputStream()
                runCatching { GifCodec.encode(processed, out) }.getOrNull()?.let { out.toByteArray() }
            }
            loadingIndicator.hide()
            if (data == null) {
                Toast.makeText(this@GifEditorActivity, R.string.gif_editor_export_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching { writeToMediaStore(data) }.isSuccess
            }
            Toast.makeText(
                this@GifEditorActivity,
                if (ok) R.string.gif_editor_exported else R.string.gif_editor_export_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (ok) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
    }

    private fun writeToMediaStore(bytes: ByteArray): Uri {
        val collection = android.provider.MediaStore.Images.Media
            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "Glimpse_GIF_${System.currentTimeMillis()}"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.gif")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                "${android.os.Environment.DIRECTORY_PICTURES}/Glimpse"
            )
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"

        fun createIntent(context: android.content.Context, uri: Uri) =
            Intent(context, GifEditorActivity::class.java).apply { data = uri }
    }
}
