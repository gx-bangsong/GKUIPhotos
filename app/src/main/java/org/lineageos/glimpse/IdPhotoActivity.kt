/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.utils.media.IdPhotoGenerator
import org.lineageos.glimpse.viewmodels.IdPhotoViewModel

/**
 * ID Photo Tool Activity (Single live-editing surface).
 *
 * Implements Xiaomi Gallery workflow reference:
 * - Persistent center preview with locked aspect ratio, face guide oval, and rule-of-thirds grid
 * - Live tap-to-switch parameter rails beneath preview (same-ratio switches preserve framing pixel-identically)
 * - Overlay catalog picker for all 32 presets
 * - [INSERTION POINT - Slice 2]: Background color replacement rail
 * - [INSERTION POINT - Slice 3 / P2]: Print sheet tiling toggle
 */
class IdPhotoActivity : AppCompatActivity(R.layout.activity_id_photo) {

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val cropImageView by lazy { findViewById<CropImageView>(R.id.cropImageView) }
    private val specBadgeText by lazy { findViewById<TextView>(R.id.specBadgeText) }
    private val loadingIndicator by lazy { findViewById<CircularProgressIndicator>(R.id.loadingIndicator) }
    private val bottomControls by lazy { findViewById<View>(R.id.bottomControls) }
    private val allSpecsButton by lazy { findViewById<MaterialButton>(R.id.allSpecsButton) }
    private val presetRailRecyclerView by lazy { findViewById<RecyclerView>(R.id.presetRailRecyclerView) }

    // Full catalog picker overlay views
    private val catalogPickerOverlay by lazy { findViewById<View>(R.id.catalogPickerOverlay) }
    private val pickerCloseButton by lazy { findViewById<ImageButton>(R.id.pickerCloseButton) }
    private val pickerGroupChipGroup by lazy { findViewById<ChipGroup>(R.id.pickerGroupChipGroup) }
    private val pickerRecyclerView by lazy { findViewById<RecyclerView>(R.id.pickerRecyclerView) }

    private val savingOverlay by lazy { findViewById<View>(R.id.savingOverlay) }

    val viewModel: IdPhotoViewModel by viewModels()

    private var previousPresetRatio: Float? = null

    private val railAdapter = PresetRailAdapter { preset ->
        onPresetSelectedLive(preset)
    }

    private val catalogPickerAdapter = IdPhotoCatalogGridAdapter { preset ->
        onPresetSelectedLive(preset)
        viewModel.setPickerOpen(false)
    }

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"

        fun createIntent(context: Context, uri: Uri, mimeType: String? = null) =
            Intent(context, IdPhotoActivity::class.java).apply {
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                putExtra(EXTRA_MEDIA_URI, uri)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val rootLayout = findViewById<View>(R.id.idPhotoRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            bottomControls.updatePadding(bottom = navBars.bottom + 16)
            catalogPickerOverlay.updatePadding(bottom = navBars.bottom + 16)
            insets
        }

        if (viewModel.sourceUri == null) {
            val uri: Uri? = intent.data ?: intent.getParcelableExtra(EXTRA_MEDIA_URI)
            if (uri == null) {
                finish()
                return
            }
            viewModel.sourceUri = uri
        }

        viewModel.ensurePreviewBitmapLoaded(contentResolver)

        toolbar.setNavigationOnClickListener {
            handleBackNavigation()
        }
        toolbar.setOnMenuItemClickListener(::onToolbarMenuItemSelected)

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        setupCropView()
        setupPresetRail()
        setupCatalogPicker()

        allSpecsButton.setOnClickListener {
            viewModel.setPickerOpen(true)
        }

        observeViewModelState()
    }

    private fun setupCropView() {
        cropImageView.showFaceGuide = true
        cropImageView.onFramingModified = {
            viewModel.isFramingModified = true
        }
    }

    private fun setupPresetRail() {
        presetRailRecyclerView.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        presetRailRecyclerView.adapter = railAdapter
    }

    private fun setupCatalogPicker() {
        pickerCloseButton.setOnClickListener {
            viewModel.setPickerOpen(false)
        }

        pickerRecyclerView.layoutManager = GridLayoutManager(this, 2)
        pickerRecyclerView.adapter = catalogPickerAdapter

        setupCatalogGroupChips()
    }

