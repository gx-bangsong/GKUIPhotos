/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.glimpse.viewmodels

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup

enum class IdPhotoBottomTab {
    SPECS,
    BACKGROUND,
}

data class IdPhotoBgColorItem(
    val colorInt: Int,
    val isLight: Boolean = false,
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

        val STANDARD_BG_COLORS = listOf(
            IdPhotoBgColorItem(Color.WHITE, isLight = true),
            IdPhotoBgColorItem(0xFF64C5FF.toInt()), // Sky blue
            IdPhotoBgColorItem(0xFF438EDB.toInt()), // Industry standard blue
            IdPhotoBgColorItem(0xFFD32F2F.toInt()), // National flag / marriage red
            IdPhotoBgColorItem(0xFFD2D2D2.toInt(), isLight = true), // Neutral light gray
            IdPhotoBgColorItem(0xFF004898.toInt()), // Deep judicial blue
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

    // Selected background color
    private val _selectedBgColor = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_BG_COLOR) ?: Color.WHITE
    )
    val selectedBgColor: StateFlow<Int> = _selectedBgColor.asStateFlow()

    fun selectBgColor(colorInt: Int) {
        _selectedBgColor.value = colorInt
        savedStateHandle[KEY_BG_COLOR] = colorInt
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

    private val _isBitmapLoading = MutableStateFlow(false)
    val isBitmapLoading: StateFlow<Boolean> = _isBitmapLoading.asStateFlow()

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
    }
}
