/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
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
import org.lineageos.glimpse.viewmodels.IdPhotoBgColorItem
import org.lineageos.glimpse.viewmodels.IdPhotoBottomTab
import org.lineageos.glimpse.viewmodels.IdPhotoViewModel

/**
 * ID Photo Tool Activity (Single live-editing surface).
 *
 * Implements Xiaomi Gallery reference:
 * - Persistent center preview with locked aspect ratio, face guide oval
 * - Top Action Bar: Cancel on left, Save on right, clean center (no top toggle)
 * - Bottom multi-layer controls:
 *   - Specs Mode: Top category tabs with Search icon; Middle expand button and preset cards rail
 *     (active card styled in warm golden amber #F5C542 with black text; inactive cards dark #2C2C2E with white text)
 *   - Background Mode: Standard official ID background color swatches (White, Sky Blue, Blue, Red, Grey, Dark Blue)
 *   - Bottom-most Tab Bar: "规格" (Specs) and "背景" (Background)
 * - Overlay catalog picker for all 32 presets
 */
class IdPhotoActivity : AppCompatActivity(R.layout.activity_id_photo) {

    private val cancelButton by lazy { findViewById<TextView>(R.id.cancelButton) }
    private val saveButton by lazy { findViewById<TextView>(R.id.saveButton) }
    private val titleText by lazy { findViewById<TextView>(R.id.titleText) }

    private val cropImageView by lazy { findViewById<CropImageView>(R.id.cropImageView) }
    private val specBadgeText by lazy { findViewById<TextView>(R.id.specBadgeText) }
    private val loadingIndicator by lazy { findViewById<CircularProgressIndicator>(R.id.loadingIndicator) }
    private val bottomControls by lazy { findViewById<View>(R.id.bottomControls) }

    // Specs mode views
    private val specsContainer by lazy { findViewById<View>(R.id.specsContainer) }
    private val searchButton by lazy { findViewById<ImageButton>(R.id.searchButton) }
    private val categoryTabsContainer by lazy { findViewById<LinearLayout>(R.id.categoryTabsContainer) }
    private val allSpecsExpandButton by lazy { findViewById<View>(R.id.allSpecsExpandButton) }
    private val presetRailRecyclerView by lazy { findViewById<RecyclerView>(R.id.presetRailRecyclerView) }

    // Background mode views
    private val backgroundContainer by lazy { findViewById<View>(R.id.backgroundContainer) }
    private val backgroundColorRecyclerView by lazy { findViewById<RecyclerView>(R.id.backgroundColorRecyclerView) }

    // Bottom tab bar views
    private val tabSpecsButton by lazy { findViewById<TextView>(R.id.tabSpecsButton) }
    private val tabBackgroundButton by lazy { findViewById<TextView>(R.id.tabBackgroundButton) }

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

    private val bgColorAdapter = BackgroundColorAdapter { colorInt ->
        viewModel.selectBgColor(colorInt)
    }

    private val catalogPickerAdapter = IdPhotoCatalogGridAdapter { preset ->
        onPresetSelectedLive(preset)
        viewModel.setPickerOpen(false)
    }

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"
        private const val COLOR_SELECTED_GOLD = 0xFFF5C542.toInt()
        private const val COLOR_TEXT_UNSELECTED = 0xFF8E8E93.toInt()
        private const val COLOR_CARD_UNSELECTED = 0xFF2C2C2E.toInt()
        private const val COLOR_BORDER_UNSELECTED = 0xFF3A3A3C.toInt()

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
            bottomControls.updatePadding(bottom = navBars.bottom + 8)
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

