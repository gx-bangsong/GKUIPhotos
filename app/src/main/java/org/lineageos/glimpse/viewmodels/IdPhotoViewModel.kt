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

enum class IdPhotoWizardStep {
    CATALOG,
    FRAMING,
    // [INSERTION POINT - Slice 2]: BACKGROUND_REPLACEMENT step
    // [INSERTION POINT - Slice 3 / P2]: PRINT_SHEET_LAYOUT step
}

class IdPhotoViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    companion object {
        private const val KEY_SOURCE_URI = "idphoto_source_uri"
        private const val KEY_PRESET_ID = "idphoto_preset_id"
        private const val KEY_GROUP_NAME = "idphoto_group_name"
        private const val KEY_STEP = "idphoto_wizard_step"
        private const val KEY_SCALE = "idphoto_framing_scale"
        private const val KEY_TX = "idphoto_framing_tx"
        private const val KEY_TY = "idphoto_framing_ty"
        private const val KEY_HAS_TRANSFORM = "idphoto_has_transform"
    }

    // Source URI: preserved across process death
    var sourceUri: Uri?
        get() = savedStateHandle.get<String>(KEY_SOURCE_URI)?.let { Uri.parse(it) }
        set(value) {
            savedStateHandle[KEY_SOURCE_URI] = value?.toString()
        }

    // Selected Preset: ID preserved across process death
    private val _selectedPreset = MutableStateFlow<IdPhotoPreset?>(
        savedStateHandle.get<String>(KEY_PRESET_ID)?.let { IdPhotoCatalog.getById(it) }
    )
    val selectedPreset: StateFlow<IdPhotoPreset?> = _selectedPreset.asStateFlow()

    fun selectPreset(preset: IdPhotoPreset) {
        _selectedPreset.value = preset
        savedStateHandle[KEY_PRESET_ID] = preset.id
    }

    // Selected Group: preserved across configuration changes and process death
    private val _selectedGroup = MutableStateFlow<PresetGroup?>(
        savedStateHandle.get<String>(KEY_GROUP_NAME)?.let { name ->
            runCatching { PresetGroup.valueOf(name) }.getOrNull()
        }
    )
    val selectedGroup: StateFlow<PresetGroup?> = _selectedGroup.asStateFlow()

    fun selectGroup(group: PresetGroup?) {
        _selectedGroup.value = group
        savedStateHandle[KEY_GROUP_NAME] = group?.name
    }

    // Current wizard step
    private val _currentStep = MutableStateFlow(
        savedStateHandle.get<String>(KEY_STEP)?.let { stepName ->
            runCatching { IdPhotoWizardStep.valueOf(stepName) }.getOrNull()
        } ?: IdPhotoWizardStep.CATALOG
    )
    val currentStep: StateFlow<IdPhotoWizardStep> = _currentStep.asStateFlow()

    fun setStep(step: IdPhotoWizardStep) {
        _currentStep.value = step
        savedStateHandle[KEY_STEP] = step.name
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

    fun resetTransform() {
        hasFramingTransform = false
        framingScale = 1f
        framingTranslateX = 0f
        framingTranslateY = 0f
    }

    // In-memory preview bitmap downsampled for smooth framing
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
