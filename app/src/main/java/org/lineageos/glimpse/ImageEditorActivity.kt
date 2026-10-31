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
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.ext.editSaveBehavior
import org.lineageos.glimpse.ext.createWriteRequest
import org.lineageos.glimpse.ui.views.AnnotationOverlayView
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.ui.views.MarkupTool
import org.lineageos.glimpse.ui.views.ShapeType
import org.lineageos.glimpse.utils.EditSaveBehavior
import org.lineageos.glimpse.utils.media.AdjustParameters
import org.lineageos.glimpse.utils.media.ImageAdjuster
import org.lineageos.glimpse.utils.media.ImageFilter
import org.lineageos.glimpse.utils.media.ImageToolbox
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

    enum class EditorCategory(val titleRes: Int) {
        CROP(R.string.editor_tab_crop),
        ADJUST(R.string.editor_tab_adjust),
        FILTERS(R.string.editor_tab_filters),
        MARKUP(R.string.editor_tab_markup),
        TOOLS(R.string.editor_tab_tools),
    }

    enum class AdjustParam(val labelRes: Int, val isBipolar: Boolean) {
        SATURATION(R.string.editor_adjust_saturation, true),
        CONTRAST(R.string.editor_adjust_contrast, true),
        TEMPERATURE(R.string.editor_adjust_temperature, true),
        SHARPEN(R.string.editor_adjust_sharpen, false),
    }

    private val appBarLayout by lazy { findViewById<View>(R.id.appBarLayout) }
    private val toolbar by lazy { findViewById<MaterialToolbar>(R.id.toolbar) }
    private val cropImageView by lazy { findViewById<CropImageView>(R.id.cropImageView) }
    private val savingOverlay by lazy { findViewById<View>(R.id.savingOverlay) }

    // Bottom Deck & Strips
    private val editorBottomDeck by lazy { findViewById<View>(R.id.editorBottomDeck) }
    private val stripCrop by lazy { findViewById<View>(R.id.stripCrop) }
    private val stripAdjust by lazy { findViewById<View>(R.id.stripAdjust) }
    private val stripFilters by lazy { findViewById<View>(R.id.stripFilters) }
    private val stripMarkup by lazy { findViewById<View>(R.id.stripMarkup) }
    private val stripTools by lazy { findViewById<View>(R.id.stripTools) }

    // Strip Controls: Crop
    private val rotateButton by lazy { findViewById<Button>(R.id.rotateButton) }
    private val ratioChipGroup by lazy { findViewById<ChipGroup>(R.id.ratioChipGroup) }

    // Strip Controls: Adjust
    private val adjustParamChipGroup by lazy { findViewById<ChipGroup>(R.id.adjustParamChipGroup) }
    private val adjustSliderRow by lazy { findViewById<View>(R.id.adjustSliderRow) }
    private val adjustSlider by lazy { findViewById<Slider>(R.id.adjustSlider) }
    private val adjustValueText by lazy { findViewById<TextView>(R.id.adjustValueText) }
    private val adjustResetButton by lazy { findViewById<Button>(R.id.adjustResetButton) }

    // Strip Controls: Markup
    private val annotationOverlayView by lazy { findViewById<AnnotationOverlayView>(R.id.annotationOverlayView) }
    private val markupToolChipGroup by lazy { findViewById<ChipGroup>(R.id.markupToolChipGroup) }
    private val markupUndoButton by lazy { findViewById<Button>(R.id.markupUndoButton) }
    private val markupOptionsScroll by lazy { findViewById<HorizontalScrollView>(R.id.markupOptionsScroll) }
    private val markupOptionsContent by lazy { findViewById<LinearLayout>(R.id.markupOptionsContent) }

    // Strip Controls: Filters & Tools
    private val filterChipGroup by lazy { findViewById<ChipGroup>(R.id.filterChipGroup) }
    private val faceGuideButton by lazy { findViewById<Button>(R.id.faceGuideButton) }
    private val watermarkButton by lazy { findViewById<Button>(R.id.watermarkButton) }

    // Tier 2: Category Tabs
    private val categoryChipGroup by lazy { findViewById<ChipGroup>(R.id.categoryChipGroup) }

    // Tier 3: Action Bar
    private val cancelButton by lazy { findViewById<Button>(R.id.cancelButton) }
    private val saveButton by lazy { findViewById<Button>(R.id.saveButton) }

    private var activeCategory: EditorCategory = EditorCategory.CROP
    private var activeAdjustParam: AdjustParam? = null
    private var adjustParameters = AdjustParameters()

    private var sourceUri: Uri? = null
    private var sourceMimeType: String? = null

    /** Rotated (but unfiltered) source. */
    private var baseBitmap: Bitmap? = null
    private var rotation = 0
    private var currentFilter: ImageFilter = ImageFilter.NONE
    private var isFramingModified = false

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
        enableEdgeToEdge()

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        sourceUri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_MEDIA_URI)
        sourceMimeType = intent.type ?: intent.getStringExtra(EXTRA_MIME_TYPE) ?: "image/jpeg"
        val uri = sourceUri
        if (uri == null) {
            finish()
            return
        }

        toolbar.setNavigationOnClickListener { handleCancel() }
        cancelButton.setOnClickListener { handleCancel() }
        saveButton.setOnClickListener { onSave() }

        onBackPressedDispatcher.addCallback(this) {
            handleCancel()
        }

        ViewCompat.setOnApplyWindowInsetsListener(appBarLayout) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.updatePadding(top = statusBars.top)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(editorBottomDeck) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = navBars.bottom)
            insets
        }

        cropImageView.onFramingModified = {
            isFramingModified = true
        }

        rotateButton.setOnClickListener {
            rotation = (rotation + 90) % 360
            refreshBaseBitmap()
        }
        faceGuideButton.setOnClickListener {
            val src = sourceUri ?: return@setOnClickListener
            if (isEditorDirty()) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.id_photo_title)
                    .setMessage(R.string.id_photo_editor_entry_warning)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.id_photo_editor_entry_continue) { _, _ ->
                        startActivity(IdPhotoActivity.createIntent(this, src, sourceMimeType))
                    }
                    .show()
            } else {
                startActivity(IdPhotoActivity.createIntent(this, src, sourceMimeType))
            }
        }
        watermarkButton.setOnClickListener {
            showPrivacyWatermarkDialog()
        }
        updateWatermarkButtonState()

        annotationOverlayView.cropRectProvider = { cropImageView.getCropRect() }
        annotationOverlayView.onAnnotationChanged = {
            markupUndoButton.isEnabled = annotationOverlayView.hasAnnotations()
        }

        setupRatioChips()
        setupAdjustControls()
        setupFilterChips()
        setupMarkupControls()
        setupCategoryTabs()
        switchCategory(EditorCategory.CROP)

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

    private fun setupCategoryTabs() {
        categoryChipGroup.removeAllViews()
        EditorCategory.values().forEach { category ->
            val chip = Chip(this).apply {
                text = getString(category.titleRes)
                isCheckable = true
                id = category.ordinal
                isChecked = category == activeCategory
            }
            chip.setOnClickListener {
                switchCategory(category)
            }
            categoryChipGroup.addView(chip)
        }
    }

    private fun switchCategory(category: EditorCategory) {
        activeCategory = category
        stripCrop.isVisible = category == EditorCategory.CROP
        stripAdjust.isVisible = category == EditorCategory.ADJUST
        stripFilters.isVisible = category == EditorCategory.FILTERS
        stripMarkup.isVisible = category == EditorCategory.MARKUP
        stripTools.isVisible = category == EditorCategory.TOOLS

        // Crop guides are visible only in CROP mode to give clean inspection in other modes
        cropImageView.showCropGuides = category == EditorCategory.CROP

        // If not in markup mode, disable active annotation tool
        if (category != EditorCategory.MARKUP) {
            annotationOverlayView.activeTool = MarkupTool.NONE
            markupToolChipGroup.clearCheck()
            markupOptionsScroll.isVisible = false
        }
    }

    private fun handleCancel() {
        if (isEditorDirty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.editor_discard_title)
                .setMessage(R.string.editor_discard_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.editor_discard_confirm) { _, _ ->
                    finish()
                }
                .show()
        } else {
            finish()
        }
    }

    private fun isEditorDirty(): Boolean {
        val hasRotation = rotation != 0
        val hasFilter = currentFilter != ImageFilter.NONE
        val hasWatermark = !cropImageView.watermarkText.isNullOrBlank()
        val hasRatioChange = ratioChipGroup.checkedChipId != 0
        val hasAdjustments = !adjustParameters.isDefault
        val hasAnnotations = annotationOverlayView.hasAnnotations()
        return hasRotation || hasFilter || hasWatermark || hasRatioChange || isFramingModified || hasAdjustments || hasAnnotations
    }

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_save) {
            onSave()
            return true
        }
        return false
    }

    private fun setupAdjustControls() {
        adjustParamChipGroup.removeAllViews()
        AdjustParam.values().forEachIndexed { index, param ->
            val chip = Chip(this).apply {
                text = getString(param.labelRes)
                isCheckable = true
                id = index
            }
            chip.setOnClickListener {
                selectAdjustParam(param)
            }
            adjustParamChipGroup.addView(chip)
        }

        adjustSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val param = activeAdjustParam ?: return@addOnChangeListener
                val intVal = value.toInt()
                adjustParameters = when (param) {
                    AdjustParam.SATURATION -> adjustParameters.copy(saturation = intVal)
                    AdjustParam.CONTRAST -> adjustParameters.copy(contrast = intVal)
                    AdjustParam.TEMPERATURE -> adjustParameters.copy(temperature = intVal)
                    AdjustParam.SHARPEN -> adjustParameters.copy(sharpen = intVal)
                }
                updateAdjustValueText(intVal, param.isBipolar)
                refreshBaseBitmap()
            }
        }

        adjustResetButton.setOnClickListener {
            val param = activeAdjustParam ?: return@setOnClickListener
            adjustParameters = when (param) {
                AdjustParam.SATURATION -> adjustParameters.copy(saturation = 0)
                AdjustParam.CONTRAST -> adjustParameters.copy(contrast = 0)
                AdjustParam.TEMPERATURE -> adjustParameters.copy(temperature = 0)
                AdjustParam.SHARPEN -> adjustParameters.copy(sharpen = 0)
            }
            adjustSlider.value = 0f
            updateAdjustValueText(0, param.isBipolar)
            refreshBaseBitmap()
        }
    }

    private fun selectAdjustParam(param: AdjustParam) {
        activeAdjustParam = param
        adjustSliderRow.isVisible = true

        val currentVal = when (param) {
            AdjustParam.SATURATION -> adjustParameters.saturation
            AdjustParam.CONTRAST -> adjustParameters.contrast
            AdjustParam.TEMPERATURE -> adjustParameters.temperature
            AdjustParam.SHARPEN -> adjustParameters.sharpen
        }

        if (param.isBipolar) {
            adjustSlider.valueFrom = -100f
            adjustSlider.valueTo = 100f
        } else {
            adjustSlider.valueFrom = 0f
            adjustSlider.valueTo = 100f
        }
        adjustSlider.value = currentVal.toFloat().coerceIn(adjustSlider.valueFrom, adjustSlider.valueTo)
        updateAdjustValueText(currentVal, param.isBipolar)
    }

    private fun updateAdjustValueText(valInt: Int, isBipolar: Boolean) {
        adjustValueText.text = if (isBipolar && valInt > 0) "+$valInt" else "$valInt"
    }

    private fun setupMarkupControls() {
        markupToolChipGroup.removeAllViews()

        val tools = listOf(
            MarkupTool.DOODLE to R.string.editor_markup_doodle,
            MarkupTool.SHAPES to R.string.editor_markup_shapes,
            MarkupTool.TEXT to R.string.editor_markup_text,
            MarkupTool.MOSAIC to R.string.editor_markup_mosaic,
        )

        tools.forEachIndexed { index, (tool, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
            }
            chip.setOnClickListener {
                if (annotationOverlayView.activeTool == tool) {
                    annotationOverlayView.activeTool = MarkupTool.NONE
                    markupToolChipGroup.clearCheck()
                    markupOptionsScroll.isVisible = false
                } else {
                    annotationOverlayView.activeTool = tool
                    showMarkupToolOptions(tool)
                }
            }
            markupToolChipGroup.addView(chip)
        }

        markupUndoButton.setOnClickListener {
            annotationOverlayView.undo()
        }
    }

    private fun showMarkupToolOptions(tool: MarkupTool) {
        markupOptionsContent.removeAllViews()
        markupOptionsScroll.isVisible = true

        val sizes = listOf(
            4f to R.string.editor_size_fine,
            8f to R.string.editor_size_medium,
            16f to R.string.editor_size_bold,
        )

        when (tool) {
            MarkupTool.DOODLE -> {
                addStrokeSizeSelector(sizes)
                addColorPalette()
            }
            MarkupTool.SHAPES -> {
                addShapeTypeSelector()
                addStrokeSizeSelector(sizes)
                addColorPalette()
            }
            MarkupTool.TEXT -> {
                // Prepared for Commit 4
            }
            MarkupTool.MOSAIC -> {
                // Prepared for Commit 5
            }
            MarkupTool.NONE -> {
                markupOptionsScroll.isVisible = false
            }
        }
    }

    private fun addShapeTypeSelector() {
        val shapeTypes = listOf(
            ShapeType.ARROW to R.string.editor_shape_arrow,
            ShapeType.RECTANGLE to R.string.editor_shape_rect,
            ShapeType.ELLIPSE to R.string.editor_shape_circle,
            ShapeType.LINE to R.string.editor_shape_line,
        )
        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
            isSelectionRequired = true
        }
        shapeTypes.forEachIndexed { index, (st, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = st == annotationOverlayView.selectedShapeType
            }
            chip.setOnClickListener {
                annotationOverlayView.selectedShapeType = st
            }
            chipGroup.addView(chip)
        }
        markupOptionsContent.addView(chipGroup)
    }

    private fun addStrokeSizeSelector(sizes: List<Pair<Float, Int>>) {
        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
            isSelectionRequired = true
            setPadding(12, 0, 12, 0)
        }
        sizes.forEachIndexed { index, (sizePx, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = kotlin.math.abs(sizePx - annotationOverlayView.activeStrokeWidth) < 1f
            }
            chip.setOnClickListener {
                annotationOverlayView.activeStrokeWidth = sizePx
            }
            chipGroup.addView(chip)
        }
        markupOptionsContent.addView(chipGroup)
    }

    private fun addColorPalette() {
        val colors = listOf(
            Color.parseColor("#F44336"), // Red
            Color.WHITE,
            Color.BLACK,
            Color.parseColor("#FFEB3B"), // Yellow
            Color.parseColor("#2196F3"), // Blue
            Color.parseColor("#4CAF50"), // Green
        )
        val paletteContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }

        colors.forEach { c ->
            val card = MaterialCardView(this).apply {
                radius = 14f * resources.displayMetrics.density
                cardElevation = 0f
                strokeWidth = if (c == Color.WHITE || c == Color.BLACK) (1.5f * resources.displayMetrics.density).toInt() else 0
                strokeColor = Color.DKGRAY
                setCardBackgroundColor(c)
                val size = (28f * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(6, 0, 6, 0)
                }
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    annotationOverlayView.activeColor = c
                }
            }
            paletteContainer.addView(card)
        }
        markupOptionsContent.addView(paletteContainer)
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
            Ratio(R.string.ratio_square, 1f, false),
            Ratio(R.string.ratio_5_7, 5f / 7f, false),
            Ratio(R.string.ratio_7_9, 7f / 9f, false),
            Ratio(R.string.ratio_11_16, 11f / 16f, false),
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

    /** Rebuild the displayed (rotated + filtered + adjusted) bitmap on the crop view. */
    private fun refreshBaseBitmap() {
        val bmp = baseBitmap ?: return
        lifecycleScope.launch {
            val displayed = withContext(Dispatchers.Default) {
                val rotated = rotate(bmp, rotation)
                val filtered = applyFilter(rotated, currentFilter)
                if (filtered !== rotated && rotated !== bmp) rotated.recycle()
                val adjusted = ImageAdjuster.applyAdjustments(filtered, adjustParameters)
                if (adjusted !== filtered && filtered !== bmp) filtered.recycle()
                adjusted
            }
            cropImageView.setBitmap(displayed)
        }
    }

    private fun onSave() {
        val cropped = cropImageView.crop()
        val result = if (cropped != null) {
            cropped
        } else {
            val bmp = baseBitmap ?: return
            val rotated = rotate(bmp, rotation)
            val filtered = applyFilter(rotated, currentFilter)
            if (filtered !== rotated && rotated !== bmp) rotated.recycle()
            val adjusted = ImageAdjuster.applyAdjustments(filtered, adjustParameters)
            if (adjusted !== filtered && filtered !== bmp) filtered.recycle()
            adjusted
        }
        val finalResult = if (annotationOverlayView.hasAnnotations()) {
            val canvasBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            canvas.drawBitmap(result, 0f, 0f, null)
            annotationOverlayView.rasterize(canvas, cropImageView.getCropRect())
            if (result !== baseBitmap && result !== cropped) result.recycle()
            canvasBmp
        } else {
            result
        }

        val watermarkedResult = cropImageView.watermarkText?.takeIf { it.isNotBlank() }?.let {
            ImageToolbox.applyWatermark(finalResult, it)
        } ?: finalResult
        showSaving()
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.Default) {
                encode(watermarkedResult, sourceMimeType ?: "image/jpeg")
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

    private fun showPrivacyWatermarkDialog() {
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(R.layout.dialog_privacy_watermark)

        val editText = dialog.findViewById<TextInputEditText>(R.id.watermarkEditText)!!
        val chipGroup = dialog.findViewById<ChipGroup>(R.id.watermarkShortcutChipGroup)!!
        val clearButton = dialog.findViewById<android.widget.Button>(R.id.watermarkClearButton)!!
        val cancelButton = dialog.findViewById<android.widget.Button>(R.id.watermarkCancelButton)!!
        val applyButton = dialog.findViewById<android.widget.Button>(R.id.watermarkApplyButton)!!

        val currentText = cropImageView.watermarkText ?: ""
        editText.setText(currentText)
        if (currentText.isNotEmpty()) {
            editText.setSelection(currentText.length)
        }

        clearButton.isVisible = currentText.isNotBlank()
        clearButton.setOnClickListener {
            cropImageView.watermarkText = null
            updateWatermarkButtonState()
            Toast.makeText(this, R.string.editor_watermark_removed, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        applyButton.setOnClickListener {
            val text = editText.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                cropImageView.watermarkText = text
                updateWatermarkButtonState()
                Toast.makeText(this, R.string.editor_watermark_applied, Toast.LENGTH_SHORT).show()
            } else {
                cropImageView.watermarkText = null
                updateWatermarkButtonState()
            }
            dialog.dismiss()
        }

        data class WatermarkPreset(
            val labelRes: Int,
            val templateRes: Int,
            val isCustomPlaceholder: Boolean = false,
        )

        val presets = listOf(
            WatermarkPreset(
                R.string.editor_watermark_preset_used_for,
                R.string.editor_watermark_template_used_for,
                isCustomPlaceholder = true
            ),
            WatermarkPreset(
                R.string.editor_watermark_preset_id_verification,
                R.string.editor_watermark_preset_id_verification
            ),
            WatermarkPreset(
                R.string.editor_watermark_preset_business,
                R.string.editor_watermark_preset_business
            ),
            WatermarkPreset(
                R.string.editor_watermark_preset_rental,
                R.string.editor_watermark_preset_rental
            ),
            WatermarkPreset(
                R.string.editor_watermark_preset_sample,
                R.string.editor_watermark_preset_sample
            ),
            WatermarkPreset(
                R.string.editor_watermark_preset_invalid_other,
                R.string.editor_watermark_preset_invalid_other
            ),
        )

        chipGroup.removeAllViews()
        presets.forEach { preset ->
            val chip = Chip(this).apply {
                text = getString(preset.labelRes)
                isCheckable = false
            }
            chip.setOnClickListener {
                val templateText = getString(preset.templateRes)
                editText.setText(templateText)
                if (preset.isCustomPlaceholder) {
                    val ellipsisCn = "……"
                    val ellipsisEn = "..."
                    val idxCn = templateText.indexOf(ellipsisCn)
                    val idxEn = templateText.indexOf(ellipsisEn)
                    if (idxCn != -1) {
                        editText.setSelection(idxCn, idxCn + ellipsisCn.length)
                    } else if (idxEn != -1) {
                        editText.setSelection(idxEn, idxEn + ellipsisEn.length)
                    } else {
                        editText.setSelection(templateText.length)
                    }
                } else {
                    editText.setSelection(templateText.length)
                }
                editText.requestFocus()
            }
            chipGroup.addView(chip)
        }

        dialog.show()
    }

    private fun updateWatermarkButtonState() {
        val hasWatermark = !cropImageView.watermarkText.isNullOrBlank()
        if (hasWatermark) {
            watermarkButton.setText(R.string.editor_watermark_active)
        } else {
            watermarkButton.setText(R.string.editor_watermark)
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