        cancelButton.setOnClickListener {
            handleBackNavigation()
        }
        saveButton.setOnClickListener {
            onSaveClicked()
        }

        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }

        setupCropView()
        setupBottomTabs()
        setupCategoryTabs()
        setupPresetRail()
        setupBackgroundColors()
        setupCatalogPicker()

        searchButton.setOnClickListener {
            viewModel.setPickerOpen(true)
        }
        allSpecsExpandButton.setOnClickListener {
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

    private fun setupBottomTabs() {
        tabSpecsButton.setOnClickListener {
            viewModel.selectBottomTab(IdPhotoBottomTab.SPECS)
        }
        tabBackgroundButton.setOnClickListener {
            viewModel.selectBottomTab(IdPhotoBottomTab.BACKGROUND)
        }
    }

    private fun setupCategoryTabs() {
        val categories = listOf(
            PresetGroup.COMMON to R.string.id_photo_group_common,
            PresetGroup.VISA to R.string.id_photo_group_visa,
            PresetGroup.OFFICIAL to R.string.id_photo_group_official,
            PresetGroup.EXAM to R.string.id_photo_group_exam,
        )

        categoryTabsContainer.removeAllViews()
        categories.forEach { (group, labelRes) ->
            val tabView = TextView(this).apply {
                setText(labelRes)
                textSize = 13sp
                setPadding(24, 8, 24, 8)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.color.transparent)
                setOnClickListener {
                    viewModel.selectRailGroup(group)
                }
            }
            categoryTabsContainer.addView(tabView)
        }
    }

    private fun updateCategoryTabsUI(activeGroup: PresetGroup?) {
        for (i in 0 until categoryTabsContainer.childCount) {
            val child = categoryTabsContainer.getChildAt(i) as? TextView ?: continue
            val categories = listOf(
                PresetGroup.COMMON,
                PresetGroup.VISA,
                PresetGroup.OFFICIAL,
                PresetGroup.EXAM,
            )
            val isCurrent = categories.getOrNull(i) == activeGroup
            if (isCurrent) {
                child.setTextColor(Color.WHITE)
                child.paint.isFakeBoldText = true
            } else {
                child.setTextColor(COLOR_TEXT_UNSELECTED)
                child.paint.isFakeBoldText = false
            }
            child.invalidate()
        }
    }

    private val Int.sp: Float
        get() = this.toFloat()

    private fun setupPresetRail() {
        presetRailRecyclerView.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        presetRailRecyclerView.adapter = railAdapter
    }

    private fun setupBackgroundColors() {
        backgroundColorRecyclerView.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        backgroundColorRecyclerView.adapter = bgColorAdapter
        bgColorAdapter.setColors(IdPhotoViewModel.STANDARD_BG_COLORS, viewModel.selectedBgColor.value)
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
                    }
                }

                launch {
                    viewModel.previewBitmap.collectLatest { bmp ->
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
                    viewModel.activeBottomTab.collectLatest { tab ->
                        updateBottomTabUI(tab)
                    }
                }

                launch {
                    viewModel.selectedRailGroup.collectLatest { group ->
                        updateCategoryTabsUI(group)
                        updateRailItemsForGroup(group, viewModel.selectedPreset.value)
                    }
                }

                launch {
                    viewModel.selectedBgColor.collectLatest { colorInt ->
                        bgColorAdapter.setSelectedColor(colorInt)
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

    private fun updateBottomTabUI(tab: IdPhotoBottomTab) {
        when (tab) {
            IdPhotoBottomTab.SPECS -> {
                specsContainer.isVisible = true
                backgroundContainer.isVisible = false
                tabSpecsButton.setTextColor(COLOR_SELECTED_GOLD)
                tabSpecsButton.paint.isFakeBoldText = true
                tabBackgroundButton.setTextColor(COLOR_TEXT_UNSELECTED)
                tabBackgroundButton.paint.isFakeBoldText = false
            }
            IdPhotoBottomTab.BACKGROUND -> {
                specsContainer.isVisible = false
                backgroundContainer.isVisible = true
                tabSpecsButton.setTextColor(COLOR_TEXT_UNSELECTED)
                tabSpecsButton.paint.isFakeBoldText = false
                tabBackgroundButton.setTextColor(COLOR_SELECTED_GOLD)
                tabBackgroundButton.paint.isFakeBoldText = true
            }
        }
        tabSpecsButton.invalidate()
        tabBackgroundButton.invalidate()
    }

    private fun applyPresetLive(preset: IdPhotoPreset) {
        val oldRatio = previousPresetRatio
        previousPresetRatio = preset.aspectRatio

        cropImageView.applyPresetRatio(preset.aspectRatio, oldRatio)

        val nameStr = getString(preset.nameRes)
        specBadgeText.text = "$nameStr · ${preset.targetWidthPx}×${preset.targetHeightPx} px · ${preset.dpi} DPI (${preset.widthMm}×${preset.heightMm}mm)"

        updateRailItemsForGroup(viewModel.selectedRailGroup.value, preset)
    }

    private fun updateRailItemsForGroup(group: PresetGroup?, selected: IdPhotoPreset) {
        val railItems = if (group != null) {
            val groupPresets = IdPhotoCatalog.getByGroup(group)
            if (groupPresets.none { it.id == selected.id }) {
                listOf(selected) + groupPresets
            } else {
                groupPresets
            }
        } else {
            IdPhotoCatalog.presets
        }

        railAdapter.setItems(railItems, selected)

        val activeIndex = railItems.indexOfFirst { it.id == selected.id }
        if (activeIndex >= 0) {
            presetRailRecyclerView.smoothScrollToPosition(activeIndex)
        }
    }

    private fun onPresetSelectedLive(preset: IdPhotoPreset) {
        val (scale, tx, ty) = cropImageView.getTransformState()
        viewModel.updateTransform(scale, tx, ty)
        viewModel.selectPreset(preset)
        viewModel.selectRailGroup(preset.group)
    }

    override fun onPause() {
        super.onPause()
        val (scale, tx, ty) = cropImageView.getTransformState()
        viewModel.updateTransform(scale, tx, ty)
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
     * Selected card has warm golden yellow background (#F5C542) with black text.
     * Inactive cards have dark gray background (#2C2C2E) with white text.
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

            if (isSelected) {
                card.setCardBackgroundColor(COLOR_SELECTED_GOLD)
                card.setStrokeColor(COLOR_SELECTED_GOLD)
                nameText.setTextColor(Color.BLACK)
                pxText.setTextColor(0xFF333333.toInt())
            } else {
                card.setCardBackgroundColor(COLOR_CARD_UNSELECTED)
                card.setStrokeColor(COLOR_BORDER_UNSELECTED)
                nameText.setTextColor(Color.WHITE)
                pxText.setTextColor(COLOR_TEXT_UNSELECTED)
            }

            itemView.setOnClickListener { onPresetSelected(preset) }
        }
    }

    /**
     * Adapter for ID photo background color swatches.
     */
    private class BackgroundColorAdapter(
        private val onColorSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<BackgroundColorViewHolder>() {

        private var colors: List<IdPhotoBgColorItem> = emptyList()
        private var selectedColor: Int = Color.WHITE

        fun setColors(items: List<IdPhotoBgColorItem>, selected: Int) {
            colors = items
            selectedColor = selected
            notifyDataSetChanged()
        }

        fun setSelectedColor(selected: Int) {
            selectedColor = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BackgroundColorViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_id_photo_bg_color, parent, false)
            return BackgroundColorViewHolder(view, onColorSelected)
        }

        override fun onBindViewHolder(holder: BackgroundColorViewHolder, position: Int) {
            val item = colors[position]
            holder.bind(item, item.colorInt == selectedColor)
        }

        override fun getItemCount(): Int = colors.size
    }

    private class BackgroundColorViewHolder(
        itemView: View,
        private val onColorSelected: (Int) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val card = itemView.findViewById<MaterialCardView>(R.id.colorSwatchCard)
        private val checkIcon = itemView.findViewById<ImageView>(R.id.colorCheckIcon)

        fun bind(item: IdPhotoBgColorItem, isSelected: Boolean) {
            card.setCardBackgroundColor(item.colorInt)

            val density = card.context.resources.displayMetrics.density
            if (isSelected) {
                card.strokeWidth = (2.5f * density).toInt()
                card.setStrokeColor(COLOR_SELECTED_GOLD)
                checkIcon.isVisible = true
                checkIcon.setColorFilter(if (item.isLight) Color.BLACK else Color.WHITE)
            } else {
                card.strokeWidth = if (item.isLight) (1f * density).toInt() else 0
                card.setStrokeColor(0x33FFFFFF)
                checkIcon.isVisible = false
            }

            itemView.setOnClickListener { onColorSelected(item.colorInt) }
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
