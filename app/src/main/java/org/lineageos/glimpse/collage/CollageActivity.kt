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
import android.view.ViewGroup
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
    private lateinit var replace: MaterialButton
    private lateinit var filmstrip: RecyclerView
    private lateinit var filmstripAdapter: CollageFilmstripAdapter
    private lateinit var layoutCards: LinearLayout
    private lateinit var modes: MaterialButtonToggleGroup
    private var job: Job? = null
    private var loading = true
    private var syncingZoom = false
    private var renderedSelected = -1
    private var renderedCount = -1
    private var uris = arrayListOf<Uri>()
    private var replacementIndex = -1
    private var pendingReplacement: Uri? = null
    private val replacePhoto = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null && replacementIndex in uris.indices) {
            try {
                val type = contentResolver.getType(uri)
                require(type == null || type.startsWith("image/"))
                if (data.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                pendingReplacement = uri
                if (!loading) applyPendingReplacement()
            } catch (e: Exception) {
                replacementIndex = -1
                Toast.makeText(this, R.string.collage_replace_failed, Toast.LENGTH_LONG).show()
            }
        } else {
            replacementIndex = -1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = findViewById<View>(R.id.collageRoot)
        // The edge-to-edge status bar overlays the actual surface, not the system theme.
        val surface = MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = ColorUtils.calculateLuminance(surface) > 0.5
            isAppearanceLightNavigationBars = ColorUtils.calculateLuminance(surface) > 0.5
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        preview = findViewById(R.id.collagePreview)
        progress = findViewById(R.id.collageProgress)
        zoom = findViewById(R.id.collageZoom)
        save = findViewById(R.id.collageSave)
        replace = findViewById(R.id.collageReplace)
        replacementIndex = savedInstanceState?.getInt(KEY_REPLACEMENT_INDEX, -1) ?: -1
        pendingReplacement = savedInstanceState?.getParcelable(KEY_PENDING_REPLACEMENT)
        filmstrip = findViewById(R.id.collageFilmstrip)
        layoutCards = findViewById(R.id.collageLayoutCards)
        modes = findViewById(R.id.collageModes)
        modes.addOnButtonCheckedListener { _, checkedId, checked ->
            if (checked) showMode(checkedId == R.id.collageModeLayout)
        }
        val showLayouts = savedInstanceState?.getBoolean(KEY_LAYOUT_MODE, false) ?: false
        modes.check(if (showLayouts) R.id.collageModeLayout else R.id.collageModePhoto)
        showMode(showLayouts)
        filmstripAdapter = CollageFilmstripAdapter(
            photos = { preview.photos }, selected = { preview.selected },
            select = { index ->
                if (job == null) {
                    preview.selected = index
                    preview.invalidate()
                    updateControls()
                }
            },
            move = { from, to -> movePhoto(from, to) },
        )
        filmstrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        filmstrip.adapter = filmstripAdapter
        filmstrip.itemAnimator = null
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = holder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (job != null || from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                movePhoto(from, to)
                return true
            }
            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    val position = viewHolder.bindingAdapterPosition
                    if (position in preview.photos.indices) {
                        preview.selected = position
                        renderedSelected = position
                        preview.invalidate()
                    }
                    filmstripAdapter.dragging = true
                    for (i in 0 until filmstrip.childCount) {
                        val card = filmstrip.getChildAt(i) as? MaterialCardView ?: continue
                        card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
                        card.strokeWidth = (if (card === viewHolder.itemView) 3 else 1) * resources.displayMetrics.density.toInt().coerceAtLeast(1)
                    }
                    viewHolder.itemView.alpha = 0.65f
                    viewHolder.itemView.scaleX = 1.08f
                    viewHolder.itemView.scaleY = 1.08f
                }
            }
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                filmstripAdapter.dragging = false
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                renderedSelected = -1
                updateControls()
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun isLongPressDragEnabled() = job == null
        }).attachToRecyclerView(filmstrip)
        uris = savedInstanceState?.getParcelableArrayList(KEY_URIS)
            ?: intent.getParcelableArrayListExtra(KEY_URIS) ?: arrayListOf()
        if (uris.size !in 2..6) { finish(); return }
        preview.layout = savedInstanceState?.getInt(KEY_LAYOUT, 0) ?: 0
        preview.selected = (savedInstanceState?.getInt(KEY_SELECTED, 0) ?: 0).coerceIn(0, uris.lastIndex)
        preview.onSelectionChanged = { updateControls() }
        buildLayoutCards()
        zoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !syncingZoom) {
                preview.photos.getOrNull(preview.selected)?.zoom = value
                preview.invalidate()
            }
        }
        findViewById<View>(R.id.collageCancel).setOnClickListener { cancel() }
        onBackPressedDispatcher.addCallback(this) { cancel() }
        save.setOnClickListener { saveCollage() }
        replace.setOnClickListener {
            if (job == null && preview.selected in preview.photos.indices) {
                replacementIndex = preview.selected
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                try {
                    replacePhoto.launch(intent)
                } catch (e: android.content.ActivityNotFoundException) {
                    replacementIndex = -1
                    Toast.makeText(this, R.string.collage_replace_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
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
                applyPendingReplacement()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@CollageActivity, R.string.collage_load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun applyPendingReplacement() {
        val uri = pendingReplacement ?: return
        val index = replacementIndex
        replaceAt(index, uri)
    }

    private fun replaceAt(index: Int, uri: Uri) {
        if (job != null || index !in preview.photos.indices) return
        setBusy(true)
        job = lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeCollageImage(contentResolver, uri, 768) }
                preview.photos[index] = CollagePhoto(uri, bitmap)
                uris[index] = uri
                pendingReplacement = null
                replacementIndex = -1
                preview.selected = index
                preview.invalidate()
                filmstripAdapter.notifyItemChanged(index)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingReplacement = null
                replacementIndex = -1
                Toast.makeText(this@CollageActivity, R.string.collage_replace_failed, Toast.LENGTH_LONG).show()
            } finally {
                job = null
                if (!isFinishing && !isDestroyed) setBusy(false)
            }
        }
    }

    private fun showMode(layoutMode: Boolean) {
        findViewById<View>(R.id.collagePhotoPanel).isVisible = !layoutMode
        findViewById<View>(R.id.collageLayoutPanel).isVisible = layoutMode
    }

    private fun updateControls() {
        val photo = preview.photos.getOrNull(preview.selected)
        syncingZoom = true
        zoom.value = photo?.zoom?.coerceIn(1f, 4f) ?: 1f
        syncingZoom = false
        findViewById<TextView>(R.id.collageHint).text = if (photo == null) {
            getString(R.string.collage_preview_hint)
        } else {
            getString(R.string.collage_selected_photo, preview.selected + 1, preview.photos.size)
        }
        findViewById<TextView>(R.id.collageZoomLabel).text = if (photo == null) {
            getString(R.string.collage_zoom)
        } else {
            getString(R.string.collage_zoom_selected, preview.selected + 1)
        }
        if (renderedSelected != preview.selected || renderedCount != preview.photos.size) {
            renderedSelected = preview.selected
            renderedCount = preview.photos.size
            filmstripAdapter.notifyDataSetChanged()
            if (photo != null) filmstrip.smoothScrollToPosition(preview.selected)
        }
        updateLayoutSelection()
    }

    private fun buildLayoutCards() {
        layoutCards.removeAllViews()
        val density = resources.displayMetrics.density
        listOf(R.string.collage_layout_one, R.string.collage_layout_two).forEachIndexed { index, label ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = (8 * density).toInt() }
                minimumWidth = (116 * density).toInt()
                minimumHeight = (112 * density).toInt()
                radius = 16 * density
                cardElevation = 0f
                isClickable = true
                isFocusable = true
                contentDescription = getString(R.string.collage_layout_description, getString(label), uris.size)
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant))
                setOnClickListener { preview.layout = index; preview.invalidate(); updateControls() }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            }
            content.addView(CollageLayoutPreview(this, uris.size, index),
                LinearLayout.LayoutParams((68 * density).toInt(), (68 * density).toInt()))
            content.addView(TextView(this).apply {
                text = getString(label)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                gravity = Gravity.CENTER
                maxLines = 2
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(content)
            layoutCards.addView(card)
        }
        updateLayoutSelection()
    }

    private fun updateLayoutSelection() {
        for (index in 0 until layoutCards.childCount) {
            val card = layoutCards.getChildAt(index) as MaterialCardView
            card.isSelected = index == preview.layout
            card.strokeWidth = if (card.isSelected) (2 * resources.displayMetrics.density).toInt() else 0
            card.strokeColor = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary)
            card.setCardBackgroundColor(MaterialColors.getColor(card,
                if (card.isSelected) com.google.android.material.R.attr.colorSecondaryContainer
                else com.google.android.material.R.attr.colorSurfaceVariant))
        }
    }

    private fun movePhoto(from: Int, to: Int) {
        if (job != null || from !in preview.photos.indices || to !in preview.photos.indices || from == to) return
        preview.selected = moveCollageItem(preview.photos, preview.selected, from, to)
        uris = ArrayList(preview.photos.map { it.uri })
        filmstripAdapter.notifyItemMoved(from, to)
        filmstripAdapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(to - from) + 1)
        renderedSelected = preview.selected
        preview.invalidate()
        findViewById<TextView>(R.id.collageHint).text =
            getString(R.string.collage_selected_photo, preview.selected + 1, preview.photos.size)
        findViewById<TextView>(R.id.collageZoomLabel).text =
            getString(R.string.collage_zoom_selected, preview.selected + 1)
    }

    private fun setBusy(busy: Boolean) {
        progress.isVisible = busy
        save.isEnabled = !busy
        replace.isEnabled = !busy
        preview.isEnabled = !busy
        zoom.isEnabled = !busy
        filmstrip.isEnabled = !busy
        findViewById<View>(R.id.collageModePhoto).isEnabled = !busy
        findViewById<View>(R.id.collageModeLayout).isEnabled = !busy
        for (i in 0 until layoutCards.childCount) layoutCards.getChildAt(i).isEnabled = !busy
        if (!busy) updateControls()
    }

    private fun cancel() {
        if (job != null && !loading) {
            // Keep controls disabled until the IO worker has closed and removed its pending row.
            if (pendingReplacement != null) {
                pendingReplacement = null
                replacementIndex = -1
            }
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
        outState.putInt(KEY_REPLACEMENT_INDEX, replacementIndex)
        outState.putParcelable(KEY_PENDING_REPLACEMENT, pendingReplacement)
        outState.putBoolean(KEY_LAYOUT_MODE, modes.checkedButtonId == R.id.collageModeLayout)
        outState.putInt(KEY_LAYOUT, preview.layout)
        outState.putInt(KEY_SELECTED, preview.selected)
        if (preview.photos.isNotEmpty()) outState.putFloatArray(KEY_TRANSFORMS,
            preview.photos.flatMap { listOf(it.zoom, it.offsetX, it.offsetY) }.toFloatArray())
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_PENDING_REPLACEMENT = "collage_pending_replacement"
        private const val KEY_REPLACEMENT_INDEX = "collage_replacement_index"
        private const val KEY_URIS = "collage_uris"
        private const val KEY_LAYOUT_MODE = "collage_layout_mode"
        private const val KEY_LAYOUT = "collage_layout"
        private const val KEY_SELECTED = "collage_selected"
        private const val KEY_TRANSFORMS = "collage_transforms"
        fun createIntent(context: Context, uris: List<Uri>) = Intent(context, CollageActivity::class.java)
            .putParcelableArrayListExtra(KEY_URIS, ArrayList(uris))
    }
}
