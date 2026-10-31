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
import org.lineageos.glimpse.models.idphoto.IdPhotoCatalog
import org.lineageos.glimpse.models.idphoto.IdPhotoPreset
import org.lineageos.glimpse.models.idphoto.PresetGroup

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
        private const val KEY_PICKER_OPEN = "idphoto_picker_open"
        private const val KEY_SCALE = "idphoto_framing_scale"
        private const val KEY_TX = "idphoto_framing_tx"
        private const val KEY_TY = "idphoto_framing_ty"
        private const val KEY_HAS_TRANSFORM = "idphoto_has_transform"
        private const val KEY_MODIFIED = "idphoto_framing_modified"
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
    var previewBitmap: Bitmap? = null
        private set

    private val _isBitmapLoading = MutableStateFlow(false)
    val isBitmapLoading: StateFlow<Boolean> = _isBitmapLoading.asStateFlow()

    fun ensurePreviewBitmapLoaded(contentResolver: ContentResolver) {
        val uri = sourceUri ?: return
        val currentBitmap = previewBitmap
        if (currentBitmap != null && !currentBitmap.isRecycled) return

        _isBitmapLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = loadDownsampledBitmap(contentResolver, uri)
            withContext(Dispatchers.Main) {
                previewBitmap = bmp
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
        previewBitmap?.recycle()
        previewBitmap = null
    }
}