    private fun setupCatalogGroupChips() {
        pickerGroupChipGroup.removeAllViews()
        val groups = listOf(
            null to R.string.id_photo_group_all,
            PresetGroup.COMMON to R.string.id_photo_group_common,
            PresetGroup.OFFICIAL to R.string.id_photo_group_official,
            PresetGroup.VISA to R.string.id_photo_group_visa,
            PresetGroup.EXAM to R.string.id_photo_group_exam,
        )

        val currentGroup = viewModel.selectedCatalogGroup.value

        groups.forEachIndexed { index, (group, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = (group == currentGroup)
            }
            chip.setOnClickListener {
                viewModel.selectCatalogGroup(group)
            }
            pickerGroupChipGroup.addView(chip)
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isBitmapLoading.collectLatest { isLoading ->
                        loadingIndicator.isVisible = isLoading
                        val bmp = viewModel.previewBitmap
                        if (bmp != null) {
                            if (viewModel.hasFramingTransform) {
                                cropImageView.setBitmap(
                                    bmp,
                                    Triple(
                                        viewModel.framingScale,
                                        viewModel.framingTranslateX,
                                        viewModel.framingTranslateY,
                                    ),
                                )
                            } else {
                                cropImageView.setBitmap(bmp)
                                val (scale, tx, ty) = cropImageView.getTransformState()
                                viewModel.updateTransform(scale, tx, ty)
                            }
                        }
                    }
                }

                launch {
                    viewModel.selectedPreset.collectLatest { preset ->
                        applyPresetLive(preset)
                    }
                }

                launch {
                    viewModel.isPickerOpen.collectLatest { isOpen ->
                        catalogPickerOverlay.isVisible = isOpen
                    }
                }

                launch {
                    viewModel.selectedCatalogGroup.collectLatest { group ->
                        val list = if (group == null) {
                            IdPhotoCatalog.presets
                        } else {
                            IdPhotoCatalog.getByGroup(group)
                        }
                        catalogPickerAdapter.submitList(list)
                    }
                }
            }
        }
    }

    private fun applyPresetLive(preset: IdPhotoPreset) {
        val oldRatio = previousPresetRatio
        previousPresetRatio = preset.aspectRatio

        cropImageView.applyPresetRatio(preset.aspectRatio, oldRatio)

        val nameStr = getString(preset.nameRes)
        specBadgeText.text = "$nameStr · ${preset.targetWidthPx}×${preset.targetHeightPx} px · ${preset.dpi} DPI (${preset.widthMm}×${preset.heightMm}mm)"
        toolbar.subtitle = "$nameStr · ${preset.targetWidthPx}×${preset.targetHeightPx} px"

        val railItems = buildPresetRailList(preset)
        railAdapter.setItems(railItems, preset)

        // Smooth scroll rail so active preset is visible
        val activeIndex = railItems.indexOfFirst { it.id == preset.id }
        if (activeIndex >= 0) {
            presetRailRecyclerView.smoothScrollToPosition(activeIndex)
        }
    }

    private fun onPresetSelectedLive(preset: IdPhotoPreset) {
        val (scale, tx, ty) = cropImageView.getTransformState()
        viewModel.updateTransform(scale, tx, ty)
        viewModel.selectPreset(preset)
    }

    private fun buildPresetRailList(selected: IdPhotoPreset): List<IdPhotoPreset> {
        // Rail composition rationale:
        // 1. Same-ratio group siblings adjacent to selected preset for instant ratio-locked comparison;
        // 2. High-frequency common presets (1-inch, 2-inch, ID card, passport, visa, exams) deduplicated;
        // 3. One line, horizontally scrollable, instant live switching.
        val sameRatio = IdPhotoCatalog.getSameRatioPresets(selected)
        val commonCoreIds = listOf(
            "standard_1_inch", "standard_2_inch", "large_1_inch", "small_1_inch",
            "small_2_inch", "id_card_cn", "driver_license_paper", "passport_cn",
            "visa_us", "visa_schengen", "exam_postgraduate", "exam_ntce"
        )
        val commonCore = commonCoreIds.mapNotNull { IdPhotoCatalog.getById(it) }
        return (sameRatio + commonCore).distinctBy { it.id }
    }

    override fun onPause() {
        super.onPause()
        val (scale, tx, ty) = cropImageView.getTransformState()
        viewModel.updateTransform(scale, tx, ty)
    }

    private fun onToolbarMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            onSaveClicked()
            return true
        }
        return false
    }

    private fun handleBackNavigation() {
        if (viewModel.isPickerOpen.value) {
            viewModel.setPickerOpen(false)
            return
        }
        if (viewModel.isFramingModified) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.id_photo_discard_title)
                .setMessage(R.string.id_photo_discard_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.id_photo_discard_confirm) { _, _ ->
                    finish()
                }
                .show()
        } else {
            finish()
        }
    }

    private fun onSaveClicked() {
        val preset = viewModel.selectedPreset.value
        val normalizedRect = cropImageView.getNormalizedCropRect()
        if (normalizedRect == null) {
            Toast.makeText(this, R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        executeSavePipeline(normalizedRect, preset)
    }

    private fun executeSavePipeline(cropRect: RectF, preset: IdPhotoPreset) {
        val uri = viewModel.sourceUri ?: return
        savingOverlay.isVisible = true

        lifecycleScope.launch {
            val resultUri = withContext(Dispatchers.IO) {
                runCatching {
                    IdPhotoGenerator.generate(
                        context = this@IdPhotoActivity,
                        sourceUri = uri,
                        cropRectNormalized = cropRect,
                        preset = preset,
                    )
                }.getOrNull()
            }

            savingOverlay.isVisible = false

            if (resultUri != null) {
                Toast.makeText(this@IdPhotoActivity, R.string.id_photo_success, Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@IdPhotoActivity, R.string.id_photo_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Compact horizontal rail adapter for live preset switching.
     */
    private class PresetRailAdapter(
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.Adapter<PresetRailViewHolder>() {

        private var items: List<IdPhotoPreset> = emptyList()
        private var selectedPreset: IdPhotoPreset? = null

        fun setItems(newItems: List<IdPhotoPreset>, selected: IdPhotoPreset) {
            items = newItems
            selectedPreset = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetRailViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_id_photo_rail_chip, parent, false)
            return PresetRailViewHolder(view, onPresetSelected)
        }

        override fun onBindViewHolder(holder: PresetRailViewHolder, position: Int) {
            val preset = items[position]
            holder.bind(preset, preset.id == selectedPreset?.id)
        }

        override fun getItemCount(): Int = items.size
    }

    private class PresetRailViewHolder(
        itemView: View,
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val card = itemView.findViewById<MaterialCardView>(R.id.railCardView)
        private val nameText = itemView.findViewById<TextView>(R.id.railChipName)
        private val pxText = itemView.findViewById<TextView>(R.id.railChipPx)

        fun bind(preset: IdPhotoPreset, isSelected: Boolean) {
            nameText.setText(preset.nameRes)
            pxText.text = "${preset.targetWidthPx}×${preset.targetHeightPx}"

            card.isCheckable = true
            card.isChecked = isSelected
            card.alpha = if (isSelected) 1.0f else 0.75f

            itemView.setOnClickListener { onPresetSelected(preset) }
        }
    }

    /**
     * 2-column catalog grid adapter for the full catalog picker overlay.
     */
    private class IdPhotoCatalogGridAdapter(
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.Adapter<IdPhotoCatalogGridViewHolder>() {

        private var items: List<IdPhotoPreset> = emptyList()

        fun submitList(newItems: List<IdPhotoPreset>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IdPhotoCatalogGridViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_id_photo_preset, parent, false)
            return IdPhotoCatalogGridViewHolder(view, onPresetSelected)
        }

        override fun onBindViewHolder(holder: IdPhotoCatalogGridViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class IdPhotoCatalogGridViewHolder(
        itemView: View,
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameText = itemView.findViewById<TextView>(R.id.presetNameText)
        private val specText = itemView.findViewById<TextView>(R.id.presetSpecText)
        private val usageText = itemView.findViewById<TextView>(R.id.presetUsageText)

        fun bind(preset: IdPhotoPreset) {
            nameText.setText(preset.nameRes)
            specText.text = "${preset.widthMm}x${preset.heightMm}mm · ${preset.targetWidthPx}x${preset.targetHeightPx} px · ${preset.dpi} DPI"
            usageText.text = preset.description
            itemView.setOnClickListener { onPresetSelected(preset) }
        }
    }
}
