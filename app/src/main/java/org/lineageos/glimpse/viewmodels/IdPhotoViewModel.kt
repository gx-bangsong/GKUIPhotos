/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.viewmodels

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lineageos.glimpse.R
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup
import org.lineageos.glimpse.utils.media.MattingResult
import org.lineageos.glimpse.utils.media.SolidColorMattingEngine

enum class IdPhotoBottomTab {
    SPECS,
    BACKGROUND,
}

data class IdPhotoBgColorItem(
    val id: String,
    val nameRes: Int,
    val colorInt: Int?, // null = 保留原底 (original background)
    val isLight: Boolean = false,
    val annotation: String? = null,
)

/**
 * Shared ViewModel for [org.lineageos.glimpse.IdPhotoActivity].
 * Survives configuration changes and process death via [SavedStateHandle].
 */
class IdPhotoViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_SOURCE_URI = "idphoto_source_uri"
        private const val KEY_PRESET_ID = "idphoto_preset_id"
        private const val KEY_GROUP_NAME = "idphoto_group_name"
        private const val KEY_RAIL_GROUP_NAME = "idphoto_rail_group_name"
        private const val KEY_BOTTOM_TAB = "idphoto_bottom_tab"
        private const val KEY_BG_COLOR = "idphoto_bg_color"
        private const val KEY_PICKER_OPEN = "idphoto_picker_open"
        private const val KEY_SCALE = "idphoto_framing_scale"
        private const val KEY_TX = "idphoto_framing_tx"
        private const val KEY_TY = "idphoto_framing_ty"
        private const val KEY_HAS_TRANSFORM = "idphoto_has_transform"
        private const val KEY_MODIFIED = "idphoto_framing_modified"

        /**
         * Authoritative ID photo background palette per docs/ID_PHOTO_PLAN.md §2.5.
         * Includes NIA (国家移民管理局) lower-bound values and industry-standard colors.
         */
        val STANDARD_BG_COLORS = listOf(
            IdPhotoBgColorItem("original", R.string.id_photo_bg_original, null, isLight = true),
            IdPhotoBgColorItem("white", R.string.id_photo_bg_white, 0xFFFFFFFF.toInt(), isLight = true),
            // NIA lower-bound Windows HSL (135, 240, >=167) -> RGB(100, 197, 255) / #64C5FF
            IdPhotoBgColorItem("light_blue_nia", R.string.id_photo_bg_light_blue, 0xFF64C5FF.toInt(), annotation = "NIA lower bound"),
            IdPhotoBgColorItem("blue_standard", R.string.id_photo_bg_blue, 0xFF438EDB.toInt()),
            IdPhotoBgColorItem("dark_blue", R.string.id_photo_bg_dark_blue, 0xFF004898.toInt()),
            // National flag red per §2.5 -> #C41F26 / RGB(196, 31, 38)
            IdPhotoBgColorItem("flag_red", R.string.id_photo_bg_flag_red, 0xFFC41F26.toInt()),
            // Marriage registry red per §2.5 -> #D9001B / RGB(217, 0, 27)
            IdPhotoBgColorItem("marriage_red", R.string.id_photo_bg_marriage_red, 0xFFD9001B.toInt()),
            // NIA lower-bound Windows HSL (0, 0, >=198) -> RGB(210, 210, 210) / #D2D2D2
            IdPhotoBgColorItem("light_gray_nia", R.string.id_photo_bg_light_gray, 0xFFD2D2D2.toInt(), isLight = true, annotation = "NIA lower bound"),
        )
    }

    // Source URI: preserved across process death
    var sourceUri: Uri?
        get() = savedStateHandle.get<String>(KEY_SOURCE_URI)?.let { Uri.parse(it) }
        set(value) {
            savedStateHandle[KEY_SOURCE_URI] = value?.toString()
        }

    // Selected Preset: defaults to Standard 1-inch if unset; preserved across process death
    private val _selectedPreset = MutableStateFlow(
        savedStateHandle.get<String>(KEY_PRESET_ID)?.let { IdPhotoCatalog.getById(it) }
            ?: IdPhotoCatalog.presets.first()
    )
    val selectedPreset: StateFlow<IdPhotoPreset> = _selectedPreset.asStateFlow()

    fun selectPreset(preset: IdPhotoPreset) {
        _selectedPreset.value = preset
        savedStateHandle[KEY_PRESET_ID] = preset.id
    }

    // Active bottom navigation tab: SPECS vs BACKGROUND
    private val _activeBottomTab = MutableStateFlow(
        savedStateHandle.get<String>(KEY_BOTTOM_TAB)?.let {
            runCatching { IdPhotoBottomTab.valueOf(it) }.getOrNull()
        } ?: IdPhotoBottomTab.SPECS
    )
    val activeBottomTab: StateFlow<IdPhotoBottomTab> = _activeBottomTab.asStateFlow()

    fun selectBottomTab(tab: IdPhotoBottomTab) {
        _activeBottomTab.value = tab
        savedStateHandle[KEY_BOTTOM_TAB] = tab.name
    }

    // Selected background color (null = 保留原底 / original background)
    private val _selectedBgColor = MutableStateFlow<Int?>(
        savedStateHandle.get<Int?>(KEY_BG_COLOR)
    )
    val selectedBgColor: StateFlow<Int?> = _selectedBgColor.asStateFlow()

    fun selectBgColor(colorInt: Int?) {
        _selectedBgColor.value = colorInt
        savedStateHandle[KEY_BG_COLOR] = colorInt
        updateMattedPreview()
    }

    // Active Category Group filter in the horizontal preset rail
    private val _selectedRailGroup = MutableStateFlow<PresetGroup?>(
        savedStateHandle.get<String>(KEY_RAIL_GROUP_NAME)?.let { name ->
            runCatching { PresetGroup.valueOf(name) }.getOrNull()
        } ?: PresetGroup.COMMON
    )
    val selectedRailGroup: StateFlow<PresetGroup?> = _selectedRailGroup.asStateFlow()

    fun selectRailGroup(group: PresetGroup?) {
        _selectedRailGroup.value = group
        savedStateHandle[KEY_RAIL_GROUP_NAME] = group?.name
    }

    // Full catalog picker overlay visibility state
    private val _isPickerOpen = MutableStateFlow(
        savedStateHandle.get<Boolean>(KEY_PICKER_OPEN) ?: false
    )
    val isPickerOpen: StateFlow<Boolean> = _isPickerOpen.asStateFlow()

    fun setPickerOpen(open: Boolean) {
        _isPickerOpen.value = open
        savedStateHandle[KEY_PICKER_OPEN] = open
    }

    // Selected Catalog Group filter inside the overlay picker
    private val _selectedCatalogGroup = MutableStateFlow<PresetGroup?>(
        savedStateHandle.get<String>(KEY_GROUP_NAME)?.let { name ->
            runCatching { PresetGroup.valueOf(name) }.getOrNull()
        }
    )
    val selectedCatalogGroup: StateFlow<PresetGroup?> = _selectedCatalogGroup.asStateFlow()

    fun selectCatalogGroup(group: PresetGroup?) {
        _selectedCatalogGroup.value = group
        savedStateHandle[KEY_GROUP_NAME] = group?.name
    }

    // Framing transform zoom/pan
    var hasFramingTransform: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_HAS_TRANSFORM) ?: false
        private set(value) {
            savedStateHandle[KEY_HAS_TRANSFORM] = value
        }

    var framingScale: Float
        get() = savedStateHandle.get<Float>(KEY_SCALE) ?: 1f
        private set(value) {
            savedStateHandle[KEY_SCALE] = value
        }

    var framingTranslateX: Float
        get() = savedStateHandle.get<Float>(KEY_TX) ?: 0f
        private set(value) {
            savedStateHandle[KEY_TX] = value
        }

    var framingTranslateY: Float
        get() = savedStateHandle.get<Float>(KEY_TY) ?: 0f
        private set(value) {
            savedStateHandle[KEY_TY] = value
        }

    fun updateTransform(scale: Float, tx: Float, ty: Float) {
        framingScale = scale
        framingTranslateX = tx
        framingTranslateY = ty
        hasFramingTransform = true
    }

    // Track whether framing has been modified by the user (for confirm-discard on exit)
    var isFramingModified: Boolean
        get() = savedStateHandle.get<Boolean>(KEY_MODIFIED) ?: false
        set(value) {
            savedStateHandle[KEY_MODIFIED] = value
        }

    // In-memory preview bitmap downsampled for smooth framing (survives rotation)
    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    // Live matted preview bitmap composited over selectedBgColor
    private val _mattedPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val mattedPreviewBitmap: StateFlow<Bitmap?> = _mattedPreviewBitmap.asStateFlow()

    private val _isBitmapLoading = MutableStateFlow(false)
    val isBitmapLoading: StateFlow<Boolean> = _isBitmapLoading.asStateFlow()

    // Matting refusal event for UX messaging
    private val _mattingRefusalEvent = MutableStateFlow<String?>(null)
    val mattingRefusalEvent: StateFlow<String?> = _mattingRefusalEvent.asStateFlow()

    fun clearMattingRefusalEvent() {
        _mattingRefusalEvent.value = null
    }

    fun ensurePreviewBitmapLoaded(contentResolver: ContentResolver) {
        val uri = sourceUri ?: return
        val currentBitmap = _previewBitmap.value
        if (currentBitmap != null && !currentBitmap.isRecycled) return

        _isBitmapLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = loadDownsampledBitmap(contentResolver, uri)
            withContext(Dispatchers.Main) {
                _previewBitmap.value = bmp
                _isBitmapLoading.value = false
                updateMattedPreview()
            }
        }
    }

    private fun updateMattedPreview() {
        val sourceBmp = _previewBitmap.value ?: return
        val targetColor = _selectedBgColor.value

        if (targetColor == null) {
            _mattedPreviewBitmap.value = null
            return
        }

        viewModelScope.launch(Dispatchers.Default) {
            when (val result = SolidColorMattingEngine.process(sourceBmp, targetColor)) {
                is MattingResult.Success -> {
                    withContext(Dispatchers.Main) {
                        _mattedPreviewBitmap.value = result.bitmap
                    }
                }
                is MattingResult.Refused -> {
                    withContext(Dispatchers.Main) {
                        _selectedBgColor.value = null
                        savedStateHandle[KEY_BG_COLOR] = null
                        _mattedPreviewBitmap.value = null
                        _mattingRefusalEvent.value = result.reason
                    }
                }
            }
        }
    }

    private fun loadDownsampledBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val maxEdge = 2048
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _previewBitmap.value?.recycle()
        _previewBitmap.value = null
        _mattedPreviewBitmap.value?.recycle()
        _mattedPreviewBitmap.value = null
    }
}
