/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
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
import org.lineageos.glimpse.ui.views.AngleRulerView
import org.lineageos.glimpse.ui.views.AnnotationItem
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Image Editor aligned directly with Google Photos Editor UI specifications:
 *  - Top-bar-free edge-to-edge black canvas
 *  - Pinned center preview maximizing screen real estate
 *  - Three-tier Google Photos bottom deck:
 *     1. Contextual circular tool strip and slider dial
 *     2. Category pill tabs (裁剪 · 调整 · 滤镜 · 标注 · 工具)
 *     3. Fixed bottom action bar (Cancel text on left, Material 3 Save Copy pill on right)
 */
class ImageEditorActivity : AppCompatActivity(R.layout.activity_image_editor) {

    enum class EditorCategory(val titleRes: Int) {
        CROP(R.string.editor_tab_crop),
        ADJUST(R.string.editor_tab_adjust),
        FILTERS(R.string.editor_tab_filters),
        MARKUP(R.string.editor_tab_markup),
        TOOLS(R.string.editor_tab_tools),
    }

    enum class AdjustParam(val labelRes: Int, val iconRes: Int, val isBipolar: Boolean) {
        CONTRAST(R.string.editor_adjust_contrast, R.drawable.ic_adjust_contrast, true),
        SATURATION(R.string.editor_adjust_saturation, R.drawable.ic_adjust_saturation, true),
        TEMPERATURE(R.string.editor_adjust_temperature, R.drawable.ic_adjust_temperature, true),
        SHARPEN(R.string.editor_adjust_sharpen, R.drawable.ic_adjust_sharpen, false),
    }

    private val cropImageView by lazy { findViewById<CropImageView>(R.id.cropImageView) }
    private val annotationOverlayView by lazy { findViewById<AnnotationOverlayView>(R.id.annotationOverlayView) }
    private val savingOverlay by lazy { findViewById<View>(R.id.savingOverlay) }

    // Bottom Deck & Strips
    private val editorBottomDeck by lazy { findViewById<View>(R.id.editorBottomDeck) }
    private val stripCrop by lazy { findViewById<View>(R.id.stripCrop) }
    private val stripAdjust by lazy { findViewById<View>(R.id.stripAdjust) }
    private val stripFilters by lazy { findViewById<View>(R.id.stripFilters) }
    private val stripMarkup by lazy { findViewById<View>(R.id.stripMarkup) }
    private val stripTools by lazy { findViewById<View>(R.id.stripTools) }

    // Strip Controls: Crop
    private val cropAngleText by lazy { findViewById<TextView>(R.id.cropAngleText) }
    private val cropAngleRuler by lazy { findViewById<AngleRulerView>(R.id.cropAngleRuler) }
    private val cropRatioItem by lazy { findViewById<View>(R.id.cropRatioItem) }
    private val cropRatioLabel by lazy { findViewById<TextView>(R.id.cropRatioLabel) }
    private val cropRotateItem by lazy { findViewById<View>(R.id.cropRotateItem) }
    private val cropFlipItem by lazy { findViewById<View>(R.id.cropFlipItem) }
    private val cropFlipIcon by lazy { findViewById<ImageView>(R.id.cropFlipIcon) }
    private val cropFlipLabel by lazy { findViewById<TextView>(R.id.cropFlipLabel) }
    private val cropResetButton by lazy { findViewById<View>(R.id.cropResetButton) }

    // Strip Controls: Adjust
    private val adjustCarouselScroll by lazy { findViewById<View>(R.id.adjustCarouselScroll) }
    private val adjustToolItemsLayout by lazy { findViewById<LinearLayout>(R.id.adjustToolItemsLayout) }
    private val adjustSliderLayout by lazy { findViewById<View>(R.id.adjustSliderLayout) }
    private val adjustActiveParamTitle by lazy { findViewById<TextView>(R.id.adjustActiveParamTitle) }
    private val adjustValueText by lazy { findViewById<TextView>(R.id.adjustValueText) }
    private val adjustSlider by lazy { findViewById<Slider>(R.id.adjustSlider) }
    private val adjustResetButton by lazy { findViewById<View>(R.id.adjustResetButton) }

    // Strip Controls: Filters
    private val filtersContainer by lazy { findViewById<LinearLayout>(R.id.filtersContainer) }

    // Strip Controls: Markup
    private val markupToolsRow by lazy { findViewById<LinearLayout>(R.id.markupToolsRow) }
    private val markupOptionsScroll by lazy { findViewById<View>(R.id.markupOptionsScroll) }
    private val markupOptionsContent by lazy { findViewById<LinearLayout>(R.id.markupOptionsContent) }

