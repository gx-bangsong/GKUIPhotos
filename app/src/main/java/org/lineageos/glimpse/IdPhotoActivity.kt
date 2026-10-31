/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }

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

    private val railAdapter = PresetRailAdapter(
        onPresetSelected = { preset ->
            if (preset.id == CUSTOM_ADD_PRESET.id) {
                showCustomSizeDialog()
            } else {
                onPresetSelectedLive(preset)
            }
        },
        onCustomLongPressed = { preset ->
            showDeleteCustomPresetDialog(preset)
        },
    )

    private val bgColorAdapter = BackgroundColorAdapter { colorInt ->
        viewModel.selectBgColor(colorInt)
    }

    private val catalogPickerAdapter = IdPhotoCatalogGridAdapter(
        onPresetSelected = { preset ->
            if (preset.id == CUSTOM_ADD_PRESET.id) {
                viewModel.setPickerOpen(false)
                showCustomSizeDialog()
            } else {
                onPresetSelectedLive(preset)
                viewModel.setPickerOpen(false)
            }
        },
        onCustomLongPressed = { preset ->
            viewModel.setPickerOpen(false)
            showDeleteCustomPresetDialog(preset)
        },
    )

    companion object {
        const val EXTRA_MEDIA_URI = "extra_media_uri"

        val CUSTOM_ADD_PRESET = IdPhotoPreset(
            id = "custom_action_add",
            nameRes = R.string.id_photo_custom_add,
            widthMm = 0,
            heightMm = 0,
            dpi = 300,
            group = PresetGroup.COMMON,
            specSource = "",
        )

        fun createIntent(context: Context, uri: Uri, mimeType: String? = null) =
            Intent(context, IdPhotoActivity::class.java).apply {
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                putExtra(EXTRA_MEDIA_URI, uri)
            }

        fun getThemeColor(context: Context, attrName: String, fallback: Int = Color.WHITE): Int {
            val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName)
                .takeIf { it != 0 }
                ?: context.resources.getIdentifier(attrName, "attr", "com.google.android.material")
                .takeIf { it != 0 }
                ?: context.resources.getIdentifier(attrName, "attr", "androidx.appcompat")
            if (attrId == 0) return fallback
            val typedValue = android.util.TypedValue()
            return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
                typedValue.data
            } else {
                fallback
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val rootLayout = findViewById<View>(R.id.idPhotoRootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(navBars.bottom, ime.bottom)

            bottomControls.updatePadding(bottom = navBars.bottom + 8)
            catalogPickerOverlay.updatePadding(
                top = statusBars.top,
                bottom = bottomInset + 16,
            )
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

        val density = resources.displayMetrics.density
        val hPad = (10 * density).toInt()
        val vPad = (4 * density).toInt()

        categoryTabsContainer.removeAllViews()
        categories.forEach { (group, labelRes) ->
            val tabView = TextView(this).apply {
                setText(labelRes)
                textSize = 13f
                setPadding(hPad, vPad, hPad, vPad)
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
        val colorOnSurface = getThemeColor(this, "colorOnSurface", Color.WHITE)
        val colorOnSurfaceVariant = getThemeColor(this, "colorOnSurfaceVariant", Color.GRAY)

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
                child.setTextColor(colorOnSurface)
                child.setTypeface(null, Typeface.BOLD)
            } else {
                child.setTextColor(colorOnSurfaceVariant)
                child.setTypeface(null, Typeface.NORMAL)
            }
            child.invalidate()
        }
    }

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
                        if (bmp != null && viewModel.mattedPreviewBitmap.value == null) {
                            updateCropImageViewBitmap(bmp)
                        }
                    }
                }

                launch {
                    viewModel.mattedPreviewBitmap.collectLatest { mattedBmp ->
                        val bmpToDisplay = mattedBmp ?: viewModel.previewBitmap.value
                        if (bmpToDisplay != null) {
                            updateCropImageViewBitmap(bmpToDisplay)
                        }
                    }
                }

                launch {
                    viewModel.mattingRefusalEvent.collectLatest { reason ->
                        if (reason != null) {
                            MaterialAlertDialogBuilder(this@IdPhotoActivity)
                                .setTitle(R.string.id_photo_title)
                                .setMessage(reason)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            viewModel.clearMattingRefusalEvent()
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
                        val customPresets = IdPhotoCustomPresetManager.getSavedCustomPresets(this@IdPhotoActivity)
                        catalogPickerAdapter.submitList(list + customPresets + listOf(CUSTOM_ADD_PRESET))
                    }
                }
            }
        }
    }

    private fun updateCropImageViewBitmap(bmp: Bitmap) {
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

    private fun updateBottomTabUI(tab: IdPhotoBottomTab) {
        val colorPrimary = getThemeColor(this, "colorPrimary", Color.WHITE)
        val colorOnSurfaceVariant = getThemeColor(this, "colorOnSurfaceVariant", Color.GRAY)

        when (tab) {
            IdPhotoBottomTab.SPECS -> {
                specsContainer.isVisible = true
                backgroundContainer.isVisible = false
                tabSpecsButton.setTextColor(colorPrimary)
                tabSpecsButton.setTypeface(null, Typeface.BOLD)
                tabBackgroundButton.setTextColor(colorOnSurfaceVariant)
                tabBackgroundButton.setTypeface(null, Typeface.NORMAL)
            }
            IdPhotoBottomTab.BACKGROUND -> {
                specsContainer.isVisible = false
                backgroundContainer.isVisible = true
                tabSpecsButton.setTextColor(colorOnSurfaceVariant)
                tabSpecsButton.setTypeface(null, Typeface.NORMAL)
                tabBackgroundButton.setTextColor(colorPrimary)
                tabBackgroundButton.setTypeface(null, Typeface.BOLD)
            }
        }
        tabSpecsButton.invalidate()
        tabBackgroundButton.invalidate()
    }

    private fun applyPresetLive(preset: IdPhotoPreset) {
        val oldRatio = previousPresetRatio
        previousPresetRatio = preset.aspectRatio

        cropImageView.applyPresetRatio(preset.aspectRatio, oldRatio)

        val nameStr = if (preset.id.startsWith("custom_")) {
            "${getString(R.string.id_photo_custom_title)} ${preset.widthMm}×${preset.heightMm}"
        } else {
            getString(preset.nameRes)
        }
        specBadgeText.text = "$nameStr · ${preset.targetWidthPx}×${preset.targetHeightPx} px · ${preset.dpi} DPI (${preset.widthMm}×${preset.heightMm}mm)"
        toolbar.subtitle = "$nameStr · ${preset.targetWidthPx}×${preset.targetHeightPx} px"

        updateRailItemsForGroup(viewModel.selectedRailGroup.value, preset)
    }

    private fun updateRailItemsForGroup(group: PresetGroup?, selected: IdPhotoPreset) {
        val baseItems = if (group != null) {
            val groupPresets = IdPhotoCatalog.getByGroup(group)
            if (groupPresets.none { it.id == selected.id } && !selected.id.startsWith("custom_")) {
                listOf(selected) + groupPresets
            } else {
                groupPresets
            }
        } else {
            IdPhotoCatalog.presets
        }

        val customPresets = IdPhotoCustomPresetManager.getSavedCustomPresets(this)
        val railItems = baseItems + customPresets + listOf(CUSTOM_ADD_PRESET)

        railAdapter.setItems(railItems, selected)

        val activeIndex = railItems.indexOfFirst { it.id == selected.id }
        if (activeIndex >= 0) {
            presetRailRecyclerView.smoothScrollToPosition(activeIndex)
        }
    }

    private fun showCustomSizeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_id_photo_custom_size, null)
        val chipGroup = dialogView.findViewById<ChipGroup>(R.id.customModeChipGroup)
        val widthInput = dialogView.findViewById<TextInputEditText>(R.id.customWidthEditText)
        val heightInput = dialogView.findViewById<TextInputEditText>(R.id.customHeightEditText)
        val dpiInput = dialogView.findViewById<TextInputEditText>(R.id.customDpiEditText)
        val widthLayout = dialogView.findViewById<TextInputLayout>(R.id.customWidthInputLayout)
        val heightLayout = dialogView.findViewById<TextInputLayout>(R.id.customHeightInputLayout)
        val dpiLayout = dialogView.findViewById<TextInputLayout>(R.id.customDpiInputLayout)

        var isPxMode = false

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            isPxMode = checkedIds.contains(R.id.chipModePx)
            if (isPxMode) {
                widthLayout.suffixText = "px"
                heightLayout.suffixText = "px"
                dpiLayout.isVisible = false
            } else {
                widthLayout.suffixText = "mm"
                heightLayout.suffixText = "mm"
                dpiLayout.isVisible = true
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.id_photo_custom_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val w = widthInput.text?.toString()?.toFloatOrNull() ?: 0f
                val h = heightInput.text?.toString()?.toFloatOrNull() ?: 0f
                val dpi = dpiInput.text?.toString()?.toIntOrNull() ?: 300

                val errorRes = IdPhotoCustomPresetManager.validate(isPxMode, w, h, dpi)
                if (errorRes != null) {
                    Toast.makeText(this, errorRes, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val customPreset = IdPhotoCustomPresetManager.createPreset(isPxMode, w, h, dpi)
                IdPhotoCustomPresetManager.saveCustomPreset(this, customPreset)
                onPresetSelectedLive(customPreset)
                viewModel.setPickerOpen(false)
            }
            .show()
    }

    private fun showDeleteCustomPresetDialog(preset: IdPhotoPreset) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.id_photo_custom_delete_confirm)
            .setMessage("${preset.widthMm}×${preset.heightMm} (${preset.targetWidthPx}×${preset.targetHeightPx} px)")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.id_photo_custom_delete) { _, _ ->
                IdPhotoCustomPresetManager.deleteCustomPreset(this, preset.id)
                if (viewModel.selectedPreset.value.id == preset.id) {
                    onPresetSelectedLive(IdPhotoCatalog.PRESET_ONE_INCH)
                } else {
                    updateRailItemsForGroup(viewModel.selectedRailGroup.value, viewModel.selectedPreset.value)
                }
            }
            .show()
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
        val targetBg = viewModel.selectedBgColor.value
        savingOverlay.isVisible = true

        lifecycleScope.launch {
            val resultUri = withContext(Dispatchers.IO) {
                runCatching {
                    IdPhotoGenerator.generate(
                        context = this@IdPhotoActivity,
                        sourceUri = uri,
                        cropRectNormalized = cropRect,
                        preset = preset,
                        targetBackgroundColor = targetBg,
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
        private val onCustomLongPressed: (IdPhotoPreset) -> Unit,
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
            return PresetRailViewHolder(view, onPresetSelected, onCustomLongPressed)
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
        private val onCustomLongPressed: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val card = itemView.findViewById<MaterialCardView>(R.id.railCardView)
        private val nameText = itemView.findViewById<TextView>(R.id.railChipName)
        private val pxText = itemView.findViewById<TextView>(R.id.railChipPx)

        fun bind(preset: IdPhotoPreset, isSelected: Boolean) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            if (preset.id == CUSTOM_ADD_PRESET.id) {
                nameText.setText(preset.nameRes)
                pxText.text = context.getString(R.string.id_photo_custom_dialog_title)

                val surfaceContainer = getThemeColor(context, "colorSurfaceContainerHigh", Color.DKGRAY)
                val primary = getThemeColor(context, "colorPrimary", Color.WHITE)

                card.setCardBackgroundColor(surfaceContainer)
                card.strokeWidth = (1 * density).toInt()
                card.setStrokeColor(primary)
                nameText.setTextColor(primary)
                pxText.setTextColor(primary)

                itemView.setOnClickListener { onPresetSelected(preset) }
                itemView.setOnLongClickListener(null)
                return
            }

            if (preset.id.startsWith("custom_")) {
                nameText.text = "${context.getString(R.string.id_photo_custom_title)} ${preset.widthMm}×${preset.heightMm}"
                pxText.text = "${preset.targetWidthPx}×${preset.targetHeightPx}"
                itemView.setOnLongClickListener {
                    onCustomLongPressed(preset)
                    true
                }
            } else {
                nameText.setText(preset.nameRes)
                pxText.text = "${preset.targetWidthPx}×${preset.targetHeightPx}"
                itemView.setOnLongClickListener(null)
            }

            if (isSelected) {
                val primaryContainer = getThemeColor(context, "colorPrimaryContainer", Color.WHITE)
                val onPrimaryContainer = getThemeColor(context, "colorOnPrimaryContainer", Color.BLACK)
                val primary = getThemeColor(context, "colorPrimary", Color.WHITE)

                card.setCardBackgroundColor(primaryContainer)
                card.strokeWidth = (2 * density).toInt()
                card.setStrokeColor(primary)
                nameText.setTextColor(onPrimaryContainer)
                pxText.setTextColor(onPrimaryContainer)
            } else {
                val surfaceContainer = getThemeColor(context, "colorSurfaceContainerHigh", Color.DKGRAY)
                val onSurface = getThemeColor(context, "colorOnSurface", Color.WHITE)
                val onSurfaceVariant = getThemeColor(context, "colorOnSurfaceVariant", Color.GRAY)
                val outlineVariant = getThemeColor(context, "colorOutlineVariant", Color.GRAY)

                card.setCardBackgroundColor(surfaceContainer)
                card.strokeWidth = (1 * density).toInt()
                card.setStrokeColor(outlineVariant)
                nameText.setTextColor(onSurface)
                pxText.setTextColor(onSurfaceVariant)
            }

            itemView.setOnClickListener { onPresetSelected(preset) }
        }
    }

    /**
     * Adapter for ID photo background color swatches.
     */
    private class BackgroundColorAdapter(
        private val onColorSelected: (Int?) -> Unit,
    ) : RecyclerView.Adapter<BackgroundColorViewHolder>() {

        private var colors: List<IdPhotoBgColorItem> = emptyList()
        private var selectedColor: Int? = null

        fun setColors(items: List<IdPhotoBgColorItem>, selected: Int?) {
            colors = items
            selectedColor = selected
            notifyDataSetChanged()
        }

        fun setSelectedColor(selected: Int?) {
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
        private val onColorSelected: (Int?) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val card = itemView.findViewById<MaterialCardView>(R.id.colorSwatchCard)
        private val originalLabel = itemView.findViewById<TextView>(R.id.originalLabel)
        private val checkIcon = itemView.findViewById<ImageView>(R.id.colorCheckIcon)

        fun bind(item: IdPhotoBgColorItem, isSelected: Boolean) {
            val context = itemView.context
            val density = card.context.resources.displayMetrics.density

            if (item.colorInt == null) {
                val cardBg = getThemeColor(context, "colorSurfaceContainerHigh", Color.DKGRAY)
                card.setCardBackgroundColor(cardBg)
                originalLabel.isVisible = !isSelected
                originalLabel.setText(item.nameRes)
            } else {
                card.setCardBackgroundColor(item.colorInt)
                originalLabel.isVisible = false
            }

            if (isSelected) {
                val primary = getThemeColor(context, "colorPrimary", Color.WHITE)
                card.strokeWidth = (2.5f * density).toInt()
                card.setStrokeColor(primary)
                checkIcon.isVisible = true
                checkIcon.setColorFilter(if (item.isLight) Color.BLACK else Color.WHITE)
            } else {
                val outlineVariant = getThemeColor(context, "colorOutlineVariant", Color.GRAY)
                card.strokeWidth = if (item.isLight) (1f * density).toInt() else 0
                card.setStrokeColor(outlineVariant)
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
        private val onCustomLongPressed: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.Adapter<IdPhotoCatalogGridViewHolder>() {

        private var items: List<IdPhotoPreset> = emptyList()

        fun submitList(newItems: List<IdPhotoPreset>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IdPhotoCatalogGridViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_id_photo_preset, parent, false)
            return IdPhotoCatalogGridViewHolder(view, onPresetSelected, onCustomLongPressed)
        }

        override fun onBindViewHolder(holder: IdPhotoCatalogGridViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size
    }

    private class IdPhotoCatalogGridViewHolder(
        itemView: View,
        private val onPresetSelected: (IdPhotoPreset) -> Unit,
        private val onCustomLongPressed: (IdPhotoPreset) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {

        private val nameText = itemView.findViewById<TextView>(R.id.presetNameText)
        private val specText = itemView.findViewById<TextView>(R.id.presetSpecText)
        private val usageText = itemView.findViewById<TextView>(R.id.presetUsageText)

        fun bind(preset: IdPhotoPreset) {
            val context = itemView.context
            if (preset.id == CUSTOM_ADD_PRESET.id) {
                nameText.setText(preset.nameRes)
                specText.text = context.getString(R.string.id_photo_custom_dialog_title)
                usageText.text = ""
                itemView.setOnClickListener { onPresetSelected(preset) }
                itemView.setOnLongClickListener(null)
                return
            }

            if (preset.id.startsWith("custom_")) {
                nameText.text = "${context.getString(R.string.id_photo_custom_title)} ${preset.widthMm}×${preset.heightMm}"
                specText.text = "${preset.widthMm}x${preset.heightMm}mm · ${preset.targetWidthPx}x${preset.targetHeightPx} px · ${preset.dpi} DPI"
                usageText.text = preset.description
                itemView.setOnClickListener { onPresetSelected(preset) }
                itemView.setOnLongClickListener {
                    onCustomLongPressed(preset)
                    true
                }
            } else {
                nameText.setText(preset.nameRes)
                specText.text = "${preset.widthMm}x${preset.heightMm}mm · ${preset.targetWidthPx}x${preset.targetHeightPx} px · ${preset.dpi} DPI"
                usageText.text = preset.description
                itemView.setOnClickListener { onPresetSelected(preset) }
                itemView.setOnLongClickListener(null)
            }
        }
    }
}
