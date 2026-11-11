/* Offline collage editor entered from the album selection action bar.
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.glimpse.collage

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.lineageos.glimpse.R

class CollageActivity : AppCompatActivity(R.layout.activity_collage) {
    private lateinit var preview: CollageView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var zoom: Slider
    private lateinit var save: MaterialButton
    private lateinit var previous: MaterialButton
    private lateinit var next: MaterialButton
    private lateinit var layoutOne: MaterialButton
    private lateinit var layoutTwo: MaterialButton
    private lateinit var selectNext: MaterialButton
    private var job: Job? = null
    private var loading = true
    private var syncingZoom = false
    private var uris = arrayListOf<Uri>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.collageRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        preview = findViewById(R.id.collagePreview)
        progress = findViewById(R.id.collageProgress)
        zoom = findViewById(R.id.collageZoom)
        save = findViewById(R.id.collageSave)
        previous = findViewById(R.id.collagePrevious)
        next = findViewById(R.id.collageNext)
        layoutOne = findViewById(R.id.collageLayoutOne)
        layoutTwo = findViewById(R.id.collageLayoutTwo)
        selectNext = findViewById(R.id.collageSelectNext)
        uris = savedInstanceState?.getParcelableArrayList(KEY_URIS)
            ?: intent.getParcelableArrayListExtra(KEY_URIS) ?: arrayListOf()
        if (uris.size !in 2..6) { finish(); return }
        preview.layout = savedInstanceState?.getInt(KEY_LAYOUT, 0) ?: 0
        preview.selected = (savedInstanceState?.getInt(KEY_SELECTED, 0) ?: 0).coerceIn(0, uris.lastIndex)
        preview.onSelectionChanged = { updateControls() }
        layoutOne.setOnClickListener { preview.layout = 0; preview.invalidate(); updateControls() }
        layoutTwo.setOnClickListener { preview.layout = 1; preview.invalidate(); updateControls() }
        selectNext.setOnClickListener {
            if (preview.photos.isNotEmpty()) {
                preview.selected = (preview.selected + 1) % preview.photos.size
                preview.invalidate()
                updateControls()
            }
        }
        previous.setOnClickListener { move(-1) }
        next.setOnClickListener { move(1) }
        zoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !syncingZoom) {
                preview.photos.getOrNull(preview.selected)?.zoom = value
                preview.invalidate()
            }
        }
        findViewById<View>(R.id.collageCancel).setOnClickListener { cancel() }
        onBackPressedDispatcher.addCallback(this) { cancel() }
        save.setOnClickListener { saveCollage() }
        val state = savedInstanceState?.getFloatArray(KEY_TRANSFORMS)
        setBusy(true)
        job = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    uris.mapIndexed { index, uri ->
                        coroutineContext.ensureActive()
                        val bmp = decodeCollageImage(contentResolver, uri, 768)
                        CollagePhoto(uri, bmp,
                            state?.getOrNull(index * 3) ?: 1f,
                            state?.getOrNull(index * 3 + 1) ?: 0f,
                            state?.getOrNull(index * 3 + 2) ?: 0f)
                    }.toMutableList()
                }
                preview.photos = loaded
                preview.invalidate()
                loading = false
                job = null
                setBusy(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@CollageActivity, R.string.collage_load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun updateControls() {
        val photo = preview.photos.getOrNull(preview.selected)
        syncingZoom = true
        zoom.value = photo?.zoom?.coerceIn(1f, 4f) ?: 1f
        syncingZoom = false
        previous.isEnabled = photo != null && preview.selected > 0 && job == null
        next.isEnabled = photo != null && preview.selected < preview.photos.lastIndex && job == null
        findViewById<TextView>(R.id.collageHint).text = if (photo == null) {
            getString(R.string.collage_preview_hint)
        } else {
            getString(R.string.collage_selected_photo, preview.selected + 1, preview.photos.size) + "\n" +
                getString(R.string.collage_preview_hint)
        }
        layoutOne.isSelected = preview.layout == 0
        layoutTwo.isSelected = preview.layout == 1
        layoutOne.strokeWidth = if (preview.layout == 0) (2 * resources.displayMetrics.density).toInt() else 0
        layoutTwo.strokeWidth = if (preview.layout == 1) (2 * resources.displayMetrics.density).toInt() else 0
    }

    private fun move(delta: Int) {
        val from = preview.selected
        val to = from + delta
        if (to !in preview.photos.indices) return
        java.util.Collections.swap(preview.photos, from, to)
        preview.selected = to
        uris = ArrayList(preview.photos.map { it.uri })
        preview.invalidate()
        updateControls()
    }

    private fun setBusy(busy: Boolean) {
        progress.isVisible = busy
        save.isEnabled = !busy
        preview.isEnabled = !busy
        zoom.isEnabled = !busy
        layoutOne.isEnabled = !busy
        layoutTwo.isEnabled = !busy
        selectNext.isEnabled = !busy
        previous.isEnabled = !busy && preview.selected > 0
        next.isEnabled = !busy && preview.selected < preview.photos.lastIndex
        if (!busy) updateControls()
    }

    private fun cancel() {
        if (job != null && !loading) {
            // Keep controls disabled until the IO worker has closed and removed its pending row.
            job?.cancel()
        } else {
            job?.cancel()
            finish()
        }
    }

    private fun saveCollage() {
        if (job != null || preview.photos.size !in 2..6) return
        // Snapshot mutable UI state before crossing dispatchers.
        val photos = preview.photos.map { it.copy() }
        val layout = preview.layout
        setBusy(true)
        job = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { renderAndSave(photos, layout) }
                job = null
                Toast.makeText(this@CollageActivity, R.string.collage_saved, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } catch (e: CancellationException) {
                if (!isFinishing && !isDestroyed) {
                    Toast.makeText(this@CollageActivity, R.string.collage_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@CollageActivity, R.string.collage_save_failed, Toast.LENGTH_LONG).show()
            } finally {
                job = null
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private suspend fun renderAndSave(photos: List<CollagePhoto>, layout: Int) {
        val edge = 1800
        val output = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        var target: Uri? = null
        try {
            val canvas = Canvas(output)
            collageCells(photos.size, layout).forEachIndexed { index, cell ->
                coroutineContext.ensureActive()
                val photo = photos[index]
                val bitmap = decodeCollageImage(contentResolver, photo.uri, edge)
                try {
                    drawCollagePhoto(canvas, bitmap, RectF(cell.left * edge, cell.top * edge,
                        cell.right * edge, cell.bottom * edge), photo.zoom, photo.offsetX, photo.offsetY)
                } finally { bitmap.recycle() }
            }
            coroutineContext.ensureActive()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Glimpse_Collage_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Glimpse")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Unable to create image")
            target = uri
            contentResolver.openOutputStream(uri, "w")?.use {
                check(output.compress(Bitmap.CompressFormat.JPEG, 95, it))
            } ?: error("Unable to open image")
            coroutineContext.ensureActive()
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(contentResolver.update(uri, values, null, null) == 1)
            target = null
        } finally {
            target?.let { contentResolver.delete(it, null, null) }
            output.recycle()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelableArrayList(KEY_URIS, uris)
        outState.putInt(KEY_LAYOUT, preview.layout)
        outState.putInt(KEY_SELECTED, preview.selected)
        if (preview.photos.isNotEmpty()) outState.putFloatArray(KEY_TRANSFORMS,
            preview.photos.flatMap { listOf(it.zoom, it.offsetX, it.offsetY) }.toFloatArray())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_URIS = "collage_uris"
        private const val KEY_LAYOUT = "collage_layout"
        private const val KEY_SELECTED = "collage_selected"
        private const val KEY_TRANSFORMS = "collage_transforms"
        fun createIntent(context: Context, uris: List<Uri>) = Intent(context, CollageActivity::class.java)
            .putParcelableArrayListExtra(KEY_URIS, ArrayList(uris))
    }
}