    // Strip Controls: Tools
    private val toolIdPhotoItem by lazy { findViewById<View>(R.id.toolIdPhotoItem) }
    private val toolWatermarkItem by lazy { findViewById<View>(R.id.toolWatermarkItem) }
    private val watermarkIcon by lazy { findViewById<ImageView>(R.id.watermarkIcon) }
    private val watermarkLabel by lazy { findViewById<TextView>(R.id.watermarkLabel) }

    // Deck: Tabs & Action Bar
    private val categoryTabsScroll by lazy { findViewById<View>(R.id.categoryTabsScroll) }
    private val categoryTabsTopDivider by lazy { findViewById<View>(R.id.categoryTabsTopDivider) }
    private val categoryTabsBottomDivider by lazy { findViewById<View>(R.id.categoryTabsBottomDivider) }
    private val categoryTabsContainer by lazy { findViewById<LinearLayout>(R.id.categoryTabsContainer) }
    private val regularActionBar by lazy { findViewById<View>(R.id.regularActionBar) }
    private val cancelButton by lazy { findViewById<View>(R.id.cancelButton) }
    private val saveButton by lazy { findViewById<View>(R.id.saveButton) }

    // Sub-edit Action Bar
    private val subEditActionBar by lazy { findViewById<View>(R.id.subEditActionBar) }
    private val subEditCancelButton by lazy { findViewById<TextView>(R.id.subEditCancelButton) }
    private val subEditUndoButton by lazy { findViewById<ImageView>(R.id.subEditUndoButton) }
    private val subEditDoneButton by lazy { findViewById<View>(R.id.subEditDoneButton) }

    private var sourceUri: Uri? = null
    private var sourceMimeType: String? = null
    private var baseBitmap: Bitmap? = null
    private var rotation: Int = 0
    private var fineRotation: Float = 0f
    private var currentFilter: ImageFilter = ImageFilter.NONE
    private var filterThumbnails: Map<ImageFilter, Bitmap>? = null
    private var adjustParameters = AdjustParameters()
    private var activeAdjustParam: AdjustParam? = null

    private var activeCategory = EditorCategory.CROP
    private var isFramingModified = false
    private var isFlippedHorizontal = false
    private var pendingOverwrite: ByteArray? = null

