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
import android.graphics.RectF
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.ext.editSaveBehavior
import org.lineageos.glimpse.ext.createWriteRequest
import org.lineageos.glimpse.models.BrushColorMode
import org.lineageos.glimpse.models.CustomBrush
import org.lineageos.glimpse.models.CustomCropRatioManager
import org.lineageos.glimpse.models.CustomEditorAssetManager
import org.lineageos.glimpse.models.CustomShape
import org.lineageos.glimpse.models.CustomShapeParseResult
import org.lineageos.glimpse.ui.views.AngleRulerView
import org.lineageos.glimpse.ui.views.AnnotationItem
import org.lineageos.glimpse.ui.views.AnnotationOverlayView
import org.lineageos.glimpse.ui.views.BrushType
import org.lineageos.glimpse.ui.views.CropImageView
import org.lineageos.glimpse.ui.views.MarkupTool
import org.lineageos.glimpse.ui.views.MosaicEffect
import org.lineageos.glimpse.ui.views.ShapeStyle
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
    private val markupToolsScroll by lazy { findViewById<HorizontalScrollView>(R.id.markupToolsScroll) }
    private var markupAdvancedExpanded = false
    private var activeCustomMosaicId: String? = null
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
    // The bitmap transform includes the translation used by Bitmap.createBitmap.
    private var baseToDisplayBitmap = Matrix()
    private fun baseToView(): Matrix = Matrix().apply {
        setConcat(cropImageView.getImageMatrix(), baseToDisplayBitmap)
    }
    private var rotation: Int = 0
    private var fineRotation: Float = 0f
    private var currentFilter: ImageFilter = ImageFilter.NONE
    private var filterThumbnails: Map<ImageFilter, Bitmap>? = null
    private var adjustParameters = AdjustParameters()
    private var activeAdjustParam: AdjustParam? = null

    private var activeCategory = EditorCategory.CROP
    private var isFramingModified = false
    private var isFlippedHorizontal = false
    private var refreshJob: Job? = null
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

    private val pickCustomStickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val asset = CustomEditorAssetManager.importCustomSticker(this, uri)
            val bmp = asset?.loadBitmap() ?: loadStickerBitmap(uri)
            if (bmp != null) {
                annotationOverlayView.addSticker(bmp)
                showMarkupToolOptions(MarkupTool.STICKER)
            } else {
                Toast.makeText(this, R.string.editor_sticker_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (idx != -1) return cursor.getString(idx)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private val pickBrushTextureLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val fileName = getFileNameFromUri(uri)
            val name = fileName?.substringBeforeLast(".") ?: getString(R.string.editor_brush_import)
            val isSvg = fileName?.endsWith(".svg", true) == true ||
                runCatching { contentResolver.getType(uri).equals("image/svg+xml", true) }.getOrDefault(false)
            val brush = CustomEditorAssetManager.importCustomBrush(this, uri, name)
            if (brush != null) {
                annotationOverlayView.activeBrushType = BrushType.CUSTOM_TEXTURE
                annotationOverlayView.activeCustomBrush = brush
                annotationOverlayView.activeCustomBrushTipBitmap = brush.loadTipBitmap()
                annotationOverlayView.activeStrokeWidth = brush.sizeDp * resources.displayMetrics.density
                showMarkupToolOptions(MarkupTool.DOODLE)
            } else {
                Toast.makeText(this, if (isSvg) R.string.editor_brush_svg_failed
                    else R.string.editor_brush_import_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val pickCustomShapeLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                val name = getFileNameFromUri(uri)?.substringBeforeLast(".") ?: getString(R.string.editor_shape_custom)
                when (val result = CustomEditorAssetManager.importCustomShape(this, content, name)) {
                    is CustomShapeParseResult.Success -> {
                        val shapes = CustomEditorAssetManager.getCustomShapes(this)
                        val imported = shapes.firstOrNull()
                        if (imported != null) {
                            annotationOverlayView.selectedShapeType = ShapeType.CUSTOM
                            annotationOverlayView.activeCustomShape = imported
                            showMarkupToolOptions(MarkupTool.SHAPES)
                        }
                    }
                    is CustomShapeParseResult.Error -> {
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this, R.string.editor_shape_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickMosaicTextureLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val asset = CustomEditorAssetManager.importCustomMosaic(this, uri)
            val bmp = asset?.loadBitmap()
            if (bmp != null) {
                annotationOverlayView.activeMosaicEffect = MosaicEffect.CUSTOM_PATTERN
                annotationOverlayView.activeCustomMosaicPattern = bmp
                activeCustomMosaicId = asset?.id
                showMarkupToolOptions(MarkupTool.MOSAIC)
            }
        }
    }

    private val pickRatioConfigLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                contentResolver.openInputStream(uri)?.reader()?.use { it.readText() }
            }.getOrNull()
            if (!content.isNullOrBlank()) {
                val parsed = CustomCropRatioManager.parseConfigFile(content)
                if (parsed.isNotEmpty()) {
                    parsed.forEach { CustomCropRatioManager.saveRatio(this, it) }
                    val msg = getString(R.string.editor_ratio_import_success, parsed.size)
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.editor_ratio_import_failed, Toast.LENGTH_SHORT).show()
                }
            }
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
        findViewById<View>(R.id.editorRootLayout).addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop && annotationOverlayView.activeTool != MarkupTool.NONE) {
                updateMarkupPanelHeight()
            }
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
            annotationOverlayView.isMarkupRestrictedToCrop = cropImageView.hasUserModifiedCrop
            annotationOverlayView.invalidate()
        }
        cropImageView.onPreviewGeometryChanged = {
            annotationOverlayView.isMarkupRestrictedToCrop = cropImageView.hasUserModifiedCrop
            annotationOverlayView.invalidate()
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
        annotationOverlayView.imageBoundsProvider = { cropImageView.getImageBounds() }
        annotationOverlayView.displayBitmapProvider = { cropImageView.getDisplayBitmap() }
        annotationOverlayView.imageMatrixProvider = { baseToView() }
        annotationOverlayView.displayToBaseMatrixProvider = {
            Matrix().also { baseToDisplayBitmap.invert(it) }
        }
        annotationOverlayView.baseBoundsProvider = {
            baseBitmap?.let { RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) } ?: RectF()
        }
        annotationOverlayView.isPreviewFrameAvailable = { cropImageView.getDisplayBitmap() != null }
        annotationOverlayView.onPreviewGesture = { px, py, x, y, scale ->
            cropImageView.updatePreviewGesture(px, py, x, y, scale)
        }
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
                minWidth = (48 * density).toInt()
                minHeight = (48 * density).toInt()
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
        annotationOverlayView.isMarkupRestrictedToCrop = cropImageView.hasUserModifiedCrop
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
        val scrollView = androidx.core.widget.NestedScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            setPadding(16, 24, 16, 32)
        }

        data class RatioItem(val label: String, val ratio: Float)
        val originalRatio = baseBitmap?.let { bmp ->
            if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else null
        }

        val ratios = mutableListOf<RatioItem>()
        ratios.add(RatioItem(getString(R.string.editor_crop_free), 0f))
        if (originalRatio != null && originalRatio > 0.01f) {
            ratios.add(RatioItem(getString(R.string.ratio_original), originalRatio))
        }
        ratios.add(RatioItem(getString(R.string.ratio_square), 1f))
        ratios.add(RatioItem(getString(R.string.ratio_4_3), 4f / 3f))
        ratios.add(RatioItem(getString(R.string.ratio_3_4), 3f / 4f))
        ratios.add(RatioItem(getString(R.string.ratio_16_9), 16f / 9f))
        ratios.add(RatioItem(getString(R.string.ratio_9_16), 9f / 16f))
        ratios.add(RatioItem(getString(R.string.ratio_3_2), 3f / 2f))
        ratios.add(RatioItem(getString(R.string.ratio_2_3), 2f / 3f))
        ratios.add(RatioItem(getString(R.string.ratio_5_7), 5f / 7f))

        // Custom Presets Header if saved
        val savedCustom = CustomCropRatioManager.getSavedRatios(this)
        if (savedCustom.isNotEmpty()) {
            val header = TextView(this).apply {
                text = getString(R.string.ratio_custom_presets_header)
                textSize = 12f
                setTextColor(Color.parseColor("#8E9192"))
                setPadding(24, 24, 24, 8)
            }
            list.addView(header)

            savedCustom.forEach { custom ->
                val tv = TextView(this).apply {
                    text = custom.label
                    textSize = 15f
                    setPadding(24, 20, 24, 20)
                    val isSelected = abs(cropImageView.aspectRatio - custom.ratio) < 0.01f
                    setTextColor(if (isSelected) Color.parseColor("#A8C7FA") else Color.WHITE)
                    setOnClickListener {
                        val old = cropImageView.aspectRatio
                        cropImageView.applyPresetRatio(custom.ratio, old)
                        cropRatioLabel.text = custom.label
                        dialog.dismiss()
                    }
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(this@ImageEditorActivity)
                            .setMessage("删除预设 \"${custom.label}\"？")
                            .setPositiveButton("删除") { _, _ ->
                                CustomCropRatioManager.deleteRatio(this@ImageEditorActivity, custom.ratio)
                                dialog.dismiss()
                                showRatioBottomSheet()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                        true
                    }
                }
                list.addView(tv)
            }
        }

        ratios.forEach { item ->
            val tv = TextView(this).apply {
                text = item.label
                textSize = 15f
                setPadding(24, 20, 24, 20)
                val isSelected = (item.ratio == 0f && cropImageView.aspectRatio == 0f) ||
                        (item.ratio > 0f && abs(cropImageView.aspectRatio - item.ratio) < 0.01f)
                setTextColor(if (isSelected) Color.parseColor("#A8C7FA") else Color.WHITE)
                setOnClickListener {
                    val old = cropImageView.aspectRatio
                    if (item.ratio == 0f) {
                        cropImageView.aspectRatio = 0f
                        cropImageView.computeCropRect()
                        cropImageView.invalidate()
                    } else {
                        cropImageView.applyPresetRatio(item.ratio, old)
                    }
                    cropRatioLabel.text = item.label
                    dialog.dismiss()
                }
            }
            list.addView(tv)
        }

        val customBtn = TextView(this).apply {
            text = "+ " + getString(R.string.ratio_custom)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#A8C7FA"))
            setPadding(24, 24, 24, 20)
            setOnClickListener {
                dialog.dismiss()
                showCustomRatioDialog()
            }
        }
        list.addView(customBtn)

        scrollView.addView(list)
        dialog.setContentView(scrollView)
        dialog.show()
    }

    private fun showCustomRatioDialog() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val widthInput = TextInputEditText(this).apply {
            hint = getString(R.string.ratio_custom_width)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val colonTv = TextView(this).apply {
            text = " : "
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }

        val heightInput = TextInputEditText(this).apply {
            hint = getString(R.string.ratio_custom_height)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        row.addView(widthInput)
        row.addView(colonTv)
        row.addView(heightInput)
        container.addView(row)

        val labelInput = TextInputEditText(this).apply {
            hint = "预设名称（可选，如 21:9、A4）"
            setPadding(0, (16 * density).toInt(), 0, (8 * density).toInt())
        }
        container.addView(labelInput)

        val saveCheckBox = android.widget.CheckBox(this).apply {
            text = getString(R.string.ratio_custom_save)
            isChecked = true
            setTextColor(Color.WHITE)
        }
        container.addView(saveCheckBox)

        val importFileBtn = TextView(this).apply {
            text = getString(R.string.editor_ratio_import_file)
            setTextColor(Color.parseColor("#A8C7FA"))
            textSize = 13f
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
            setOnClickListener {
                pickRatioConfigLauncher.launch("*/*")
            }
        }
        container.addView(importFileBtn)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ratio_custom_title)
            .setView(container)
            .setPositiveButton("应用") { _, _ ->
                val w = widthInput.text?.toString()?.toFloatOrNull()
                val h = heightInput.text?.toString()?.toFloatOrNull()
                if (w == null || h == null || !CustomCropRatioManager.validate(w, h)) {
                    Toast.makeText(this, R.string.ratio_custom_error, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ratio = w / h
                val customName = labelInput.text?.toString()?.takeIf { it.isNotBlank() }
                val label = CustomCropRatioManager.formatLabel(w, h, customName)

                if (saveCheckBox.isChecked) {
                    CustomCropRatioManager.saveRatio(this, CustomCropRatioManager.CustomRatio(label, w, h))
                }

                val old = cropImageView.aspectRatio
                cropImageView.applyPresetRatio(ratio, old)
                cropRatioLabel.text = label
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupAdjustControls() {
        updateAdjustToolItems()

        adjustSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val param = activeAdjustParam ?: return@addOnChangeListener
            val intVal = value.toInt()
            adjustValueText.text = if (intVal > 0 && param.isBipolar) "+$intVal" else "$intVal"

            setAdjustParamValue(param, intVal)
            refreshBaseBitmap(debounceMs = 32)
        }

        adjustSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}

            override fun onStopTrackingTouch(slider: Slider) {
                refreshBaseBitmap(debounceMs = 0)
                updateAdjustToolItems()
            }
        })

        adjustResetButton.setOnClickListener {
            val param = activeAdjustParam ?: return@setOnClickListener
            adjustSlider.value = 0f
            adjustValueText.text = "0"
            setAdjustParamValue(param, 0)
            refreshBaseBitmap(debounceMs = 0)
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

    private fun markupBackground(selected: Boolean, circle: Boolean = false): android.graphics.drawable.Drawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = if (circle) android.graphics.drawable.GradientDrawable.OVAL else android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24f * resources.displayMetrics.density
            setColor(MaterialColors.getColor(markupToolsRow, if (selected)
                com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceVariant))
        }

    private fun updateMarkupToolButtons() {
        markupToolsRow.removeAllViews()
        val density = resources.displayMetrics.density

        val tools = listOf(
            MarkupTool.DOODLE to (R.string.editor_markup_doodle to R.drawable.ic_markup_brush),
            MarkupTool.SHAPES to (R.string.editor_markup_shapes to R.drawable.ic_markup_shapes),
            MarkupTool.TEXT to (R.string.editor_markup_text to R.drawable.ic_markup_text),
            MarkupTool.MOSAIC to (R.string.editor_markup_mosaic to R.drawable.ic_markup_mosaic),
            MarkupTool.STICKER to (R.string.editor_markup_sticker to R.drawable.ic_markup_sticker),
        )

        // Reserve the same width for every tool, using the widest bold label so
        // changing selection does not shift adjacent tools in any locale.
        val compact = annotationOverlayView.activeTool != MarkupTool.NONE
        val measuringLabel = TextView(this).apply {
            textSize = if (compact) 14f else 11f
            setTypeface(null, Typeface.BOLD)
        }
        val itemWidth = maxOf((64 * density).toInt(),
            kotlin.math.ceil(tools.maxOf { measuringLabel.paint.measureText(getString(it.second.first)) }).toInt() +
                (16 * density).toInt())

        tools.forEach { (tool, pair) ->
            val (labelRes, iconRes) = pair
            val selected = annotationOverlayView.activeTool == tool
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    itemWidth, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minimumWidth = (64 * density).toInt()
                minimumHeight = (48 * density).toInt()
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                isClickable = true
                isFocusable = true
                isSelected = selected
                if (compact && selected) background = markupBackground(true)
                contentDescription = getString(labelRes)
                val outValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                foreground = getDrawable(outValue.resourceId)
                setOnClickListener {
                    selectMarkupTool(tool)
                }
            }

            val circle = FrameLayout(this).apply {
                val size = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                background = markupBackground(selected, circle = true)

                val iv = ImageView(this@ImageEditorActivity).apply {
                    val iconSize = (24 * density).toInt()
                    layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                        gravity = Gravity.CENTER
                    }
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(
                        MaterialColors.getColor(this, if (selected) com.google.android.material.R.attr.colorOnPrimaryContainer
                            else com.google.android.material.R.attr.colorOnSurfaceVariant)
                    )
                }
                addView(iv)
            }

            val label = TextView(this).apply {
                text = getString(labelRes)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                setSingleLine(true)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                textSize = if (compact) 14f else 11f
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(MaterialColors.getColor(this, if (selected) androidx.appcompat.R.attr.colorPrimary
                    else com.google.android.material.R.attr.colorOnSurface))
                setPadding(0, if (compact) 0 else (4 * density).toInt(), 0, 0)
            }

            if (!compact) itemLayout.addView(circle)
            itemLayout.addView(label)
            markupToolsRow.addView(itemLayout)
            if (selected) markupToolsScroll.post {
                markupToolsScroll.smoothScrollTo(
                    itemLayout.left - (markupToolsScroll.width - itemLayout.width) / 2, 0
                )
            }
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

    /** Each section owns its own scroll direction; sliders never live in a horizontal scroller. */
    private fun addMarkupSection(title: Int, horizontal: Boolean, showTitle: Boolean = true, build: () -> Unit) {
        val start = markupOptionsContent.childCount
        build()
        val children = mutableListOf<View>()
        while (markupOptionsContent.childCount > start) {
            val child = markupOptionsContent.getChildAt(start)
            markupOptionsContent.removeViewAt(start)
            children.add(child)
        }
        if (children.isEmpty()) return
        val density = resources.displayMetrics.density
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (showTitle) section.addView(TextView(this).apply {
            text = getString(title)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
        })
        if (horizontal) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            children.forEach { child -> row.addView(child) }
            val scroller = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                isHorizontalFadingEdgeEnabled = true
                setFadingEdgeLength((20 * density).toInt())
                addView(row)
            }
            section.addView(scroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            if (title == R.string.markup_effect_section) scroller.post {
                val group = children.filterIsInstance<ChipGroup>().firstOrNull()
                val selected = group?.let { g ->
                    (0 until g.childCount).map { g.getChildAt(it) }
                        .filterIsInstance<Chip>().firstOrNull { it.isChecked }
                }
                if (selected != null) {
                    val bounds = android.graphics.Rect()
                    selected.getDrawingRect(bounds)
                    row.offsetDescendantRectToMyCoords(selected, bounds)
                    scroller.smoothScrollTo((bounds.centerX() - scroller.width / 2).coerceAtLeast(0), 0)
                }
            }
        } else {
            children.forEach { child -> section.addView(child, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)) }
        }
        markupOptionsContent.addView(section)
    }

    private fun updateMarkupPanelHeight() {
        val screenHeight = findViewById<View>(R.id.editorRootLayout).height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val limit = minOf((260 * resources.displayMetrics.density).toInt(), (screenHeight * 0.30f).toInt())
        // Measure complete sections rather than cutting through the next heading at rest.
        val width = markupOptionsContent.width - markupOptionsContent.paddingLeft - markupOptionsContent.paddingRight
        if (width <= 0) return
        var fullHeight = markupOptionsContent.paddingTop + markupOptionsContent.paddingBottom
        var included = 0
        for (index in 0 until markupOptionsContent.childCount) {
            val child = markupOptionsContent.getChildAt(index)
            child.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (fullHeight + child.measuredHeight > limit) break
            fullHeight += child.measuredHeight
            included++
        }
        // A section taller than the viewport must remain scrollable on a small window.
        val desired = if (included > 0) fullHeight else limit
        val params = markupOptionsScroll.layoutParams
        if (params.height != desired) {
            params.height = desired
            markupOptionsScroll.layoutParams = params
        }
    }

    private fun showMarkupToolOptions(tool: MarkupTool) {
        markupOptionsContent.removeAllViews()
        markupOptionsScroll.isVisible = (tool != MarkupTool.NONE)
        when (tool) {
            MarkupTool.DOODLE -> {
                addMarkupSection(R.string.markup_brush_section, true) { addDoodleBrushTypeSelector() }
                addMarkupSection(R.string.editor_markup_stroke_width, false, showTitle = false) { addStrokeWidthSlider() }
                val custom = annotationOverlayView.activeCustomBrush
                if (custom == null || custom.colorMode != BrushColorMode.ORIGINAL) {
                    addMarkupSection(R.string.markup_color_section, true) { addColorPalette() }
                } else {
                    addMarkupSection(R.string.markup_color_section, false) {
                        markupOptionsContent.addView(TextView(this).apply {
                            text = getString(R.string.editor_brush_original_hint)
                            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                        })
                    }
                }
                if (custom != null) {
                    val toggle = MaterialButton(this).apply {
                        text = getString(R.string.markup_advanced)
                        setAllCaps(false)
                        setOnClickListener { markupAdvancedExpanded = !markupAdvancedExpanded; showMarkupToolOptions(tool) }
                    }
                    markupOptionsContent.addView(toggle)
                    if (markupAdvancedExpanded) addMarkupSection(R.string.markup_advanced, false, showTitle = false) { addCustomBrushControls(custom) }
                }
            }
            MarkupTool.SHAPES -> {
                addMarkupSection(R.string.markup_shape_section, true) { addShapeTypeSelector() }
                addMarkupSection(R.string.markup_style_section, true) { addShapeStyleSelector() }
                addMarkupSection(R.string.editor_markup_stroke_width, false, showTitle = false) { addStrokeWidthSlider() }
                addMarkupSection(R.string.markup_color_section, true) {
                    addColorPalette { c ->
                        annotationOverlayView.activeShape?.color = c
                        annotationOverlayView.invalidate()
                    }
                }
            }
            MarkupTool.TEXT -> {
                addMarkupSection(R.string.markup_text_section, true, showTitle = false) {
                    markupOptionsContent.addView(MaterialButton(this).apply {
                        text = getString(R.string.markup_text_action)
                        setAllCaps(false)
                        setOnClickListener {
                            if (annotationOverlayView.activeText == null) {
                                val rect = cropImageView.getCropRect()
                                annotationOverlayView.addTextItem("", rect.centerX(), rect.centerY())
                            }
                            annotationOverlayView.activeText?.let { showTextInputDialog(it) }
                        }
                    })
                    markupOptionsContent.addView(Chip(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = (12 * resources.displayMetrics.density).toInt() }
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                        text = getString(R.string.editor_markup_text_bg)
                        isCheckable = true
                        isChecked = annotationOverlayView.hasTextBackgroundPill
                        setOnCheckedChangeListener { _, checked ->
                            annotationOverlayView.hasTextBackgroundPill = checked
                            annotationOverlayView.activeText?.hasBackgroundPill = checked
                            annotationOverlayView.invalidate()
                        }
                    })
                }
                addMarkupSection(R.string.markup_color_section, true) {
                    addColorPalette { c -> annotationOverlayView.activeText?.color = c; annotationOverlayView.invalidate() }
                }
            }
            MarkupTool.MOSAIC -> {
                addMarkupSection(R.string.markup_effect_section, true) { addMosaicEffectSelector() }
                addMarkupSection(R.string.editor_markup_mosaic_brush, false, showTitle = false) { addMosaicBrushSlider() }
            }
            MarkupTool.STICKER -> addMarkupSection(R.string.markup_sticker_section, true, showTitle = false) { addStickerSelector() }
            MarkupTool.NONE -> Unit
        }
        if (tool != MarkupTool.NONE) markupOptionsScroll.post { updateMarkupPanelHeight() }
    }

    private fun addDoodleBrushTypeSelector() {
        val density = resources.displayMetrics.density
        val brushTypes = listOf(
            BrushType.NORMAL to R.string.editor_brush_normal,
            BrushType.HIGHLIGHTER to R.string.editor_brush_highlighter,
            BrushType.NEON to R.string.editor_brush_neon,
            BrushType.DASHED to R.string.editor_brush_dashed,
        )
        val typeGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
            isSelectionRequired = true
        }
        brushTypes.forEachIndexed { index, (bt, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = bt == annotationOverlayView.activeBrushType && annotationOverlayView.activeCustomBrush == null
            }
            chip.setOnClickListener {
                annotationOverlayView.activeBrushType = bt
                annotationOverlayView.activeCustomBrush = null
                annotationOverlayView.activeCustomBrushTipBitmap = null
                showMarkupToolOptions(MarkupTool.DOODLE)
            }
            typeGroup.addView(chip)
        }
        markupOptionsContent.addView(typeGroup)

        val customBrushes = CustomEditorAssetManager.getCustomBrushesList(this)
        if (customBrushes.isNotEmpty()) {
            val cGroup = ChipGroup(this).apply {
                isSingleSelection = true
                isSingleLine = true
            }
            customBrushes.forEach { brush ->
                val chip = Chip(this).apply {
                    text = brush.name
                    isCheckable = true
                    isChecked = annotationOverlayView.activeCustomBrush?.id == brush.id
                }
                chip.setOnClickListener {
                    annotationOverlayView.activeBrushType = BrushType.CUSTOM_TEXTURE
                    annotationOverlayView.activeCustomBrush = brush
                    annotationOverlayView.activeCustomBrushTipBitmap = brush.loadTipBitmap()
                    annotationOverlayView.activeStrokeWidth = brush.sizeDp * density
                    showMarkupToolOptions(MarkupTool.DOODLE)
                }
                chip.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@ImageEditorActivity)
                        .setTitle(R.string.editor_brush_delete_title)
                        .setMessage(R.string.editor_brush_delete_message)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            CustomEditorAssetManager.deleteCustomBrush(this@ImageEditorActivity, brush.id)
                            if (annotationOverlayView.activeCustomBrush?.id == brush.id) {
                                annotationOverlayView.activeCustomBrush = null
                                annotationOverlayView.activeCustomBrushTipBitmap = null
                                annotationOverlayView.activeBrushType = BrushType.NORMAL
                            }
                            showMarkupToolOptions(MarkupTool.DOODLE)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
                cGroup.addView(chip)
            }
            markupOptionsContent.addView(cGroup)
        }

        val importBrushBtn = TextView(this).apply {
            minimumHeight = (48 * density).toInt()
            gravity = Gravity.CENTER
            isFocusable = true
            text = getString(R.string.editor_brush_import)
            background = markupBackground(false)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                // SVG is not offered by all image-only pickers; validate the selected file on import.
                pickBrushTextureLauncher.launch("*/*")
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (8 * density).toInt()
        }
        markupOptionsContent.addView(importBrushBtn, lp)
    }

    private fun addCustomBrushControls(brush: CustomBrush) {
        val density = resources.displayMetrics.density

        // Spacing slider (5%..200%)
        val spacingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val spacingLabel = TextView(this).apply {
            text = "${getString(R.string.editor_brush_spacing)}: ${brush.spacingPercent}%"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 12f
            minWidth = (80 * density).toInt()
        }
        val spacingSlider = Slider(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            valueFrom = 5f
            valueTo = 200f
            stepSize = 5f
            value = brush.spacingPercent.toFloat().coerceIn(5f, 200f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    brush.spacingPercent = value.toInt()
                    spacingLabel.text = "${getString(R.string.editor_brush_spacing)}: ${brush.spacingPercent}%"
                    val all = CustomEditorAssetManager.getCustomBrushesList(this@ImageEditorActivity).toMutableList()
                    val idx = all.indexOfFirst { it.id == brush.id }
                    if (idx != -1) {
                        all[idx] = brush
                        CustomEditorAssetManager.saveCustomBrushesList(this@ImageEditorActivity, all)
                    }
                }
            }
        }
        spacingSlider.id = View.generateViewId()
        spacingLabel.labelFor = spacingSlider.id
        spacingContainer.addView(spacingLabel)
        spacingContainer.addView(spacingSlider)
        markupOptionsContent.addView(spacingContainer)

        // Opacity slider (10%..100%)
        val opacityContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }
        val opacityLabel = TextView(this).apply {
            text = "${getString(R.string.editor_brush_opacity)}: ${brush.opacity}%"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 12f
            minWidth = (80 * density).toInt()
        }
        val opacitySlider = Slider(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            valueFrom = 10f
            valueTo = 100f
            stepSize = 5f
            value = brush.opacity.toFloat().coerceIn(10f, 100f)
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    brush.opacity = value.toInt()
                    opacityLabel.text = "${getString(R.string.editor_brush_opacity)}: ${brush.opacity}%"
                    val all = CustomEditorAssetManager.getCustomBrushesList(this@ImageEditorActivity).toMutableList()
                    val idx = all.indexOfFirst { it.id == brush.id }
                    if (idx != -1) {
                        all[idx] = brush
                        CustomEditorAssetManager.saveCustomBrushesList(this@ImageEditorActivity, all)
                    }
                }
            }
        }
        opacitySlider.id = View.generateViewId()
        opacityLabel.labelFor = opacitySlider.id
        opacityContainer.addView(opacityLabel)
        opacityContainer.addView(opacitySlider)
        markupOptionsContent.addView(opacityContainer)

        // Follow direction toggle chip
        val followChip = Chip(this).apply {
            text = getString(R.string.editor_brush_follow_direction)
            isCheckable = true
            isChecked = brush.followDirection
            setOnCheckedChangeListener { _, isChecked ->
                brush.followDirection = isChecked
                val all = CustomEditorAssetManager.getCustomBrushesList(this@ImageEditorActivity).toMutableList()
                val idx = all.indexOfFirst { it.id == brush.id }
                if (idx != -1) {
                    all[idx] = brush
                    CustomEditorAssetManager.saveCustomBrushesList(this@ImageEditorActivity, all)
                }
            }
        }
        markupOptionsContent.addView(followChip)
    }

    private fun addShapeTypeSelector() {
        val density = resources.displayMetrics.density
        val shapeTypes = listOf(
            ShapeType.ARROW to R.string.editor_shape_arrow,
            ShapeType.DOUBLE_ARROW to R.string.editor_shape_double_arrow,
            ShapeType.RECTANGLE to R.string.editor_shape_rect,
            ShapeType.ROUNDED_RECTANGLE to R.string.editor_shape_round_rect,
            ShapeType.ELLIPSE to R.string.editor_shape_circle,
            ShapeType.LINE to R.string.editor_shape_line,
            ShapeType.TRIANGLE to R.string.editor_shape_triangle,
            ShapeType.STAR to R.string.editor_shape_star,
            ShapeType.DIAMOND to R.string.editor_shape_diamond,
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
                isChecked = st == annotationOverlayView.selectedShapeType && annotationOverlayView.activeCustomShape == null
            }
            chip.setOnClickListener {
                annotationOverlayView.selectedShapeType = st
                annotationOverlayView.activeCustomShape = null
                showMarkupToolOptions(MarkupTool.SHAPES)
            }
            chipGroup.addView(chip)
        }
        markupOptionsContent.addView(chipGroup)

        val customShapes = CustomEditorAssetManager.getCustomShapes(this)
        if (customShapes.isNotEmpty()) {
            val sGroup = ChipGroup(this).apply {
                isSingleSelection = true
                isSingleLine = true
            }
            customShapes.forEach { shape ->
                val chip = Chip(this).apply {
                    text = shape.name
                    isCheckable = true
                    isChecked = annotationOverlayView.activeCustomShape?.id == shape.id
                }
                chip.setOnClickListener {
                    annotationOverlayView.selectedShapeType = ShapeType.CUSTOM
                    annotationOverlayView.activeCustomShape = shape
                    showMarkupToolOptions(MarkupTool.SHAPES)
                }
                chip.setOnLongClickListener {
                    MaterialAlertDialogBuilder(this@ImageEditorActivity)
                        .setTitle(R.string.editor_shape_delete_title)
                        .setMessage(R.string.editor_shape_delete_message)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            CustomEditorAssetManager.deleteCustomShape(this@ImageEditorActivity, shape.id)
                            if (annotationOverlayView.activeCustomShape?.id == shape.id) {
                                annotationOverlayView.activeCustomShape = null
                                annotationOverlayView.selectedShapeType = ShapeType.ARROW
                            }
                            showMarkupToolOptions(MarkupTool.SHAPES)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    true
                }
                sGroup.addView(chip)
            }
            markupOptionsContent.addView(sGroup)
        }

        val importShapeBtn = TextView(this).apply {
            minimumHeight = (48 * density).toInt()
            gravity = Gravity.CENTER
            isFocusable = true
            text = getString(R.string.editor_shape_import)
            background = markupBackground(false)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                pickCustomShapeLauncher.launch("*/*")
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = (8 * density).toInt()
        }
        markupOptionsContent.addView(importShapeBtn, lp)
    }

    private fun addShapeStyleSelector() {
        val styles = listOf(
            ShapeStyle.OUTLINE to R.string.editor_shape_style_outline,
            ShapeStyle.SOLID to R.string.editor_shape_style_solid,
            ShapeStyle.TRANSLUCENT to R.string.editor_shape_style_translucent,
        )
        val styleGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSingleLine = true
            isSelectionRequired = true
        }
        styles.forEachIndexed { index, (st, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                id = index
                isChecked = st == annotationOverlayView.activeShapeStyle
            }
            chip.setOnClickListener {
                annotationOverlayView.activeShapeStyle = st
                annotationOverlayView.activeShape?.let { s ->
                    s.style = st
                    annotationOverlayView.invalidate()
                }
            }
            styleGroup.addView(chip)
        }
        markupOptionsContent.addView(styleGroup)

    }

    private fun addMosaicEffectSelector() {
        val density = resources.displayMetrics.density
        // One mutually exclusive row replaces the old category + effect double selection.
        val effects = listOf(
            MosaicEffect.PIXELATE to R.string.editor_mosaic_pixelate,
            MosaicEffect.BLUR to R.string.editor_mosaic_blur,
            MosaicEffect.BLACKOUT to R.string.editor_mosaic_blackout,
            MosaicEffect.STRIPES to R.string.editor_mosaic_stripes,
            MosaicEffect.NOISE to R.string.editor_mosaic_noise,
        )
        val group = ChipGroup(this).apply { isSingleSelection = true; isSingleLine = true }
        effects.forEach { (effect, label) ->
            val chip = Chip(this).apply {
                text = getString(label)
                isCheckable = true
                isChecked = annotationOverlayView.activeMosaicEffect == effect
                setOnClickListener {
                    annotationOverlayView.activeMosaicEffect = effect
                    annotationOverlayView.activeCustomMosaicPattern = null
                    activeCustomMosaicId = null
                    if (effect == MosaicEffect.PIXELATE) annotationOverlayView.activeMosaicCellSize = 16
                    showMarkupToolOptions(MarkupTool.MOSAIC)
                }
            }
            group.addView(chip)
        }
        CustomEditorAssetManager.getCustomMosaics(this).forEach { asset ->
            group.addView(Chip(this).apply {
                text = asset.name
                isCheckable = true
                isChecked = annotationOverlayView.activeMosaicEffect == MosaicEffect.CUSTOM_PATTERN &&
                    activeCustomMosaicId == asset.id
                setOnClickListener {
                    asset.loadBitmap()?.let { bitmap ->
                        annotationOverlayView.activeMosaicEffect = MosaicEffect.CUSTOM_PATTERN
                        annotationOverlayView.activeCustomMosaicPattern = bitmap
                        activeCustomMosaicId = asset.id
                        showMarkupToolOptions(MarkupTool.MOSAIC)
                    }
                }
            })
        }
        markupOptionsContent.addView(group)
        markupOptionsContent.addView(TextView(this).apply {
            minimumHeight = (48 * density).toInt()
            gravity = Gravity.CENTER
            isFocusable = true
            text = getString(R.string.editor_mosaic_import)
            background = markupBackground(false)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 12f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setOnClickListener { pickMosaicTextureLauncher.launch("image/*") }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * density).toInt() })
    }

    private fun addStickerSelector() {
        val density = resources.displayMetrics.density
        val importBtn = TextView(this).apply {
            minimumHeight = (48 * density).toInt()
            gravity = Gravity.CENTER
            isFocusable = true
            text = getString(R.string.editor_sticker_import)
            background = markupBackground(false)
            setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setOnClickListener {
                pickCustomStickerLauncher.launch("image/*")
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = (8 * density).toInt()
        }
        markupOptionsContent.addView(importBtn, lp)

        val customStickers = CustomEditorAssetManager.getCustomStickers(this)
        customStickers.forEach { asset ->
            val iv = ImageView(this).apply {
                val cardSize = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
                    marginEnd = (8 * density).toInt()
                }
                background = markupBackground(false, circle = true)
                contentDescription = asset.name
                isFocusable = true
                val inset = (4 * density).toInt()
                setPadding(inset, inset, inset, inset)
                val bmp = asset.loadBitmap()
                if (bmp != null) setImageBitmap(bmp)
                setOnClickListener {
                    asset.loadBitmap()?.let { b ->
                        annotationOverlayView.addSticker(b)
                        showMarkupToolOptions(MarkupTool.STICKER)
                    }
                }
            }
            markupOptionsContent.addView(iv)
        }

        val presets = listOf("⭐", "❤️", "⭕", "❌", "🏷️", "📌", "🔒", "🔏")
        presets.forEach { symbol ->
            val tv = TextView(this).apply {
                text = symbol
                textSize = 18f
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                val cardSize = (48 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(cardSize, cardSize).apply {
                    marginEnd = (8 * density).toInt()
                }
                background = markupBackground(false, circle = true)
                contentDescription = symbol
                isFocusable = true
                setOnClickListener {
                    val bmp = createPresetStickerBitmap(symbol)
                    annotationOverlayView.addSticker(bmp)
                    showMarkupToolOptions(MarkupTool.STICKER)
                }
            }
            markupOptionsContent.addView(tv)
        }
    }

    private fun addMosaicBrushSlider() {
        val density = resources.displayMetrics.density
        val mosaicBrushContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val currentBrushDp = (kotlin.math.round((annotationOverlayView.activeMosaicBrushSize / density) / 2f) * 2f).coerceIn(10f, 60f)
        val brushLabel = TextView(this).apply {
            text = "${getString(R.string.editor_markup_mosaic_brush)}: ${currentBrushDp.toInt()}dp"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 12f
            minWidth = (96 * density).toInt()
        }

        val brushSlider = Slider(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            valueFrom = 10f
            valueTo = 60f
            stepSize = 2f
            value = currentBrushDp

            addOnChangeListener { _, valDp, _ ->
                annotationOverlayView.activeMosaicBrushSize = valDp * density
                brushLabel.text = "${getString(R.string.editor_markup_mosaic_brush)}: ${valDp.toInt()}dp"
            }
        }

        brushSlider.id = View.generateViewId()
        brushLabel.labelFor = brushSlider.id
        mosaicBrushContainer.addView(brushLabel)
        mosaicBrushContainer.addView(brushSlider)
        markupOptionsContent.addView(mosaicBrushContainer)
    }

    private fun loadStickerBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxDim = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDim / sample > 512) sample *= 2

        val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    private fun createPresetStickerBitmap(symbol: String, color: Int = Color.WHITE): Bitmap {
        val density = resources.displayMetrics.density
        val baseSize = (72 * density).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 38f * density
            textAlign = Paint.Align.CENTER
            this.color = color
        }
        val fm = paint.fontMetrics
        val textWidth = paint.measureText(symbol)
        val textHeight = fm.descent - fm.ascent
        val pad = 16f * density

        val w = max(baseSize.toFloat(), textWidth + pad).toInt()
        val h = max(baseSize.toFloat(), textHeight + pad).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val baseline = h / 2f - (fm.descent + fm.ascent) / 2f
        canvas.drawText(symbol, w / 2f, baseline, paint)
        return bmp
    }

    private fun addStrokeWidthSlider() {
        val density = resources.displayMetrics.density

        val sliderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val currentDp = kotlin.math.round(annotationOverlayView.activeStrokeWidth / density).coerceIn(2f, 40f)

        val label = TextView(this).apply {
            text = "${getString(R.string.editor_markup_stroke_width)}: ${currentDp.toInt()}dp"
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            textSize = 12f
            minWidth = (80 * density).toInt()
        }

        val slider = Slider(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            valueFrom = 2f
            valueTo = 40f
            stepSize = 1f
            value = currentDp

            addOnChangeListener { _, valDp, _ ->
                val px = valDp * density
                annotationOverlayView.activeStrokeWidth = px
                annotationOverlayView.activeCustomBrush?.let { brush ->
                    brush.sizeDp = valDp
                    val all = CustomEditorAssetManager.getCustomBrushesList(this@ImageEditorActivity).toMutableList()
                    val idx = all.indexOfFirst { it.id == brush.id }
                    if (idx != -1) {
                        all[idx] = brush
                        CustomEditorAssetManager.saveCustomBrushesList(this@ImageEditorActivity, all)
                    }
                }
                annotationOverlayView.activeShape?.let { s ->
                    s.strokeWidth = px
                    annotationOverlayView.invalidate()
                }
                label.text = "${getString(R.string.editor_markup_stroke_width)}: ${valDp.toInt()}dp"
            }
        }

        slider.id = View.generateViewId()
        label.labelFor = slider.id
        sliderContainer.addView(label)
        sliderContainer.addView(slider)

        markupOptionsContent.addView(sliderContainer)
    }

    private fun addColorPalette(onColorChanged: ((Int) -> Unit)? = null) {
        val density = resources.displayMetrics.density
        val colors = listOf(
            Color.parseColor("#F44336") to R.string.markup_color_red,
            Color.WHITE to R.string.markup_color_white,
            Color.BLACK to R.string.markup_color_black,
            Color.parseColor("#FFEB3B") to R.string.markup_color_yellow,
            Color.parseColor("#2196F3") to R.string.markup_color_blue,
            Color.parseColor("#4CAF50") to R.string.markup_color_green,
        )
        val paletteContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val cards = mutableListOf<Pair<Int, MaterialCardView>>()

        colors.forEach { (c, label) ->
            val card = MaterialCardView(this).apply {
                radius = 24f * density
                cardElevation = 0f
                strokeWidth = if (c == annotationOverlayView.activeColor) (3f * density).toInt() else if (c == Color.BLACK) (1.5f * density).toInt().coerceAtLeast(1) else 0
                strokeColor = if (c == annotationOverlayView.activeColor) MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary) else Color.WHITE
                setCardBackgroundColor(c)
                val size = (48f * density).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (2 * density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                isSelected = c == annotationOverlayView.activeColor
                contentDescription = getString(label)
                setOnClickListener {
                    annotationOverlayView.activeColor = c
                    onColorChanged?.invoke(c)
                    // Update selection borders on existing cards in place without duplicating palette
                    cards.forEach { (cardColor, cardView) ->
                        cardView.strokeWidth = if (cardColor == c) (3f * density).toInt() else if (cardColor == Color.BLACK) (1.5f * density).toInt().coerceAtLeast(1) else 0
                        cardView.strokeColor = if (cardColor == c) MaterialColors.getColor(cardView, androidx.appcompat.R.attr.colorPrimary) else Color.WHITE
                        cardView.isSelected = cardColor == c
                    }
                }
            }
            cards.add(c to card)
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
                    annotationOverlayView.constrainActiveText()
                    annotationOverlayView.invalidate()
                    annotationOverlayView.onAnnotationChanged?.invoke()
                } else {
                    annotationOverlayView.undo()
                }
            }
            .show()
    }

    /** Rebuild the displayed (rotated + flipped + filtered + adjusted) bitmap on the crop view. */
    private fun refreshBaseBitmap(debounceMs: Long = 0) {
        val bmp = baseBitmap ?: return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            val displayed = withContext(Dispatchers.Default) {
                ensureActive()
                val degrees = rotation.toFloat() + fineRotation
                val flipped = isFlippedHorizontal
                val mapping = baseToDisplayMatrix(bmp, degrees, flipped)
                val transformed = transformBitmap(bmp, degrees, flipped)
                ensureActive()
                val filtered = applyFilter(transformed, currentFilter)
                if (filtered !== transformed && transformed !== bmp) transformed.recycle()
                ensureActive()
                val adjusted = ImageAdjuster.applyAdjustments(filtered, adjustParameters)
                if (adjusted !== filtered && filtered !== bmp) filtered.recycle()
                adjusted to mapping
            }
            baseToDisplayBitmap = displayed.second
            cropImageView.setBitmap(displayed.first)
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
            val targetRect = if (cropped != null) cropImageView.getCropOutputBounds() else cropImageView.getImageBounds()
            val viewToOutput = Matrix().apply {
                setRectToRect(targetRect, RectF(0f, 0f, result.width.toFloat(), result.height.toFloat()),
                    Matrix.ScaleToFit.FILL)
            }
            val baseToOutput = Matrix().apply { setConcat(viewToOutput, baseToView()) }
            annotationOverlayView.rasterize(canvas, baseToOutput)
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
        val exifOrientation = contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

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
        while (maxDim / sample > 2560) sample *= 2
        opts.inSampleSize = sample

        val decoded = contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val rotationDegrees = when (exifOrientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val flipHorizontal = exifOrientation == ExifInterface.ORIENTATION_FLIP_HORIZONTAL ||
                exifOrientation == ExifInterface.ORIENTATION_FLIP_VERTICAL

        if (rotationDegrees != 0f || flipHorizontal) {
            val matrix = Matrix().apply {
                if (rotationDegrees != 0f) postRotate(rotationDegrees)
                if (flipHorizontal) postScale(-1f, 1f)
            }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    }.getOrNull()

    /** Matrix corresponding to Bitmap.createBitmap's transformed and normalized bounds. */
    private fun baseToDisplayMatrix(src: Bitmap, degrees: Float, flipHorizontal: Boolean): Matrix {
        val matrix = Matrix().apply {
            if (abs(degrees % 360f) >= 0.01f) postRotate(degrees)
            if (flipHorizontal) postScale(-1f, 1f)
        }
        val bounds = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        matrix.mapRect(bounds)
        matrix.postTranslate(-bounds.left, -bounds.top)
        return matrix
    }

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