    private val writeRequestContract = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val bytes = pendingOverwrite
            pendingOverwrite = null
            if (bytes != null) overwrite(bytes) else finishWithSaved()
        } else {
            pendingOverwrite = null
            hideSaving()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.editorRootLayout)) { _, insets ->
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            cropImageView.updatePadding(top = status.top)
            annotationOverlayView.updatePadding(top = status.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(editorBottomDeck) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom)
            insets
        }

        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        onBackPressedDispatcher.addCallback(this) { handleExitAttempt() }
        cancelButton.setOnClickListener { handleExitAttempt() }
        saveButton.setOnClickListener { onSave() }

        val uri = intent.getParcelableExtra<Uri>(EXTRA_MEDIA_URI)
            ?: intent.data
            ?: run { finish(); return }
        sourceUri = uri
        sourceMimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: contentResolver.getType(uri)

        cropImageView.onFramingModified = {
            isFramingModified = true
        }

        // Crop Controls
        cropRotateItem.setOnClickListener {
            rotation = (rotation + 90) % 360
            val totalDeg = (rotation + fineRotation).roundToInt()
            cropAngleText.text = "${totalDeg}°"
            refreshBaseBitmap()
        }
        cropAngleRuler.onAngleChanged = { angle ->
            fineRotation = angle
            val totalDeg = (rotation + fineRotation).roundToInt()
            cropAngleText.text = "${totalDeg}°"
            refreshBaseBitmap()
        }
        cropRatioItem.setOnClickListener {
            showRatioBottomSheet()
        }
        cropFlipItem.setOnClickListener {
            isFlippedHorizontal = !isFlippedHorizontal
            updateFlipButtonUI()
            refreshBaseBitmap()
        }
        cropResetButton.setOnClickListener {
            rotation = 0
            fineRotation = 0f
            cropAngleRuler.currentAngle = 0f
            isFlippedHorizontal = false
            isFramingModified = false
            cropAngleText.text = "0°"
            cropImageView.aspectRatio = 0f
            cropImageView.reset()
            cropRatioLabel.setText(R.string.editor_crop_free)
            updateFlipButtonUI()
            refreshBaseBitmap()
        }

        // Tools Controls
        toolIdPhotoItem.setOnClickListener {
            sourceUri?.let { src ->
                startActivity(IdPhotoActivity.createIntent(this, src, sourceMimeType))
            }
        }
        toolWatermarkItem.setOnClickListener {
            showPrivacyWatermarkDialog()
        }
        updateWatermarkButtonState()

        // Annotation Overlay Callbacks
        annotationOverlayView.cropRectProvider = { cropImageView.getCropRect() }
        annotationOverlayView.displayBitmapProvider = { cropImageView.getDisplayBitmap() }
        annotationOverlayView.onAnnotationChanged = {
            subEditUndoButton.isEnabled = annotationOverlayView.hasAnnotations()
            subEditUndoButton.alpha = if (annotationOverlayView.hasAnnotations()) 1f else 0.4f
        }
        annotationOverlayView.onTextEditRequested = { textItem ->
            showTextInputDialog(textItem)
        }

        // Setup Strips
        setupAdjustControls()
        setupFilterCards()
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
            cropImageView.aspectRatio = 0f
            cropImageView.showFaceGuide = false
            refreshBaseBitmap()

            // Precompute filter thumbnail previews (128x128)
            val thumbs = withContext(Dispatchers.Default) {
                val thumbSize = 128
                val maxDim = max(bmp.width, bmp.height)
                val scale = thumbSize.toFloat() / maxDim
                val tw = max((bmp.width * scale).toInt(), 1)
                val th = max((bmp.height * scale).toInt(), 1)
                val scaled = Bitmap.createScaledBitmap(bmp, tw, th, true)
                ImageFilter.values().associateWith { f ->
                    applyFilter(scaled, f)
                }
            }
            filterThumbnails = thumbs
            setupFilterCards()
        }
    }

    private fun setupCategoryTabs() {
        categoryTabsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        EditorCategory.values().forEach { category ->
            val tv = TextView(this).apply {
                text = getString(category.titleRes)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
                val isSelected = category == activeCategory
                if (isSelected) {
                    setBackgroundResource(R.drawable.bg_google_photos_pill_selected)
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                } else {
                    background = null
                    setTextColor(Color.parseColor("#A0A0A0"))
                    typeface = Typeface.DEFAULT
                }
                setOnClickListener {
                    switchCategory(category)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
            }
            categoryTabsContainer.addView(tv, lp)
        }
    }

    private fun setCategoryTabsVisible(visible: Boolean) {
        categoryTabsScroll.isVisible = visible
        categoryTabsTopDivider.isVisible = visible
        categoryTabsBottomDivider.isVisible = visible
    }

    private fun switchCategory(category: EditorCategory) {
        activeCategory = category
        setupCategoryTabs()

        stripCrop.isVisible = category == EditorCategory.CROP
        stripAdjust.isVisible = category == EditorCategory.ADJUST
        stripFilters.isVisible = category == EditorCategory.FILTERS
        stripMarkup.isVisible = category == EditorCategory.MARKUP
        stripTools.isVisible = category == EditorCategory.TOOLS

        // Crop guides only in CROP mode
        cropImageView.showCropGuides = (category == EditorCategory.CROP)
        annotationOverlayView.isVisible = true

        // Exit adjust slider if active
        if (activeAdjustParam != null) {
            activeAdjustParam = null
            updateAdjustToolItems()
            adjustCarouselScroll.isVisible = true
            adjustSliderLayout.isVisible = false
        }

        // In Google Photos (e.g. google-photos-markup.jpg), categories start in neutral state:
        // No tool is active, tool options are hidden, Category Tabs bar is visible, Cancel/Save copy action bar is visible.
        annotationOverlayView.activeTool = MarkupTool.NONE
        markupOptionsScroll.isVisible = false
        updateMarkupToolButtons()

        setCategoryTabsVisible(true)
        regularActionBar.isVisible = true
        subEditActionBar.isVisible = false
    }

    private fun updateFlipButtonUI() {
        cropFlipLabel.setTextColor(
            if (isFlippedHorizontal) Color.parseColor("#A8C7FA") else Color.parseColor("#B0B0B0")
        )
        cropFlipIcon.imageTintList = ColorStateList.valueOf(
            if (isFlippedHorizontal) Color.parseColor("#A8C7FA") else Color.WHITE
        )
    }

    private fun isDirty(): Boolean {
        return abs(fineRotation) > 0.1f ||
                rotation != 0 ||
                isFlippedHorizontal ||
                currentFilter != ImageFilter.NONE ||
                !adjustParameters.isDefault ||
                annotationOverlayView.hasAnnotations() ||
                !cropImageView.watermarkText.isNullOrBlank() ||
                isFramingModified
    }

    private fun handleExitAttempt() {
        if (activeAdjustParam != null) {
            closeAdjustSlider()
            return
        }
        if (activeCategory == EditorCategory.MARKUP && annotationOverlayView.activeTool != MarkupTool.NONE) {
            exitMarkupSubEdit()
            return
        }
        if (!isDirty()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_discard_title)
            .setMessage(R.string.editor_discard_message)
            .setPositiveButton(R.string.editor_discard_confirm) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRatioBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            setPadding(16, 24, 16, 32)
        }

        data class RatioItem(val labelRes: Int, val ratio: Float)
        val ratios = listOf(
            RatioItem(R.string.editor_crop_free, 0f),
            RatioItem(R.string.ratio_square, 1f),
            RatioItem(R.string.ratio_5_7, 5f / 7f),
            RatioItem(R.string.ratio_7_9, 7f / 9f),
            RatioItem(R.string.ratio_11_16, 11f / 16f),
        )

        ratios.forEach { item ->
            val tv = TextView(this).apply {
                text = getString(item.labelRes)
                textSize = 15f
                setPadding(24, 20, 24, 20)
                val isSelected = (item.ratio == 0f && cropImageView.aspectRatio == 0f) ||
                        (abs(cropImageView.aspectRatio - item.ratio) < 0.01f)
                setTextColor(if (isSelected) Color.parseColor("#A8C7FA") else Color.WHITE)
                setOnClickListener {
                    cropImageView.aspectRatio = item.ratio
                    cropRatioLabel.text = getString(item.labelRes)
                    dialog.dismiss()
                }
            }
            list.addView(tv)
        }
        dialog.setContentView(list)
        dialog.show()
    }

    private fun setupAdjustControls() {
        updateAdjustToolItems()

        adjustSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val param = activeAdjustParam ?: return@addOnChangeListener
            val intVal = value.toInt()
            adjustValueText.text = if (intVal > 0 && param.isBipolar) "+$intVal" else "$intVal"

            setAdjustParamValue(param, intVal)
            refreshBaseBitmap()
            updateAdjustToolItems()
        }

        adjustResetButton.setOnClickListener {
            val param = activeAdjustParam ?: return@setOnClickListener
            adjustSlider.value = 0f
            adjustValueText.text = "0"
            setAdjustParamValue(param, 0)
            refreshBaseBitmap()
            updateAdjustToolItems()
        }
    }

    private fun getAdjustParamValue(param: AdjustParam): Int = when (param) {
        AdjustParam.SATURATION -> adjustParameters.saturation
        AdjustParam.CONTRAST -> adjustParameters.contrast
        AdjustParam.TEMPERATURE -> adjustParameters.temperature
        AdjustParam.SHARPEN -> adjustParameters.sharpen
    }

    private fun setAdjustParamValue(param: AdjustParam, value: Int) {
        adjustParameters = when (param) {
            AdjustParam.SATURATION -> adjustParameters.copy(saturation = value)
            AdjustParam.CONTRAST -> adjustParameters.copy(contrast = value)
            AdjustParam.TEMPERATURE -> adjustParameters.copy(temperature = value)
            AdjustParam.SHARPEN -> adjustParameters.copy(sharpen = value)
        }
    }

    private fun updateAdjustToolItems() {
        adjustToolItemsLayout.removeAllViews()
        val density = resources.displayMetrics.density

        AdjustParam.values().forEach { param ->
            val isApplied = getAdjustParamValue(param) != 0
            val isSelected = activeAdjustParam == param

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectAdjustParam(param)
                }
            }

            val iconCircle = FrameLayout(this).apply {
                val size = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_circle_tool_selected else R.drawable.bg_circle_tool
                )

                val iv = ImageView(this@ImageEditorActivity).apply {
                    val iconSize = (24 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(param.iconRes)
                    imageTintList = ColorStateList.valueOf(
                        if (isSelected) Color.parseColor("#001D35") else Color.WHITE
                    )
                }
                addView(iv)

                // Google Photos applied indicator dot
                if (isApplied && !isSelected) {
                    val dot = View(this@ImageEditorActivity).apply {
                        val dotSize = (8 * density).toInt()
                        layoutParams = FrameLayout.LayoutParams(dotSize, dotSize).apply {
                            gravity = Gravity.TOP or Gravity.END
                            setMargins(0, (4 * density).toInt(), (4 * density).toInt(), 0)
                        }
                        setBackgroundResource(R.drawable.bg_circle_tool_selected)
                    }
                    addView(dot)
                }
            }

            val label = TextView(this).apply {
                text = getString(param.labelRes)
                textSize = 11f
                setTextColor(if (isApplied || isSelected) Color.parseColor("#A8C7FA") else Color.parseColor("#B0B0B0"))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            itemLayout.addView(iconCircle)
            itemLayout.addView(label)
            adjustToolItemsLayout.addView(itemLayout)
        }
    }

    private fun selectAdjustParam(param: AdjustParam) {
        activeAdjustParam = param
        updateAdjustToolItems()

        adjustCarouselScroll.isVisible = false
        adjustSliderLayout.isVisible = true

        // Category Tabs automatically disappear in sub-editing mode
        setCategoryTabsVisible(false)
        regularActionBar.isVisible = false
        subEditActionBar.isVisible = true
        subEditUndoButton.isVisible = false
        subEditCancelButton.setText(R.string.cancel)

        val initialVal = getAdjustParamValue(param)
        subEditCancelButton.setOnClickListener {
            // Revert slider change
            setAdjustParamValue(param, initialVal)
            refreshBaseBitmap()
            closeAdjustSlider()
        }
        subEditDoneButton.setOnClickListener {
            closeAdjustSlider()
        }

        adjustActiveParamTitle.text = getString(param.labelRes)

        if (param.isBipolar) {
            adjustSlider.valueFrom = -100f
            adjustSlider.valueTo = 100f
            adjustSlider.stepSize = 1f
        } else {
            adjustSlider.valueFrom = 0f
            adjustSlider.valueTo = 100f
            adjustSlider.stepSize = 1f
        }

        val curVal = initialVal
        adjustSlider.value = curVal.toFloat()
        adjustValueText.text = if (curVal > 0 && param.isBipolar) "+$curVal" else "$curVal"
    }

    private fun closeAdjustSlider() {
        activeAdjustParam = null
        updateAdjustToolItems()
        adjustCarouselScroll.isVisible = true
        adjustSliderLayout.isVisible = false
        // Category Tabs bar reappears when exiting sub-editing mode
        setCategoryTabsVisible(true)
        regularActionBar.isVisible = true
        subEditActionBar.isVisible = false
    }

    private fun setupFilterCards() {
        filtersContainer.removeAllViews()
        val density = resources.displayMetrics.density

        val filters = listOf(
            ImageFilter.NONE to R.string.editor_filter_none,
            ImageFilter.WARM to R.string.editor_filter_warm,
            ImageFilter.COOL to R.string.editor_filter_cool,
            ImageFilter.GRAYSCALE to R.string.editor_filter_grayscale,
            ImageFilter.SEPIA to R.string.editor_filter_sepia,
            ImageFilter.INVERT to R.string.editor_filter_invert,
        )

        filters.forEach { (filter, labelRes) ->
            val isSelected = currentFilter == filter
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    currentFilter = filter
                    setupFilterCards()
                    refreshBaseBitmap()
                }
            }

            val card = MaterialCardView(this).apply {
                radius = 8 * density
                val cardSize = (56 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(cardSize, cardSize)
                cardElevation = 0f
                strokeWidth = if (isSelected) (2.5f * density).toInt() else 0
                strokeColor = Color.parseColor("#A8C7FA")
                setCardBackgroundColor(Color.parseColor("#222222"))

                val thumb = filterThumbnails?.get(filter)
                if (thumb != null) {
                    val iv = ImageView(this@ImageEditorActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(thumb)
                    }
                    addView(iv)

                    if (isSelected) {
                        val checkBadge = ImageView(this@ImageEditorActivity).apply {
                            val badgeSize = (20 * density).toInt()
                            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                                gravity = Gravity.BOTTOM or Gravity.END
                                setMargins(0, 0, (4 * density).toInt(), (4 * density).toInt())
                            }
                            setBackgroundResource(R.drawable.bg_circle_tool_selected)
                            setImageResource(R.drawable.ic_check)
                            imageTintList = ColorStateList.valueOf(Color.parseColor("#001D35"))
                            setPadding((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
                        }
                        addView(checkBadge)
                    }
                } else {
                    val iv = ImageView(this@ImageEditorActivity).apply {
                        val iconSize = (24 * density).toInt()
                        layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                            gravity = Gravity.CENTER
                        }
                        setImageResource(if (isSelected) R.drawable.ic_check else R.drawable.ic_image)
                        imageTintList = ColorStateList.valueOf(Color.WHITE)
                    }
                    addView(iv)
                }
            }

            val label = TextView(this).apply {
                text = getString(labelRes)
                textSize = 11f
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#A0A0A0"))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            itemLayout.addView(card)
            itemLayout.addView(label)
            filtersContainer.addView(itemLayout)
        }
    }

    private fun setupMarkupControls() {
        updateMarkupToolButtons()
    }

    private fun updateMarkupToolButtons() {
        markupToolsRow.removeAllViews()
        val density = resources.displayMetrics.density

        val tools = listOf(
            MarkupTool.DOODLE to (R.string.editor_markup_doodle to R.drawable.ic_edit),
            MarkupTool.SHAPES to (R.string.editor_markup_shapes to R.drawable.ic_crop_ratio),
            MarkupTool.TEXT to (R.string.editor_markup_text to R.drawable.ic_contact_page),
            MarkupTool.MOSAIC to (R.string.editor_markup_mosaic to R.drawable.ic_compress),
        )

        tools.forEach { (tool, pair) ->
            val (labelRes, iconRes) = pair
            val isSelected = annotationOverlayView.activeTool == tool

            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectMarkupTool(tool)
                }
            }

            val circle = FrameLayout(this).apply {
                val size = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setBackgroundResource(
                    if (isSelected) R.drawable.bg_circle_tool_selected else R.drawable.bg_circle_tool
                )

                val iv = ImageView(this@ImageEditorActivity).apply {
                    val iconSize = (24 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(
                        if (isSelected) Color.parseColor("#001D35") else Color.WHITE
                    )
                }
                addView(iv)
            }

            val label = TextView(this).apply {
                text = getString(labelRes)
                textSize = 11f
                setTextColor(if (isSelected) Color.parseColor("#A8C7FA") else Color.parseColor("#B0B0B0"))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            itemLayout.addView(circle)
            itemLayout.addView(label)
            markupToolsRow.addView(itemLayout)
        }
    }

    private fun selectMarkupTool(tool: MarkupTool) {
        annotationOverlayView.activeTool = tool
        updateMarkupToolButtons()
        showMarkupToolOptions(tool)

        if (tool != MarkupTool.NONE) {
            // Category Tabs automatically disappear in sub-editing mode (text-on-images.jpg & use-arrow-tool.jpg)
            setCategoryTabsVisible(false)
            regularActionBar.isVisible = false
            subEditActionBar.isVisible = true

            subEditCancelButton.setText(R.string.editor_markup_clear)
            subEditCancelButton.setOnClickListener {
                annotationOverlayView.clear()
            }
            subEditUndoButton.isVisible = true
            subEditUndoButton.isEnabled = annotationOverlayView.hasAnnotations()
            subEditUndoButton.alpha = if (annotationOverlayView.hasAnnotations()) 1f else 0.4f
            subEditUndoButton.setOnClickListener {
                annotationOverlayView.undo()
            }
            subEditDoneButton.setOnClickListener {
                exitMarkupSubEdit()
            }
        } else {
            exitMarkupSubEdit()
        }
    }

    private fun exitMarkupSubEdit() {
        annotationOverlayView.activeTool = MarkupTool.NONE
        updateMarkupToolButtons()
        markupOptionsScroll.isVisible = false
        // Category Tabs bar reappears when exiting sub-editing mode (google-photos-markup.jpg)
        setCategoryTabsVisible(true)
        regularActionBar.isVisible = true
        subEditActionBar.isVisible = false
    }

    private fun showMarkupToolOptions(tool: MarkupTool) {
        markupOptionsContent.removeAllViews()
        markupOptionsScroll.isVisible = (tool != MarkupTool.NONE)

        when (tool) {
            MarkupTool.DOODLE -> {
                addStrokeWidthSlider()
                addColorPalette()
            }
            MarkupTool.SHAPES -> {
                addShapeTypeSelector()
                addStrokeWidthSlider()
                addColorPalette()
            }
            MarkupTool.TEXT -> {
                val addTextBtn = TextView(this).apply {
                    text = getString(R.string.editor_markup_add_text)
                    setBackgroundResource(R.drawable.bg_google_photos_pill_selected)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    setPadding(32, 16, 32, 16)
                    setOnClickListener {
                        val rect = cropImageView.getCropRect()
                        val cx = if (!rect.isEmpty) rect.centerX() else cropImageView.width / 2f
                        val cy = if (!rect.isEmpty) rect.centerY() else cropImageView.height / 2f
                        annotationOverlayView.addTextItem("", cx, cy)
                    }
                }
                markupOptionsContent.addView(addTextBtn)

                val bgChip = Chip(this).apply {
                    text = getString(R.string.editor_markup_text_bg)
                    isCheckable = true
                    isChecked = annotationOverlayView.hasTextBackgroundPill
                    setOnCheckedChangeListener { _, isChecked ->
                        annotationOverlayView.hasTextBackgroundPill = isChecked
                        annotationOverlayView.activeText?.hasBackgroundPill = isChecked
                        annotationOverlayView.invalidate()
                    }
                }
                markupOptionsContent.addView(bgChip)

                addColorPalette { c ->
                    annotationOverlayView.activeText?.color = c
                    annotationOverlayView.invalidate()
                }
            }
            MarkupTool.MOSAIC -> {
                val cellSizes = listOf(
                    8 to R.string.editor_mosaic_low,
                    16 to R.string.editor_mosaic_medium,
                    24 to R.string.editor_mosaic_high,
                )
                val cellGroup = ChipGroup(this).apply {
                    isSingleSelection = true
                    isSingleLine = true
                    isSelectionRequired = true
                }
                cellSizes.forEachIndexed { index, (cs, labelRes) ->
                    val chip = Chip(this).apply {
                        text = getString(labelRes)
                        isCheckable = true
                        id = index
                        isChecked = cs == annotationOverlayView.activeMosaicCellSize
                    }
                    chip.setOnClickListener {
                        annotationOverlayView.activeMosaicCellSize = cs
                    }
                    cellGroup.addView(chip)
                }
                markupOptionsContent.addView(cellGroup)

                val density = resources.displayMetrics.density
                val mosaicBrushContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                }

                val currentBrushDp = (kotlin.math.round((annotationOverlayView.activeMosaicBrushSize / density) / 2f) * 2f).coerceIn(10f, 60f)
                val brushLabel = TextView(this).apply {
                    text = "${getString(R.string.editor_markup_mosaic_brush)}: ${currentBrushDp.toInt()}dp"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    minWidth = (96 * density).toInt()
                }

                val brushSlider = Slider(this).apply {
                    layoutParams = LinearLayout.LayoutParams((150 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                    valueFrom = 10f
                    valueTo = 60f
                    stepSize = 2f
                    value = currentBrushDp
                    thumbTintList = ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
                    trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
                    trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#3C4043"))
                    haloTintList = ColorStateList.valueOf(Color.parseColor("#33A8C7FA"))

                    addOnChangeListener { _, valDp, _ ->
                        annotationOverlayView.activeMosaicBrushSize = valDp * density
                        brushLabel.text = "${getString(R.string.editor_markup_mosaic_brush)}: ${valDp.toInt()}dp"
                    }
                }

                mosaicBrushContainer.addView(brushLabel)
                mosaicBrushContainer.addView(brushSlider)

                markupOptionsContent.addView(mosaicBrushContainer)
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

    private fun addStrokeWidthSlider() {
        val density = resources.displayMetrics.density

        val sliderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        val currentDp = kotlin.math.round(annotationOverlayView.activeStrokeWidth / density).coerceIn(2f, 40f)

        val dotPreview = View(this).apply {
            val sizePx = (currentDp * density).toInt().coerceIn(4, 28)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = (8 * density).toInt()
            }
            setBackgroundResource(R.drawable.bg_circle_tool_selected)
        }

        val label = TextView(this).apply {
            text = "${getString(R.string.editor_markup_stroke_width)}: ${currentDp.toInt()}dp"
            setTextColor(Color.WHITE)
            textSize = 12f
            minWidth = (96 * density).toInt()
        }

        val slider = Slider(this).apply {
            layoutParams = LinearLayout.LayoutParams((150 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            valueFrom = 2f
            valueTo = 40f
            stepSize = 1f
            value = currentDp
            thumbTintList = ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
            trackActiveTintList = ColorStateList.valueOf(Color.parseColor("#A8C7FA"))
            trackInactiveTintList = ColorStateList.valueOf(Color.parseColor("#3C4043"))
            haloTintList = ColorStateList.valueOf(Color.parseColor("#33A8C7FA"))

            addOnChangeListener { _, valDp, _ ->
                val px = valDp * density
                annotationOverlayView.activeStrokeWidth = px
                label.text = "${getString(R.string.editor_markup_stroke_width)}: ${valDp.toInt()}dp"
                val previewSize = px.toInt().coerceIn(4, 28)
                dotPreview.layoutParams = LinearLayout.LayoutParams(previewSize, previewSize).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
        }

        sliderContainer.addView(dotPreview)
        sliderContainer.addView(label)
        sliderContainer.addView(slider)

        markupOptionsContent.addView(sliderContainer)
    }

    private fun addColorPalette(onColorChanged: ((Int) -> Unit)? = null) {
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
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 0, 8, 0)
        }

        colors.forEach { c ->
            val card = MaterialCardView(this).apply {
                radius = 14f * resources.displayMetrics.density
                cardElevation = 0f
                strokeWidth = if (c == annotationOverlayView.activeColor) (2.5f * resources.displayMetrics.density).toInt() else 0
                strokeColor = Color.parseColor("#A8C7FA")
                setCardBackgroundColor(c)
                val size = (28f * resources.displayMetrics.density).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(6, 0, 6, 0)
                }
                layoutParams = lp
                isClickable = true
                setOnClickListener {
                    annotationOverlayView.activeColor = c
                    onColorChanged?.invoke(c)
                    addColorPalette(onColorChanged)
                }
            }
            paletteContainer.addView(card)
        }
        markupOptionsContent.addView(paletteContainer)
    }

    private fun showTextInputDialog(textItem: AnnotationItem.Text) {
        val input = TextInputEditText(this).apply {
            setText(textItem.text)
            hint = getString(R.string.editor_markup_text_hint)
            setSelection(textItem.text.length)
            isSingleLine = false
            maxLines = 4
        }
        val container = FrameLayout(this).apply {
            val pad = (20f * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.editor_markup_text)
            .setView(container)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (textItem.text.isBlank()) {
                    annotationOverlayView.undo()
                }
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val str = input.text?.toString()?.trim() ?: ""
                if (str.isNotBlank()) {
                    textItem.text = str
                    annotationOverlayView.invalidate()
                    annotationOverlayView.onAnnotationChanged?.invoke()
                } else {
                    annotationOverlayView.undo()
                }
            }
            .show()
    }

    /** Rebuild the displayed (rotated + flipped + filtered + adjusted) bitmap on the crop view. */
    private fun refreshBaseBitmap() {
        val bmp = baseBitmap ?: return
        lifecycleScope.launch {
            val displayed = withContext(Dispatchers.Default) {
                val transformed = transformBitmap(bmp, rotation.toFloat() + fineRotation, isFlippedHorizontal)
                val filtered = applyFilter(transformed, currentFilter)
                if (filtered !== transformed && transformed !== bmp) transformed.recycle()
                val adjusted = ImageAdjuster.applyAdjustments(filtered, adjustParameters)
                if (adjusted !== filtered && filtered !== bmp) filtered.recycle()
                adjusted
            }
            cropImageView.setBitmap(displayed)
            annotationOverlayView.invalidateMosaicCache()
        }
    }

    private fun onSave() {
        val cropped = cropImageView.crop()
        val result = if (cropped != null) {
            cropped
        } else {
            val bmp = baseBitmap ?: return
            val transformed = transformBitmap(bmp, rotation.toFloat() + fineRotation, isFlippedHorizontal)
            val filtered = applyFilter(transformed, currentFilter)
            if (filtered !== transformed && transformed !== bmp) transformed.recycle()
            val adjusted = ImageAdjuster.applyAdjustments(filtered, adjustParameters)
            if (adjusted !== filtered && filtered !== bmp) filtered.recycle()
            adjusted
        }
        val finalResult = if (annotationOverlayView.hasAnnotations()) {
            val canvasBmp = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBmp)
            canvas.drawBitmap(result, 0f, 0f, null)
            annotationOverlayView.rasterize(canvas, cropImageView.getCropRect(), result)
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
        watermarkLabel.setTextColor(if (hasWatermark) Color.parseColor("#A8C7FA") else Color.parseColor("#B0B0B0"))
        watermarkIcon.imageTintList = ColorStateList.valueOf(if (hasWatermark) Color.parseColor("#A8C7FA") else Color.WHITE)
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
        val target = contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Cannot insert MediaStore row")
        contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Cannot open output stream")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(target, values, null, null)
        return target
    }

    private fun showSaving() { savingOverlay.isVisible = true }
    private fun hideSaving() { savingOverlay.isVisible = false }
    private fun showFailed() {
        hideSaving()
        Toast.makeText(this, R.string.editor_save_failed, Toast.LENGTH_LONG).show()
    }
    private fun finishWithSaved() {
        hideSaving()
        Toast.makeText(this, R.string.editor_saved, Toast.LENGTH_SHORT).show()
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > 4096) sample *= 2
        opts.inSampleSize = sample

        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    private fun transformBitmap(src: Bitmap, degrees: Float, flipHorizontal: Boolean): Bitmap {
        if (abs(degrees % 360f) < 0.01f && !flipHorizontal) return src
        val m = Matrix().apply {
            if (abs(degrees % 360f) >= 0.01f) postRotate(degrees)
            if (flipHorizontal) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    private fun applyFilter(src: Bitmap, filter: ImageFilter): Bitmap {
        if (filter == ImageFilter.NONE) return src
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun encode(bmp: Bitmap, mimeType: String): ByteArray {
        val out = ByteArrayOutputStream()
        val format = when {
            mimeType.contains("png", ignoreCase = true) -> Bitmap.CompressFormat.PNG
            mimeType.contains("webp", ignoreCase = true) -> Bitmap.CompressFormat.WEBP
            else -> Bitmap.CompressFormat.JPEG
        }
        val quality = if (format == Bitmap.CompressFormat.PNG) 100 else 92
        bmp.compress(format, quality, out)
        return out.toByteArray()
    }

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MIME_TYPE = "mime_type"

        fun createIntent(context: android.content.Context, uri: Uri, mimeType: String?) =
            Intent(context, ImageEditorActivity::class.java).apply {
                if (mimeType != null) {
                    setDataAndType(uri, mimeType)
                } else {
                    data = uri
                }
                putExtra(EXTRA_MEDIA_URI, uri)
            }
    }
}
